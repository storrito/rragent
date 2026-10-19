(ns rragent.fake-sprite-env
  "A `sprite-env` that records its calls in a file and fails an
   `automation update` or `get` of what was never created, as the real
   one does."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn make!
  "`{:script :log}` in a fresh temp dir."
  []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-sprite-env-"))
        log (path/join dir "calls")
        script (path/join dir "sprite-env")]
    (fs/writeFileSync script
                      (str "#!/bin/sh\n"
                           "echo \"$@\" >> " log "\n"
                           "case \"$1 $2\" in\n"
                           "  'automation update'|'automation get') grep -q \"automation create $3 \" " log " || { echo not found >&2; exit 1; } ;;\n"
                           "esac\n"
                           "exit 0\n"))
    (fs/chmodSync script "700")
    {:script script :log log}))

(defn calls
  [log]
  (if (fs/existsSync log)
    (vec (remove str/blank? (str/split-lines (fs/readFileSync log "utf8"))))
    []))
