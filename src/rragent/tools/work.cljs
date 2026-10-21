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
            [rragent.home :as home]))

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
