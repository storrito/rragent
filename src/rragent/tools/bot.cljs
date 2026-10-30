(ns rragent.tools.bot
  "The `bot` tool: a bot makes another bot on the machine, lists them,
   or sends one a message. `create` with a name, the instructions (the
   new bot's job in a few sentences, put on top of the generic
   assistant definition every bot starts from, so that it adopts a
   skill or asks) and, when the job is one of the skills on the
   machine, the skill's name, which the new bot takes at its first
   message, one of ours or one of the person's on the machine
   (`rragent.skills`). The new bot's conversation is made in the
   call's own commit (`createConversation` of the transaction,
   ownerless, so it is a bot and not the call's subagent; the skill
   is checked before, so a refused create leaves no conversation
   behind), then the machine gives it its folder and definition
   (`rragent.machine/adopt!`); the call's result in the creator's
   transcript records it. `list` names the bots with their state.
   `message` with a bot's id and a text sends it a message: a task of
   the sender's conversation, made in the call's commit
   (`rragent.messages`), puts it into the other bot's chat, waits for
   the answer and brings it back into the sender's chat as a message
   from that bot; the call answers at once. A message of a run's or a
   subagent's call is the bot's: the answer arrives in the bot's
   chat. The tool is made over the functions of the machine, so that
   the harness needs nothing of it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.messages :as messages]
            [rragent.tools.work :as work]))

(def description
  (str "The bots of this machine, each a mind of its own with its own conversation, definition, schedules, hooks, facts and work folder. "
       "`create` with `name` and `instructions` (the new bot's job in a few sentences) makes one: it starts from the generic assistant definition "
       "with your instructions on top, so it adopts one of the skills on the machine or asks the person; `skill` names a skill it takes at once. "
       "The person reaches it on its own page. `list` shows the bots with their state. "
       "`message` with a bot's `id` and a `text` sends it a message: it arrives in that bot's chat like a message of the person's, "
       "and its answer comes back into this chat as a message from that bot, once it has answered; the call itself answers at once. "
       "Say in the text everything the bot needs: it knows nothing of this chat."))

(defn listed
  "The bots as a text for the model."
  [bots]
  (if (empty? bots)
    "No bots"
    (str/join "\n" (map (fn [{:keys [id name state]}] (str name " (" id "): " (clojure.core/name state))) bots))))

(defn- by-id
  [bots id]
  (some (fn [bot] (when (= (str id) (str (:id bot))) bot)) bots))

(defn- ^:async message!
  "The task of the message made in the call's commit, in the
   conversation of the bot of the call (`rragent.tools.work/bot-id`);
   the receiver one of the bots listed, not the sender."
  [args api call-context list]
  (let [to (str/trim (str (.-id args)))
        text (str/trim (str (.-text args)))
        bots (list)]
    (when (str/blank? to)
      (throw (js/Error. "message needs the id of the bot")))
    (when (str/blank? text)
      (throw (js/Error. "message needs the text")))
    (let [from (await (work/bot-id api call-context))
          receiver (or (by-id bots to)
                       (throw (js/Error. (str "No bot " to " on this machine. The bots:\n" (listed bots)))))
          sender (by-id bots from)]
      (when (= (str from) to)
        (throw (js/Error. "That is you: the person reads this chat, tell them here.")))
      (await (.commit api
                      (fn [tx] (messages/create-in tx {:from from
                                                       :from-name (or (:name sender) (str from))
                                                       :to (js/Number to)
                                                       :to-name (:name receiver)
                                                       :text text}))
                      call-context))
      (work/text (str "Sent to " (:name receiver) " (" to "). Its answer arrives in this chat as a message from it once it has answered; "
                      "until then go on, or end your answer.")))))

(defn tool
  "The tool over `adopt!` (a conversation id and a map of `:name`,
   `:instructions` and `:skill` to a promise of `{:id :name}`), `list`
   (the bots, each `{:id :name :state}`) and `check-skill!` (throws for
   a skill the machine does not have)."
  [{:keys [adopt! list check-skill!]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list") (.Literal Type "message")])
                                       :name (.Optional Type (.String Type #js {:description "create: the new bot's name, for the person"}))
                                       :instructions (.Optional Type (.String Type #js {:description "create: the new bot's job, a few sentences"}))
                                       :skill (.Optional Type (.String Type #js {:description "create: a skill the new bot takes at once, by its folder name: one of ours, or one of the person's (the skill tool lists them)"}))
                                       :id (.Optional Type (.String Type #js {:description "message: the id of the bot, as list shows it"}))
                                       :text (.Optional Type (.String Type #js {:description "message: the message, complete: the bot knows nothing of this chat"}))})
        :execute (^:async fn [args api call-context]
                   (case (.-action args)
                     "create" (do (when (str/blank? (str (.-name args)))
                                    (throw (js/Error. "create needs the name")))
                                  (when (str/blank? (str (.-instructions args)))
                                    (throw (js/Error. "create needs the instructions")))
                                  ;; before the conversation is made: a skill the machine lacks
                                  ;; would leave a conversation without a bot
                                  (check-skill! (not-empty (.-skill args)))
                                  (let [record (await (.commit api
                                                               (fn [tx] (.createConversation tx #js {:ownership #js {:kind "ownerless"}}))
                                                               call-context))
                                        {:keys [id name]} (await (adopt! (.-id record) {:name (.-name args)
                                                                                         :instructions (.-instructions args)
                                                                                         :skill (not-empty (.-skill args))}))]
                                    (work/text (str "The bot " name " (" id ") is ready on its page, /bot/" id "."))))
                     "list" (work/text (listed (list)))
                     "message" (await (message! args api call-context list))))}))

(defn extension
  "The extension of the tool, for the registry of the machine, with
   the task of a message."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]
                        :tasks #js [messages/Task]}))
