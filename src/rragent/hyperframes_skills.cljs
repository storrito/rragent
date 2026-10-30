(ns rragent.hyperframes-skills
  "The HyperFrames skills that a bot reads before it writes a
   composition (`templates/hyperframes/TEMPLATE.md`:
   `~/.agents/skills/hyperframes/`). The image installs them
   (`deploy/Dockerfile.full`: `hyperframes skills`), but a machine made
   from an earlier image has none, and a release does not change the
   image: a bot then looked for them all over the disk (a `find /` that
   ran into the 30-second timeout) and installed them itself, its
   person watching. So at the start of `serve`, in the background:
   `hyperframes skills check`, and when it says they are missing or
   out of date, `hyperframes skills`; one line on what happened.
   Without the HyperFrames CLI on the machine nothing is done."
  (:require ["node:child_process" :as child]
            [clojure.string :as str]
            [rragent.programs :as programs]))

(def check-timeout-ms
  60000)

(def install-timeout-ms
  "The install fetches the skills over the network with npx."
  300000)

(defn- run
  "Runs the CLI with `args`: whether it ended with 0, and its last line."
  [cli args timeout-ms]
  (js/Promise.
    (fn [resolve]
      (child/execFile cli (clj->js args)
                      #js {:timeout timeout-ms :maxBuffer (* 4 1024 1024)}
                      (fn [error stdout stderr]
                        (resolve {:ok (nil? error)
                                  :last (or (last (remove str/blank? (str/split-lines (str stdout stderr))))
                                            (some-> error .-message))}))))))

(defn ^:async ensure!
  "Checks the HyperFrames skills and installs them when the check
   fails; answers one line for the start's report."
  []
  (if-let [cli (programs/locate "hyperframes")]
    (let [check (await (run cli ["skills" "check"] check-timeout-ms))]
      (if (:ok check)
        "hyperframes skills: there"
        (let [install (await (run cli ["skills"] install-timeout-ms))]
          (if (:ok install)
            "hyperframes skills: installed, the check had failed"
            (str "hyperframes skills: missing, and the install failed (" (:last install) ")")))))
    "hyperframes skills: no HyperFrames CLI on this machine"))
