(ns rragent.tools.schedule
  "The `schedule` tool: the agent sets, lists and deletes its schedules
   (`rragent.schedule`), and the machine's alarm follows
   (`rragent.wake`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.docs :as docs]
            [rragent.schedule :as schedule]))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(defn- ^:async rearm!
  "The machine's alarm set to the earliest next run."
  [api call-context]
  (schedule/rearm! (await (docs/read api schedule/Schedules call-context)) (js/Date.now)))

(def tool
  (defineTool
   #js {:name "schedule"
        :description (str "Your schedules: when the machine wakes you with a message of your own. "
                          "`set` with a name, a cron line (five fields, or six with seconds first), a time zone (IANA name, UTC by default) "
                          "and the text you want to receive then; `delete` with the name; `list`. "
                          "A set schedule replaces one of the same name. Write the cron line yourself, for example `0 8 * * 1-5` for weekday mornings at eight.")
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "set") (.Literal Type "delete") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "The schedule's name"}))
                                       :cron (.Optional Type (.String Type #js {:description "The cron line"}))
                                       :tz (.Optional Type (.String Type #js {:description "The time zone of the cron line, an IANA name such as Europe/Berlin"}))
                                       :text (.Optional Type (.String Type #js {:description "The message you receive when it fires: what to do then"}))})
        :execute (^:async fn [args api call-context]
                   (let [action (.-action args)
                         name (.-name args)]
                     (case action
                       "set" (do (when (or (empty? name) (empty? (.-cron args)) (empty? (.-text args)))
                                   (throw (js/Error. "set needs name, cron and text")))
                                 (let [at (await (schedule/set! api name (.-cron args) (.-tz args) (.-text args) (js/Date.now) call-context))]
                                   (await (rearm! api call-context))
                                   (text (str "Schedule " name " set; next run " (.toISOString (js/Date. at))))))
                       "delete" (do (when (empty? name)
                                      (throw (js/Error. "delete needs the name")))
                                    (let [found (await (schedule/delete! api name call-context))]
                                      (await (rearm! api call-context))
                                      (text (if found (str "Schedule " name " deleted") (str "No schedule " name)))))
                       "list" (let [schedules (await (docs/read api schedule/Schedules call-context))
                                    listed (schedule/listed schedules)]
                                (text (if (empty? listed) "No schedules" listed))))))}))
