(ns rragent.storrito-test
  "Storrito's procedures as deferred tools, against a fake of the
   organization's host: the catalog, `POST /api/v1/<name>` behind the
   Bearer credential, and the retries."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.storrito :as storrito]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def credential
  "cred_1:secret_1")

(def catalog
  "A catalog as the organization's host serves it: a procedure without
   input, one with the schema features of the real ones."
  {:catalogVersion 1
   :baseUrlTemplate "https://ORG_UUID.storrito.com/api/v1/"
   :procedures [{:name "generate-uuid"
                 :doc "Generates a UUID.\n\n```\ncurl -X POST https://ORG_UUID.storrito.com/api/v1/generate-uuid\n```\n\n   "
                 :inputSchema {:type "object" :properties {} :additionalProperties false}}
                {:name "status-instagram-story"
                 :doc "The status of a story post.\n\n```bash\ncurl ...\n```\n\n\n   Second paragraph."
                 :inputSchema {:type "object"
                               :properties {:storyPostUuid {:type "string" :format "uuid" :description "The UUID of the story post"}
                                            :verbose {:type "boolean"}
                                            :limit {:type "integer" :minimum 1}
                                            :destination {:anyOf [{:type "string"} {:type "integer"}]}}
                               :required ["storyPostUuid"]
                               :additionalProperties false}}]})

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

(defn fake-host
  "The organization's host: the catalog, and the procedures behind the
   credential, recorded in `calls`; the first `flaky` procedure calls
   are answered 503."
  [calls flaky]
  (http/createServer
   (fn [req res]
     (-> (read-body req)
         (.then (fn [body]
                  (let [url (.-url req)
                        auth (aget (.-headers req) "authorization")]
                    (cond
                      (= url "/documentation/api/v1/catalog.json")
                      (json! res 200 catalog)

                      (str/starts-with? url "/api/v1/")
                      (let [name (subs url (count "/api/v1/"))
                            params (when (seq body) (js->clj (js/JSON.parse body)))]
                        (swap! calls conj {:name name :params params :authorization auth
                                           :content-type (aget (.-headers req) "content-type")})
                        (cond
                          (not= auth (str "Bearer " credential)) (json! res 401 {:errorMessage "Not authorized"})
                          (pos? @flaky) (do (swap! flaky dec) (json! res 503 "deploying"))
                          (= name "generate-uuid") (json! res 200 {:uuid "0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d"})
                          (= name "status-instagram-story") (if (get params "storyPostUuid")
                                                              (json! res 200 {:status "scheduled" :params params})
                                                              (json! res 400 {:errorMessage "invalid params for procedure: status-instagram-story"
                                                                              :validationErrorExplanation {:storyPostUuid ["missing required key"]}}))
                          :else (json! res 404 {:errorMessage "unknown procedure"})))

                      :else
                      (json! res 404 {})))))))))

(defn- ^:async listen!
  [server]
  (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
  (str "http://127.0.0.1:" (.-port (.address server))))

(deftest description-test
  (is (= "Generates a UUID." (storrito/description (:doc (first (:procedures catalog))))))
  (is (= "The status of a story post.\n\nSecond paragraph." (storrito/description (:doc (second (:procedures catalog))))))
  (is (= "" (storrito/description nil))))

(deftest config-test
  (let [env (.-env js/process)
        before [(aget env "STORRITO_API_URL") (aget env "STORRITO_CREDENTIAL")]]
    (try
      (js-delete env "STORRITO_API_URL")
      (is (nil? (storrito/config)) "no URL, no Storrito")
      (aset env "STORRITO_API_URL" "https://acme.storrito.com/")
      (aset env "STORRITO_CREDENTIAL" credential)
      (is (= {:url "https://acme.storrito.com" :credential credential} (storrito/config)) "the trailing slash goes")
      (finally
        (doseq [[k v] (map vector ["STORRITO_API_URL" "STORRITO_CREDENTIAL"] before)]
          (if (nil? v) (js-delete env k) (aset env k v)))))))

(deftest extension-test
  (async done
    (let [server (fake-host (atom []) (atom 0))]
      (-> ((^:async fn []
             (let [url (await (listen! server))
                   extension (await (storrito/extension {:url url :credential credential}))
                   tools (array-seq (.-tools extension))
                   status (second tools)]
               (is (= "storrito" (.-name extension)))
               (is (= ["generate-uuid" "status-instagram-story"] (map #(.-name %) tools)))
               (is (every? #(true? (.-deferred %)) tools) "not offered until found")
               (is (= "The status of a story post.\n\nSecond paragraph." (.-description status)))
               (is (= ["storyPostUuid" "verbose" "limit" "destination"] (vec (js/Object.keys (.-properties (.-parameters status))))) "the procedure's schema as it is")
               (is (= ["storyPostUuid"] (array-seq (.-required (.-parameters status)))))
               (let [text ((.-render (aget (.-sections extension) 0)) nil nil)]
                 (is (str/includes? text "tool_search"))
                 (is (str/includes? text "generate-uuid, status-instagram-story")))
               (testing "without a config there is no extension; a config without the credential is a mistake"
                 (is (nil? (await (storrito/extension nil))))
                 (let [e (try (await (storrito/extension {:url url})) nil (catch :default e e))]
                   (is (str/includes? (str (some-> e .-message)) "STORRITO_CREDENTIAL")))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))

(deftest call-test
  (async done
    (let [calls (atom [])
          flaky (atom 2)
          server (fake-host calls flaky)]
      (-> ((^:async fn []
             (let [url (await (listen! server))
                   config {:url url :credential credential :delay-ms 0}]
               (testing "a 503 is tried again"
                 (is (= "{\"uuid\":\"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" (await (storrito/call config "generate-uuid" #js {}))))
                 (is (= 3 (count @calls))))
               (testing "what the API said about a bad call reaches the model"
                 (let [e (try (await (storrito/call config "status-instagram-story" #js {})) nil (catch :default e e))]
                   (is (str/includes? (str (some-> e .-message)) "HTTP 400"))
                   (is (str/includes? (str (some-> e .-message)) "missing required key"))))
               (testing "a wrong credential"
                 (let [e (try (await (storrito/call (assoc config :credential "cred_1:wrong") "generate-uuid" #js {})) nil (catch :default e e))]
                   (is (str/includes? (str (some-> e .-message)) "HTTP 401")))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))

(deftest through-the-harness-test
  (async done
    (let [calls (atom [])
          server (fake-host calls (atom 0))
          uuid "7a1c2d3e-4f50-4617-8899-aabbccddeeff"]
      (-> ((^:async fn []
             (let [url (await (listen! server))
                   home (test-home/make)
                   {:keys [faux models]} (tools-test/faux-models)]
               (.setResponses faux #js [(tools-test/tool-call "status-instagram-story" {:storyPostUuid uuid} "s0")
                                        (tools-test/tool-call "tool_search" {:query "status instagram story"} "t1")
                                        (tools-test/tool-call "status-instagram-story" {:storyPostUuid uuid :limit "2"} "s1")
                                        (tools-test/answer "Scheduled.")])
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"
                                                 :storrito {:url url :credential credential :delay-ms 0}
                                                 :composio nil}))]
                 (try
                   (is (= "Scheduled." (await (harness/ask agent "How is the story doing?"))))
                   (testing "the call reached the host with the credential, the arguments coerced by the schema"
                     (is (= [{:name "status-instagram-story" :params {"storyPostUuid" uuid "limit" 2}
                              :authorization (str "Bearer " credential) :content-type "application/json"}]
                            @calls)))
                   (testing "unknown before tool_search loaded it, answered after"
                     (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                           results (->> (array-seq (.-items page))
                                        (filter #(= "pi.tool-result" (.-kind %)))
                                        (map #(aget (.-model %) 0)))
                           by-call (into {} (map (fn [r] [(.-toolCallId r) r])) results)]
                       (is (true? (.-isError (get by-call "s0"))) "the first call, the tool not loaded yet")
                       (is (str/includes? (js/JSON.stringify (get by-call "t1")) "status-instagram-story") "tool_search found it")
                       (is (str/includes? (js/JSON.stringify (get by-call "s1")) "scheduled"))))
                   (testing "the loaded tool stays offered, the section is in the prompt"
                     (let [resolved (await (.agent (:root agent) harness/context))]
                       (is (contains? (set (map #(.-name %) (.-tools resolved))) "status-instagram-story"))
                       (is (= ["generate-uuid"] (map #(.-name %) (.-deferred resolved))))
                       (is (contains? (set (map #(.-key %) (.-sections resolved))) "storrito"))))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home)))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))
