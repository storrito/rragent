(ns rragent.tools.report
  "The `report` tool of a run: what the person must know now, submitted
   into the bot's chat as an input the bot answers, an entry of the
   kind `rragent.runs/report-kind` (a failed run reports by itself,
   `rragent.runs`). The bot's own conversation has no use for it: the
   person reads that chat."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.runs :as runs]
            [rragent.tools.work :as work]))

(defn ^:async owner-task
  "The task that owns the call's conversation, a run's; nil for a
   conversation nobody owns, the bot's."
  [api call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (some-> (await (.conversation tx (.-conversationId api))) .-owner .-taskId))
                  call-context)))

(def tool
  (defineTool
   #js {:name "report"
        :description (str "For a run: tell the person something now, through the bot's chat, in one or two sentences: "
                          "what could not be done and needs their word, what they should look at. "
                          "The bot gets it as a message and tells the person. Your closing line is not a report; it shows in the activity by itself.")
        :parameters (.Object Type #js {:text (.String Type #js {:description "One or two sentences for the person"})})
        :execute (^:async fn [args api call-context]
                   (let [bot-id (await (work/bot-id api call-context))
                         text (str (.-text args))]
                     (when (= bot-id (.-conversationId api))
                       (throw (js/Error. "You are the bot: the person reads this chat, tell them here.")))
                     (when (empty? text)
                       (throw (js/Error. "report needs the text")))
                     (let [run (await (owner-task api call-context))
                           bot (await (.conversation api bot-id call-context))]
                       (await (.submit bot
                                       #js {:type "input"
                                            :content (runs/report-content run text bot-id)
                                            :whenBusy "followUp"
                                            :requestId (str "run-report:" run ":" (.-callId api))
                                            :entry #js {:kind runs/report-kind
                                                        :data #js {:run run :state "report" :line text}}}
                                       call-context))
                       (work/text "Reported to the bot's chat."))))}))
