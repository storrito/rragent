(ns rragent.tools.schedule
  "The `schedule` tool: the agent sets, lists and deletes its schedules
   (`rragent.schedule`), and the machine's alarm follows
   (`rragent.wake`). A run or a subagent of the bot has no schedules
   of its own (`rragent.tools.work/bot-call!`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.docs :as docs]
            [rragent.schedule :as schedule]
            [rragent.tools.work :as work]))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(defn- ^:async rearm!
  "The machine's alarm set to the earliest next run of every bot."
  []
  (await (schedule/rearm! (js/Date.now))))

(def tool
  (defineTool
   #js {:name "schedule"
        :description (str "Your schedules: when the machine starts a run of yours with a text of your own. "
                          "`set` with a name, a cron line (five fields, or six with seconds first), a time zone (IANA name, UTC by default) "
                          "and the text the run gets then: what to do. Each fire starts a run, a fresh conversation with your definition, "
                          "your events and your artifacts, which does the job and ends with one line the person sees in your activity; "
                          "nothing of it enters this chat unless the run failed or reported something. "
                          "A run knows nothing of this chat, only your definition, your events and your artifacts: write the job into your definition before you set a schedule. "
                          "`mode: message` instead has the text reach you here as a message, for a rare reminder you want to talk about; never for anything frequent. "
                          "`delete` with the name; `list`. "
                          "A set schedule replaces one of the same name. Write the cron line yourself, for example `0 8 * * 1-5` for weekday mornings at eight.")
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "set") (.Literal Type "delete") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "The schedule's name"}))
                                       :cron (.Optional Type (.String Type #js {:description "The cron line"}))
                                       :tz (.Optional Type (.String Type #js {:description "The time zone of the cron line, an IANA name such as Europe/Berlin"}))
                                       :text (.Optional Type (.String Type #js {:description "The text the run gets when it fires: what to do then"}))
                                       :mode (.Optional Type (.Union Type #js [(.Literal Type "run") (.Literal Type "message")]))})
        :execute (^:async fn [args api call-context]
                   (let [action (.-action args)
                         name (.-name args)]
                     (await (work/bot-call! api call-context "Schedules"))
                     (case action
                       "set" (do (when (or (empty? name) (empty? (.-cron args)) (empty? (.-text args)))
                                   (throw (js/Error. "set needs name, cron and text")))
                                 (let [at (await (schedule/set! api name (.-cron args) (.-tz args) (.-text args) (.-mode args) (js/Date.now) call-context))]
                                   (await (rearm!))
                                   (text (str "Schedule " name " set; next run " (.toISOString (js/Date. at)) "."
                                              (when-not (= "message" (.-mode args))
                                                (await (work/job-warning api call-context)))))))
                       "delete" (do (when (empty? name)
                                      (throw (js/Error. "delete needs the name")))
                                    (let [found (await (schedule/delete! api name call-context))]
                                      (await (rearm!))
                                      (text (if found (str "Schedule " name " deleted") (str "No schedule " name)))))
                       "list" (let [schedules (await (docs/read api schedule/Schedules call-context))
                                    listed (schedule/listed schedules)]
                                (text (if (empty? listed) "No schedules" listed))))))}))
