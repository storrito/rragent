(ns rragent.test-home
  "A temporary agent folder for the tests, with a definition in it."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def definition-text
  "---\nname: watcher\ndescription: Watches a page.\nmetadata:\n  title: \"Page watcher\"\n  model: faux-1\n---\n\n# watcher\n\nYou watch a page. Your folder is <skill>.\n")

(defn make
  "A fresh folder with `AGENT.md` (or the named file) holding `text`."
  ([] (make "AGENT.md" definition-text))
  ([file-name text]
   (let [home (fs/mkdtempSync (path/join (os/tmpdir) "rragent-"))]
     (fs/writeFileSync (path/join home file-name) text)
     home)))

(defn remove!
  [home]
  (fs/rmSync home #js {:recursive true :force true}))
