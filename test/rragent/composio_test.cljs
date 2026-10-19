(ns rragent.composio-test
  "Composio's meta tools against a fake of its API: the session made
   once for the user with the key as `x-api-key` (or without one, as
   behind the Sprites gateway), `execute_meta` per call, a failed run
   as the tool's error, a stale session replaced."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [clojure.walk :as walk]
            [rragent.composio :as composio]
            [rragent.harness :as harness]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- read-body
  [req]
  (js/Promise. (fn [resolve]
                 (let [chunks (atom [])]
                   (.on req "data" #(swap! chunks conj %))
                   (.on req "end" #(resolve (.toString (js/Buffer.concat (clj->js @chunks)))))))))

(defn- json!
  [res status value]
  (.setHeader res "Content-Type" "application/json")
  (.writeHead res status)
  (.end res (js/JSON.stringify (clj->js value))))

(defn- meta-answer
  "What the fake answers to a meta tool: a search completes with
   tools, a batch fails, the connection manager completes with a link."
  [slug]
  (case slug
    "COMPOSIO_SEARCH_TOOLS" {:result_type "completed" :log_id "log_1"
                             :data {:results [{:primary_tool_slugs ["GMAIL_SEND_EMAIL"]}] :session {:id "search_1"}}}
    "COMPOSIO_MULTI_EXECUTE_TOOL" {:result_type "failed" :log_id "log_2" :error "GMAIL_SEND_EMAIL: gmail is not connected"
                                   :data {:results [{:index 0 :tool_slug "GMAIL_SEND_EMAIL" :error "not connected"}]}}
    "COMPOSIO_MANAGE_CONNECTIONS" {:result_type "completed" :log_id "log_3"
                                   :data {:message "1 connection initiated" :results {:gmail {:redirect_url "https://connect.example/gmail"}}}}
    {:result_type "completed" :log_id "log_4" :data {}}))

(defn fake-composio
  "Composio's v3.1 API: sessions made for a user, `execute_meta` on a
   session, a 404 for one in `dead`; every request recorded."
  [requests dead]
  (let [sessions (atom 0)]
    (http/createServer
     (fn [req res]
       (-> (read-body req)
           (.then (fn [body]
                    (let [url (.-url req)
                          parsed (when (seq body) (js->clj (js/JSON.parse body)))
                          [_ session-id] (re-find #"^/api/v3.1/tool_router/session/([^/]+)/execute_meta$" url)]
                      (swap! requests conj {:method (.-method req) :url url :body parsed :api-key (aget (.-headers req) "x-api-key")})
                      (cond
                        (and (= "POST" (.-method req)) (= "/api/v3.1/tool_router/session" url))
                        (let [n (swap! sessions inc)]
                          (json! res 201 {:session_id (str "trs_" n)
                                          :mcp {:type "http" :url (str "https://backend.composio.dev/tool_router/trs_" n "/mcp")}
                                          :tool_router_tools ["COMPOSIO_SEARCH_TOOLS" "COMPOSIO_MULTI_EXECUTE_TOOL"
                                                              "COMPOSIO_MANAGE_CONNECTIONS" "COMPOSIO_GET_TOOL_SCHEMAS"]}))

                        (and session-id (contains? @dead session-id))
                        (json! res 404 {:error {:message "Session not found" :code 404 :slug "SESSION_NOT_FOUND" :status 404}})

                        session-id
                        (json! res 200 (meta-answer (get parsed "slug")))

                        :else
                        (json! res 404 {:error {:message "no such route" :code 404 :slug "NOT_FOUND" :status 404}}))))))))))

(defn- ^:async listen!
  [server]
  (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
  (str "http://127.0.0.1:" (.-port (.address server))))

(deftest config-test
  (let [env (.-env js/process)
        names ["COMPOSIO_URL" "COMPOSIO_API_KEY" "COMPOSIO_USER_ID"]
        before (mapv #(aget env %) names)]
    (try
      (js-delete env "COMPOSIO_URL")
      (is (nil? (composio/config)) "no URL, no Composio")
      (aset env "COMPOSIO_URL" "https://api.sprites.dev/v1/gateway/composio/conn_1/")
      (js-delete env "COMPOSIO_API_KEY")
      (aset env "COMPOSIO_USER_ID" "org_agent")
      (is (= {:url "https://api.sprites.dev/v1/gateway/composio/conn_1" :api-key nil :user-id "org_agent"} (composio/config)) "the gateway adds the key itself")
      (finally
        (doseq [[k v] (map vector names before)]
          (if (nil? v) (js-delete env k) (aset env k v)))))))

(deftest extension-test
  (is (nil? (composio/extension nil)))
  (let [e (try (composio/extension {:url "https://backend.composio.dev"}) nil (catch :default e e))]
    (is (str/includes? (str (some-> e .-message)) "COMPOSIO_USER_ID")))
  (let [extension (composio/extension {:url "https://backend.composio.dev" :api-key "key_1" :user-id "org_agent"})]
    (is (= "composio" (.-name extension)))
    (is (= ["COMPOSIO_SEARCH_TOOLS" "COMPOSIO_GET_TOOL_SCHEMAS" "COMPOSIO_MULTI_EXECUTE_TOOL" "COMPOSIO_MANAGE_CONNECTIONS"]
           (map #(.-name %) (array-seq (.-tools extension)))) "named as Composio names them")
    (is (not-any? #(.-deferred %) (array-seq (.-tools extension))) "four tools, offered as they are")))

(deftest session-test
  (async done
    (let [requests (atom [])
          server (fake-composio requests (atom #{}))]
      (-> ((^:async fn []
             (let [url (await (listen! server))]
               (testing "with the key, as against Composio itself"
                 (is (= "trs_1" (await (composio/create-session! {:url url :api-key "key_1" :user-id "org_agent"}))))
                 (let [{:keys [body api-key]} (first @requests)]
                   (is (= "key_1" api-key))
                   (is (= {"user_id" "org_agent" "instant" false "workbench" {"enable" false}
                           "proxy_execute" {"enable" false} "manage_connections" {"enable" true}}
                          body))))
               (testing "without the key, as behind the gateway"
                 (is (= "trs_2" (await (composio/create-session! {:url url :user-id "org_agent"}))))
                 (is (nil? (:api-key (second @requests)))))
               (testing "an answer that is no session"
                 (let [e (try (await (composio/create-session! {:url (str url "/nowhere") :user-id "org_agent"})) nil (catch :default e e))]
                   (is (str/includes? (str (some-> e .-message)) "HTTP 404"))
                   (is (str/includes? (str (some-> e .-message)) "no such route")))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))

(deftest through-the-harness-test
  (async done
    (let [requests (atom [])
          dead (atom #{})
          server (fake-composio requests dead)]
      (-> ((^:async fn []
             (let [url (await (listen! server))
                   home (test-home/make)
                   {:keys [faux models]} (tools-test/faux-models)
                   search {:queries [{:use_case "send an email from Gmail"}] :session {:generate_id true}}
                   batch {:tools [{:tool_slug "GMAIL_SEND_EMAIL" :arguments {:to "a@example.com" :subject "Hi"}}] :session_id "search_1"}
                   connect {:toolkits ["gmail"] :session_id "search_1"}
                   execute-metas (fn [] (->> @requests (filter #(str/includes? (:url %) "/execute_meta")) (map (juxt :url :body))))]
               (.setResponses faux #js [(tools-test/tool-call "COMPOSIO_SEARCH_TOOLS" search "c1")
                                        (tools-test/tool-call "COMPOSIO_MULTI_EXECUTE_TOOL" batch "c2")
                                        (tools-test/tool-call "COMPOSIO_MANAGE_CONNECTIONS" connect "c3")
                                        (tools-test/answer "Please connect Gmail at https://connect.example/gmail")
                                        (tools-test/tool-call "COMPOSIO_SEARCH_TOOLS" search "c4")
                                        (tools-test/answer "Found it.")])
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"
                                                 :storrito nil
                                                 :composio {:url url :api-key "key_1" :user-id "org_agent"}}))
                     results (^:async fn []
                               (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))]
                                 (->> (array-seq (.-items page))
                                      (filter #(= "pi.tool-result" (.-kind %)))
                                      (map #(aget (.-model %) 0))
                                      (into {} (map (fn [r] [(.-toolCallId r) r]))))))]
                 (try
                   (is (= "Please connect Gmail at https://connect.example/gmail" (await (harness/ask agent "Send Lydia the report by Gmail."))))
                   (testing "one session for the user, every meta tool run in it with the model's arguments"
                     (is (= 1 (count (filter #(= "/api/v3.1/tool_router/session" (:url %)) @requests))))
                     (is (= "org_agent" (get-in (first @requests) [:body "user_id"])))
                     (is (= [["/api/v3.1/tool_router/session/trs_1/execute_meta" {"slug" "COMPOSIO_SEARCH_TOOLS" "arguments" (walk/stringify-keys search)}]
                             ["/api/v3.1/tool_router/session/trs_1/execute_meta" {"slug" "COMPOSIO_MULTI_EXECUTE_TOOL" "arguments" (walk/stringify-keys batch)}]
                             ["/api/v3.1/tool_router/session/trs_1/execute_meta" {"slug" "COMPOSIO_MANAGE_CONNECTIONS" "arguments" (walk/stringify-keys connect)}]]
                            (execute-metas)))
                     (is (every? #(= "key_1" (:api-key %)) @requests)))
                   (testing "the session is remembered with the conversation"
                     (is (= {"sessionId" "trs_1"} (js->clj (await (.snapshot (:harness agent) composio/Session (.-id (:root agent)) harness/context))))))
                   (testing "a completed run is the tool's text, a failed one its error, with the data"
                     (let [by-call (await (results))]
                       (is (str/includes? (js/JSON.stringify (get by-call "c1")) "GMAIL_SEND_EMAIL"))
                       (is (true? (.-isError (get by-call "c2"))))
                       (is (str/includes? (js/JSON.stringify (get by-call "c2")) "gmail is not connected"))
                       (is (str/includes? (js/JSON.stringify (get by-call "c2")) "\\\"index\\\":0"))
                       (is (str/includes? (js/JSON.stringify (get by-call "c3")) "https://connect.example/gmail"))))
                   (testing "a session Composio forgot is made anew, once"
                     (swap! dead conj "trs_1")
                     (is (= "Found it." (await (harness/ask agent "Search again."))))
                     (is (= 2 (count (filter #(= "/api/v3.1/tool_router/session" (:url %)) @requests))))
                     (is (= "/api/v3.1/tool_router/session/trs_2/execute_meta" (first (last (execute-metas)))))
                     (is (= {"sessionId" "trs_2"} (js->clj (await (.snapshot (:harness agent) composio/Session (.-id (:root agent)) harness/context)))))
                     (is (str/includes? (js/JSON.stringify (get (await (results)) "c4")) "GMAIL_SEND_EMAIL")))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home)))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))
