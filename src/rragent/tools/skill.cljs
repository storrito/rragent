(ns rragent.tools.skill
  "The `skill` tool: the person's own skills on the machine
   (`rragent.skills`), written by a bot when the person wants to keep
   what it does as a how-to for their other bots, and listed."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.skills :as skills]
            [rragent.tools.work :as work]))

(defn ^:async machine-home
  "The machine's folder, above the bot's of the call."
  [api call-context]
  (skills/machine-home (await (work/bot-dir api call-context))))

(def description
  (str "The person's own skills on this machine, beside ours. `create` writes `skills/<name>/SKILL.md` on the machine from "
       "`name` (lowercase letters, digits, dashes), `description` (one line), `title` and `summary` (what the assistant offers a person), "
       "`ask` (the first question a new bot asks: the inputs, such as the account to post to), `instructions` (the body: the job as a skill, "
       "in the second person, with the inputs as the answers the person gives) and `files` (paths under your working directory to copy "
       "beside it: templates, examples; name them in the instructions as `<skill>/<file>`). "
       "Use it when the person wants to keep what you do as a how-to for their other bots: \"keep this as a how-to\", \"make a skill of this\". "
       "A skill of ours a person wants changed is copied under a name of your own and changed there; ours stay as they are. "
       "`list` names the person's skills with their folders; a new bot takes one with the `bot` tool and `skill: <name>`."))

(defn- string
  [what]
  (.Optional Type (.String Type #js {:description what})))

(def tool
  (defineTool
   #js {:name "skill"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list")])
                                       :name (string "The skill's folder name: lowercase letters, digits, dashes")
                                       :description (string "One line: what the skill does")
                                       :title (string "What the assistant offers, two or three words")
                                       :summary (string "What the assistant offers, one sentence")
                                       :ask (string "The first question a new bot asks the person: the inputs")
                                       :placeholder (string "The composer's placeholder for the answer")
                                       :instructions (string "The body of SKILL.md: the job as a skill")
                                       :files (.Optional Type (.Array Type (.String Type #js {:description "A path under your working directory"})))})
        :execute (^:async fn [args api call-context]
                   (let [home (await (machine-home api call-context))]
                     (case (.-action args)
                       "create" (let [name (str (.-name args))
                                      folder (skills/write! home
                                                            {:name name
                                                             :description (.-description args)
                                                             :title (.-title args)
                                                             :summary (.-summary args)
                                                             :ask (.-ask args)
                                                             :placeholder (.-placeholder args)
                                                             :body (.-instructions args)
                                                             :files (some-> (.-files args) array-seq vec)}
                                                            (work/dir api))]
                                  (work/text (str "Skill " name " at " folder "; `<skill>` in it means that folder. "
                                                  "The assistant offers it with ours, a new bot takes it with skill: " name ".")))
                       "list" (work/text (skills/listed home)))))}))
