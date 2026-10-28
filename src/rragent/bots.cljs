(ns rragent.bots
  "The bots of a machine on disk. A bot is a conversation of the
   machine's one storage (`rragent.machine` lists them through
   pi-durable, there is no index file), and its folder is
   `<home>/bots/<id>/`, named by the conversation's id
   (`rragent.home` says what is in it). Beside them `<home>/shared/`,
   the folder every bot's bash reads and writes, `<home>/assistant.md`,
   the generic definition a new bot starts from (`deploy/agent/AGENT.md`;
   the build keeps a copy beside the bundle as `assistant.md`, written
   anew over an earlier version of it, see `assistant-text`), and
   `<home>/trash/<id>/`, where a deleted bot's folder goes.

   A machine from before the bots ran one agent at the top of the
   home, `AGENT.md` (or a skill's `SKILL.md`) and `work/` beside
   `agent.sqlite`, the home being the agent's folder: `migrate!` moves
   everything but the storage into `bots/1/`, the folder of the root
   conversation (a skill's `tools/`, `README.md` … with its
   definition, which names them by `<skill>`), one rename each,
   nothing copied, the storage untouched, and leaves a home in the new
   layout alone (`rragent.harness/open` runs it). A
   failure undoes the renames made and throws, the old layout as it
   was."
  (:require ["@earendil-works/pi-durable" :refer [ROOT_CONVERSATION_ID]]
            ["node:crypto" :as crypto]
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

(defn assistant-file
  [home]
  (path/join home "assistant.md"))

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

(defn- regular-file?
  [p]
  (try
    (.isFile (fs/statSync p))
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

(defn bundled-assistant-file
  "The generic definition as the bundle carries it: `assistant.md`
   beside the bundle (the build copies `deploy/agent/AGENT.md` there),
   else that file of the checkout."
  []
  (bundled "assistant.md" "deploy/agent/AGENT.md"))

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

(defn normalize
  "A definition with its skills repository written back to the
   placeholder of `deploy/agent/AGENT.md`, which the image fills in."
  [text]
  (str/replace-first (str text) #"`github\.com/[^`\s]+`" "`github.com/SKILLS_REPO`"))

(defn digest
  [text]
  (-> (crypto/createHash "sha256") (.update (str text) "utf8") (.digest "hex")))

(defn known-versions
  "The sums of every version of the generic definition, normalized
   (`deploy/agent/versions.txt`, beside the bundle as
   `assistant-versions.txt`)."
  []
  (try
    (->> (str/split-lines (fs/readFileSync (bundled "assistant-versions.txt" "deploy/agent/versions.txt") "utf8"))
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (set))
    (catch :default _
      #{})))

(defn bundled-assistant
  "The bundle's generic definition with the skills repository of the
   clone filled in, as the image fills it into its copy."
  []
  (let [text (fs/readFileSync (bundled-assistant-file) "utf8")]
    (if-let [repo (skills-repo)]
      (str/replace text "`github.com/SKILLS_REPO`" (str "`github.com/" repo "`"))
      text)))

(defn assistant-text
  "The text of `<home>/assistant.md`, the generic definition a new bot
   starts from: written from the bundle's copy when it is missing, and
   again when it is an earlier version of that copy (`known-versions`),
   so that a release reaches every new bot of a machine made before it.
   A file someone changed is no version of it and stays as it is."
  [home]
  (let [file (assistant-file home)
        current (when (fs/existsSync file) (fs/readFileSync file "utf8"))
        text (bundled-assistant)]
    (if (and (not= current text)
             (or (nil? current) (contains? (known-versions) (digest (normalize current)))))
      (do (fs/writeFileSync file text)
          text)
      current)))

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

(defn skill-line
  "What has a new bot take the skill at its first message, the way the
   generic definition describes."
  [skill]
  (str "Take the skill `" skill "` at your first message, the way this file describes below, "
       "without offering the others."))

(defn definition-text
  "The definition of a new bot: `assistant`, the generic text, with
   the bot's `name` as its name, its `instructions` at the top of the
   body as its job, and with a `skill` the line that has it take that
   skill. Without any of the three the assistant's text as it is."
  [assistant {:keys [name instructions skill]}]
  (let [[head body] (split-frontmatter assistant)
        top (str/join "\n\n" (remove str/blank? [(when (seq (str/trim (str instructions)))
                                                   (str "## Your job\n\n" (str/trim instructions)))
                                                 (when (seq (str skill))
                                                   (skill-line skill))]))]
    (str (if (seq (str name)) (with-name head name) head)
         (when (seq top) (str "\n" top "\n\n"))
         (if (seq top) (str/triml body) body))))

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
   from the page: the generic definition tells the bot to leave the
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

(defn- generic?
  "Whether a definition text is the generic one, by its name."
  [text]
  (= definition/generic-name (:name (definition/frontmatter text))))

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
   stays. And
   `<home>/assistant.md` written from the definition when that is the
   generic one (the image filled the skills repository into it), else
   from the bundle's copy at the first use. Answers the first bot's
   id; nil for a home in the new layout, which is left alone. A step
   that fails undoes the renames made and throws with the reason."
  [home]
  (when (old-layout? home)
    (let [dir (home/bot-dir home root-id)
          text (some-> (home/definition-file home) (fs/readFileSync "utf8"))
          moved (atom [])]
      (try
        (fs/mkdirSync dir #js {:recursive true})
        (doseq [name (agent-files home)
                :let [from (path/join home name)
                      to (path/join dir name)]]
          (fs/renameSync from to)
          (swap! moved conj [from to]))
        (when (and text (generic? text) (not (regular-file? (assistant-file home))))
          (fs/writeFileSync (assistant-file home) text))
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
  (definition-text (assistant-text "/tmp/rragent-home") {:name "Reviewer" :instructions "Review the pull requests." :skill "reviews"})
  (migrate! "/tmp/rragent-home")
  )
