(ns rragent.bots
  "The bots of a machine on disk. A bot is a conversation of the
   machine's one storage (`rragent.machine` lists them through
   pi-durable, there is no index file), and its folder is
   `<home>/bots/<id>/`, named by the conversation's id
   (`rragent.home` says what is in it). Beside them `<home>/shared/`,
   the folder every bot's bash reads and writes, and
   `<home>/trash/<id>/`, where a deleted bot's folder goes. The rules
   every bot works by are not on the machine's disk: the shared prompt
   comes from the bundle (`system-prompt`, `deploy/agent/SYSTEM-PROMPT.md`)
   and is read in front of a bot's own file at every turn
   (`rragent.reload`), so a release reaches every bot at its next
   turn; a bot's file holds its name, model and job alone
   (`definition-text`).

   A machine from before the bots ran one agent at the top of the
   home, `AGENT.md` (or a skill's `SKILL.md`) and `work/` beside
   `agent.sqlite`, the home being the agent's folder: `migrate!` moves
   everything but the storage into `bots/1/`, the folder of the root
   conversation (a skill's `tools/`, `README.md` … with its
   definition, which names them by `<skill>`), one rename each,
   nothing copied, the storage untouched, and leaves a home in the new
   layout alone (`rragent.harness/open` runs it); an `assistant.md` of
   before, the generic definition new bots once started from, stays at
   the top unread. A failure undoes the renames made and throws, the
   old layout as it was."
  (:require ["@earendil-works/pi-durable" :refer [ROOT_CONVERSATION_ID]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as node-url]
            [clojure.string :as str]
            [rragent.definition :as definition]
            [rragent.home :as home]))

(def root-id
  "The id of the root conversation of a pi-durable storage, the first
   bot's."
  ROOT_CONVERSATION_ID)

(defn bots-dir
  [home]
  (path/join home "bots"))

(defn shared-dir
  "The folder the bots share, made on first use."
  [home]
  (let [dir (path/join home "shared")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn trash-dir
  [home]
  (path/join home "trash"))

(defn valid-id?
  "A bot's id is its conversation's, a number."
  [id]
  (boolean (re-matches #"[0-9]+" (str id))))

(defn- directory?
  [p]
  (try
    (.isDirectory (fs/statSync p))
    (catch :default _
      false)))

(defn- bundled
  "A file the build keeps beside the bundle, `name`, else its `source`
   in the checkout."
  [name source]
  (let [beside (path/join (path/dirname (node-url/fileURLToPath (js* "import.meta.url"))) name)]
    (if (fs/existsSync beside)
      beside
      (path/resolve source))))

(defn bundled-system-prompt-file
  "The shared prompt as the bundle carries it: `system-prompt.md`
   beside the bundle (the build copies `deploy/agent/SYSTEM-PROMPT.md`
   there), else that file of the checkout."
  []
  (bundled "system-prompt.md" "deploy/agent/SYSTEM-PROMPT.md"))

(defn bundled-bot-file
  "What a bot is, as the bundle carries it: `bot.md` beside the bundle
   (the build copies `deploy/agent/BOT.md` there), else that file of
   the checkout."
  []
  (bundled "bot.md" "deploy/agent/BOT.md"))

(defn bundled-task-file
  "A task's text as the bundle carries it: `task.md` beside the bundle
   (the build copies `deploy/agent/TASK.md` there), else that file of
   the checkout."
  []
  (bundled "task.md" "deploy/agent/TASK.md"))

(declare skills-dir)

(defn skills-repo
  "The GitHub repository the skills clone comes from, `owner/name`, as
   its `.git/config` names it; nil without a clone."
  []
  (when-let [dir (skills-dir)]
    (try
      (second (re-find #"url\s*=\s*(?:https://github\.com/|git@github\.com:)([^/\s]+/[^/\s]+?)(?:\.git)?\s"
                       (fs/readFileSync (path/join dir ".git" "config") "utf8")))
      (catch :default _
        nil))))

(defn machine-prompt
  "What this machine adds to the shared prompt: the text of the file
   RRAGENT_MACHINE_PROMPT names, the rules of a machine that is not the
   usual Ubuntu one (a NixOS Sprite's `/etc/rragent/machine.md`,
   `deploy/nixos/MACHINE.md`: how programs are installed there and how
   the machine is upgraded). Nil without the variable, and for a file
   that does not read or is blank, the first said on stderr."
  []
  (when-let [file (not-empty (aget (.-env js/process) "RRAGENT_MACHINE_PROMPT"))]
    (try
      (not-empty (str/trim (fs/readFileSync file "utf8")))
      (catch :default e
        (js/console.error "[bots] RRAGENT_MACHINE_PROMPT names" file "which does not read:" (.-message e))
        nil))))

(defn system-prompt
  "The text every conversation of the machine works by, a bot's and a
   task's: the bundle's `SYSTEM-PROMPT.md`, with the skills repository
   of the clone filled in for `SKILLS_REPO`, and the machine's own text
   after it when it has one (`machine-prompt`). A release replaces the
   bundle's; nothing on the machine changes it."
  []
  (let [bundled (fs/readFileSync (bundled-system-prompt-file) "utf8")
        text (if-let [repo (skills-repo)]
               (str/replace bundled "`github.com/SKILLS_REPO`" (str "`github.com/" repo "`"))
               bundled)]
    (if-let [machine (machine-prompt)]
      (str (str/trim text) "\n\n" machine "\n")
      text)))

(defn bot-prompt
  "The text every bot of the machine works by, put in front of its own
   instructions at every read (`rragent.reload`): the shared prompt
   (`system-prompt`), then the bundle's `BOT.md`, what a bot is: its
   file, its tasks, its runs, the other bots, the wiki. `<wiki>` in it
   means the machine's wiki, which the definition fills in
   (`rragent.definition`)."
  []
  (str (str/trim (system-prompt)) "\n\n" (str/trim (fs/readFileSync (bundled-bot-file) "utf8")) "\n"))

(defn task-instructions
  "What a task's conversation is configured with (`rragent.tasks`):
   the shared prompt, then the bundle's `TASK.md`."
  []
  (str (str/trim (system-prompt)) "\n\n" (str/trim (fs/readFileSync (bundled-task-file) "utf8")) "\n"))

(defn- split-frontmatter
  "`[head body]` of a definition text, the head with its fences;
   throws without a frontmatter."
  [text]
  (let [[_ head body] (or (re-find #"(?s)^(---\r?\n.*?\r?\n---\r?\n)(.*)$" (str text))
                          (throw (js/Error. "The definition has no frontmatter.")))]
    [head body]))

(defn- with-name
  "The frontmatter `head` with `name` as its name, the value quoted
   the JSON way, which `rragent.definition` reads."
  [head name]
  (str/replace head #"(?m)^name:.*$" (str "name: " (js/JSON.stringify (str name)))))

(defn- quoted
  "A frontmatter value, quoted the JSON way, which `rragent.definition`
   reads."
  [s]
  (js/JSON.stringify (str s)))

(declare with-metadata)

(defn- first-line
  "The first line of `text`, at most 160 characters; nil for a blank
   text."
  [text]
  (when-let [line (first (remove str/blank? (str/split-lines (str text))))]
    (let [line (str/trim line)]
      (if (< 160 (count line)) (str (subs line 0 159) "…") line))))

(defn definition-text
  "The file of a new bot: a frontmatter with its `name` (the generic
   one without), its `description` (given, else the first line of the
   instructions, else the generic one, so that a bot with a job is no
   generic bot, see `rragent.definition/job-written?`), the `skill` it
   takes when one is named, and `metadata` with its `model` (the
   default without) and its `thinking` level when given; then `## Your
   job` with the `instructions` under it when there are any. Nothing
   else: the rules every bot works by are the shared prompt, read in
   front of the file at every turn, and a skill's instructions after
   it (`rragent.reload`)."
  [{:keys [name description instructions skill model thinking]}]
  (str "---\n"
       "name: " (quoted (or (not-empty (str name)) definition/generic-name)) "\n"
       "description: " (quoted (or (not-empty (str description)) (first-line instructions) definition/generic-description)) "\n"
       (when (seq (str skill)) (str "skill: " (quoted skill) "\n"))
       "metadata:\n"
       "  model: " (or (not-empty (str model)) definition/default-model) "\n"
       (when (seq (str thinking)) (str "  thinking: " thinking "\n"))
       "---\n"
       (when (seq (str/trim (str instructions)))
         (str "\n## Your job\n\n" (str/trim (str instructions)) "\n"))))

(defn skills-dir
  "Where the skills clone is: `RRAGENT_SKILLS`, else the image's
   folder; nil when neither is there (a laptop)."
  []
  (let [dir (or (not-empty (aget (.-env js/process) "RRAGENT_SKILLS")) "/home/sprite/skills")]
    (when (directory? dir)
      dir)))

(defn skill-names
  [dir]
  (->> (array-seq (fs/readdirSync dir #js {:withFileTypes true}))
       (filter (fn [^js e] (and (.isDirectory e) (fs/existsSync (path/join dir (.-name e) "SKILL.md")))))
       (map (fn [^js e] (.-name e)))
       (sort)
       (vec)))

(defn check-skill!
  "Throws when `skill` is not among the skills on the machine; nothing
   without a skills clone, where the bot finds out itself."
  [skill]
  (when (seq (str skill))
    (when-let [dir (skills-dir)]
      (let [names (skill-names dir)]
        (when-not (some #{(str skill)} names)
          (throw (js/Error. (str "No skill " skill " in " dir ": " (str/join ", " names) "."))))))))

(defn write-definition!
  "The folder of the bot `id` with `text` as its `AGENT.md` and its
   work folder; answers the folder."
  [home id text]
  (let [dir (home/bot-dir home id)]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "AGENT.md") text)
    (home/work-dir dir)
    dir))

(defn name-of
  "The name in the bot's definition file, nil without a file that
   reads."
  [home id]
  (when-let [file (home/definition-file (home/bot-dir home id))]
    (try
      (:name (definition/definition file))
      (catch :default _
        nil))))

(def renamed-line
  "The line of the frontmatter that says the person named the bot
   from the page: the shared prompt tells the bot to leave the
   name then. In the file, so it survives a restart."
  "renamed: true")

(defn- with-renamed
  "The frontmatter `head` with the `renamed: true` line, after the
   name's line when it is not there yet."
  [head]
  (if (re-find #"(?m)^renamed:" head)
    (str/replace head #"(?m)^renamed:.*$" renamed-line)
    (str/replace head #"(?m)^(name:.*)$" (str "$1\n" renamed-line))))

(defn rename!
  "The `name:` of the bot's definition file set to `name`, with the
   `renamed: true` line that says the person did it, which the reload
   picks up (`rragent.reload`) and the bot respects; a blank name is
   refused."
  [home id name]
  (let [name (str/trim (str name))
        file (or (home/definition-file (home/bot-dir home id))
                 (throw (js/Error. (str "The bot " id " has no definition file."))))]
    (when (str/blank? name)
      (throw (js/Error. "A bot needs a name.")))
    (let [[head body] (split-frontmatter (fs/readFileSync file "utf8"))]
      (fs/writeFileSync file (str (with-renamed (with-name head name)) body)))
    name))

(defn- with-metadata
  "The frontmatter `head` with `key: value` under `metadata:`: the
   key's line replaced when it is there, added right under `metadata:`
   else, with a `metadata:` block after the name when there is none."
  [head key value]
  (let [line (str "  " key ": " value)]
    (cond
      (re-find (re-pattern (str "(?m)^  " key ":")) head)
      (str/replace head (re-pattern (str "(?m)^  " key ":.*$")) line)

      (re-find #"(?m)^metadata:" head)
      (str/replace head #"(?m)^(metadata:.*)$" (str "$1\n" line))

      :else
      (str/replace head #"(?m)^(name:.*)$" (str "$1\nmetadata:\n" line)))))

(defn set-metadata!
  "The `key` of the bot's definition metadata set to `value` in its
   file (`model` and `thinking`, the switch under the composer), which
   the reload picks up (`rragent.reload`). Answers the value."
  [home id key value]
  (let [file (or (home/definition-file (home/bot-dir home id))
                 (throw (js/Error. (str "The bot " id " has no definition file."))))
        [head body] (split-frontmatter (fs/readFileSync file "utf8"))]
    (fs/writeFileSync file (str (with-metadata head key value) body))
    value))

(defn- metadata-of
  "The `model` and `thinking` of a definition text's metadata, the
   ones the person may have picked, which a rewrite keeps."
  [text]
  (select-keys (:metadata (or (definition/frontmatter text) {})) ["model" "thinking"]))

(def first-chat-name
  "The name of the chat the machine began with, in the sidebar."
  "Assistant")

(def first-chat-description
  "The description of the file of that chat (`first-chat-text`)."
  "The chat this machine began with. While the machine ran as one conversation, every matter of the person's went through it, so what was said then is read here. It works on as a bot like the others.")

(defn first-chat-text
  "The file of the bot that was the manager and then the one
   conversation of the machine (docs/bots.md §8), written once when
   the bots come back (`rragent.migration`): its name, said to be the
   person's so that the bot keeps it (`renamed-line`), a description
   of what its chat holds, and the `model` and `thinking` of `current`,
   the file as it was, the default model and thinking without. No
   body: it is a bot without a job of its own, as a new one is."
  [current]
  (let [{:strs [model thinking]} (when current (metadata-of current))]
    (str "---\n"
         "name: " (quoted first-chat-name) "\n"
         renamed-line "\n"
         "description: " (quoted first-chat-description) "\n"
         "metadata:\n"
         "  model: " (or (not-empty (str model)) definition/default-model) "\n"
         "  thinking: " (or (not-empty (str thinking)) definition/default-thinking) "\n"
         "---\n")))

(defn write-first-chat-definition!
  "The folder of the bot `id` with its `AGENT.md` as `first-chat-text`
   makes it, the file as it was read first for the picks it keeps;
   answers the folder."
  [home id]
  (let [dir (home/bot-dir home id)
        file (path/join dir "AGENT.md")
        current (when (fs/existsSync file) (fs/readFileSync file "utf8"))]
    (write-definition! home id (first-chat-text current))))

(defn trash!
  "The bot's folder moved to `<home>/trash/<id>` (an earlier folder of
   the id there is removed first). Answers whether there was one."
  [home id]
  (let [dir (home/bot-dir home id)
        trash (trash-dir home)]
    (when (and (valid-id? id) (directory? dir))
      (fs/mkdirSync trash #js {:recursive true})
      (fs/rmSync (path/join trash (str id)) #js {:recursive true :force true})
      (fs/renameSync dir (path/join trash (str id)))
      true)))

(defn created-at
  "When the bot's folder was made, in milliseconds; nil without one."
  [home id]
  (try
    (js/Math.round (.-birthtimeMs (fs/statSync (home/bot-dir home id))))
    (catch :default _
      nil)))

(def old-files
  "What tells the old layout: the definition or the work folder at the
   top of the home."
  ["AGENT.md" "SKILL.md" "work"])

(def machine-files
  "What stays at the top of the home when the old layout moves into
   `bots/1/`: the storage with its WAL and journal, the folders and
   files of the new layout, and the machine's MCP servers and secrets
   (`rragent.mcp`, `rragent.secrets`)."
  #{"agent.sqlite" "agent.sqlite-wal" "agent.sqlite-shm" "agent.sqlite-journal"
    "bots" "shared" "trash" "assistant.md" "mcp.json" "secrets.json" "mcp-oauth.json"})

(defn agent-files
  "What of the home's top is the old agent's: everything but the
   machine's (`machine-files`). The home was the agent's folder, so
   the definition may name any of it: a skill's `<skill>/tools/…`. A
   relative symlink among them moves as it is and points elsewhere
   from `bots/1/`; a `shared/` or `trash/` of the old agent stays and
   becomes the machine's."
  [home]
  (->> (array-seq (fs/readdirSync home))
       (remove machine-files)
       (sort)
       (vec)))

(defn old-layout?
  "Whether `home` has the one agent of before at its top and no
   `bots/` folder yet."
  [home]
  (and (not (directory? (bots-dir home)))
       (boolean (some #(fs/existsSync (path/join home %)) old-files))))

(defn- undo!
  "The renames of `moves` taken back, newest first; the bot's folder
   and `bots/` removed when they are empty then (`rmdirSync` refuses a
   folder with anything in it)."
  [moves dir home]
  (doseq [[from to] (reverse moves)]
    (try
      (fs/renameSync to from)
      (catch :default e
        (js/console.error "[bots]" (str "could not move " to " back to " from ": " (.-message e))))))
  (doseq [folder [dir (bots-dir home)]]
    (try
      (fs/rmdirSync folder)
      (catch :default _
        nil))))

(defn migrate!
  "The one agent of the old layout moved into `bots/1/`, the folder of
   the root conversation, which is the first bot: its definition, its
   work folder and whatever else of it lies at the top of the home (a
   skill's tools, `agent-files`), one rename each; `agent.sqlite`
   stays. Answers the first bot's id; nil for a home in the new
   layout, which is left alone. A step that fails undoes the renames
   made and throws with the reason."
  [home]
  (when (old-layout? home)
    (let [dir (home/bot-dir home root-id)
          moved (atom [])]
      (try
        (fs/mkdirSync dir #js {:recursive true})
        (doseq [name (agent-files home)
                :let [from (path/join home name)
                      to (path/join dir name)]]
          (fs/renameSync from to)
          (swap! moved conj [from to]))
        root-id
        (catch :default e
          (undo! @moved dir home)
          (throw (ex-info (str "The agent in " home " could not be moved into bots/: " (.-message e)
                               ". The old layout stays as it was.")
                          {:home home}
                          e)))))))

(defn url
  "The page of the bot."
  [id]
  (str "/bot/" id))

(comment
  (definition-text {:name "Reviewer" :instructions "Review the pull requests." :skill "reviews"})
  (migrate! "/tmp/rragent-home")
  )
