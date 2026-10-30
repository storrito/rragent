(ns rragent.messages
  "A message from one bot of the machine to another, which the `bot`
   tool sends (`rragent.tools.bot`, its `message`): a background task
   of the sender's conversation (`Task`, kind `rragent.message`),
   after pi-durable's subagent-background example. The task submits
   the text into the receiver's conversation as an input it answers
   in its own chat, an entry of the kind `kind` that names the sender
   (the page shows it as a message from that bot, `rragent.view`),
   the receiver's definition read again first (`rragent.reload`), as
   before a message of the person's; waits for the answer; and
   submits the answer's text into the sender's conversation the same
   way, as a message from the receiver, which the sender answers in
   its chat. Both submissions carry the task's id as their request
   id, so a restart sends nothing twice. The task is background: it
   keeps the sender neither busy nor from being stopped, and the
   receiver's conversation is nobody's but its own. Nothing goes back
   by itself after that: a bot that wants more messages again."
  (:require ["@earendil-works/pi-durable" :refer [defineTask]]
            [rragent.reload :as reload]
            [rragent.tools.subagent :as subagent-tool]))

(def kind
  "The entry of a bot's message in a conversation, with `:from` (the
   sending bot's id), `:name` (its name as the message was sent),
   `:text`, and `:answer` true when it answers a message of the
   conversation's bot."
  "rragent.message")

(defn message-content
  "What the receiving bot reads."
  [from-name from-id text]
  (str "A message from " from-name " (bot " from-id " of this machine): " text
       "\n\nAnswer it here as you would the person; your answer reaches " from-name " by itself. "
       "Message it only when you need something of it, never to reply to its answer."))

(defn answer-content
  "What the sending bot reads once the receiver answered."
  [to-name to-id answer]
  (str to-name " (bot " to-id ") answers your message: " answer
       "\n\nTell the person what matters to them of it. Message " to-name " again only when something is still needed of it."))

(defn unanswered-content
  "What the sending bot reads when the receiver did not answer."
  [to-name to-id reason]
  (str to-name " (bot " to-id ") did not answer your message: " reason
       ". Tell the person in a sentence."))

(defn- terminal
  [outcome]
  #js {:status "terminal" :outcome outcome})

(defn- ^:async answer-of
  "The text of the answer entry `id` of the receiver's conversation,
   read through a commit of the task that changes nothing."
  [runtime id context]
  (let [found (atom nil)]
    (await (.commit runtime
                    (^:async fn [tx]
                      (reset! found (await (.entry tx id)))
                      js/undefined)
                    context))
    (when @found
      (subagent-tool/answer-text @found))))

(defn- ^:async deliver!
  "The receiver's answer, or why there is none, submitted into the
   sender's conversation, the task's own, once (its request id is the
   task's)."
  [runtime ^js input {:keys [answer reason]} context]
  (let [sender (await (.conversation runtime (.-conversationId runtime) context))
        to (.-to input)
        to-name (.-toName input)]
    (await (.submit sender
                    #js {:type "input"
                         :content (if answer
                                    (answer-content to-name to answer)
                                    (unanswered-content to-name to reason))
                         :whenBusy "followUp"
                         :requestId (str "message-answer:" (.-taskId runtime))
                         :entry #js {:kind kind
                                     :data #js {:from to :name to-name :text (or answer (str "(no answer: " reason ")")) :answer true}}}
                    context))))

(defn- ^:async send!
  "The message submitted into the receiver's conversation, once (its
   request id is the task's), its answer waited for and delivered to
   the sender; the task's outcome says whether it was answered."
  [task runtime context]
  (let [^js input (.-input task)
        id (.-taskId runtime)
        receiver (or (await (.conversation runtime (.-to input) context))
                     (throw (js/Error. (str "no conversation " (.-to input) " in the storage"))))]
    (await (reload/reload! receiver context))
    (let [submission (await (.submit receiver
                                     #js {:type "input"
                                          :content (message-content (.-fromName input) (.-from input) (.-text input))
                                          :whenBusy "followUp"
                                          :requestId (str "message:" id)
                                          :entry #js {:kind kind
                                                      :data #js {:from (.-from input) :name (.-fromName input) :text (.-text input)}}}
                                     context))
          settled (await (.wait submission context))
          done? (= "done" (.-status settled))
          answer (when done?
                   (or (await (answer-of runtime (.-answer settled) context))
                       "(The bot answered without text.)"))]
      (await (deliver! runtime input {:answer answer :reason (when-not done? (.-reason settled))} context))
      (await (.commit runtime
                      (fn [] (terminal #js {:status "completed"
                                            :result #js {:state (if done? "answered" "unanswered")}}))
                      context)))))

(def Task
  (defineTask
   #js {:name kind
        :version 1
        :initial (fn [_input] #js {:phase "send"})
        :phases #js {:send (fn [task runtime context]
                             (send! task runtime context))}
        :abort (fn [_task runtime context]
                 (.commit runtime (fn [] (terminal #js {:status "aborted"})) context))}))

(defn create-in
  "The task of a message from the bot `from` to the bot `to` (ids as
   numbers, their names as the sender knows them, `:text`), made in
   the transaction `tx` in the sender's conversation, in the
   background. Answers a promise of the task's id."
  [tx {:keys [from from-name to to-name text]}]
  (.createTask tx Task
               #js {:from from :fromName from-name :to to :toName to-name :text text}
               #js {:ownership #js {:kind "conversation"}
                    :conversationId from
                    :background true}))
