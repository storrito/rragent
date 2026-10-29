(ns rragent.schedule
  "The agent's schedules: cron lines the agent writes itself, each a
   durable pi-durable task that sleeps until the next due minute and
   then starts a run of the bot with the schedule's text
   (`rragent.runs`), then sleeps again; a schedule of the mode
   `message` submits the text as an input to the bot's own
   conversation instead, as if the person had typed it. The schedules
   live in the document `rragent.schedules`, `{name {cron tz text mode
   taskId}}`; a schedule set again or deleted leaves its old task to
   end at its next wake, when it finds it is no longer the current one.
   Five fields, or six with seconds first; the time zone an IANA name,
   UTC by default. A fire reads the definition again first
   (`rragent.reload`), as the composer does, so the run starts from
   the file as it is.

   The machine's alarm (`rragent.wake`) is one for all the bots of
   the process: every open bot keeps how to read its earliest next
   run in its map of the one atom (`watch!`, by `rragent.harness`), and `rearm!` sets the
   alarm to the earliest across them, after every change of a schedule
   and after every fire."
  (:require ["croner" :refer [Cron]]
            ["@earendil-works/pi-durable" :refer [defineDoc defineTask]]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.reload :as reload]
            [rragent.runs :as runs]
            [rragent.wake :as wake]))

(def Schedules
  (defineDoc #js {:kind "rragent.schedules"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "current"
                  :initial (fn [] #js {})}))

(defn check-cron
  "The cron line parsed, or a throw with croner's reason."
  [cron tz]
  (Cron. cron #js {:timezone (or tz "UTC")}))

(defn next-run
  "The next run of the cron line after `from-ms`, as milliseconds; nil
   when there is none."
  [cron tz from-ms]
  (some-> (.nextRun (check-cron cron tz) (js/Date. from-ms)) .getTime))

(def catch-up-ms
  "How far behind a schedule is still run: a machine asleep for an hour
   runs what it missed, one asleep for a day does not run a day of
   runs."
  (* 60 60 1000))

(defn next-after
  "The run after the one at `at`: the schedule's own next run, so that
   a rerun of the phase after a crash (its submission deduplicated by
   the request id) does not skip a run; but one more than `catch-up-ms`
   behind `now` is skipped, the next run from now instead."
  [cron tz at now]
  (let [next (next-run cron tz (inc at))]
    (if (and next (< next (- now catch-up-ms)))
      (next-run cron tz now)
      next)))

(defn next-due
  "The earliest next run of the schedules in the document, nil without
   any."
  [schedules now]
  (let [runs (keep (fn [s] (next-run (.-cron s) (.-tz s) now)) (js/Object.values (or schedules #js {})))]
    (when (seq runs)
      (apply min runs))))

(defn watch!
  "The bot of the conversation `id` has schedules to read with `due-fn`,
   a function of no arguments that answers a promise of its earliest
   next run in milliseconds, nil without one: its `:schedule-source` in
   the one atom (`rragent.db`)."
  [id due-fn]
  (swap! db/db assoc-in (conj (db/bot-path id) :schedule-source) due-fn))

(defn sources
  "The schedule sources of every open bot."
  []
  (keep :schedule-source (vals (:bots @db/db))))

(defn- ^:async due-of
  "What the source answers; nil when it fails (a bot closing)."
  [due-fn]
  (try
    (await (due-fn))
    (catch :default _
      nil)))

(defn ^:async next-due-of-all
  "The earliest next run across the open bots, nil without any."
  []
  (let [dues (await (js/Promise.all (into-array (map due-of (sources)))))]
    (when-let [known (seq (remove nil? (array-seq dues)))]
      (apply min known))))

(defn ^:async rearm!
  "The machine's alarm set to the earliest next run of every open
   bot's schedules: after every change of them and after every fire,
   so that a machine asleep is woken for the next run, whichever
   bot's and whichever schedule's it is."
  [now]
  (wake/arm! (await (next-due-of-all)) now))

(defn- message
  [name cron tz text at]
  (str "Scheduled run \"" name "\" (" cron " " tz "), due " (.toISOString (js/Date. at)) ":\n" text))

(defn- terminal
  [outcome]
  #js {:status "terminal" :outcome outcome})

(def Task
  (defineTask
   #js {:name "rragent.schedule"
        :version 1
        :initial (fn [input] #js {:phase "wait" :at (.-at input)})
        :phases
        #js {:wait
             (^:async fn [task runtime context]
               (let [{:keys [name cron tz text]} (js->clj (.-input task) :keywordize-keys true)
                     at (.. task -state -checkpoint -at)]
                 (await (.sleep runtime at context))
                 (let [schedules (await (.snapshot runtime Schedules (.-conversationId runtime) context))
                       entry (some-> schedules (aget name))
                       current (some-> entry .-taskId)]
                   (if (not= current (.-taskId runtime))
                     ;; set again or deleted meanwhile: this task's time is over
                     (await (.commit runtime (fn [] (terminal #js {:status "completed" :result nil})) context))
                     (let [next (next-after cron tz at (.now runtime))
                           content (message name cron tz text at)
                           moved (fn []
                                   (if next
                                     #js {:status "running" :checkpoint #js {:phase "wait" :at next}}
                                     (terminal #js {:status "completed" :result nil})))]
                       (await (reload/reload-in-task! runtime context))
                       (if (= "message" (.-mode entry))
                         (let [conversation (await (.conversation runtime (.-conversationId runtime) context))]
                           (await (.submit conversation
                                           #js {:type "input"
                                                :content content
                                                :whenBusy "followUp"
                                                :requestId (str "schedule:" name ":" at)
                                                :entry #js {:kind "rragent.schedule" :data #js {:name name :at at}}}
                                           context))
                           (await (.commit runtime (fn [] (moved)) context)))
                         ;; the run's task and the schedule's next step in one commit:
                         ;; a crash in between makes neither twice
                         (await (.commit runtime
                                         (^:async fn [tx]
                                           (await (runs/create-in tx (.-conversationId runtime)
                                                                  {:trigger {:kind "schedule" :name name}
                                                                   :content content}))
                                           (moved))
                                         context)))
                       ;; the alarm moves on with the schedule: the machine may well
                       ;; sleep before the next run
                       (await (rearm! (.now runtime))))))))}
        :abort (fn [_task runtime context]
                 (.commit runtime (fn [] (terminal #js {:status "aborted"})) context))}))

(def modes
  "`run`, a fire starts a run of the bot; `message`, a fire reaches the
   bot's own conversation."
  #{"run" "message"})

(defn ^:async set!
  "The schedule `name` set to `cron` in `tz` with `text` and `mode`
   (`run` without one), replacing an earlier one of the name: a new
   task, then the document naming it. Answers the next run."
  [api name cron tz text mode now call-context]
  (let [tz (or (not-empty tz) "UTC")
        mode (or (not-empty mode) "run")
        at (or (next-run cron tz now)
               (throw (js/Error. (str "the cron line " cron " has no next run"))))]
    (when-not (contains? modes mode)
      (throw (js/Error. "a schedule's mode is run or message")))
    ;; the task first, then the document that makes it the current one: a
    ;; crash in between leaves a task that ends at its first wake, since
    ;; the document does not name it
    (let [task-id (await (.createTask api Task
                                      #js {:name name :cron cron :tz tz :text text :at at}
                                      #js {:ownership #js {:kind "conversation"} :background true}
                                      call-context))]
      (await (.commit api
                      (^:async fn [tx]
                        (let [doc (await (.doc tx Schedules (.-conversationId api)))]
                          (aset doc name #js {:cron cron :tz tz :text text :mode mode :taskId task-id})))
                      call-context)))
    at))

(defn ^:async delete!
  "The schedule `name` removed from the document; its task ends at its
   next wake. Answers whether there was one."
  [api name call-context]
  (let [found (atom false)]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Schedules (.-conversationId api)))]
                        (reset! found (some? (aget doc name)))
                        (js-delete doc name)))
                    call-context))
    @found))

(defn listed
  "The schedules without their task ids, for the model."
  [schedules]
  (->> (js/Object.entries (or schedules #js {}))
       (map (fn [[name s]] (str name ": " (.-cron s) " " (.-tz s) " (next " (some-> (next-run (.-cron s) (.-tz s) (js/Date.now)) (js/Date.) .toISOString) "): " (.-text s) (when (= "message" (.-mode s)) " · mode message"))))
       (str/join "\n")))
