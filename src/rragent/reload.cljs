(ns rragent.reload
  "The definition read again before every run. The root conversation
   is configured from the `AGENT.md` when the agent opens
   (`rragent.harness/open`); but the file changes while the agent
   runs: the person edits it, or the agent writes it anew when it takes
   a skill (`deploy/agent/AGENT.md`), and the next turn should run with
   the new instructions and the new model, the page show the new name,
   without a restart. So every submission, the composer's text, a
   schedule's run and a hook's call, first reads the file again, a
   cheap read, and configures the conversation when the text changed
   since the last read; an unchanged file transacts nothing. A file
   that does not read (no frontmatter, no name, gone) leaves the agent
   as it was, with a line on stderr.

   The agents of this process are kept here by the id of their root
   conversation, since a schedule's task knows its conversation's id
   and nothing else of the agent."
  (:require ["@earendil-works/pi-durable" :refer [configure]]
            ["node:fs" :as fs]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.home :as home]))

(defonce ^{:doc "By conversation id: `{:home :work :provider :text :definition}`,
                 the text of the file as last applied and the definition read from it."}
  agents
  (atom {}))

(defn register!
  "The agent of the root conversation `id`: its folder, the working
   directory of its tools and the models' provider; nothing applied
   yet."
  [id {:keys [home work provider]}]
  (swap! agents assoc id {:home home :work work :provider provider}))

(defn forget!
  [id]
  (swap! agents dissoc id))

(defn current
  "The definition of the agent `id` as last applied."
  [id]
  (get-in @agents [id :definition]))

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
  "The text of the agent's definition file, nil when there is none."
  [home]
  (when-let [file (home/definition-file home)]
    [file (fs/readFileSync file "utf8")]))

(defn changed
  "`{:text :definition}` when the file of the agent `id` reads and its
   text is not the one last applied; nil when it is, or when the file
   does not read (said on stderr)."
  [id]
  (let [{:keys [home text]} (get @agents id)
        [file now] (read-text home)]
    (cond
      (nil? file) (do (js/console.error "[definition]" (str "No AGENT.md or SKILL.md in " home ", the agent stays as it was.")) nil)
      (= text now) nil
      :else (try
              {:text now :definition (definition/from-text now file)}
              (catch :default error
                (js/console.error "[definition]" (str (.-message error) " The agent stays as it was."))
                nil)))))

(defn applied!
  "The definition applied to the agent `id`: kept with its text for the
   next comparison, and the page's copy of it replaced when the page
   shows this agent (`rragent.db`), so the name and the model on it
   follow the file."
  [id text definition]
  (swap! agents update id assoc :text text :definition definition)
  (when (= id (some-> (get-in @db/db [:agent :root]) .-id))
    (swap! db/db assoc-in [:agent :definition] definition)))

(defn ^:async reload!
  "The root `conversation` of an agent configured from its file when
   the text changed since the last read, in its own commit; nothing
   otherwise. Answers the definition applied, nil when none was."
  [conversation context]
  (let [id (.-id conversation)]
    (when-let [{:keys [text definition]} (changed id)]
      (let [{:keys [provider work]} (get @agents id)]
        (await (.configure conversation (change definition provider work) context))
        (applied! id text definition)
        definition))))

(defn ^:async reload-in-task!
  "The same from inside a task of the conversation, a schedule's: the
   change goes into a commit of the task's `runtime`, which leaves the
   task's state as it is."
  [runtime context]
  (let [id (.-conversationId runtime)]
    (when-let [{:keys [text definition]} (changed id)]
      (let [{:keys [provider work]} (get @agents id)]
        (await (.commit runtime
                        (^:async fn [tx _current]
                          (await (configure tx id (change definition provider work)))
                          js/undefined)
                        context))
        (applied! id text definition)
        definition))))
