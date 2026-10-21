(ns rragent.reload
  "The definition read again before every run. A bot's conversation
   is configured from its `AGENT.md` when the bot opens
   (`rragent.harness`); but the file changes while the bot runs: the
   person edits it, or the bot writes it anew when it takes a skill
   (`deploy/agent/AGENT.md`), and the next turn should run with the
   new instructions and the new model, the page show the new name,
   without a restart. So every submission, the composer's text, a
   schedule's run and a hook's call, first reads the file again, a
   cheap read, and configures the conversation when the text changed
   since the last read; an unchanged file transacts nothing. A file
   that does not read (no frontmatter, no name, gone) leaves the bot
   as it was, with a line on stderr.

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
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.home :as home]))

(defn register!
  "The bot of the conversation `id`: its folder and the models'
   provider; nothing applied yet."
  [id {:keys [home provider]}]
  (swap! db/db assoc-in (conj (db/bot-path id) :reload) {:home home :provider provider}))

(defn current
  "The definition of the bot `id` as last applied."
  [id]
  (get-in @db/db (conj (db/bot-path id) :definition)))

(defn change
  "pi-durable's agent change for the definition: the model with its
   provider, the thinking level, the instructions, and the bot's
   `work/` as the directory the tools run in."
  [{:keys [model thinking instructions]} provider work]
  #js {:model #js {:provider provider :modelId model}
       :thinkingLevel (or thinking js/undefined)
       :instructions instructions
       :cwd work})

(defn- read-text
  "The text of the bot's definition file, nil when there is none."
  [home]
  (when-let [file (home/definition-file home)]
    [file (fs/readFileSync file "utf8")]))

(defn changed
  "`{:text :definition}` when the file of the bot `id` reads and its
   text is not the one last applied; nil when it is, when the file
   does not read (said on stderr), or when no bot `id` is registered."
  [id]
  (when-let [{:keys [home text]} (get-in @db/db (conj (db/bot-path id) :reload))]
    (let [[file now] (read-text home)]
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
  "The same from inside a task of the conversation, a schedule's: the
   change goes into a commit of the task's `runtime`, which leaves the
   task's state as it is."
  [runtime context]
  (let [id (.-conversationId runtime)]
    (when-let [{:keys [text definition]} (changed id)]
      (let [{:keys [home provider]} (get-in @db/db (conj (db/bot-path id) :reload))]
        (await (.commit runtime
                        (^:async fn [tx _current]
                          (await (configure tx id (change definition provider (home/work-dir home))))
                          js/undefined)
                        context))
        (applied! id text definition)
        definition))))
