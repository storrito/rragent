(ns rragent.bots
  "The bots of a machine on disk. `<home>/bots/<id>/` is a bot's folder
   (`rragent.home` says what is in it), `<home>/bots.edn` the index of
   them, `<home>/shared/` the folder every bot's bash reads and writes,
   `<home>/assistant.md` the generic definition a new bot starts from
   (`deploy/agent/AGENT.md`; the build keeps a copy beside the bundle
   as `assistant.md`), `<home>/trash/<id>/` where a deleted bot's
   folder goes. An id is eight random hex digits. The index is a
   vector of `{:id :name :created :used}`, the bots in the order they
   were made; `:name` is the one the person or the `bot` tool gave, nil
   for a bot named by its definition; `:used` when a page last showed
   it.

   A machine from before the bots ran one agent at the top of the
   home, `AGENT.md`, `agent.sqlite` and `work/`: `migrate!` moves them
   into `bots/<id>/` as the first bot, one rename per file, nothing
   copied, and leaves a home in the new layout alone
   (`rragent.machine` runs it at start). A failure undoes the renames
   made and throws, the old layout as it was."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as node-url]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rragent.definition :as definition]
            [rragent.home :as home]))

(defn bots-dir
  [home]
  (path/join home "bots"))

(defn bot-dir
  [home id]
  (path/join (bots-dir home) (str id)))

(defn index-file
  [home]
  (path/join home "bots.edn"))

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
  [id]
  (boolean (re-matches #"[0-9a-f]{8}" (str id))))

(defn new-id
  "Eight hex digits no bot of `home` has."
  [home]
  (let [id (.toString (crypto/randomBytes 4) "hex")]
    (if (fs/existsSync (bot-dir home id))
      (recur home)
      id)))

(defn- directory?
  [p]
  (try
    (.isDirectory (fs/statSync p))
    (catch :default _
      false)))

(defn read-index
  "The index of `home`, `[]` without one; a file that does not read
   throws, since the bots in it would be lost otherwise."
  [home]
  (let [file (index-file home)]
    (if (fs/existsSync file)
      (let [value (try
                    (reader/read-string (fs/readFileSync file "utf8"))
                    (catch :default e
                      (throw (ex-info (str file " does not read: " (.-message e)) {:file file}))))]
        (if (vector? value)
          value
          (throw (ex-info (str file " does not hold a vector of bots") {:file file}))))
      [])))

(defn write-index!
  "The index written whole, through a temp file and a rename, so that
   a crash leaves the old one or the new one."
  [home bots]
  (let [file (index-file home)
        tmp (str file ".tmp")]
    (fs/writeFileSync tmp (str (pr-str (vec bots)) "\n"))
    (try
      (fs/renameSync tmp file)
      (catch :default e
        (fs/rmSync tmp #js {:force true})
        (throw e)))))

(defn entry
  "The index entry of the bot `id`, nil without one."
  [home id]
  (some #(when (= (str id) (:id %)) %) (read-index home)))

(defn- update-entry!
  [home id f]
  (write-index! home (mapv #(if (= (str id) (:id %)) (f %) %) (read-index home))))

(defn bundled-assistant-file
  "The generic definition as the bundle carries it: `assistant.md`
   beside the bundle (the build copies `deploy/agent/AGENT.md` there),
   else that file of the checkout."
  []
  (let [beside (path/join (path/dirname (node-url/fileURLToPath (js* "import.meta.url"))) "assistant.md")]
    (if (fs/existsSync beside)
      beside
      (path/resolve "deploy/agent/AGENT.md"))))

(defn assistant-text
  "The text of `<home>/assistant.md`, written from the bundle's copy
   when it is missing."
  [home]
  (let [file (assistant-file home)]
    (when-not (fs/existsSync file)
      (fs/copyFileSync (bundled-assistant-file) file))
    (fs/readFileSync file "utf8")))

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
  (let [[_ head body] (or (re-find #"(?s)^(---\r?\n.*?\r?\n---\r?\n)(.*)$" (str assistant))
                          (throw (js/Error. "The assistant's definition has no frontmatter.")))
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

(defn create!
  "A new bot: its folder with its definition (`definition-text` over
   the assistant's) and its work folder, its entry at the end of the
   index. `name` nil makes a bot named by its definition, the assistant
   of a new machine. Answers the entry."
  [home {:keys [name skill] :as given}]
  (check-skill! skill)
  (let [id (new-id home)
        dir (bot-dir home id)
        name (not-empty (str/trim (str name)))
        entry {:id id :name name :created (js/Date.now) :used nil}]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "AGENT.md") (definition-text (assistant-text home) (assoc given :name name)))
    (home/work-dir dir)
    (write-index! home (conj (read-index home) entry))
    entry))

(defn rename!
  "The bot's name in the index; a blank one is refused."
  [home id name]
  (let [name (str/trim (str name))]
    (when (str/blank? name)
      (throw (js/Error. "A bot needs a name.")))
    (update-entry! home id #(assoc % :name name))
    name))

(defn touch!
  "The bot was used `now`: for the order the root redirects by."
  [home id now]
  (update-entry! home id #(assoc % :used now)))

(defn delete!
  "The bot's folder moved to `<home>/trash/<id>` (an earlier folder of
   the id there is removed first) and its entry taken out of the index.
   Answers whether there was a folder."
  [home id]
  (let [dir (bot-dir home id)
        trash (trash-dir home)
        found? (and (valid-id? id) (directory? dir))]
    (when found?
      (fs/mkdirSync trash #js {:recursive true})
      (fs/rmSync (path/join trash (str id)) #js {:recursive true :force true})
      (fs/renameSync dir (path/join trash (str id))))
    (write-index! home (remove #(= (str id) (:id %)) (read-index home)))
    found?))

(def old-files
  "What the old layout had at the top of the home, and the name each
   takes in the bot's folder: the definition, the SQLite file with the
   side files of its write-ahead log, and the work folder."
  [["AGENT.md" "AGENT.md"]
   ["SKILL.md" "SKILL.md"]
   ["agent.sqlite" "bot.sqlite"]
   ["agent.sqlite-wal" "bot.sqlite-wal"]
   ["agent.sqlite-shm" "bot.sqlite-shm"]
   ["work" "work"]])

(defn old-layout?
  "Whether `home` has the one agent of before at its top and no
   `bots/` folder yet."
  [home]
  (and (not (directory? (bots-dir home)))
       (boolean (some #(fs/existsSync (path/join home (first %))) old-files))))

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
  "The one agent of the old layout moved into `bots/<id>/` as the
   first bot, named by its definition (`:name` nil), and
   `<home>/assistant.md` written from its definition when that is the
   generic one (the image filled the skills repository into it), else
   from the bundle's copy at the first use. Answers the entry; nil for
   a home in the new layout, which is left alone. A step that fails
   undoes the renames made and throws with the reason."
  [home]
  (when (old-layout? home)
    (let [id (new-id home)
          dir (bot-dir home id)
          text (some-> (home/definition-file home) (fs/readFileSync "utf8"))
          moved (atom [])]
      (try
        (fs/mkdirSync dir #js {:recursive true})
        (doseq [[from to] old-files
                :let [from (path/join home from)
                      to (path/join dir to)]
                :when (fs/existsSync from)]
          (fs/renameSync from to)
          (swap! moved conj [from to]))
        (when (and text (generic? text) (not (fs/existsSync (assistant-file home))))
          (fs/writeFileSync (assistant-file home) text))
        (let [entry {:id id :name nil :created (js/Date.now) :used nil}]
          (write-index! home (conj (read-index home) entry))
          entry)
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
