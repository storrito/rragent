(ns rragent.compaction-test
  "The compaction of the loop (docs/one-loop.md §4,
   `rragent.compaction`): an answer that read more than the budget
   starts the compaction task, which has the loop write its wiki first
   (the notice entry, the loop's \"ready\") and then compacts; the
   budget is a share of the model's window unless RRAGENT_CONTEXT_TOKENS
   names one."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.compaction :as compaction]
            [rragent.harness :as harness]
            [rragent.machine :as machine]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(deftest budget-test
  (let [before (aget (.-env js/process) "RRAGENT_CONTEXT_TOKENS")]
    (try
      (js-delete (.-env js/process) "RRAGENT_CONTEXT_TOKENS")
      (is (= 140000 (harness/budget-for 200000)) "a share of the window")
      (is (= 700000 (harness/budget-for 1000000)))
      (is (= harness/default-context-tokens (harness/budget-for nil)) "a window unknown: the default")
      (aset (.-env js/process) "RRAGENT_CONTEXT_TOKENS" "50000")
      (is (= 50000 (harness/budget-for 1000000)) "named: whatever the window")
      (finally
        (if before
          (aset (.-env js/process) "RRAGENT_CONTEXT_TOKENS" before)
          (js-delete (.-env js/process) "RRAGENT_CONTEXT_TOKENS"))))))

(defn- ^:async entries
  "The entries of a conversation, oldest first, as `{:kind :data :text}`."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (reverse)
         (mapv (fn [e] {:kind (.-kind e)
                        :data (js->clj (.-data e) :keywordize-keys true)
                        :text (when (= "pi.assistant" (.-kind e))
                                (->> (array-seq (.-content (aget (.-model e) 0)))
                                     (filter #(= "text" (.-type %)))
                                     (map #(.-text %))
                                     (apply str)))})))))

(defn- ^:async until
  [pred]
  (let [started (js/Date.now)]
    (loop []
      (cond
        (await (pred)) true
        (< 20000 (- (js/Date.now) started)) (throw (js/Error. "nothing after 20 s"))
        :else (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
                  (recur))))))

(deftest the-loop-writes-the-wiki-before-a-compaction-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models {:models [{:id "wide-1" :contextWindow 1050000}]})
                 ;; some 25,000 tokens each, four characters a token
                 long-text (fn [word] (str/join " " (repeat 25000 word)))
                 before (aget (.-env js/process) "RRAGENT_CONTEXT_TOKENS")]
             (aset (.-env js/process) "RRAGENT_CONTEXT_TOKENS" "40000")
             (try
               (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
               (let [loop (machine/loop-id)
                     _ (bots/set-metadata! home loop "model" "wide-1")
                     conversation (:conversation (machine/bot loop))]
                 (.setResponses faux #js [(tools-test/answer "Noted.")
                                          (tools-test/answer "Noted again.")
                                          (tools-test/answer "ready")
                                          (tools-test/answer "Two long texts came in; the topics' pages hold the state, see wiki/index.md.")])
                 (is (= "Noted." (await (harness/ask-in conversation (long-text "red")))))
                 (is (= "Noted again." (await (harness/ask-in conversation (long-text "blue")))) "past the budget: the compaction task starts")
                 (await (until (fn [] (-> (entries conversation)
                                          (.then (fn [es] (some #(= "pi.compaction" (:kind %)) es)))))))
                 (let [es (await (entries conversation))
                       kinds (mapv :kind es)
                       notice (.indexOf kinds compaction/notice-kind)
                       compacted (.indexOf kinds "pi.compaction")]
                   (testing "the notice first, the loop's ready, then the compaction"
                     (is (<= 0 notice) (pr-str kinds))
                     (is (< notice compacted))
                     (is (= {:reason "budget"} (:data (nth es notice))))
                     (is (some #(and (= "pi.assistant" (:kind %)) (= "ready" (:text %))) es))
                     (is (some #(and (= "pi.compaction" (:kind %))) es)))
                   (testing "once: no second task while the first is under way"
                     (is (false? (await (compaction/running? (:harness (machine/agent)) (js/Number loop) harness/context))) "the task ended")
                     (is (= 0 (.getPendingResponseCount faux)) "every scripted answer used, the summary among them"))))
               (finally
                 (if before
                   (aset (.-env js/process) "RRAGENT_CONTEXT_TOKENS" before)
                   (js-delete (.-env js/process) "RRAGENT_CONTEXT_TOKENS"))
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
