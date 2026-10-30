(ns rragent.history-test
  "The messages a compaction took out of the model's view stay on the
   page: read from the storage once the context's head marker changes,
   shown above the note, the note telling what the model keeps."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.history :as history]
            [rragent.machine :as machine]
            [rragent.page :as page]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.view :as view]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- ^:async history-of
  "The bot's `:history` once it is there, waited for up to five
   seconds; nil without one."
  [id]
  (loop [tries 50]
    (let [history (:history (machine/bot id))]
      (if (or history (zero? tries))
        history
        (do (await (sleep 100))
            (recur (dec tries)))))))

(deftest history-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "faux-1" "wide-1"))
                 {:keys [faux models]} (tools-test/faux-models {:models [{:id "wide-1" :contextWindow 1050000}]})
                 ;; some 25,000 tokens each, four characters a token
                 long-text (fn [word] (str/join " " (repeat 25000 word)))
                 env (.-env js/process)
                 budget-was (aget env "RRAGENT_CONTEXT_TOKENS")]
             (.setResponses faux #js [(tools-test/answer "Noted.") (tools-test/answer "Noted again.") (tools-test/answer "The person sent two long texts.")])
             ;; no compaction by the budget: the test asks for one itself
             ;; once the conversation is long enough for a summary to keep
             ;; the newest 20,000 tokens and cut before them
             (aset env "RRAGENT_CONTEXT_TOKENS" "1000000")
             (let [[bot] (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
                   id (:id bot)]
               (try
                 (is (= "Noted." (await (harness/ask-in (:conversation bot) (long-text "red")))))
                 (is (nil? (history/marker (:value (machine/bot id)))) "the context starts at the transcript's start")
                 (is (nil? (:history (machine/bot id))) "no history without a marker")
                 (is (= "Noted again." (await (harness/ask-in (:conversation bot) (long-text "blue")))))
                 (await (harness/compact-unless-compacting! (:harness (machine/agent)) (js/Number id)))
                 (let [{:keys [head entries] :as history} (await (history-of id))
                       marker (history/marker (:value (machine/bot id)))]
                   (is (some? history) "the compaction placed its summary, the history was read")
                   (is (= "pi.compaction" (some-> marker .-kind)))
                   (is (= (some-> marker .-id) head) "read for this marker")
                   (is (seq entries))
                   (is (every? #(< (:id %) (.-head marker)) entries) "the entries before the first one kept")
                   (is (= (sort (map :id entries)) (map :id entries)) "oldest first")
                   (is (some #(and (= "pi.user" (:kind %)) (str/starts-with? (str (:content (first (:model %)))) "red red")) entries) "the first message is in it")
                   (testing "the page: the whole conversation, the note where the model's view begins, the summary behind it"
                     (let [items (vec (view/items (page/conversation (:value (machine/bot id)) history)))
                           note (first (filter #(= :note (:kind %)) items))]
                       (is (= :user (:kind (first items))))
                       (is (str/starts-with? (:text (first items)) "red red"))
                       (is (some? note))
                       (is (pos? (.indexOf items note)) "after the history")
                       (is (str/includes? (str (:summary note)) "two long texts") "what the model keeps")
                       (is (= [:i18n :chat/compacted] (:text note)))))
                   (testing "the same marker reads nothing again"
                     (await (sleep 200))
                     (is (identical? history (:history (machine/bot id))))))
                 (finally
                   (if budget-was
                     (aset env "RRAGENT_CONTEXT_TOKENS" budget-was)
                     (js-delete env "RRAGENT_CONTEXT_TOKENS"))
                   (await (machine/close-all!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
