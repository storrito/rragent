(ns rragent.skills
  "The person's own skills on the machine: `<home>/skills/<name>/`,
   one folder per skill with a `SKILL.md` as ours have it (a
   frontmatter with `name`, `description` and a `metadata` of `title`,
   `summary`, `ask` and `placeholder`; the body the job's
   instructions), beside the clone of ours (`rragent.bots/skills-dir`),
   which stays read-only. The `skill` tool writes here when the person
   wants to keep what a bot does as a how-to for their other bots
   (`rragent.tools.skill`); the assistant offers both folders, a new
   bot takes a skill of either (`check!`). A skill of ours a person
   wants changed is copied under a name of their own, so the two
   never share a name."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.definition :as definition]))

(defn dir
  "The folder of the person's skills, made on first use."
  [home]
  (let [dir (path/join home "skills")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn machine-home
  "The machine's folder above a bot's, `bots/<id>/`."
  [bot-dir]
  (path/dirname (path/dirname bot-dir)))

(defn folder
  [home name]
  (path/join (dir home) (str name)))

(defn valid-name?
  [name]
  (boolean (re-matches #"[a-z0-9][a-z0-9-]*" (str name))))

(defn names
  "The person's skills on the machine, by folder name."
  [home]
  (bots/skill-names (dir home)))

(defn ours
  "The names of our skills, the clone's; none without a clone."
  []
  (if-let [dir (bots/skills-dir)]
    (bots/skill-names dir)
    []))

(defn- quoted
  "A frontmatter value, quoted the JSON way, which `rragent.definition`
   reads."
  [s]
  (js/JSON.stringify (str s)))

(defn text
  "The `SKILL.md` of a skill: the frontmatter from `name`,
   `description`, `title` (the name without one), `summary` (the
   description without one), `ask` and `placeholder` when given, then
   `body`, the instructions."
  [{:keys [name description title summary ask placeholder body]}]
  (str "---\n"
       "name: " (quoted name) "\n"
       "description: " (quoted (or description "")) "\n"
       "metadata:\n"
       "  title: " (quoted (or (not-empty title) name)) "\n"
       "  summary: " (quoted (or (not-empty summary) description "")) "\n"
       (when (seq ask) (str "  ask: " (quoted ask) "\n"))
       (when (seq placeholder) (str "  placeholder: " (quoted placeholder) "\n"))
       "---\n\n"
       (str/trim (str body)) "\n"))

(defn- copy-of
  "`[from relative]` for `file`, a path under `work`; a path outside
   `work` or one that is not there is refused."
  [work file]
  (let [work (path/resolve work)
        from (path/resolve work (str file))
        relative (path/relative work from)]
    (when (or (str/starts-with? relative "..") (path/isAbsolute relative) (str/blank? relative))
      (throw (js/Error. (str "The file " file " is not under your working directory."))))
    (when-not (fs/existsSync from)
      (throw (js/Error. (str "There is no " file " under your working directory."))))
    [from relative]))

(defn- copy-in!
  "`from` copied into the skill's `folder` at `relative`."
  [folder [from relative]]
  (let [to (path/join folder relative)]
    (fs/mkdirSync (path/dirname to) #js {:recursive true})
    (fs/cpSync from to #js {:recursive true})))

(defn write!
  "The skill written: its folder under `home`'s skills with the
   `SKILL.md` of `text` and the `files` (paths under `work`, the
   bot's working directory) copied beside it. A name that is not
   lowercase letters, digits and dashes, one of ours, or a file that
   is not under `work` is refused, and nothing is written then; an
   own skill of the name is written anew. Answers the folder."
  [home {:keys [name files body] :as skill} work]
  (when-not (valid-name? name)
    (throw (js/Error. "A skill's name is lowercase letters, digits and dashes.")))
  (when (str/blank? (str body))
    (throw (js/Error. "A skill needs its instructions.")))
  (when (some #{(str name)} (ours))
    (throw (js/Error. (str "The skill " name " is one of ours: copy it under a name of your own, ours stay as they are."))))
  ;; the files checked before anything is written: a refused one leaves
  ;; no half skill behind
  (let [copies (mapv #(copy-of work %) files)
        folder (folder home name)]
    (fs/mkdirSync folder #js {:recursive true})
    (fs/writeFileSync (path/join folder "SKILL.md") (text skill))
    (doseq [copy copies]
      (copy-in! folder copy))
    folder))

(defn listed
  "The person's skills as a text for the model: each with its title,
   its summary and its folder."
  [home]
  (let [lines (for [name (names home)
                    :let [folder (folder home name)
                          {:keys [description metadata]} (definition/frontmatter (fs/readFileSync (path/join folder "SKILL.md") "utf8"))]]
                (str name ": " (or (get metadata "title") name) " — " (or (get metadata "summary") description "") " (" folder ")"))]
    (if (seq lines)
      (str/join "\n" lines)
      (str "No skills of the person's yet; they would live in " (dir home) "."))))

(defn check!
  "Throws when `skill` is neither one of the person's skills on the
   machine nor one of ours (`rragent.bots/check-skill!`, which passes
   anything without a clone); nothing for no skill."
  [home skill]
  (when (seq (str skill))
    (when-not (some #{(str skill)} (names home))
      (bots/check-skill! skill))))
