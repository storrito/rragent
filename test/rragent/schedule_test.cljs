(ns rragent.schedule-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.schedule :as schedule]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(deftest next-run-test
  (let [from (.getTime (js/Date. "2026-10-16T22:30:00Z"))]
    (is (= (.getTime (js/Date. "2026-10-19T06:00:00Z")) (schedule/next-run "0 8 * * 1-5" "Europe/Berlin" from)) "Friday night: Monday 8:00 Berlin, summer time")
    (is (= (+ from 2000) (schedule/next-run "*/2 * * * * *" "UTC" from)) "six fields: seconds")
    (is (thrown? js/Error (schedule/next-run "nonsense" "UTC" from)))
    (is (= (+ from 2000) (schedule/next-due #js {:a #js {:cron "*/2 * * * * *" :tz "UTC"} :b #js {:cron "0 8 * * *" :tz "UTC"}} from)) "the earliest of the schedules")
    (is (nil? (schedule/next-due #js {} from)))))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async kinds
  "The kinds of the conversation's entries, oldest first."
  [{:keys [root]}]
  (let [page (await (.entries root #js {} 100 js/undefined harness/context))]
    (mapv #(.-kind %) (reverse (array-seq (.-items page))))))

(deftest a-schedule-fires-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick: say tock."} "s1")
                                                   (tools-test/answer "Set.")]
                                                  (repeat 20 (tools-test/answer "Tock.")))))
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                 (try
                   (is (= "Set." (await (harness/ask agent "Every second, say tock."))))
                   (await (sleep 2500))
                   (testing "the schedule's text arrives as an input of its own kind, answered"
                     (let [k (await (kinds agent))]
                       (is (<= 1 (count (filter #(= "rragent.schedule" %) k))) (pr-str k))
                       (is (str/includes? (pr-str k) "pi.assistant"))))
                   (testing "deleted: no more runs after the task's next wake"
                     (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "delete" :name "tick"} "s2")
                                                           (tools-test/answer "Deleted.")]
                                                          (repeat 20 (tools-test/answer "Tock.")))))
                     (is (= "Deleted." (await (harness/ask agent "Stop that."))))
                     (await (sleep 1500))
                     (let [before (count (filter #(= "rragent.schedule" %) (await (kinds agent))))]
                       (await (sleep 2500))
                       (is (= before (count (filter #(= "rragent.schedule" %) (await (kinds agent))))) "no new runs")))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest hooks-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/answer "Made.")
                                      (tools-test/answer "A five-star review, posting.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Made." (await (harness/ask agent "Make a hook for reviews."))))
                 (let [doc (await (.snapshot (:harness agent) hooks/Hooks (.-id (:root agent)) harness/context))
                       secret (.. doc -reviews -secret)]
                   (is (re-matches #"[0-9a-f]{32}" secret))
                   (is (= {:ok false} (await (hooks/receive! agent "reviews" "wrong" {} "x" harness/context))))
                   (is (= {:ok false} (await (hooks/receive! agent "nothing" secret {} "x" harness/context))))
                   (let [{:keys [ok submission]} (await (hooks/receive! agent "reviews" secret {"content-type" "application/json" "x-secret" "no"} "{\"stars\":5}" harness/context))]
                     (is (true? ok))
                     (is (some? submission))
                     (await (.wait (await (.submission (:harness agent) submission harness/context)) harness/context))
                     (let [k (await (kinds agent))]
                       (is (= "rragent.hook" (last (butlast k))) (pr-str k)))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
