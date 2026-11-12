(ns rragent.model-requests-test
  "The net under the pictures: a request to a model measured, a chat
   completion or a message the Anthropic way, an answer that says it
   was too large turned into the overflow pi-ai knows, every other
   answer and request untouched."
  (:require ["@earendil-works/pi-ai/api/anthropic-messages" :refer [streamSimple]]
            ["@earendil-works/pi-ai/providers/openrouter.models" :refer [OPENROUTER_MODELS]]
            ["@earendil-works/pi-ai/utils/overflow" :refer [isContextOverflow]]
            ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.definition :as definition]
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
  (let [answer (fn [url]
                 (cond
                   (str/starts-with? url "/gateway/") [502 #js {"content-type" "application/json"} gateway-502]
                   (str/starts-with? url "/openrouter/") [502 #js {"content-type" "application/json" "x-request-id" "req_1"} openrouter-502]
                   (str/starts-with? url "/text/") [400 #js {"content-type" "application/json"} "{\"error\":{\"message\":\"The total text input size exceeds 8 MB\",\"code\":400}}"]
                   (str/starts-with? url "/large/") [413 #js {"content-type" "application/json"} "{\"error\":\"request body too large\"}"]
                   :else [200 #js {"content-type" "application/json"} "{\"choices\":[]}"]))
        server (http/createServer
                (fn [request response]
                  ;; the whole request read first: megabytes may be on their way
                  (.resume request)
                  (.on request "end"
                       (fn []
                         (let [[status headers body] (answer (str (.-url request)))]
                           (.writeHead response status headers)
                           (.end response body))))))]
    (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
    {:url (str "http://127.0.0.1:" (.-port (.address server)))
     :stop (fn [] (js/Promise. (fn [resolve] (.close server resolve))))}))

(def body
  "A request with one picture in it."
  (js/JSON.stringify #js {:model "m" :messages #js [#js {:role "user" :content #js [#js {:type "text" :text "hi"}
                                                                                    #js {:type "image_url" :image_url #js {:url (str "data:image/png;base64," (apply str (repeat 400 "A")))}}]}]}))

(def messages-body
  "The same the Anthropic way, as pi-ai writes a picture."
  (js/JSON.stringify #js {:model "m" :messages #js [#js {:role "user" :content #js [#js {:type "text" :text "hi"}
                                                                                    #js {:type "image" :source #js {:type "base64" :media_type "image/webp" :data (apply str (repeat 400 "A"))}}]}]}))

(defn- large
  "The JSON `body` with a text that takes it above `cap-bytes`: a
   request the gateway does not forward, its 502 a refusal of the
   size."
  [body]
  (let [^js request (js/JSON.parse body)]
    (.push (.-messages request) #js {:role "user" :content (.repeat "x" model-requests/cap-bytes)})
    (js/JSON.stringify request)))

(defn- post
  "A request with `body` (the small completion without one) through
   `fetching`."
  [fetching url & [body*]]
  (fetching url #js {:method "POST" :headers #js {"content-type" "application/json"} :body (or body* body)}))

(def claude
  "The default model as pi-ai's catalog has it."
  (aget OPENROUTER_MODELS definition/default-model))

(defn- ^:async ask
  "The message pi-ai's own API and the Anthropic SDK make of the
   answer to a request with a picture for the default model, sent to
   `base` through `fetching`; without one through the global `fetch`,
   as on a machine."
  [fetching base]
  (let [model (js/Object.assign #js {} claude #js {:baseUrl base})
        context #js {:messages #js [#js {:role "user"
                                         :content #js [#js {:type "text" :text "hi"}
                                                       #js {:type "image" :mimeType "image/webp" :data (apply str (repeat 400 "A"))}]
                                         :timestamp 0}]}
        options (cond-> #js {:apiKey "sprite" :sessionId "conv-9"}
                  fetching (doto (aset "fetch" fetching)))]
    (await (.result (streamSimple model context options)))))

(deftest measure-test
  (let [{:keys [bytes images]} (model-requests/measure body)]
    (is (= (count body) bytes))
    (is (= (+ (count "data:image/png;base64,") 400) images) "the picture's data URL")
    (is (= {:bytes 2 :images 0} (model-requests/measure "{}"))))
  (let [{:keys [bytes images]} (model-requests/measure messages-body)]
    (is (= (count messages-body) bytes))
    (is (= 400 images) "the picture's base64, the Anthropic way"))
  (is (= 7 (:bytes (model-requests/measure "\"ä€\""))) "bytes as they go out, not characters"))

(deftest kind-test
  (is (= :completion (model-requests/kind "http://sprite.internal/v1/gateway/openrouter/c1/v1/chat/completions")))
  (is (= :completion (model-requests/kind (js/URL. "https://openrouter.ai/api/v1/chat/completions?x=1"))))
  (is (= :messages (model-requests/kind "http://sprite.internal/v1/gateway/openrouter/c1/v1/messages")))
  (is (= :messages (model-requests/kind (js/URL. "https://openrouter.ai/api/v1/messages?beta=true"))))
  (is (= :messages (model-requests/kind (js/Request. "https://openrouter.ai/api/v1/messages" #js {:method "POST" :body "{}"}))))
  (is (nil? (model-requests/kind "https://openrouter.ai/api/v1/messages/count_tokens")))
  (is (nil? (model-requests/kind "https://openrouter.ai/api/v1/models")))
  (is (nil? (model-requests/kind nil))))

(deftest too-large-test
  (let [small 60000
        big (inc model-requests/cap-bytes)]
    (is (model-requests/too-large? 413 "" (js/Headers.) small))
    (is (model-requests/too-large? 400 "{\"error\":{\"message\":\"The total text input size exceeds 8 MB\"}}" (js/Headers.) small))
    (is (model-requests/too-large? 502 gateway-502 (js/Headers.) big))
    (is (not (model-requests/too-large? 502 gateway-502 (js/Headers.) small)) "the gateway's 502 to a small request is not about its size")
    (is (not (model-requests/too-large? 502 gateway-502 (js/Headers.) (inc model-requests/warn-bytes))) "nor to one it warned about and forwards")
    (is (not (model-requests/too-large? 502 gateway-502 (js/Headers.) model-requests/cap-bytes)) "nor to one at the cap")
    (is (not (model-requests/too-large? 502 openrouter-502 (js/Headers. #js {"x-request-id" "req_1"}) big)) "OpenRouter's own 502 carries its request id")
    (is (not (model-requests/too-large? 502 gateway-502 (js/Headers. #js {"x-request-id" "req_1"}) big)))
    (is (not (model-requests/too-large? 400 "{\"error\":{\"message\":\"invalid model\"}}" (js/Headers.) big)))
    (is (not (model-requests/too-large? 500 gateway-502 (js/Headers.) big)))))

(deftest wrap-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (serve))
                 errors (atom [])
                 original-error (.-error js/console)
                 original-warn (.-warn js/console)
                 fetching (model-requests/wrap model-requests/original-fetch)]
             (set! (.-error js/console) (fn [& args] (swap! errors conj (str/join " " args))))
             (set! (.-warn js/console) (fn [& _]))
             (try
               (testing "the gateway's 502 to a request above the cap becomes the 413 pi-ai reads as an overflow"
                 (let [response (await (post fetching (str url "/gateway/chat/completions") (large body)))
                       text (await (.text response))]
                   (is (= 413 (.-status response)))
                   (is (str/includes? text "request_too_large"))
                   (is (str/includes? text "HTTP 502"))
                   (is (= 1 (count @errors)))
                   (is (str/includes? (first @errors) "refused as too large: HTTP 502"))))
               (testing "the gateway's 502 to a small request goes through, for the retry: it is not about the size"
                 (let [response (await (post fetching (str url "/gateway/chat/completions")))]
                   (is (= 502 (.-status response)))
                   (is (= gateway-502 (await (.text response))))
                   (is (= 1 (count @errors)))))
               (testing "OpenRouter's own 502 goes through, for the retry"
                 (let [response (await (post fetching (str url "/openrouter/chat/completions") (large body)))]
                   (is (= 502 (.-status response)))
                   (is (= openrouter-502 (await (.text response))))))
               (testing "OpenRouter's 400 for too much text, and a 413, are overflows too"
                 (is (= 413 (.-status (await (post fetching (str url "/text/chat/completions"))))))
                 (is (str/includes? (await (.text (await (post fetching (str url "/large/chat/completions"))))) "request_too_large")))
               (testing "a message the Anthropic way: the same three answers"
                 (let [before (count @errors)
                       response (await (post fetching (str url "/large/v1/messages?beta=true") messages-body))
                       text (await (.text response))]
                   (is (= 413 (.-status response)))
                   (is (str/includes? text "request_too_large"))
                   (is (str/includes? text "HTTP 413"))
                   (is (= (inc before) (count @errors)))
                   (is (str/includes? (last @errors) "refused as too large: HTTP 413")))
                 (let [response (await (post fetching (str url "/gateway/v1/messages") (large messages-body)))
                       text (await (.text response))]
                   (is (= 413 (.-status response)))
                   (is (str/includes? text "request_too_large"))
                   (is (str/includes? text "HTTP 502")))
                 (let [response (await (post fetching (str url "/gateway/v1/messages") messages-body))]
                   (is (= 502 (.-status response)) "to a small request the gateway's 502 as it is")
                   (is (= gateway-502 (await (.text response)))))
                 (is (str/includes? (await (.text (await (post fetching (str url "/text/v1/messages") messages-body)))) "request_too_large")))
               (testing "a good answer and any other request are untouched"
                 (is (= 200 (.-status (await (post fetching (str url "/ok/chat/completions"))))))
                 (let [response (await (fetching (str url "/gateway/models")))]
                   (is (= 502 (.-status response)) "not a completion: the gateway's answer as it is")))
               (finally
                 (set! (.-error js/console) original-error)
                 (set! (.-warn js/console) original-warn)
                 (await (stop)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest answers-handed-on-test
  (async done
    (let [answers (atom {})
          fetching (model-requests/wrap (fn [input _init]
                                          (let [response (js/Response. "{}" #js {:status (if (str/includes? input "/busy/") 429 200)})]
                                            (swap! answers assoc input response)
                                            (js/Promise.resolve response))))
          ask (fn [url body] (.then (fetching url #js {:method "POST" :body body}) (fn [response] [(get @answers url) response])))]
      (-> (js/Promise.all #js [(ask "https://openrouter.ai/api/v1/messages" messages-body)
                               (ask "https://openrouter.ai/api/busy/v1/messages" messages-body)
                               (ask "https://openrouter.ai/api/v1/chat/completions" body)
                               (ask "https://openrouter.ai/api/v1/models" "{}")])
          (.then (fn [[[sent got] [busy-sent busy-got] [completion-sent completion-got] [other-sent other-got]]]
                   (is (identical? sent got) "a message's answer is the response as it came: nothing reads its stream")
                   (is (identical? busy-sent busy-got) "a 429 too")
                   (is (not (identical? completion-sent completion-got)) "a chat completion's is split for the charge")
                   (is (= 200 (.-status completion-got)))
                   (is (identical? other-sent other-got) "any other request's as it came")))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally done)))))

(deftest through-pi-ai-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (serve))
                 original-error (.-error js/console)
                 fetching (model-requests/wrap model-requests/original-fetch)]
             (set! (.-error js/console) (fn [& _]))
             (try
               (is (= "anthropic-messages" (some-> claude .-api)) "the default model is spoken to the Anthropic way; another API needs its case here")
               (testing "the gateway's 413 as pi-ai and the Anthropic SDK word it is no overflow: the turn would end with it"
                 (let [^js message (await (ask model-requests/original-fetch (str url "/large")))]
                   (is (= "error" (.-stopReason message)))
                   (is (= "413 {\"error\":\"request body too large\"}" (.-errorMessage message)))
                   (is (not (isContextOverflow message)))))
               (testing "through the wrapper it is the overflow pi-durable compacts on"
                 (let [^js message (await (ask fetching (str url "/large")))]
                   (is (= "error" (.-stopReason message)))
                   (is (str/includes? (str (.-errorMessage message)) "The request to the model was too large"))
                   (is (isContextOverflow message))))
               (testing "the gateway's 502 to this small request stays what it is, for pi-durable's retry"
                 (let [^js message (await (ask fetching (str url "/gateway")))]
                   (is (= "502 Internal server error." (.-errorMessage message)))
                   (is (not (isContextOverflow message)))))
               (testing "on a machine no fetch is handed to pi-ai: the SDK takes the global one, which `install!` wraps"
                 (let [global (.-fetch js/globalThis)]
                   (set! (.-fetch js/globalThis) fetching)
                   (try
                     (is (isContextOverflow (await (ask nil (str url "/large")))))
                     (finally
                       (set! (.-fetch js/globalThis) global)))))
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
               (await (fetching (str url "/ok/v1/messages")
                                #js {:method "POST" :headers #js {"X-Session-Id" "conv-8"} :body messages-body}))
               (is (nil? (model-requests/last-request "conv-8")) "a message the Anthropic way is not kept: the summary asks a chat completion again")
               (is (some? (model-requests/last-request "conv-7")) "another conversation's stays")
               (await (fetching (str url "/ok/v1/messages")
                                #js {:method "POST" :headers #js {"X-Session-Id" "conv-7"} :body messages-body}))
               (is (nil? (model-requests/last-request "conv-7")) "a conversation switched to Claude: its last chat completion is no longer its last request")
               (finally
                 (reset! model-requests/last-requests {})
                 (await (stop)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
