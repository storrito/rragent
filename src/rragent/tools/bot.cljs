(ns rragent.tools.bot
  "The `bot` tool: a bot makes another bot on the machine, or lists
   them. `create` with a name, the instructions (the new bot's job in
   a few sentences, put on top of the generic assistant definition
   every bot starts from, so that it adopts a skill or asks) and, when
   the job is one of the skills on the machine, the skill's name,
   which the new bot takes at its first message. `list` names the
   bots with their state. There is no message between bots: the person
   talks to each on its page. The tool is made over the functions of
   the machine (`rragent.machine`), so that the harness needs nothing
   of it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
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
  "The tool over `create!` (a map of `:name`, `:instructions` and
   `:skill` to a promise of the new bot's entry) and `list` (a promise
   of the bots, each `{:id :name :state}`)."
  [{:keys [create! list]}]
  (defineTool
   #js {:name "bot"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "The new bot's name, for the person"}))
                                       :instructions (.Optional Type (.String Type #js {:description "The new bot's job, a few sentences"}))
                                       :skill (.Optional Type (.String Type #js {:description "A skill of the machine the new bot takes at once, by its folder name"}))})
        :execute (^:async fn [args _api _call-context]
                   (case (.-action args)
                     "create" (do (when (str/blank? (str (.-name args)))
                                    (throw (js/Error. "create needs the name")))
                                  (when (str/blank? (str (.-instructions args)))
                                    (throw (js/Error. "create needs the instructions")))
                                  (let [{:keys [id name]} (await (create! {:name (.-name args)
                                                                           :instructions (.-instructions args)
                                                                           :skill (not-empty (.-skill args))}))]
                                    (work/text (str "The bot " name " (" id ") is ready on its page, /bot/" id "."))))
                     "list" (work/text (listed (await (list))))))}))

(defn extension
  "The extension of the tool, for every bot's registry."
  [fns]
  (defineExtension #js {:name "bots"
                        :tools #js [(tool fns)]}))
