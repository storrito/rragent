(ns rragent.tools.work
  "Where a tool works: the agent's `work/` folder, the `cwd` of the
   execution environment the harness built for the call
   (`rragent.harness/open`), and `work/artifacts/` in it, where the
   things for the person go (`rragent.artifacts`)."
  (:require ["node:path" :as path]
            [rragent.home :as home]))

(defn dir
  "The working directory of the call."
  [api]
  (or (some-> (.-env api) .-cwd not-empty)
      (home/work-dir (home/home))))

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
