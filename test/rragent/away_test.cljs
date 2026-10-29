(ns rragent.away-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.away :as away]
            [rragent.db :as db]
            [rragent.harness :as harness]
            [rragent.machine :as machine]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def options
  {:idle-ms 600000 :min-tokens 50000})

(deftest due-test
  (let [answers {1 {:at 0 :tokens 80000}
                 2 {:at 0 :tokens 20000}
                 3 {:at 500000 :tokens 90000}}]
    (is (= [1] (away/due answers 600000 options)) "large and quiet; a small one is not worth it, a recent one not yet")
    (is (= #{1 3} (set (away/due answers 1100000 options))))
    (is (away/pending? answers 50000 (constantly true)) "a large one waits for its compaction")
    (is (not (away/pending? {2 {:at 0 :tokens 20000}} 50000 (constantly true))))
    (is (not (away/pending? answers 50000 #{2})) "only a bot's")))

(deftest tick-test
  (async done
    (-> ((^:async fn []
           (away/forget-all!)
           (let [compacted (atom [])]
             (away/note! 1 80000 0)
             (away/note! 2 80000 0)
             (away/note! 3 80000 0)
             (away/note! 4 80000 590000)
             (await (away/tick! {:now 600000
                                 :known? #(not= 2 %)
                                 :idle? #(not= 3 %)
                                 :compact! (fn [id] (swap! compacted conj id) (js/Promise.resolve nil))}))
             (is (= [1] @compacted) "the quiet bot compacted")
             (is (= #{3 4} (set (keys @away/answers))) "no bot's forgotten, one with a run going and a recent one kept")
             (await (away/tick! {:now 600000 :known? (constantly true) :idle? (constantly true)
                                 :compact! (fn [_] (js/Promise.reject (js/Error. "no model")))}))
             (is (= #{4} (set (keys @away/answers))) "a failed compaction is said, not tried on every tick")
             (away/forget-all!))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally done))))

(deftest busy-test
  (away/forget-all!)
  (swap! db/db assoc-in (db/bot-path 1) {:id "1"})
  (try
    (is (not (machine/busy?)))
    (away/note! 1 80000 0)
    (is (machine/busy?) "a bot's large conversation keeps the machine awake until it is compacted")
    (away/forget-all!)
    (away/note! 1 2000 0)
    (is (not (machine/busy?)) "a small one does not")
    (away/forget-all!)
    (away/note! 7 80000 0)
    (is (not (machine/busy?)) "nor one that is no bot's: a subagent's")
    (finally
      (away/forget-all!)
      (swap! db/db update :bots dissoc "1"))))

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

(deftest away-compaction-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "faux-1" "wide-1"))
                 ;; a window far off, so that pi-durable's own threshold stays out of it
                 {:keys [faux models]} (tools-test/faux-models {:models [{:id "wide-1" :contextWindow 1050000}]})
                 ;; some 25,000 tokens each, four characters a token
                 long-text (fn [word] (str/join " " (repeat 25000 word)))]
             (.setResponses faux #js [(tools-test/answer "Noted.") (tools-test/answer "Noted again.") (tools-test/answer "Two long texts.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                   root (:root agent)
                   id (.-id root)]
               (try
                 (await (harness/ask agent (long-text "red")))
                 (await (harness/ask agent (long-text "blue")))
                 (let [{:keys [tokens at]} (get @away/answers id)]
                   (is (< 50000 tokens) "the last answer noted with what it read")
                   (is (<= (- (js/Date.now) 60000) at)))
                 (is (machine/busy?) "the machine stays awake for it, the bot being open")
                 (await (away/tick! {:known? (constantly true) :idle? (constantly true)
                                     :compact! #(harness/compact-unless-compacting! (:harness agent) %)}))
                 (is (nil? (await (compaction-entry root))) "not before the conversation has been quiet")
                 (await (away/tick! {:now (+ (js/Date.now) away/idle-ms)
                                     :known? (constantly true) :idle? (constantly true)
                                     :compact! #(harness/compact-unless-compacting! (:harness agent) %)}))
                 (let [entry (await (compaction-entry root))]
                   (is (some? entry) "compacted once quiet")
                   (is (str/includes? (js/JSON.stringify entry) "Two long texts.")))
                 (is (not (away/pending? (constantly true))) "and no longer waited for")
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
