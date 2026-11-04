(ns rragent.costs-test
  "OpenRouter's charge caught in the stream and put into the message's
   usage: the chunk that carries it, the split of a response, the
   fetch wrapper around it, the adoption into pi-ai's usage, a tool's
   usage, and the factor the page shows."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.costs :as costs]
            [rragent.model-requests :as model-requests]))

(defn- with-factor
  "`thunk` with `RRAGENT_COST_FACTOR` as `v` (nil unset), restored after."
  [v thunk]
  (let [env (.-env js/process)
        before (aget env "RRAGENT_COST_FACTOR")]
    (try
      (if (nil? v) (js-delete env "RRAGENT_COST_FACTOR") (aset env "RRAGENT_COST_FACTOR" v))
      (thunk)
      (finally
        (if (nil? before) (js-delete env "RRAGENT_COST_FACTOR") (aset env "RRAGENT_COST_FACTOR" before))))))

(deftest factor-test
  (testing "the factor from the environment; Storrito's markup without one or with nonsense"
    (with-factor nil #(is (= 1.3 (costs/factor))))
    (with-factor "1" #(is (= 1 (costs/factor))))
    (with-factor "1.5" #(is (= 1.5 (costs/factor))))
    (with-factor "abc" #(is (= 1.3 (costs/factor))))
    (with-factor "0" #(is (= 1.3 (costs/factor))))
    (with-factor "-2" #(is (= 1.3 (costs/factor)))))
  (testing "what the page shows"
    (with-factor nil #(is (= 2.6 (costs/priced 2))))
    (with-factor "1" #(is (= 2 (costs/priced 2))))
    (is (nil? (costs/priced nil)))))

(def final-chunk
  "The chunk OpenRouter ends a stream with (seen 2026-11-02), the usage
   with the charge on it."
  "data: {\"id\":\"gen-1762000000-abc\",\"provider\":\"OpenAI\",\"model\":\"openai/gpt-6.1-sol\",\"object\":\"chat.completion.chunk\",\"created\":1762000000,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":80,\"total_tokens\":1280,\"cost\":0.00341,\"cost_details\":{\"upstream_inference_cost\":null},\"prompt_tokens_details\":{\"cached_tokens\":1000},\"completion_tokens_details\":{\"reasoning_tokens\":20}}}")

(deftest charge-of-chunk-test
  (is (= {:id "gen-1762000000-abc" :cost 0.00341} (costs/charge-of-chunk final-chunk)))
  (is (nil? (costs/charge-of-chunk "data: {\"id\":\"gen-1\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}")) "a chunk without usage")
  (is (nil? (costs/charge-of-chunk "data: {\"id\":\"gen-1\",\"usage\":{\"prompt_tokens\":3}}")) "usage without a charge, as OpenAI sends it")
  (is (nil? (costs/charge-of-chunk "data: [DONE]")))
  (is (nil? (costs/charge-of-chunk ": OPENROUTER PROCESSING")))
  (is (nil? (costs/charge-of-chunk "data: not json")))
  (is (nil? (costs/charge-of-chunk ""))))

(deftest remember-test
  (reset! costs/charges {})
  (costs/remember! "gen-a" 0.5 1000)
  (costs/remember! "gen-b" 0.25 2000)
  (is (= 0.5 (costs/take! "gen-a")))
  (is (nil? (costs/take! "gen-a")) "taken once")
  (is (nil? (costs/take! "gen-none")))
  (is (nil? (costs/take! nil)))
  (costs/remember! "gen-c" 0.1 (+ 2000 costs/kept-ms 1))
  (is (nil? (costs/take! "gen-b")) "forgotten after kept-ms")
  (is (= 0.1 (costs/take! "gen-c"))))

(deftest scan-test
  (reset! costs/charges {})
  (testing "complete lines are scanned, the incomplete rest is answered"
    (is (= "data: {\"id\":\"gen" (costs/scan! (str final-chunk "\n\ndata: {\"id\":\"gen") 1000)))
    (is (= 0.00341 (costs/take! "gen-1762000000-abc")))))

(defn- sse-response
  "A streamed response of `parts`, each its own chunk of the body."
  [parts]
  (js/Response. (js/ReadableStream. #js {:start (fn [controller]
                                                  (let [encoder (js/TextEncoder.)]
                                                    (doseq [p parts]
                                                      (.enqueue controller (.encode encoder p)))
                                                    (.close controller)))})
                #js {:status 200 :statusText "OK" :headers #js {"content-type" "text/event-stream" "x-request-id" "r1"}}))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(def stream-text
  (str "data: {\"id\":\"gen-1762000000-abc\",\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
       "data: {\"id\":\"gen-1762000000-abc\",\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
       final-chunk "\n\n"
       "data: [DONE]\n\n"))

(deftest tee-test
  (async done
    (-> ((^:async fn []
           (reset! costs/charges {})
           (testing "the caller reads the whole stream as it was, the charge is remembered; a chunk may cut a line"
             (let [at (quot (count stream-text) 2)
                   teed (costs/tee (sse-response [(subs stream-text 0 at) (subs stream-text at)]))]
               (is (= 200 (.-status teed)))
               (is (= "r1" (.get (.-headers teed) "x-request-id")))
               (is (= stream-text (await (.text teed))))
               (await (sleep 20))
               (is (= 0.00341 (costs/take! "gen-1762000000-abc")))))
           (testing "a response without a body stays what it is"
             (let [response (js/Response. nil #js {:status 204})]
               (is (identical? response (costs/tee response)))))
           (testing "the fetch wrapper splits the stream of a chat completion, and of nothing else"
             (let [fetch (model-requests/wrap (fn [_input _init] (js/Promise.resolve (sse-response [stream-text]))))
                   response (await (fetch "https://openrouter.ai/api/v1/chat/completions" #js {:method "POST" :body "{\"stream\":true}"}))]
               (is (= stream-text (await (.text response))))
               (await (sleep 20))
               (is (= 0.00341 (costs/take! "gen-1762000000-abc"))))
             (let [fetch (model-requests/wrap (fn [_input _init] (js/Promise.resolve (sse-response [stream-text]))))
                   response (await (fetch "https://openrouter.ai/api/v1/images" #js {:method "POST" :body "{}"}))]
               (is (= stream-text (await (.text response))))
               (await (sleep 20))
               (is (nil? (costs/take! "gen-1762000000-abc")) "not a completion: not read here")))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- message
  [id cost]
  #js {:role "assistant" :responseId id :usage #js {:input 1000 :output 80 :cacheRead 1000 :cacheWrite 0 :totalTokens 2080 :cost (clj->js cost)}})

(deftest adopt-test
  (reset! costs/charges {})
  (testing "the charge replaces the estimate, the parts scaled to it"
    (costs/remember! "gen-x" 0.005 1000)
    (let [m (message "gen-x" {:input 0.002 :output 0.0008 :cacheRead 0.0002 :cacheWrite 0 :total 0.003})]
      (is (= {:estimate 0.003 :charged 0.005} (costs/adopt! m)))
      (let [cost (js->clj (.. m -usage -cost) :keywordize-keys true)]
        (is (= 0.005 (:total cost)))
        (is (< (js/Math.abs (- (/ 0.01 3) (:input cost))) 1e-12) "two thirds of the charge, as of the estimate")
        (is (< (js/Math.abs (- 0.005 (+ (:input cost) (:output cost) (:cacheRead cost) (:cacheWrite cost)))) 1e-12) "the parts add up to the total"))
      (is (nil? (costs/adopt! m)) "taken once")))
  (testing "without an estimate the charge stands as input"
    (costs/remember! "gen-y" 0.004 1000)
    (let [m (message "gen-y" {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total 0})]
      (is (= {:estimate 0 :charged 0.004} (costs/adopt! m)))
      (is (= {:input 0.004 :output 0 :cacheRead 0 :cacheWrite 0 :total 0.004} (js->clj (.. m -usage -cost) :keywordize-keys true)))))
  (testing "no charge seen: the usage as it was"
    (let [m (message "gen-z" {:input 0.002 :output 0.0008 :cacheRead 0.0002 :cacheWrite 0 :total 0.003})]
      (is (nil? (costs/adopt! m)))
      (is (= 0.003 (.. m -usage -cost -total)))
      (is (nil? (costs/adopt! #js {:role "assistant"})) "a message without usage"))))

(deftest tool-usage-test
  (is (= {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :totalTokens 0
          :cost {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total 0.07}}
         (js->clj (costs/tool-usage 0.07) :keywordize-keys true))))
