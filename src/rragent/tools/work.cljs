(ns rragent.tools.work
  "Where a tool works: the bot's `work/` folder, the `cwd` of the
   execution environment the harness built for the call
   (`rragent.harness/open`), `work/artifacts/` in it, where the
   things for the person go (`rragent.artifacts`), and the bot of the
   call: the conversation of the call, or, for a call of a subagent,
   whose conversation a task of the bot's owns, the bot's conversation
   up the chain of owners, so that the bot's map in the one atom
   (`rragent.db`) and its REPL worker are the call's too."
  (:require ["node:path" :as path]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.home :as home]
            [rragent.reload :as reload]))

(defn dir
  "The working directory of the call."
  [api]
  (or (some-> (.-env api) .-cwd not-empty)
      (throw (js/Error. "The call has no working directory."))))

(defn ^:async bot-id
  "The id of the bot's conversation: the call's, or the one that owns
   it through its tasks; read through a commit that changes nothing."
  [api call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (loop [id (.-conversationId api)]
                      (let [record (await (.conversation tx id))
                            owner (some-> record .-owner)]
                        (if owner
                          (recur (.-conversationId owner))
                          id))))
                  call-context)))

(defn ^:async bot-path
  "Where the bot of the call is in the one atom (`rragent.db`)."
  [api call-context]
  (db/bot-path (await (bot-id api call-context))))

(defn ^:async bot-dir
  "The folder of the bot of the call, `bots/<id>/` of the machine, as
   the reload registered it when the bot opened (`rragent.reload`);
   for a conversation the process does not know, the folder above the
   call's working directory, which is the bot's `work/`."
  [api call-context]
  (or (get-in @db/db (conj (db/bot-path (await (bot-id api call-context))) :reload :home))
      (path/dirname (dir api))))

(defn ^:async bot-call!
  "Throws when the call is not the bot's own conversation's but a
   run's or a subagent's: `what` (\"Hooks\") are the bot's to set."
  [api call-context what]
  (when (not= (await (bot-id api call-context)) (.-conversationId api))
    (throw (js/Error. (str what " are the bot's, not a run's or a subagent's: set none here. "
                           "Say in your answer what the bot should set up, the person tells it.")))))

(defn ^:async job-warning
  "A line for the tool's answer when the bot's definition holds no job
   yet (`rragent.definition/job-written?`), nil when it does: a run
   knows only the definition."
  [api call-context]
  (let [definition (reload/current (await (bot-id api call-context)))]
    (when (and definition (not (definition/job-written? definition)))
      (str " Your definition is still the generic assistant's: a run knows nothing of this chat, only your definition, "
           "your events and your artifacts, so write your job into AGENT.md before the first call."))))

(defn artifacts-dir
  [api]
  (home/artifacts-dir (dir api)))

(defn text
  "A tool result of one text."
  [s]
  #js {:output #js [#js {:type "text" :text (str s)}]})

(defn relative
  "`artifacts/<rest>` as the agent names a file, for an absolute path
   below the working directory."
  [api file]
  (path/relative (dir api) file))
