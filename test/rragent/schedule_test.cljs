(ns rragent.schedule-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.fake-sprite-env :as fake]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.runs :as runs]
            [rragent.wake :as wake]
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
    (is (nil? (schedule/next-due #js {} from))))
  (testing "the run after a run follows the schedule, unless it is more than an hour behind"
    (let [at (.getTime (js/Date. "2026-10-18T20:10:00Z"))]
      (is (= (.getTime (js/Date. "2026-10-18T20:15:00Z")) (schedule/next-after "*/5 * * * *" "UTC" at (.getTime (js/Date. "2026-10-18T20:18:00Z")))) "the machine woke late: the missed run still runs")
      (is (= (.getTime (js/Date. "2026-10-19T09:20:00Z")) (schedule/next-after "*/5 * * * *" "UTC" at (.getTime (js/Date. "2026-10-19T09:17:00Z")))) "a day late: the next run from now")
      (is (= (.getTime (js/Date. "2026-10-18T20:15:00Z")) (schedule/next-after "*/5 * * * *" "UTC" at at)) "right on time: the next one"))))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async kinds
  "The kinds of the conversation's entries, oldest first."
  [{:keys [root]}]
  (let [page (await (.entries root #js {} 100 js/undefined harness/context))]
    (mapv #(.-kind %) (reverse (array-seq (.-items page))))))

(defn ^:async runs-of
  "The runs of the first bot, newest first."
  [{:keys [harness root]}]
  (await (runs/listed harness (.-id root) 50 harness/context)))

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
                   (testing "each fire starts a run of the bot, its answer the run's line; the bot's chat gets nothing"
                     (let [runs (await (runs-of agent))
                           k (await (kinds agent))]
                       (is (<= 1 (count runs)) (pr-str runs))
                       (is (every? #(= {:kind "schedule" :name "tick"} (:trigger %)) runs))
                       (is (some #(= :done (:state %)) runs) (pr-str runs))
                       (is (= "Tock." (:line (first (filter #(= :done (:state %)) runs)))))
                       (is (not-any? #(= "rragent.schedule" %) k) (pr-str k))
                       (is (not-any? #(= "rragent.run-report" %) k) "nothing failed")))
                   (testing "deleted: no more runs after the task's next wake"
                     (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "delete" :name "tick"} "s2")
                                                           (tools-test/answer "Deleted.")]
                                                          (repeat 20 (tools-test/answer "Tock.")))))
                     (is (= "Deleted." (await (harness/ask agent "Stop that."))))
                     (await (sleep 1500))
                     (let [before (count (await (runs-of agent)))]
                       (await (sleep 2500))
                       (is (= before (count (await (runs-of agent)))) "no new runs")))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-message-schedule-fires-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick: say tock." :mode "message"} "s1")
                                                   (tools-test/answer "Set.")]
                                                  (repeat 20 (tools-test/answer "Tock.")))))
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                 (try
                   (is (= "Set." (await (harness/ask agent "Every second, say tock, here in the chat."))))
                   (await (sleep 2500))
                   (testing "the mode message: the schedule's text arrives in the bot's chat as an input of its own kind, answered; no run"
                     (let [k (await (kinds agent))]
                       (is (<= 1 (count (filter #(= "rragent.schedule" %) k))) (pr-str k))
                       (is (str/includes? (pr-str k) "pi.assistant"))
                       (is (= [] (await (runs-of agent))))))
                   (testing "the mode is listed"
                     (let [schedules (await (harness/schedules (:harness agent) (.-id (:root agent))))]
                       (is (str/includes? (schedule/listed schedules) "tick: * * * * * * UTC"))
                       (is (str/includes? (schedule/listed schedules) "mode message"))))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest the-alarm-moves-on-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 {:keys [script log]} (fake/make!)
                 ;; the test may cross a minute boundary: either next minute is right
                 started (js/Date.now)
                 minutes #{(wake/cron-of (wake/next-minute started)) (wake/cron-of (+ 60000 (wake/next-minute started)))}]
             (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick."} "s1")
                                                   (tools-test/answer "Set.")]
                                                  (repeat 20 (tools-test/answer "Tock.")))))
             (binding [sprite/*sprite-env* script]
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                 (try
                   (is (= "Set." (await (harness/ask agent "Every second."))))
                   (let [armed-at-set (count (filter #(str/starts-with? % "automation update rragent-wake") (fake/calls log)))]
                     (is (<= 1 armed-at-set) "the alarm set by the tool (a first fire may have come already)")
                     (await (sleep 2500))
                     (is (< armed-at-set (count (filter #(str/starts-with? % "automation update rragent-wake") (fake/calls log)))) "and again after every fire")
                     (is (every? (fn [call] (some #(str/includes? call (str "--cron " % " --exec")) minutes))
                                 (filter #(str/starts-with? % "automation update rragent-wake") (fake/calls log)))
                         "to the next minute: a second-by-second schedule is always due within it"))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest hooks-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 requests (atom [])]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/tool-call "hook" {:action "create" :name "chat" :purpose "A word for the chat" :mode "message"} "h2")
                                      (tools-test/answer "Made.")
                                      (fn [context]
                                        (swap! requests conj (js/JSON.stringify (.-messages context)))
                                        (tools-test/answer "A five-star review, posting."))
                                      (tools-test/answer "Noted the word.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Made." (await (harness/ask agent "Make a hook for reviews, and one for the chat."))))
                 (let [doc (await (.snapshot (:harness agent) hooks/Hooks (.-id (:root agent)) harness/context))
                       secret (.. doc -reviews -secret)
                       chat-secret (.. doc -chat -secret)
                       before (count (await (kinds agent)))]
                   (is (re-matches #"[0-9a-f]{32}" secret))
                   (is (= "run" (.. doc -reviews -mode)))
                   (is (= "message" (.. doc -chat -mode)))
                   (is (str/includes? (hooks/listed doc) "chat: ") )
                   (is (str/includes? (hooks/listed doc) "mode message"))
                   (testing "the bot's definition holds a job: no warning in the tool's answer"
                     (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                           results (->> (array-seq (.-items page))
                                        (filter #(= "pi.tool-result" (.-kind %)))
                                        (map #(tools-test/result-text (aget (.-model %) 0))))]
                       (is (some #(str/includes? % "each call starts a run of yours.") results))
                       (is (not-any? #(str/includes? % "generic assistant") results))))
                   (is (= {:ok false} (await (hooks/receive! agent "reviews" "wrong" {} "x" harness/context))))
                   (is (= {:ok false} (await (hooks/receive! agent "nothing" secret {} "x" harness/context))))
                   (testing "a call starts a run: the bot's copy, told that it is a run, with the call as its message; its answer is the run's line"
                     (let [{:keys [ok run submission]} (await (hooks/receive! agent "reviews" secret {"content-type" "application/json" "x-secret" "no"} "{\"stars\":5}" harness/context))]
                       (is (true? ok))
                       (is (number? run))
                       (is (nil? submission))
                       (await (.waitForTask (:harness agent) run harness/context))
                       (let [{:keys [state line trigger conversation started ended]} (await (runs/run (:harness agent) run harness/context))
                             [request] @requests
                             child (await (.conversation (:harness agent) conversation harness/context))
                             child-agent (await (.agent child harness/context))]
                         (is (= :done state))
                         (is (= "A five-star review, posting." line))
                         (is (= {:kind "hook" :name "reviews"} trigger) "no event line in a plain JSON body")
                         (is (and (number? started) (number? ended) (<= started ended)))
                         (is (not= conversation (.-id (:root agent))) "a conversation of its own")
                         (is (= (:work agent) (.-cwd child-agent)) "the bot's work folder")
                         (is (str/includes? (.-instructions child-agent) "You watch a page.") "the bot's definition")
                         (is (str/includes? (.-instructions child-agent) "You are a run of the bot") "and the run's note")
                         (is (str/includes? request "Hook \\\"reviews\\\" (A new review) was called") "the call is the run's message")
                         (is (str/includes? request "content-type: application/json"))
                         (is (not (str/includes? request "x-secret")) "a header not kept")
                         (is (= before (count (await (kinds agent)))) "nothing in the bot's chat"))))
                   (testing "a hook of the mode message reaches the bot's chat"
                     (let [{:keys [ok submission run]} (await (hooks/receive! agent "chat" chat-secret {} "hello" harness/context))]
                       (is (true? ok))
                       (is (some? submission))
                       (is (nil? run))
                       (await (.wait (await (.submission (:harness agent) submission harness/context)) harness/context))
                       (let [k (await (kinds agent))]
                         (is (= "rragent.hook" (last (butlast k))) (pr-str k))))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
