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
   chat. A bot made with `create` has the maker as its parent and,
   with `project`, a project it belongs to, both written in the
   commit that makes its conversation (`rragent.machine/relate-in!`),
   and starts on the `model` and `thinking` level given, else the
   generic definition's. The manager's actions (`rragent.machine`,
   `manager?`): `archive` marks a bot's chat as over, its files and
   page stay; `delete` removes one, refused while bots, schedules or
   hooks depend on it, so that the manager deals with those first;
   `transcript` reads a bot's chat after an entry id, folded for a
   model, so that the manager learns what a worker did, also with the
   person directly, without carrying its chat. The tool is made over
   the functions of the machine, so that the harness needs nothing of
   it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.messages :as messages]
            [rragent.tools.work :as work]))

(def description
  (str "The bots of this machine, each a mind of its own with its own conversation, definition, schedules, hooks, facts and work folder. "
       "`create` with `name` and `instructions` (the new bot's job in a few sentences) makes one: it starts from the generic assistant definition "
       "with your instructions on top, so it adopts one of the skills on the machine or asks the person; `skill` names a skill it takes at once; "
       "`project` names the project it belongs to, `model` and `thinking` what it runs on (the defaults without). You are its parent. "
       "The person reaches it on its own page. `list` shows the bots with their state, parent and project. "
       "`message` with a bot's `id` and a `text` sends it a message: it arrives in that bot's chat like a message of the person's, "
       "and its answer comes back into this chat as a message from that bot, once it has answered; the call itself answers at once. "
       "Say in the text everything the bot needs: it knows nothing of this chat. "
       "For the manager alone: `transcript` with `id` (and `since`, an entry id, 0 for all) reads a bot's chat after that entry, "
       "folded, the person's messages to it included, and names the last entry read; `archive` with `id` marks a bot's chat as over, "
       "its files and page stay; `delete` with `id` removes a bot, refused while other bots, schedules or hooks depend on it."))

(defn listed
  "The bots as a text for the model: name, id, state, and the parent,
   project and archived mark when the bot has them."
  [bots]
  (if (empty? bots)
    "No bots"
    (str/join "\n" (map (fn [{:keys [id name state parent project archived? manager?]}]
                          (str name " (" id "): " (clojure.core/name state)
                               (when manager? " · the manager")
                               (when parent (str " · parent " parent))
                               (when project (str " · project " project))
                               (when archived? " · archived")))
                        bots))))

(defn- manager-only!
  "Throws when the bot of the call is not the manager: `action` is
   the manager's."
  [manager? from action]
  (when-not (manager? from)
    (throw (js/Error. (str action " is the manager's: ask the manager, the person reaches it from the sidebar.")))))

(defn delete-text
  "What `delete` answers for the machine's outcome
   (`rragent.machine/delete!`)."
  [id name outcome]
  (cond
    (= :deleted outcome) (str "Deleted " name " (" id "): its chat is hidden, its folder in the trash.")
    (= :last outcome) "The last bot of the machine stays."
    (= :manager outcome) "The manager stays."
    (= :missing outcome) (str "No bot " id " on this machine.")
    (= :dependents (:refused outcome))
    (let [{:keys [children schedules hooks]} outcome]
      (str "Not deleted: " name " (" id ") has "
           (str/join ", " (remove nil? [(when (seq children) (str "the bots " (str/join ", " children) " as its workers"))
                                        (when (seq schedules) (str "the schedules " (str/join ", " schedules)))
                                        (when (seq hooks) (str "the hooks " (str/join ", " hooks)))]))
           ". Deal with those first, one at a time with the person: archive or delete the workers, have it drop the schedules and hooks."))
    :else (str outcome)))

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
  "The tool over the functions of the machine: `adopt!` (a conversation
   id and a map of `:name`, `:instructions`, `:skill`, `:model` and
   `:thinking` to a promise of `{:id :name}`), `list` (the bots, each
   `{:id :name :state :parent :project :archived? :manager?}`),
   `check-skill!` (throws for a skill the machine does not have),
   `relate-in!` (a transaction, a child id and `{:parent :project}`),
   `manager?` (an id to whether it is the manager's), `archive!` (an
   id), `dependents` (an id to a promise of `{:children :schedules
   :hooks}`), `delete!` (an id to a promise of the outcome,
   `delete-text` says it) and `transcript` (an id and an entry id to a
   promise of `{:text :last :more?}`)."
  [{:keys [adopt! list check-skill! relate-in! manager? archive! dependents delete! transcript]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list") (.Literal Type "message")
                                                                 (.Literal Type "transcript") (.Literal Type "archive") (.Literal Type "delete")])
                                       :name (.Optional Type (.String Type #js {:description "create: the new bot's name, for the person"}))
                                       :instructions (.Optional Type (.String Type #js {:description "create: the new bot's job, a few sentences"}))
                                       :skill (.Optional Type (.String Type #js {:description "create: a skill the new bot takes at once, by its folder name: one of ours, or one of the person's (the skill tool lists them)"}))
                                       :project (.Optional Type (.String Type #js {:description "create: the name of the project the bot belongs to"}))
                                       :model (.Optional Type (.String Type #js {:description "create: the model the bot runs on, an OpenRouter id; the default without"}))
                                       :thinking (.Optional Type (.String Type #js {:description "create: the thinking level, low, medium, high, xhigh or max; the default without"}))
                                       :id (.Optional Type (.String Type #js {:description "message, transcript, archive, delete: the id of the bot, as list shows it"}))
                                       :text (.Optional Type (.String Type #js {:description "message: the message, complete: the bot knows nothing of this chat"}))
                                       :since (.Optional Type (.Number Type #js {:description "transcript: the entry id after which to read, 0 for the whole chat; the last read answered it"}))})
        :execute (^:async fn [args api call-context]
                   (case (.-action args)
                     "create" (do (when (str/blank? (str (.-name args)))
                                    (throw (js/Error. "create needs the name")))
                                  (when (str/blank? (str (.-instructions args)))
                                    (throw (js/Error. "create needs the instructions")))
                                  ;; before the conversation is made: a skill the machine lacks
                                  ;; would leave a conversation without a bot
                                  (check-skill! (not-empty (.-skill args)))
                                  (let [parent (await (work/bot-id api call-context))
                                        project (not-empty (str/trim (str (.-project args))))
                                        record (await (.commit api
                                                               (^:async fn [tx]
                                                                 (let [record (await (.createConversation tx #js {:ownership #js {:kind "ownerless"}}))]
                                                                   (await (relate-in! tx (.-id record) {:parent parent :project project}))
                                                                   record))
                                                               call-context))
                                        {:keys [id name]} (await (adopt! (.-id record) {:name (.-name args)
                                                                                         :instructions (.-instructions args)
                                                                                         :skill (not-empty (.-skill args))
                                                                                         :model (not-empty (str/trim (str (.-model args))))
                                                                                         :thinking (not-empty (str/trim (str (.-thinking args))))}))]
                                    (work/text (str "The bot " name " (" id ") is ready on its page, /bot/" id "."
                                                    (when project (str " It belongs to the project " project "."))
                                                    " You are its parent."))))
                     "list" (work/text (listed (list)))
                     "message" (await (message! args api call-context list))
                     "transcript" (let [from (await (work/bot-id api call-context))
                                        id (str/trim (str (.-id args)))]
                                    (manager-only! manager? from "transcript")
                                    (when (str/blank? id)
                                      (throw (js/Error. "transcript needs the id of the bot")))
                                    (let [{:keys [text last more?]} (await (transcript id (or (.-since args) 0)))]
                                      (work/text (str (if (str/blank? text) "(nothing new)" text)
                                                      "\n\n(last entry " last
                                                      (if more? "; there is more: read again with since " "; read again later with since ")
                                                      last ")"))))
                     "archive" (let [from (await (work/bot-id api call-context))
                                     id (str/trim (str (.-id args)))
                                     target (by-id (list) id)]
                                 (manager-only! manager? from "archive")
                                 (when-not target
                                   (throw (js/Error. (str "No bot " id " on this machine. The bots:\n" (listed (list))))))
                                 (when (= (str from) id)
                                   (throw (js/Error. "That is you.")))
                                 (await (archive! id))
                                 (work/text (str "Archived " (:name target) " (" id "): its chat is over, its files and its page stay.")))
                     "delete" (let [from (await (work/bot-id api call-context))
                                    id (str/trim (str (.-id args)))
                                    target (by-id (list) id)]
                                (manager-only! manager? from "delete")
                                (when (str/blank? id)
                                  (throw (js/Error. "delete needs the id of the bot")))
                                (let [{:keys [children schedules hooks] :as found} (when target (await (dependents id)))]
                                  (work/text (delete-text id (:name target)
                                                          (if (or (seq children) (seq schedules) (seq hooks))
                                                            (assoc found :refused :dependents)
                                                            (await (delete! id)))))))))}))

(defn extension
  "The extension of the tool, for the registry of the machine, with
   the task of a message."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]
                        :tasks #js [messages/Task]}))
