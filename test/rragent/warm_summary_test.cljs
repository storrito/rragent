(ns rragent.warm-summary-test
  (:require ["@earendil-works/pi-durable" :refer [ProviderDoc]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.model-requests :as model-requests]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.warm-summary :as warm-summary]))

(def last-body
  "A conversation's last request as pi-ai sends it through OpenRouter."
  (js/JSON.stringify #js {:model "openai/gpt-6.1-sol"
                          :messages #js [#js {:role "system" :content "You are a video agent."}
                                         #js {:role "user" :content "Cut the clip."}
                                         #js {:role "assistant" :content "Cut."}]
                          :tools #js [#js {:type "function" :function #js {:name "bash" :parameters #js {:type "object"}}}]
                          :stream true
                          :stream_options #js {:include_usage true}
                          :max_completion_tokens 32000
                          :reasoning #js {:effort "medium"}}))

(deftest summary-body-test
  (let [^js original (js/JSON.parse last-body)
        ^js asked (js/JSON.parse (warm-summary/summary-body last-body "the cuts"))]
    (is (= (js->clj (.-messages original)) (js->clj (.slice (.-messages asked) 0 3))) "the conversation as it was, for the cache")
    (is (= (js->clj (.-tools original)) (js->clj (.-tools asked))) "its tools too")
    (is (= "medium" (.. asked -reasoning -effort)))
    (let [ask (aget (.-messages asked) 3)]
      (is (= "user" (.-role ask)))
      (is (str/starts-with? (.-content ask) "The messages above are a conversation to summarize."))
      (is (str/ends-with? (.-content ask) "Additional focus: the cuts")))
    (is (= "none" (.-tool_choice asked)) "nothing to call")
    (is (false? (.-stream asked)))
    (is (undefined? (.-stream_options asked)))
    (is (undefined? (.-max_completion_tokens asked)))
    (is (= warm-summary/max-tokens (.-max_tokens asked)))))

(deftest summary-of-test
  (is (= "## Goal\nCut." (warm-summary/summary-of #js {:choices #js [#js {:finish_reason "stop" :message #js {:content " ## Goal\nCut. "}}]})))
  (is (nil? (warm-summary/summary-of #js {:choices #js [#js {:finish_reason "length" :message #js {:content "## Go"}}]})) "cut off")
  (is (nil? (warm-summary/summary-of #js {:choices #js [#js {:finish_reason "stop" :message #js {:content ""}}]})))
  (is (nil? (warm-summary/summary-of #js {:error #js {:message "no"}})))
  (is (nil? (warm-summary/summary-of nil))))

(defn- answer
  "A provider's answer with `content`."
  [content]
  (js/Response. (js/JSON.stringify #js {:choices #js [#js {:finish_reason "stop" :message #js {:content content}}]
                                        :usage #js {:prompt_tokens 100 :prompt_tokens_details #js {:cached_tokens 98} :cost 0.001}})
                #js {:status 200 :headers #js {"content-type" "application/json"}}))

(deftest summarize-test
  (async done
    (let [before @warm-summary/post
          sent (atom nil)
          errors (atom [])
          original-error (.-error js/console)]
      (set! (.-error js/console) (fn [& args] (swap! errors conj (str/join " " args))))
      (reset! warm-summary/post (fn [url init] (reset! sent {:url url :init init}) (js/Promise.resolve (answer "## Goal\nCut the clip."))))
      (-> ((^:async fn []
             (is (= "## Goal\nCut the clip."
                    (await (warm-summary/summarize {:url "http://gw/v1/chat/completions"
                                                    :headers #js {"x-session-id" "s1" "content-length" "999" "authorization" "Bearer sprite"}
                                                    :body last-body}
                                                   nil))))
             (let [{:keys [url ^js init]} @sent]
               (is (= "http://gw/v1/chat/completions" url) "where the conversation's requests went")
               (is (= "s1" (aget (.-headers init) "x-session-id")) "in its session, so the provider routes it to its cache")
               (is (= "Bearer sprite" (aget (.-headers init) "authorization")))
               (is (undefined? (aget (.-headers init) "content-length")) "the old length dropped: the body is longer")
               (is (= "application/json" (aget (.-headers init) "accept")) "an answer, not a stream"))
             (is (some #(str/includes? % "98 of 100 tokens cached") @errors) "what came from the cache, said")
             (reset! warm-summary/post (fn [_ _] (js/Promise.resolve (js/Response. "{\"error\":{\"message\":\"down\"}}" #js {:status 502}))))
             (is (nil? (await (warm-summary/summarize {:url "u" :headers #js {} :body last-body} nil))) "a failed request: none")))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (set! (.-error js/console) original-error)
                      (reset! warm-summary/post before)
                      (done)))))))

(defn- ^:async compaction-entry
  "The `pi.compaction` entry of the conversation, waited for up to two
   seconds; nil without one."
  [conversation]
  (loop [tries 20]
    (let [view (await (.context conversation harness/context))
          entry (some #(when (= "pi.compaction" (.-kind %)) %) (array-seq (.-entries view)))]
      (if (or entry (zero? tries))
        entry
        (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
            (recur (dec tries)))))))

(deftest warm-compaction-test
  (async done
    (let [before @warm-summary/post]
      (-> ((^:async fn []
             (doseq [[label kept? summaries] [["with the last request kept, the summary from it" true ["Noted." "Noted again."]]
                                              ["without, pi-durable's own" false ["Noted." "Noted again." "Summary by pi-durable."]]]]
               (testing label
                 (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "faux-1" "wide-1"))
                       {:keys [faux models]} (tools-test/faux-models {:models [{:id "wide-1" :contextWindow 1050000}]})
                       asked (atom 0)
                       long-text (fn [word] (str/join " " (repeat 25000 word)))]
                   (reset! model-requests/last-requests {})
                   (reset! warm-summary/post (fn [_ _] (swap! asked inc) (js/Promise.resolve (answer "Summary from the cache."))))
                   (.setResponses faux (into-array (map tools-test/answer summaries)))
                   (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                         root (:root agent)]
                     (try
                       (await (harness/ask agent (long-text "red")))
                       (await (harness/ask agent (long-text "blue")))
                       (when kept?
                         (let [session (.-sessionId (await (.snapshot (:harness agent) ProviderDoc (.-id root) harness/context)))]
                           (model-requests/remember! session {:url "http://gw/v1/chat/completions" :headers #js {} :body last-body} (js/Date.now))))
                       (await (harness/compact-unless-compacting! (:harness agent) (.-id root)))
                       (let [entry (js/JSON.stringify (await (compaction-entry root)))]
                         (if kept?
                           (do (is (str/includes? entry "Summary from the cache."))
                               (is (= 1 @asked)))
                           (do (is (str/includes? entry "Summary by pi-durable."))
                               (is (zero? @asked)))))
                       (finally
                         (await (harness/close agent))
                         (test-home/remove! home)))))))))
            (.catch (fn [e] (is (nil? e) (str e))))
            (.finally (fn []
                        (reset! warm-summary/post before)
                        (reset! model-requests/last-requests {})
                        (done)))))))
