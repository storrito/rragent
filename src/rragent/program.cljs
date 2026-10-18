(ns rragent.program
  "This program, for the children that run it: a motion project is
   `rragent motion <dir>` in a process of its own, its render
   `rragent motion-render <dir> <out>` (`rragent.motion.app`). The bundle
   is `RRAGENT_JS` when set, else what Node is running.")

(defn file
  "The path of the bundle."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_JS"))
      (aget (.-argv js/process) 1)))

(defn command
  "The command line that runs this program with `args`: `{:cmd :args}`."
  [& args]
  {:cmd (.-execPath js/process)
   :args (into [(file)] args)})
