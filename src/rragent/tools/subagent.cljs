(ns rragent.tools.subagent
  "The `subagent` tool: the agent hands a self-contained task to a
   subagent and gets its answer back, so that a long job, many files
   read or many steps tried, runs in a transcript of its own and only
   the answer enters the agent's. The subagent is a conversation owned
   by the call (pi-durable's \"Abort and Subagents\"): it starts as a
   copy of the agent, its model, tools and instructions, with a note
   that it works alone and without this tool, so it starts no
   subagents of its own. Stopping the agent stops it; the agent waits
   for it. After a crash the call runs again, finds the same subagent
   and the same message, and waits on."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [AssistantEntry configure defineExtension defineTool]]))

(def note
  "What the subagent is told after the agent's instructions."
  (str "You are a subagent: the agent gave you one task, the message below. "
       "Nobody reads along and nobody answers questions, so do the task with your tools, deciding what is unclear yourself. "
       "End with an answer that holds everything the agent needs: the result, the files you made, and what did not work."))

(defn instructions
  "The subagent's instructions: the agent's, then the note."
  [agent-instructions]
  (if (seq agent-instructions)
    (str agent-instructions "\n\n" note)
    note))

(defn- text
  [s error?]
  #js {:output #js [#js {:type "text" :text s}]
       :isError error?})

(defn answer-text
  "The text of an assistant entry's model message."
  [entry]
  (->> (.-content (aget (.-model entry) 0))
       (filter #(= "text" (.-type %)))
       (map #(.-text %))
       (apply str)))

(declare extension)

(defn- ^:async child!
  "The conversation of the call: the one it owns when a rerun finds it,
   else a new one, the agent's copy with the subagent's instructions and
   without this extension."
  [api call-context]
  (let [agent (await (.agent api call-context))]
    (await (.commit api
                    (^:async fn [tx]
                      (if-let [existing (aget (.-items (await (.scanConversations tx #js {:ownerTaskId (.-taskId api)} 1))) 0)]
                        (.-id existing)
                        (let [id (.-id (await (.createConversation tx #js {:ownership #js {:kind "task" :taskId (.-taskId api)}})))]
                          (await (configure tx id #js {:instructions (instructions (.-instructions agent))
                                                       :extensions #js {:remove #js [extension]}}))
                          id)))
                    call-context))))

(def tool
  (defineTool
   #js {:name "subagent"
        :description (str "Hands a self-contained task to a subagent and answers its answer. "
                          "The subagent has your tools and instructions, works alone in a transcript of its own and cannot ask anyone, "
                          "so `task` says everything it needs: the goal, the files, what the answer should hold. "
                          "For a long job whose steps you need not see, such as reading many files or trying many approaches.")
        :parameters (.Object Type #js {:task (.String Type #js {:description "The task, complete: the subagent knows nothing else of this conversation"})})
        ;; a rerun after a crash finds the same child and the same submission
        :replay "safe"
        :execute (^:async fn [args api call-context]
                   (let [id (await (child! api call-context))]
                     ;; lets a page find the subagent's transcript from the call
                     (await (.details api #js {:conversationId id} call-context))
                     (let [conversation (await (.conversation api id call-context))
                           submission (await (.submit conversation #js {:type "input"
                                                                        :content (.-task args)
                                                                        :requestId (str "subagent:" (.-taskId api))}
                                                      call-context))
                           settled (await (.wait submission call-context))]
                       (if (= "done" (.-status settled))
                         (let [entry (await (.commit api (fn [tx] (.entry tx AssistantEntry (.-answer settled))) call-context))
                               answer (answer-text entry)]
                           (text (if (seq answer) answer "(The subagent answered without text.)") false))
                         (text (str "The subagent did not answer: " (.-reason settled)) true)))))}))

(def extension
  "The extension of the tool, which the subagent's agent leaves out."
  (defineExtension #js {:name "subagent"
                        :tools #js [tool]}))
