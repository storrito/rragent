(ns rragent.programs
  "The programs a template needs on the machine, the `needs` of its
   TEMPLATE.md (`rragent.templates`): `chrome` is CHROME in the
   environment or the first of the usual names on the PATH, any other
   need is `<name>` on the PATH; RRAGENT_<NAME> in the environment names
   one that is elsewhere (RRAGENT_HYPERFRAMES the HyperFrames CLI). The
   scripts of a template get every program it needs as <NAME> in their
   environment, a path (`CHROME=/usr/bin/google-chrome`,
   `FFMPEG=/usr/bin/ffmpeg`), and the Node that runs rragent in front
   of their PATH, so that a `#!/usr/bin/env node` finds it."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def chrome-names
  ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "chrome"])

(defn on-path
  "The executable `name` on the PATH, or nil."
  [name]
  (some (fn [dir]
          (let [file (path/join dir name)]
            (when (try (.isFile (fs/statSync file)) (catch :default _ false))
              file)))
        (str/split (str (aget (.-env js/process) "PATH")) #":")))

(defn- env-var
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn variable
  "The variable of a need: `hyperframes` is HYPERFRAMES."
  [need]
  (str/upper-case (str/replace (str need) #"[^a-zA-Z0-9]" "_")))

(defn locate
  "Where the program of `need` is, or nil."
  [need]
  (or (env-var (str "RRAGENT_" (variable need)))
      (if (= "chrome" need)
        (or (env-var "CHROME") (some on-path chrome-names))
        (on-path need))))

(defn missing
  "The needs that are not there."
  [needs]
  (vec (remove locate needs)))

(defn not-ready-message
  "Why a template cannot work on this machine, or nil when its needs
   are there: the words for the person, no stack trace."
  [template-name missing]
  (when (seq missing)
    (str "The template " template-name " needs " (str/join ", " missing)
         " on this machine, which is not installed, so no render: the operator installs it.")))

(defn environment
  "The environment of a template's script: every need found as its
   variable, and the directory of this Node first on the PATH."
  [needs]
  (into {"PATH" (str (path/dirname (.-execPath js/process)) ":" (aget (.-env js/process) "PATH"))}
        (keep (fn [need]
                (when-let [file (locate need)]
                  [(variable need) file])))
        needs))

(defn report
  "One line per template on what is there and what is missing, for the
   start: `hyperframes: chrome at /usr/bin/google-chrome, ffmpeg missing`."
  [templates]
  (str/join "\n"
            (for [{:keys [name needs]} templates]
              (str "template " name ": "
                   (if (seq needs)
                     (str/join ", " (for [need needs]
                                      (if-let [file (locate need)]
                                        (str need " at " file)
                                        (str need " missing"))))
                     "needs nothing")))))
