(ns rragent.tools.bot
  "The `bot` tool: a bot makes another bot on the machine, lists them,
   sends one a message, or reads one's chat (docs/bots.md §6). `create`
   with a name, the instructions (the new bot's job in a few
   sentences, its file's `## Your job`; the rules every bot works by
   come with the bundle's prompt) and, when the job is one of the
   skills on the machine, the skill's name, which its file names and
   whose instructions it gets with every turn, one of ours or one of
   the person's on the machine (`rragent.skills`). The new bot's
   conversation is made in the call's own commit (`createConversation`
   of the transaction, ownerless, so it is a bot and not the call's
   task; the skill is checked before, so a refused create leaves no
   conversation behind), then the machine gives it its folder and
   definition (`rragent.machine/adopt!`); the call's result in the
   creator's transcript records it. A bot made with `create` has the
   maker as its parent and, with `project`, a project it belongs to,
   both written in the commit that makes its conversation
   (`rragent.machine/relate-in!`), and starts on the `model` and
   `thinking` level given, else the defaults; the model one the page
   offers or the maker's own (`rragent.models/task-model?`), no other
   id. `list` names the bots
   with their state. `message` with a bot's id and a text sends it a
   message: a task of the sender's conversation, made in the call's
   commit (`rragent.messages`), puts it into the other bot's chat,
   waits for the answer and brings it back into the sender's chat as
   a message from that bot; the call answers at once. A message of a
   run's or a task's call is the bot's: the answer arrives in the
   bot's chat. `transcript` reads a bot's chat after an entry id,
   folded for a model, so that a bot learns what was done elsewhere,
   in the chat the machine began with above all, without carrying
   that chat. Nobody's bot deletes or archives another: that is the
   person's, on the page. The tool is made over the functions of the
   machine, so that the harness needs nothing of it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.definition :as definition]
            [rragent.messages :as messages]
            [rragent.models :as models]
            [rragent.tools.work :as work]))

(def description
  (str "The bots of this machine, each a mind of its own with its own conversation, definition, schedules, hooks, facts and work folder. "
       "`create` with `name` and `instructions` (the new bot's job in a few sentences) makes one: the instructions are its job, "
       "and it works by the same rules as you; `skill` names a skill it takes at once, whose instructions it gets with every turn; "
       "`project` names the project it belongs to, `model` and `thinking` what it runs on: the default model (`" definition/default-model "`) without, "
       "or " (str/join " or " (map #(str "`" (:id %) "`") (rest models/choices))) ", no other id. You are its parent. "
       "The person reaches it on its own page. `list` shows the bots with their state, parent and project. "
       "`message` with a bot's `id` and a `text` sends it a message: it arrives in that bot's chat like a message of the person's, "
       "and its answer comes back into this chat as a message from that bot, once it has answered; the call itself answers at once. "
       "Say in the text everything the bot needs: it knows nothing of this chat. "
       "`transcript` with `id` (and `since`, an entry id, 0 for all) reads a bot's chat after that entry, folded, "
       "the person's messages to it included, and names the last entry read: the way to learn what was done in another chat without carrying it."))

(defn listed
  "The bots as a text for the model: name, id, state, and the parent,
   project and archived mark when the bot has them."
  [bots]
  (if (empty? bots)
    "No bots"
    (str/join "\n" (map (fn [{:keys [id name state parent project archived?]}]
                          (str name " (" id "): " (clojure.core/name state)
                               (when parent (str " · parent " parent))
                               (when project (str " · project " project))
                               (when archived? " · archived")))
                        bots))))

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
   `{:id :name :state :parent :project :archived?}`), `check-skill!`
   (throws for a skill the machine does not have), `relate-in!` (a
   transaction, a child id and `{:parent :project}`) and `transcript`
   (an id and an entry id to a promise of `{:text :last :more?}`)."
  [{:keys [adopt! list check-skill! relate-in! transcript]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list") (.Literal Type "message") (.Literal Type "transcript")])
                                       :name (.Optional Type (.String Type #js {:description "create: the new bot's name, for the person"}))
                                       :instructions (.Optional Type (.String Type #js {:description "create: the new bot's job, a few sentences"}))
                                       :skill (.Optional Type (.String Type #js {:description "create: a skill the new bot takes at once, by its folder name: one of ours, or one of the person's (the skill tool lists them)"}))
                                       :project (.Optional Type (.String Type #js {:description "create: the name of the project the bot belongs to"}))
                                       :model (.Optional Type (.String Type #js {:description (str "create: the model the bot runs on, one of " (str/join ", " (map :id models/choices)) "; the default without, no other id")}))
                                       :thinking (.Optional Type (.String Type #js {:description "create: the thinking level, low, medium, high, xhigh or max; the default without"}))
                                       :id (.Optional Type (.String Type #js {:description "message, transcript: the id of the bot, as list shows it"}))
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
                                  ;; a model the page offers, or the maker's own: an id from the
                                  ;; model's training would run the bot at a price nobody picked
                                  (when-let [model (not-empty (str/trim (str (.-model args))))]
                                    (when-not (models/task-model? model (some-> (await (.agent api call-context)) .-model .-modelId))
                                      (throw (js/Error. (str "No model " model " on this machine: " (str/join ", " (map :id models/choices))
                                                             ". Call again with one of them, or without `model`.")))))
                                  (when-let [thinking (not-empty (str/trim (str (.-thinking args))))]
                                    (when-not (models/level? thinking)
                                      (throw (js/Error. (str "No thinking level " thinking ": " (str/join ", " models/levels) ". Call again with one of them, or without `thinking`.")))))
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
                     "transcript" (let [id (str/trim (str (.-id args)))]
                                    (when (str/blank? id)
                                      (throw (js/Error. "transcript needs the id of the bot")))
                                    (let [{:keys [text last more?]} (await (transcript id (or (.-since args) 0)))]
                                      (work/text (str (if (str/blank? text) "(nothing new)" text)
                                                      "\n\n(last entry " last
                                                      (if more? "; there is more: read again with since " "; read again later with since ")
                                                      last ")"))))))}))

(defn extension
  "The extension of the tool, for the registry of the machine, with
   the task of a message."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]
                        :tasks #js [messages/Task]}))
