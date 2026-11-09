(ns rragent.triggers-test
  "A trigger of the loop is an input into its conversation, not a run
   (docs/one-loop.md §6): a schedule's fire and a hook's call arrive
   as entries with the job's topic, whatever mode the job was set
   with; the activity lists no run of the loop's."
  (:require [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.machine :as machine]
            [rragent.runs :as runs]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- ^:async entries
  "The entries of a conversation, oldest first, as `{:kind :data}`."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (reverse)
         (mapv (fn [e] {:kind (.-kind e) :data (js->clj (.-data e) :keywordize-keys true)})))))

(deftest triggers-into-the-loop-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (try
                 (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
                 (let [loop (machine/loop-id)
                       _ (bots/set-metadata! home loop "model" "faux-1")
                       conversation (:conversation (machine/bot loop))
                       harness (:harness (machine/agent))]
                   (testing "a schedule of the loop fires into its chat with its topic; no run of the loop's"
                     (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick: check the covers." :topic "content-week"} "s1")
                                                           (tools-test/answer "Set.")]
                                                          (repeat 20 (tools-test/answer "Tock.")))))
                     (is (= "Set." (await (harness/ask-in conversation "Every second, check the covers."))))
                     (await (sleep 2500))
                     (let [fires (filter #(= "rragent.schedule" (:kind %)) (await (entries conversation)))]
                       (is (<= 1 (count fires)) (pr-str fires))
                       (is (every? #(= {:name "tick" :topic "content-week"} (dissoc (:data %) :at)) fires) "the fire carries the job's topic")
                       (is (empty? (await (runs/listed harness (js/Number loop) 10 harness/context))) "no run: the loop handles the fire in its chat")))
                   (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "delete" :name "tick"} "s2")
                                                         (tools-test/answer "Deleted.")]
                                                        (repeat 20 (tools-test/answer "Tock.")))))
                   (is (= "Deleted." (await (harness/ask-in conversation "Stop that."))))
                   (await (sleep 1500))
                   (testing "a hook of the loop, mode run, lands in its chat as an input with the topic"
                     (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review" :topic "content-week"} "h1")
                                              (tools-test/answer "Made.")
                                              (tools-test/answer "Noted the review.")])
                     (is (= "Made." (await (harness/ask-in conversation "Make a hook for reviews."))))
                     (let [doc (await (.snapshot harness hooks/Hooks (.-id conversation) harness/context))
                           secret (.. doc -reviews -secret)
                           result (await (hooks/receive! {:harness harness :conversation conversation} "reviews" secret {} "five stars" harness/context))]
                       (is (:ok result))
                       (is (some? (:submission result)) "an input, not a run")
                       (is (nil? (:run result)))
                       (await (.waitForIdle conversation harness/context))
                       (let [calls (filter #(= "rragent.hook" (:kind %)) (await (entries conversation)))]
                         (is (= 1 (count calls)))
                         (is (= {:name "reviews" :bytes 10 :topic "content-week"} (:data (first calls)))))
                       (is (empty? (await (runs/listed harness (js/Number loop) 10 harness/context)))))))
                 (finally
                   (await (machine/close-all!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
