(ns rragent.model-requests-test
  "The net under the pictures: a chat completion request measured, an
   answer that says it was too large turned into the overflow pi-ai
   knows, every other answer and request untouched."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.model-requests :as model-requests]))

(def gateway-502
  "What the Sprites gateway answers to a body past its cap (2026-10-23)."
  "{\"code\":\"internal_error\",\"details\":{},\"message\":\"Internal server error.\",\"request_id\":null}")

(def openrouter-502
  "A 502 of OpenRouter's own: a provider failed, worth a retry."
  "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}")

(defn- ^:async serve
  "A server whose path says what it answers: `{:url :stop}`."
  []
  (let [server (http/createServer
                (fn [request response]
                  (let [url (str (.-url request))
                        [status headers body] (cond
                                                (str/starts-with? url "/gateway/") [502 #js {"content-type" "application/json"} gateway-502]
                                                (str/starts-with? url "/openrouter/") [502 #js {"content-type" "application/json" "x-request-id" "req_1"} openrouter-502]
                                                (str/starts-with? url "/text/") [400 #js {"content-type" "application/json"} "{\"error\":{\"message\":\"The total text input size exceeds 8 MB\",\"code\":400}}"]
                                                (str/starts-with? url "/large/") [413 #js {"content-type" "application/json"} "{\"error\":\"request body too large\"}"]
                                                :else [200 #js {"content-type" "application/json"} "{\"choices\":[]}"])]
                    (.writeHead response status headers)
                    (.end response body))))]
    (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
    {:url (str "http://127.0.0.1:" (.-port (.address server)))
     :stop (fn [] (js/Promise. (fn [resolve] (.close server resolve))))}))

(def body
  "A request with one picture in it."
  (js/JSON.stringify #js {:model "m" :messages #js [#js {:role "user" :content #js [#js {:type "text" :text "hi"}
                                                                                    #js {:type "image_url" :image_url #js {:url (str "data:image/png;base64," (apply str (repeat 400 "A")))}}]}]}))

(defn- post
  "A completion request through `fetching`."
  [fetching url]
  (fetching url #js {:method "POST" :headers #js {"content-type" "application/json"} :body body}))

(deftest measure-test
  (let [{:keys [bytes images]} (model-requests/measure body)]
    (is (= (count body) bytes))
    (is (= (+ (count "data:image/png;base64,") 400) images) "the picture's data URL")
    (is (= {:bytes 2 :images 0} (model-requests/measure "{}")))))

(deftest completion-url-test
  (is (model-requests/completion-url? "http://sprite.internal/v1/gateway/openrouter/c1/v1/chat/completions"))
  (is (model-requests/completion-url? (js/URL. "https://openrouter.ai/api/v1/chat/completions?x=1")))
  (is (not (model-requests/completion-url? "https://openrouter.ai/api/v1/models")))
  (is (not (model-requests/completion-url? nil))))

(deftest too-large-test
  (is (model-requests/too-large? 413 "" (js/Headers.)))
  (is (model-requests/too-large? 400 "{\"error\":{\"message\":\"The total text input size exceeds 8 MB\"}}" (js/Headers.)))
  (is (model-requests/too-large? 502 gateway-502 (js/Headers.)))
  (is (not (model-requests/too-large? 502 openrouter-502 (js/Headers. #js {"x-request-id" "req_1"}))) "OpenRouter's own 502 carries its request id")
  (is (not (model-requests/too-large? 502 gateway-502 (js/Headers. #js {"x-request-id" "req_1"}))))
  (is (not (model-requests/too-large? 400 "{\"error\":{\"message\":\"invalid model\"}}" (js/Headers.))))
  (is (not (model-requests/too-large? 500 gateway-502 (js/Headers.)))))

(deftest wrap-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (serve))
                 errors (atom [])
                 original-error (.-error js/console)
                 fetching (model-requests/wrap model-requests/original-fetch)]
             (set! (.-error js/console) (fn [& args] (swap! errors conj (str/join " " args))))
             (try
               (testing "the gateway's 502 past the cap becomes the 413 pi-ai reads as an overflow"
                 (let [response (await (post fetching (str url "/gateway/chat/completions")))
                       text (await (.text response))]
                   (is (= 413 (.-status response)))
                   (is (str/includes? text "request_too_large"))
                   (is (str/includes? text "HTTP 502"))
                   (is (= 1 (count @errors)))
                   (is (str/includes? (first @errors) "refused as too large: HTTP 502"))))
               (testing "OpenRouter's own 502 goes through, for the retry"
                 (let [response (await (post fetching (str url "/openrouter/chat/completions")))]
                   (is (= 502 (.-status response)))
                   (is (= openrouter-502 (await (.text response))))))
               (testing "OpenRouter's 400 for too much text, and a 413, are overflows too"
                 (is (= 413 (.-status (await (post fetching (str url "/text/chat/completions"))))))
                 (is (str/includes? (await (.text (await (post fetching (str url "/large/chat/completions"))))) "request_too_large")))
               (testing "a good answer and any other request are untouched"
                 (is (= 200 (.-status (await (post fetching (str url "/ok/chat/completions"))))))
                 (let [response (await (fetching (str url "/gateway/models")))]
                   (is (= 502 (.-status response)) "not a completion: the gateway's answer as it is")))
               (finally
                 (set! (.-error js/console) original-error)
                 (await (stop)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest warn-test
  (async done
    (let [warnings (atom [])
          original-warn (.-warn js/console)
          calls (atom [])
          fetching (model-requests/wrap (fn [input _init] (swap! calls conj input) (js/Promise.resolve (js/Response. "{}" #js {:status 200}))))
          big (js/JSON.stringify #js {:messages #js [#js {:role "user" :content (apply str (repeat (inc model-requests/warn-bytes) "x"))}]})]
      (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str/join " " args))))
      (-> (fetching "https://openrouter.ai/api/v1/chat/completions" #js {:method "POST" :body big})
          (.then (fn [_]
                   (is (= 1 (count @warnings)))
                   (is (str/includes? (first @warnings) "[model] a request of 6.0 MB"))
                   (is (= 1 (count @calls)) "the request went out as it was")))
          (.finally (fn []
                      (set! (.-warn js/console) original-warn)
                      (done)))))))

(deftest session-test
  (is (= "s1" (model-requests/session-of #js {"x-session-id" "s1"} "{}")) "OpenRouter's header")
  (is (= "k1" (model-requests/session-of #js {} "{\"model\":\"m\",\"prompt_cache_key\":\"k1\"}")) "else OpenAI's key")
  (is (nil? (model-requests/session-of #js {} "{\"model\":\"m\"}"))))

(deftest remember-test
  (reset! model-requests/last-requests {})
  (doseq [k (range 10)]
    (model-requests/remember! (str "s" k) {:body (str k)} k))
  (is (= model-requests/kept-sessions (count @model-requests/last-requests)) "the newest sessions only")
  (is (nil? (model-requests/last-request "s0")))
  (is (= "9" (:body (model-requests/last-request "s9"))))
  (model-requests/remember! "s2" {:body "again"} 20)
  (is (= "again" (:body (model-requests/last-request "s2"))) "a session's last request replaces its earlier")
  (reset! model-requests/last-requests {}))

(deftest wrap-remembers-test
  (async done
    (-> ((^:async fn []
           (reset! model-requests/last-requests {})
           (let [{:keys [url stop]} (await (serve))
                 fetching (model-requests/wrap model-requests/original-fetch)]
             (try
               (await (fetching (str url "/ok/chat/completions")
                                #js {:method "POST" :headers #js {"Content-Type" "application/json" "X-Session-Id" "conv-7"} :body body}))
               (let [{:keys [headers] :as kept} (model-requests/last-request "conv-7")]
                 (is (= (str url "/ok/chat/completions") (:url kept)))
                 (is (= body (:body kept)))
                 (is (= "conv-7" (aget headers "x-session-id")) "the headers as a plain object, lower-case"))
               (await (fetching (str url "/ok/chat/completions") #js {:method "POST" :headers #js {} :body body}))
               (is (= 1 (count @model-requests/last-requests)) "a request without a session is not kept")
               (finally
                 (reset! model-requests/last-requests {})
                 (await (stop)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
