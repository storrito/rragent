(ns rragent.tools.bot
  "The `bot` tool: a bot makes another bot on the machine, or lists
   them. `create` with a name, the instructions (the new bot's job in
   a few sentences, put on top of the generic assistant definition
   every bot starts from, so that it adopts a skill or asks) and, when
   the job is one of the skills on the machine, the skill's name,
   which the new bot takes at its first message. The new bot's
   conversation is made in the call's own commit (`createConversation`
   of the transaction, ownerless, so it is a bot and not the call's
   subagent; the skill is checked before, so a refused create leaves
   no conversation behind), then the machine gives it its folder and
   definition (`rragent.machine/adopt!`); the call's result in the
   creator's transcript records it. `list` names the bots with their state.
   There is no message between bots: the person talks to each on its
   page. The tool is made over the functions of the machine, so that
   the harness needs nothing of it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.tools.work :as work]))

(def description
  (str "The bots of this machine, each a mind of its own with its own conversation, definition, schedules, hooks, facts and work folder. "
       "`create` with `name` and `instructions` (the new bot's job in a few sentences) makes one: it starts from the generic assistant definition "
       "with your instructions on top, so it adopts one of the skills on the machine or asks the person; `skill` names a skill it takes at once. "
       "The person reaches it on its own page. There is no message between bots. `list` shows the bots with their state."))

(defn listed
  "The bots as a text for the model."
  [bots]
  (if (empty? bots)
    "No bots"
    (str/join "\n" (map (fn [{:keys [id name state]}] (str name " (" id "): " (clojure.core/name state))) bots))))

(defn tool
  "The tool over `adopt!` (a conversation id and a map of `:name`,
   `:instructions` and `:skill` to a promise of `{:id :name}`) and
   `list` (the bots, each `{:id :name :state}`)."
  [{:keys [adopt! list]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "The new bot's name, for the person"}))
                                       :instructions (.Optional Type (.String Type #js {:description "The new bot's job, a few sentences"}))
                                       :skill (.Optional Type (.String Type #js {:description "A skill of the machine the new bot takes at once, by its folder name"}))})
        :execute (^:async fn [args api call-context]
                   (case (.-action args)
                     "create" (do (when (str/blank? (str (.-name args)))
                                    (throw (js/Error. "create needs the name")))
                                  (when (str/blank? (str (.-instructions args)))
                                    (throw (js/Error. "create needs the instructions")))
                                  ;; before the conversation is made: a skill the machine lacks
                                  ;; would leave a conversation without a bot
                                  (bots/check-skill! (not-empty (.-skill args)))
                                  (let [record (await (.commit api
                                                               (fn [tx] (.createConversation tx #js {:ownership #js {:kind "ownerless"}}))
                                                               call-context))
                                        {:keys [id name]} (await (adopt! (.-id record) {:name (.-name args)
                                                                                         :instructions (.-instructions args)
                                                                                         :skill (not-empty (.-skill args))}))]
                                    (work/text (str "The bot " name " (" id ") is ready on its page, /bot/" id "."))))
                     "list" (work/text (listed (list)))))}))

(defn extension
  "The extension of the tool, for the registry of the machine."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]}))
