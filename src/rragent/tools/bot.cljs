(ns rragent.tools.bot
  "The `bot` tool of the loop: the bots of before, the conversations
   the manager and its workers had until the one conversation replaced
   them (docs/one-loop.md), archived and readable. `list` names them
   with their state; `transcript` reads one's chat after an entry id,
   folded for a model, so that the loop learns what was done without
   carrying the chat; `archive` marks one's chat as over, its files
   and page stay; `delete` removes one, refused while bots, schedules
   or hooks depend on it. Every action is the loop's alone
   (`rragent.machine/loop?`). No bot is made or messaged any more: the
   loop hands work to tasks (`rragent.tools.task`). The tool is made
   over the functions of the machine, so that the harness needs
   nothing of it; the extension keeps the task of a message of before
   (`rragent.messages`), so that a message still in the storage
   settles."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.messages :as messages]
            [rragent.tools.work :as work]))

(def description
  (str "The conversations of before on this machine, archived: the manager and its workers, each with its own chat and work folder, from before this one conversation. "
       "`list` shows them with their state, parent and project. "
       "`transcript` with `id` (and `since`, an entry id, 0 for all) reads one's chat after that entry, folded, the person's messages to it included, and names the last entry read: "
       "the way to learn what was done there without carrying its chat. "
       "`archive` with `id` marks a chat as over, its files and page stay; `delete` with `id` removes one, refused while other bots, schedules or hooks depend on it."))

(defn listed
  "The bots as a text for the model: name, id, state, and the parent,
   project and archived mark when the bot has them."
  [bots]
  (if (empty? bots)
    "No bots"
    (str/join "\n" (map (fn [{:keys [id name state parent project archived? loop?]}]
                          (str name " (" id "): " (clojure.core/name state)
                               (when loop? " · this conversation")
                               (when parent (str " · parent " parent))
                               (when project (str " · project " project))
                               (when archived? " · archived")))
                        bots))))

(defn- loop-only!
  "Throws when the conversation of the call is not the loop: `action`
   is the loop's."
  [loop? from action]
  (when-not (loop? from)
    (throw (js/Error. (str action " is the conversation's of this machine, not a task's.")))))

(defn delete-text
  "What `delete` answers for the machine's outcome
   (`rragent.machine/delete!`)."
  [id name outcome]
  (cond
    (= :deleted outcome) (str "Deleted " name " (" id "): its chat is hidden, its folder in the trash.")
    (= :last outcome) "The last bot of the machine stays."
    (= :manager outcome) "This conversation stays."
    (= :missing outcome) (str "No bot " id " on this machine.")
    (= :dependents (:refused outcome))
    (let [{:keys [children schedules hooks]} outcome]
      (str "Not deleted: " name " (" id ") has "
           (str/join ", " (remove nil? [(when (seq children) (str "the bots " (str/join ", " children) " as its workers"))
                                        (when (seq schedules) (str "the schedules " (str/join ", " schedules)))
                                        (when (seq hooks) (str "the hooks " (str/join ", " hooks)))]))
           ". Deal with those first, one at a time with the person: archive or delete the workers, drop the schedules and hooks."))
    :else (str outcome)))

(defn- by-id
  [bots id]
  (some (fn [bot] (when (= (str id) (str (:id bot))) bot)) bots))

(defn tool
  "The tool over the functions of the machine: `list` (the bots, each
   `{:id :name :state :parent :project :archived? :loop?}`), `loop?`
   (an id to whether it is the loop's), `archive!` (an id),
   `dependents` (an id to a promise of `{:children :schedules
   :hooks}`), `delete!` (an id to a promise of the outcome,
   `delete-text` says it) and `transcript` (an id and an entry id to a
   promise of `{:text :last :more?}`)."
  [{:keys [list loop? archive! dependents delete! transcript]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "list") (.Literal Type "transcript") (.Literal Type "archive") (.Literal Type "delete")])
                                       :id (.Optional Type (.String Type #js {:description "transcript, archive, delete: the id of the bot, as list shows it"}))
                                       :since (.Optional Type (.Number Type #js {:description "transcript: the entry id after which to read, 0 for the whole chat; the last read answered it"}))})
        :execute (^:async fn [args api call-context]
                   (let [from (await (work/bot-id api call-context))]
                     (loop-only! loop? from (.-action args))
                     (case (.-action args)
                       "list" (work/text (listed (list)))
                       "transcript" (let [id (str/trim (str (.-id args)))]
                                      (when (str/blank? id)
                                        (throw (js/Error. "transcript needs the id of the bot")))
                                      (let [{:keys [text last more?]} (await (transcript id (or (.-since args) 0)))]
                                        (work/text (str (if (str/blank? text) "(nothing new)" text)
                                                        "\n\n(last entry " last
                                                        (if more? "; there is more: read again with since " "; read again later with since ")
                                                        last ")"))))
                       "archive" (let [id (str/trim (str (.-id args)))
                                       target (by-id (list) id)]
                                   (when-not target
                                     (throw (js/Error. (str "No bot " id " on this machine. The bots:\n" (listed (list))))))
                                   (when (= (str from) id)
                                     (throw (js/Error. "That is you.")))
                                   (await (archive! id))
                                   (work/text (str "Archived " (:name target) " (" id "): its chat is over, its files and its page stay.")))
                       "delete" (let [id (str/trim (str (.-id args)))
                                      target (by-id (list) id)]
                                  (when (str/blank? id)
                                    (throw (js/Error. "delete needs the id of the bot")))
                                  (let [{:keys [children schedules hooks] :as found} (when target (await (dependents id)))]
                                    (work/text (delete-text id (:name target)
                                                            (if (or (seq children) (seq schedules) (seq hooks))
                                                              (assoc found :refused :dependents)
                                                              (await (delete! id))))))))))}))

(defn extension
  "The extension of the tool, for the registry of the machine, with
   the task of a message of before, so that one still in the storage
   settles."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]
                        :tasks #js [messages/Task]}))
