(ns rragent.schedule
  "The agent's schedules: cron lines the agent writes itself, each a
   durable pi-durable task that sleeps until the next due minute and
   then submits the schedule's text as an input to the conversation,
   as if the person had typed it, then sleeps again. The schedules
   live in the document `rragent.schedules`, `{name {cron tz text
   taskId}}`; a schedule set again or deleted leaves its old task to
   end at its next wake, when it finds it is no longer the current one.
   Five fields, or six with seconds first; the time zone an IANA name,
   UTC by default."
  (:require ["croner" :refer [Cron]]
            ["@earendil-works/pi-durable" :refer [defineDoc defineTask]]
            [clojure.string :as str]))

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

(defn next-due
  "The earliest next run of the schedules in the document, nil without
   any."
  [schedules now]
  (let [runs (keep (fn [s] (next-run (.-cron s) (.-tz s) now)) (js/Object.values (or schedules #js {})))]
    (when (seq runs)
      (apply min runs))))

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
                       current (some-> schedules (aget name) .-taskId)]
                   (if (not= current (.-taskId runtime))
                     ;; set again or deleted meanwhile: this task's time is over
                     (await (.commit runtime (fn [] (terminal #js {:status "completed" :result nil})) context))
                     (let [conversation (await (.conversation runtime (.-conversationId runtime) context))
                           next (next-run cron tz (max (inc at) (.now runtime)))]
                       (await (.submit conversation
                                       #js {:type "input"
                                            :content (message name cron tz text at)
                                            :whenBusy "followUp"
                                            :requestId (str "schedule:" name ":" at)
                                            :entry #js {:kind "rragent.schedule" :data #js {:name name :at at}}}
                                       context))
                       (await (.commit runtime
                                       (fn []
                                         (if next
                                           #js {:status "running" :checkpoint #js {:phase "wait" :at next}}
                                           (terminal #js {:status "completed" :result nil})))
                                       context)))))))}
        :abort (fn [_task runtime context]
                 (.commit runtime (fn [] (terminal #js {:status "aborted"})) context))}))

(defn ^:async set!
  "The schedule `name` set to `cron` in `tz` with `text`, replacing an
   earlier one of the name: a new task, then the document naming it.
   Answers the next run."
  [api name cron tz text now call-context]
  (let [tz (or (not-empty tz) "UTC")
        at (or (next-run cron tz now)
               (throw (js/Error. (str "the cron line " cron " has no next run"))))]
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
                          (aset doc name #js {:cron cron :tz tz :text text :taskId task-id})))
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
       (map (fn [[name s]] (str name ": " (.-cron s) " " (.-tz s) " (next " (some-> (next-run (.-cron s) (.-tz s) (js/Date.now)) (js/Date.) .toISOString) "): " (.-text s))))
       (str/join "\n")))
