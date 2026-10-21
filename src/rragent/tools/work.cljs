(ns rragent.tools.work
  "Where a tool works: the bot's `work/` folder, the `cwd` of the
   execution environment the harness built for the call
   (`rragent.harness/open`), `work/artifacts/` in it, where the
   things for the person go (`rragent.artifacts`), and the bot's atom
   of the page, found by the folder (`rragent.db/pane`)."
  (:require ["node:path" :as path]
            [rragent.db :as db]
            [rragent.home :as home]))

(defn dir
  "The working directory of the call."
  [api]
  (or (some-> (.-env api) .-cwd not-empty)
      (throw (js/Error. "The call has no working directory."))))

(defn pane
  "The atom of the bot's page, by the folder the call works in."
  [api]
  (db/pane (dir api)))

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
