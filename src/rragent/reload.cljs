(ns rragent.reload
  "The definition read again before every run. The root conversation
   is configured from the `AGENT.md` when the bot opens
   (`rragent.harness/open`); but the file changes while the bot
   runs: the person edits it, or the bot writes it anew when it takes
   a skill (`deploy/agent/AGENT.md`), and the next turn should run with
   the new instructions and the new model, the page show the new name,
   without a restart. So every submission, the composer's text, a
   schedule's run and a hook's call, first reads the file again, a
   cheap read, and configures the conversation when the text changed
   since the last read; an unchanged file transacts nothing. A file
   that does not read (no frontmatter, no name, gone) leaves the bot
   as it was, with a line on stderr.

   The bots of this process are kept here by their work folder, the
   `cwd` of their conversation: a schedule's task knows the
   environment of its call and nothing else of the bot, and the root
   conversation of every bot's storage has the same id."
  (:require ["@earendil-works/pi-durable" :refer [configure]]
            ["node:fs" :as fs]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.home :as home]))

(defonce ^{:doc "By work folder: `{:home :work :provider :text :definition}`,
                 the text of the file as last applied and the definition read from it."}
  agents
  (atom {}))

(defn register!
  "The bot whose tools work in `work`: its folder and the models'
   provider; nothing applied yet."
  [work {:keys [home provider]}]
  (swap! agents assoc work {:home home :work work :provider provider}))

(defn forget!
  [work]
  (swap! agents dissoc work))

(defn current
  "The definition of the bot of `work` as last applied."
  [work]
  (get-in @agents [work :definition]))

(defn change
  "pi-durable's agent change for the definition: the model with its
   provider, the thinking level, the instructions, and `work` as the
   directory the tools run in."
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
  "`{:text :definition}` when the file of the bot of `work` reads and
   its text is not the one last applied; nil when it is, or when the
   file does not read (said on stderr), or when no bot of `work` is
   registered."
  [work]
  (when-let [{:keys [home text]} (get @agents work)]
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
  "The definition applied to the bot of `work`: kept with its text for
   the next comparison, and the page's copy of it replaced
   (`rragent.db/pane`), so the name and the model on the page follow
   the file."
  [work text definition]
  (swap! agents update work assoc :text text :definition definition)
  (swap! (db/pane work) assoc-in [:agent :definition] definition))

(defn ^:async reload!
  "The root `conversation` of the bot of `work` configured from its
   file when the text changed since the last read, in its own commit;
   nothing otherwise. Answers the definition applied, nil when none
   was."
  [conversation work context]
  (when-let [{:keys [text definition]} (changed work)]
    (let [{:keys [provider]} (get @agents work)]
      (await (.configure conversation (change definition provider work) context))
      (applied! work text definition)
      definition)))

(defn ^:async reload-in-task!
  "The same from inside a task of the conversation, a schedule's: the
   bot is the one of the task's environment, the change goes into a
   commit of the task's `runtime`, which leaves the task's state as
   it is."
  [runtime context]
  (let [id (.-conversationId runtime)
        work (some-> (await (.env runtime context)) .-cwd)]
    (when-let [{:keys [text definition]} (when work (changed work))]
      (let [{:keys [provider]} (get @agents work)]
        (await (.commit runtime
                        (^:async fn [tx _current]
                          (await (configure tx id (change definition provider work)))
                          js/undefined)
                        context))
        (applied! work text definition)
        definition))))
