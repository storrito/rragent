(ns rragent.fake-sprite-env
  "A `sprite-env` that records its calls in a file and fails an
   `automation update` or `get` of what was never created, as the real
   one does; `discover` prints the JSON `make!` was given, as the real
   one prints the machine's."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn make!
  "`{:script :log}` in a fresh temp dir. With `:discover`, a string,
   `sprite-env discover` prints it."
  ([]
   (make! {}))
  ([{:keys [discover]}]
   (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-sprite-env-"))
         log (path/join dir "calls")
         answer (path/join dir "discover")
         script (path/join dir "sprite-env")]
     (when discover
       (fs/writeFileSync answer discover))
     (fs/writeFileSync script
                       (str "#!/bin/sh\n"
                            "echo \"$@\" >> " log "\n"
                            "case \"$1 $2\" in\n"
                            "  'automation update'|'automation get') grep -q \"automation create $3 \" " log " || { echo not found >&2; exit 1; } ;;\n"
                            "  'discover ') cat " answer " ;;\n"
                            "esac\n"
                            "exit 0\n"))
     (fs/chmodSync script "700")
     {:script script :log log})))

(defn calls
  [log]
  (if (fs/existsSync log)
    (vec (remove str/blank? (str/split-lines (fs/readFileSync log "utf8"))))
    []))

(defn discover-json
  "What `sprite-env discover` prints for a sprite named rragent-probe6
   with `labels`, the shape seen on a Sprites 2.0 machine on 2026-10-20."
  [labels]
  (js/JSON.stringify #js {:service_grants #js []
                          :service_grants_truncated false
                          :sprite #js {:cluster_id "default"
                                       :labels (clj->js labels)
                                       :name "rragent-probe6"
                                       :ref "default/rragent-probe6"
                                       :services #js {}
                                       :status "running"}}))
