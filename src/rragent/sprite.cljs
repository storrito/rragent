(ns rragent.sprite
  "The machine's own tool when the machine is a Sprite (sprites.dev,
   2.0): `sprite-env`, inside the sprite, no token needed. Nothing
   happens on another kind of machine: `available?` says whether the
   command is there."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:dynamic *sprite-env*
  "The command; a test binds a fake."
  "sprite-env")

(defn- on-path?
  [command]
  (or (str/includes? command "/")
      (some (fn [dir] (fs/existsSync (path/join dir command)))
            (str/split (str (aget (.-env js/process) "PATH")) #":"))))

(defn available?
  []
  (on-path? *sprite-env*))

(defn sprite-env!
  "One `sprite-env` command: `{:exit :out :err}`, never a throw."
  [& args]
  (try
    (let [result (child/spawnSync *sprite-env* (clj->js (vec args)) #js {:encoding "utf8" :timeout 30000})]
      {:exit (or (.-status result) 1)
       :out (str (.-stdout result))
       :err (str (or (.-stderr result) (some-> (.-error result) .-message)))})
    (catch :default e
      {:exit 1 :out "" :err (str e)})))

(defn ok?
  [{:keys [exit]}]
  (zero? exit))
