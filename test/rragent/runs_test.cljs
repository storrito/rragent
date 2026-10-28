(ns rragent.runs-test
  "The runs of a bot through the faux model: one at a time on the
   machine, a failed run reported into the bot's chat, the run's tools
   (no hooks or schedules of its own, `report` to the bot, `remember`
   into the bot's events file), and the record of a run."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.events :as events]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.runs :as runs]
            [rragent.schedule-test :as schedule-test]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest gate-test
  (async done
    (-> ((^:async fn []
           (runs/reset-gate!)
           (is (false? (runs/active?)))
           (is (true? (await (runs/enter! 1 nil))) "free: taken at once")
           (is (true? (await (runs/enter! 1 nil))) "held already: at once")
           (is (true? (runs/active?)))
           (let [second (runs/enter! 2 nil)
                 controller (js/AbortController.)
                 third (runs/enter! 3 (.-signal controller))
                 settled (atom nil)]
             (.then second (fn [v] (reset! settled v)))
             (await (sleep 10))
             (is (nil? @settled) "the second waits")
             (.abort controller)
             (is (false? (await third)) "the third gave up")
             (runs/leave! 7)
             (await (sleep 10))
             (is (nil? @settled) "a stranger's leave changes nothing")
             (runs/leave! 1)
             (is (true? (await second)) "the second's turn")
             (runs/leave! 2)
             (is (false? (runs/active?))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest lines-test
  (is (= "Posted the story." (runs/first-line "\n\nPosted the story.\nMore words.")))
  (is (= "" (runs/first-line nil)))
  (is (str/ends-with? (runs/first-line (apply str (repeat 300 "x"))) "…"))
  (is (= "Google Drive · file created · 4.txt" (runs/trigger-line {:kind "hook" :name "drive" :line "Google Drive · file created · 4.txt"})))
  (is (= "hook reviews" (runs/trigger-line {:kind "hook" :name "reviews"})))
  (is (= "schedule tick" (runs/trigger-line {:kind "schedule" :name "tick"})))
  (is (= "/bot/1/run/42" (runs/url 1 42)))
  (is (str/includes? (runs/instructions "Watch it.") "Watch it.\n\nYou are a run of the bot"))
  (is (str/includes? (runs/report-content 42 "No text in the review." 1) "/bot/1/run/42")))

(defn- by-conversation
  "A scripted answer for whichever conversation asks the faux model
   next: the run's (its instructions carry the run's note) gets `run`,
   the bot's gets `bot`. The faux model answers in order across
   conversations, and a report puts the bot's turn before the run's
   last answer."
  [run bot]
  (fn [context]
    (if (str/includes? (js/JSON.stringify (.-messages context)) "You are a run of the bot")
      run
      bot)))

(defn- ^:async hook-secret
  [agent name]
  (.-secret (aget (await (.snapshot (:harness agent) hooks/Hooks (.-id (:root agent)) harness/context)) name)))

(defn- ^:async settled
  "The run `id` once its task is terminal."
  [agent id]
  (await (.waitForTask (:harness agent) id harness/context))
  (await (runs/run (:harness agent) id harness/context)))

(defn- ^:async results-of
  "The texts of the tool results of the conversation `id`, by call id."
  [agent id]
  (let [conversation (await (.conversation (:harness agent) id harness/context))
        page (await (.entries conversation #js {} 100 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(deftest one-at-a-time-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/answer "Made.")
                                      ;; the first run holds the machine for a second
                                      (tools-test/tool-call "bash" {:command "sleep 1"} "b1")
                                      (tools-test/answer "First.")
                                      (tools-test/answer "Second.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Made." (await (harness/ask agent "Make a hook."))))
                 (let [secret (await (hook-secret agent "reviews"))
                       {first-id :run} (await (hooks/receive! agent "reviews" secret {} "one" harness/context))
                       {second-id :run} (await (hooks/receive! agent "reviews" secret {} "two" harness/context))]
                   (is (< first-id second-id))
                   (await (sleep 400))
                   (testing "while the first runs, the second waits without a conversation of its own yet"
                     (let [[second first] (await (schedule-test/runs-of agent))]
                       (is (= first-id (:id first)))
                       (is (= :running (:state first)) (pr-str first))
                       (is (some? (:conversation first)))
                       (is (= :queued (:state second)) (pr-str second))
                       (is (nil? (:conversation second)))
                       (is (true? (runs/active?)))))
                   (let [first (await (settled agent first-id))
                         second (await (settled agent second-id))]
                     (is (= [:done "First."] [(:state first) (:line first)]))
                     (is (= [:done "Second."] [(:state second) (:line second)]))
                     (is (<= (:ended first) (:started second)) "the second started after the first ended")
                     (is (not= (:conversation first) (:conversation second)))
                     (is (false? (runs/active?)))
                     (testing "listed newest first"
                       (is (= [second-id first-id] (map :id (await (schedule-test/runs-of agent))))))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-run-and-its-tools-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/answer "Made.")
                                      ;; the run: no hooks of its own, an event into the bot's file, a word to the person
                                      (tools-test/tool-call "hook" {:action "create" :name "mine" :purpose "x"} "r1")
                                      (tools-test/tool-call "schedule" {:action "list"} "r2")
                                      (tools-test/tool-call "remember" {:event "review-posted" :data {:id 4711}} "r3")
                                      (tools-test/tool-call "report" {:text "The review has no text, I posted nothing."} "r4")
                                      ;; the run's last answer and the bot's answer to the report, in whichever order they ask
                                      (by-conversation (tools-test/answer "Nothing to post, told the person.") (tools-test/answer "I told the person."))
                                      (by-conversation (tools-test/answer "Nothing to post, told the person.") (tools-test/answer "I told the person."))])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Made." (await (harness/ask agent "Make a hook."))))
                 (let [secret (await (hook-secret agent "reviews"))
                       {:keys [run]} (await (hooks/receive! agent "reviews" secret {} "{\"stars\":5}" harness/context))
                       {:keys [state line conversation]} (await (settled agent run))
                       results (await (results-of agent conversation))]
                   (is (= :done state))
                   (is (= "Nothing to post, told the person." line))
                   (testing "hooks and schedules are the bot's"
                     (is (str/includes? (get results "r1") "Hooks are the bot's"))
                     (is (str/includes? (get results "r2") "Schedules are the bot's")))
                   (testing "the event lands in the bot's file"
                     (is (str/includes? (get results "r3") "Noted review-posted"))
                     (is (= [{:event "review-posted" :id 4711}] (mapv #(dissoc % :at) (events/read (home/events-file (home/bot-dir home (.-id (:root agent)))))))))
                   (testing "the report reaches the bot's chat as an entry of its kind, which the bot answers"
                     (is (str/includes? (get results "r4") "Reported to the bot's chat"))
                     (await (.waitForIdle (:root agent) harness/context))
                     (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                           items (reverse (array-seq (.-items page)))
                           report (some #(when (= runs/report-kind (.-kind %)) %) items)]
                       (is (some? report))
                       (is (= {"run" run "state" "report" "line" "The review has no text, I posted nothing."} (js->clj (.-data report))))
                       (is (str/includes? (js/JSON.stringify (.-model report)) "A run of yours reports: The review has no text"))
                       (is (str/includes? (js/JSON.stringify (.-model report)) (runs/url (.-id (:root agent)) run)))
                       (is (= "pi.assistant" (.-kind (last items))) "the bot answered it"))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-failed-run-reports-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/answer "Made.")
                                      ;; the run, stopped while it works; the bot answers the failure
                                      (tools-test/tool-call "bash" {:command "sleep 5"} "b1")
                                      (tools-test/answer "The run was stopped; I will say so.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Made." (await (harness/ask agent "Make a hook."))))
                 (let [secret (await (hook-secret agent "reviews"))
                       {:keys [run]} (await (hooks/receive! agent "reviews" secret {} "one" harness/context))]
                   (await (sleep 400))
                   (let [{:keys [state conversation]} (await (runs/run (:harness agent) run harness/context))]
                     (is (= :running state))
                     (await (.abort (await (.conversation (:harness agent) conversation harness/context)) harness/context)))
                   (let [{:keys [state line]} (await (settled agent run))]
                     (is (= :failed state))
                     (is (str/starts-with? line "did not answer") line))
                   (testing "the failure is reported into the bot's chat and answered"
                     (await (.waitForIdle (:root agent) harness/context))
                     (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                           items (reverse (array-seq (.-items page)))
                           report (some #(when (= runs/report-kind (.-kind %)) %) items)]
                       (is (some? report))
                       (is (= "failed" (aget (.-data report) "state")))
                       (is (= "hook reviews" (aget (.-data report) "about")))
                       (is (str/includes? (js/JSON.stringify (.-model report)) "A run of yours failed"))
                       (is (= "pi.assistant" (.-kind (last items)))))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
