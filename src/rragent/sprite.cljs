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

(defn- parse-json
  [text]
  (try
    (js->clj (js/JSON.parse text) :keywordize-keys true)
    (catch :default _
      nil)))

(defn discover
  "What the machine says of itself, `sprite-env discover`: `{:name
   :labels :status}` of the sprite, the labels plain strings. nil on
   another kind of machine (no `sprite-env`), when the command fails or
   when its answer is no JSON with a `sprite` in it."
  []
  (when (available?)
    (let [{:keys [out] :as result} (sprite-env! "discover")
          sprite (when (ok? result)
                   (:sprite (parse-json out)))]
      (when (map? sprite)
        {:name (:name sprite)
         :labels (vec (:labels sprite))
         :status (:status sprite)}))))

(defn label
  "The value of the `key:` label among `labels`, the shape the
   provisioner writes (`token:<hex>`, `agent:<uuid>`): what follows the
   colon of the first label that starts with `key:`, nil without one or
   when nothing follows."
  [labels key]
  (let [prefix (str key ":")]
    (some (fn [candidate]
            (let [candidate (str candidate)]
              (when (str/starts-with? candidate prefix)
                (not-empty (subs candidate (count prefix))))))
          labels)))
