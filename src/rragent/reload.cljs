(ns rragent.reload
  "The definition read again before every run. A bot's conversation
   is configured from its `AGENT.md` when the bot opens
   (`rragent.harness`); but the file changes while the bot runs: the
   person edits it, or the bot edits it when it takes a skill or a
   job, and the next turn should run with the new instructions and
   the new model, the page show the new name, without a restart. So
   every submission, the composer's text, a schedule's run and a
   hook's call, first reads the file again, a cheap read, and
   configures the conversation when the text changed since the last
   read; an unchanged file transacts nothing. A file that does not
   read (no frontmatter, no name, gone) leaves the bot as it was, with
   a line on stderr.

   What the conversation is configured with is more than the file
   (`assemble`): the shared prompt of the bundle in front, the rules
   every bot of the machine works by (`rragent.bots/bot-prompt`),
   which a release replaces and this read brings to every bot at its
   next turn; then the file's body, the bot's job; then what the bot
   is, the body of the skill its frontmatter names with the skill's
   folder filled in for `<skill>` (`rragent.skills/skill`); then the
   person's preferences, `preferences.md` of the machine's wiki
   (`rragent.wiki`). Nothing of that is
   copied into the file, so a change of any of it costs nothing on
   disk and is live at the next turn.

   What the bot is reloaded from lives in its map of the one atom
   (`rragent.db`): `:reload`, `{:home :provider :text}`, its folder,
   the models' provider and the text of the file as last applied; and
   `:definition`, the definition read from it, which the page shows.
   A schedule's task knows its conversation's id and nothing else of
   the bot, so the id is the key. What the conversation is configured
   with is the bot's own: its instructions, its model and thinking
   level, and its folder's `work/` as the `cwd` of its tools, which
   pi-durable keeps per conversation (`AgentState.cwd`) and hands to
   the environment of every call with the conversation's id."
  (:require ["@earendil-works/pi-durable" :refer [configure]]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.home :as home]
            [rragent.roles :as roles]
            [rragent.skills :as skills]))

(defn register!
  "The bot of the conversation `id`: its folder and the models'
   provider; nothing applied yet. With `:preferences`, a file whose
   text is appended to the definition at every read, under a heading
   of its own: `preferences.md` of the machine's wiki (`rragent.wiki`),
   what the person asked of their bots, which outlives every release."
  [id {:keys [home provider preferences]}]
  (swap! db/db assoc-in (conj (db/bot-path id) :reload) (cond-> {:home home :provider provider}
                                                           preferences (assoc :preferences preferences))))

(def ^:private frontmatter-pattern
  #"(?s)^(---\r?\n.*?\r?\n---\r?\n)(.*)$")

(defn assemble
  "The text a bot is configured from: the frontmatter of `text`, its
   file, then `shared`, the prompt every bot works by, then the file's
   body, the bot's job, then `role`, what the bot is (a skill's
   body); the parts separated by a blank line,
   the blank ones left out. A text without a frontmatter stays as it
   is, for `rragent.definition` to refuse."
  [text {:keys [shared role]}]
  (if-let [[_ head body] (re-find frontmatter-pattern (str text))]
    (str head "\n" (str/join "\n\n" (remove str/blank? (map #(str/trim (str %)) [shared body role]))) "\n")
    text))

(defn- role-text
  "What follows the job: the body of the skill the file names, with
   `<skill>` in it replaced by the skill's folder; nil without one. A
   skill the machine does not have is said on stderr, and the bot
   works without it."
  [bot-dir text]
  (when-let [name (not-empty (str (:skill (or (definition/frontmatter text) {}))))]
    (if-let [{:keys [body folder]} (skills/skill (skills/machine-home bot-dir) name)]
      (str/replace (str body) "<skill>" folder)
      (js/console.error "[definition]" (str "The skill " name " of " bot-dir " is not on the machine; the bot works without it.")))))

(def preferences-heading
  "## Your preferences")

(defn with-preferences
  "The definition `text` with the text of the preferences `file` under
   `preferences-heading`; `text` as it is without a file or an empty
   one."
  [text file]
  (let [preferences (when (and file (fs/existsSync file))
                      (not-empty (.trim (fs/readFileSync file "utf8"))))]
    (if preferences
      (str (.trimEnd (str text)) "\n\n" preferences-heading "\n\n" preferences "\n")
      text)))

(defn current
  "The definition of the bot `id` as last applied."
  [id]
  (get-in @db/db (conj (db/bot-path id) :definition)))

(defn change
  "pi-durable's agent change for the definition: the model with its
   provider, the thinking level, the instructions, the bot's `work/`
   as the directory the tools run in, and the tools its model sees: a
   bot's (`rragent.roles`, `:bot`), or those of another `role`."
  ([definition provider work]
   (change definition provider work :bot))
  ([{:keys [model thinking instructions]} provider work role]
   (let [change #js {:model #js {:provider provider :modelId model}
                     :thinkingLevel (or thinking js/undefined)
                     :instructions instructions
                     :cwd work}]
     (when-let [tools (roles/model-tools role)]
       (aset change "modelTools" tools))
     change)))

(defn- read-text
  "The text the bot is configured from (`assemble`): its definition
   file with the shared prompt and what the bot is, and the
   preferences file appended when the bot has one
   (`with-preferences`); nil when there is no definition."
  [home preferences]
  (when-let [file (home/definition-file home)]
    (let [text (fs/readFileSync file "utf8")]
      [file (with-preferences (assemble text {:shared (bots/bot-prompt)
                                              :role (role-text home text)})
                              preferences)])))

(defn changed
  "`{:text :definition}` when the file of the bot `id` reads and its
   text is not the one last applied; nil when it is, when the file
   does not read (said on stderr), or when no bot `id` is registered."
  [id]
  (when-let [{:keys [home text preferences]} (get-in @db/db (conj (db/bot-path id) :reload))]
    (let [[file now] (read-text home preferences)]
      (cond
        (nil? file) (do (js/console.error "[definition]" (str "No AGENT.md or SKILL.md in " home ", the bot stays as it was.")) nil)
        (= text now) nil
        :else (try
                {:text now :definition (definition/from-text now file)}
                (catch :default error
                  (js/console.error "[definition]" (str (.-message error) " The bot stays as it was."))
                  nil))))))

(defn applied!
  "The definition applied to the bot `id`: kept with its text for the
   next comparison, and the bot's `:definition`, which the page shows,
   replaced, so the name and the model on the page follow the file."
  [id text definition]
  (swap! db/db update-in (db/bot-path id)
         (fn [bot]
           (-> bot
               (assoc-in [:reload :text] text)
               (assoc :definition definition)))))

(defn ^:async reload!
  "The `conversation` of a bot configured from its file when the text
   changed since the last read, in its own commit; nothing otherwise.
   Answers the definition applied, nil when none was."
  [conversation context]
  (let [id (.-id conversation)]
    (when-let [{:keys [text definition]} (changed id)]
      (let [{:keys [home provider]} (get-in @db/db (conj (db/bot-path id) :reload))]
        (await (.configure conversation (change definition provider (home/work-dir home)) context))
        (applied! id text definition)
        definition))))

(defn ^:async reload-in-task!
  "The same for the conversation `id` from inside a task, whose
   handles of a conversation (`.conversation` of the `runtime`) have
   no `configure`: a schedule's task for its own conversation, a
   message's for the receiver's (`rragent.messages`). The change goes
   into a commit of the task's `runtime`, which leaves the task's
   state as it is."
  [runtime id context]
  (when-let [{:keys [text definition]} (changed id)]
    (let [{:keys [home provider]} (get-in @db/db (conj (db/bot-path id) :reload))]
      (await (.commit runtime
                      (^:async fn [tx _current]
                        (await (configure tx id (change definition provider (home/work-dir home))))
                        js/undefined)
                      context))
      (applied! id text definition)
      definition)))
