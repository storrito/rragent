(ns rragent.secrets
  "The machine's secrets: `secrets.json` in its folder, `{name value}`,
   readable by its owner only (mode 600). A secret gets there through
   the page only, a password field the person types into (`save!`),
   never through a conversation; `mcp.json` names it as `${NAME}`
   (`rragent.mcp`), which the main thread fills in when it connects.

   Before a tool's result reaches the model, every known secret value
   in it is replaced by its name (`redact`, the `afterTool` hook of
   `rragent.harness`): the values of the file, and those of the
   environment whose names say they are secret (`OPENROUTER_API_KEY`,
   `RRAGENT_TOKEN`, `…_TOKEN`, `…_SECRET`, `…_PASSWORD`). That catches
   the accidents, a `cat` of the wrong file, a server that echoes its
   token; a bot set on getting a secret out could still encode it."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def file-name
  "secrets.json")

(def name-pattern
  "A secret's name, as `${NAME}` takes it."
  #"[A-Za-z_][A-Za-z0-9_]*")

(def min-length
  "Shorter values are not redacted: a `1` or a `true` would be
   replaced everywhere."
  8)

(def max-length
  "A longer value is no secret anyone types."
  16384)

(defn file
  "The secrets of the machine's folder `home`."
  [home]
  (path/join home file-name))

(defn valid-name?
  [s]
  (boolean (and (string? s) (re-matches name-pattern s))))

(defn read-all
  "`{name value}` of the file, `{}` without it or when it is no JSON
   object; only string values."
  [file]
  (try
    (let [data (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))]
      (if (map? data)
        (into {} (filter (fn [[k v]] (and (valid-name? k) (string? v)))) data)
        {}))
    (catch :default _
      {})))

(defn names
  "The names of the saved secrets, sorted."
  [file]
  (vec (sort (keys (read-all file)))))

(defn write!
  "`secrets` as the whole file: written beside it with mode 600 and
   renamed over it, so that no reader sees half a file and the file is
   never readable by others, not even for a moment."
  [file secrets]
  (let [tmp (str file ".tmp-" (.-pid js/process) "-" (js/Date.now))]
    (fs/mkdirSync (path/dirname file) #js {:recursive true})
    (fs/writeFileSync tmp (str (js/JSON.stringify (clj->js (into (sorted-map) secrets)) nil 2) "\n")
                      #js {:mode 384})
    (fs/chmodSync tmp 384)
    (fs/renameSync tmp file)))

(defn save!
  "The secret `secret-name` set to `value`, trimmed; a blank value
   removes it. Throws for a name `${NAME}` could not name or a value
   too long."
  [file secret-name value]
  (when-not (valid-name? secret-name)
    (throw (js/Error. (str "A secret's name has letters, digits and _, and does not start with a digit: " (pr-str secret-name)))))
  (let [value (str/trim (str value))]
    (when (< max-length (count value))
      (throw (js/Error. "The secret is too long.")))
    (write! file (if (str/blank? value)
                   (dissoc (read-all file) secret-name)
                   (assoc (read-all file) secret-name value)))))

;;; Redaction

(def secret-env-pattern
  "The names of environment variables whose values are secrets."
  #"(?i)(^|_)(KEY|TOKEN|SECRET|PASSWORD|PASS|PWD|CREDENTIALS?)$")

(defn env-secrets
  "`{name value}` of the environment variables named like secrets."
  [env]
  (into {}
        (filter (fn [[k v]] (and (re-find secret-env-pattern k) (string? v))))
        (js->clj env)))

(defn redactions
  "`[[value name] …]` of `secrets`, a value at least `min-length` long,
   the longest first, so that a secret that holds another is replaced
   whole."
  [secrets]
  (->> secrets
       (keep (fn [[k v]]
               (let [v (str v)]
                 (when (<= min-length (count v))
                   [v k]))))
       (sort-by (comp - count first))
       (vec)))

(defn redact-text
  "`text` with every value of `pairs` (`redactions`) as `«NAME»`."
  [pairs text]
  (if (and (string? text) (seq pairs))
    (reduce (fn [s [value secret-name]]
              (if (str/includes? s value)
                (str/join (str "«" secret-name "»") (str/split s value -1))
                s))
            text
            pairs)
    text))

(defn redact
  "Every string in `data` (`clojure.walk`) with the secrets of `pairs`
   replaced by their names."
  [pairs data]
  (if (seq pairs)
    (walk/postwalk #(if (string? %) (redact-text pairs %) %) data)
    data))

(defn source
  "What `current` reads the redactions from: the secrets of `file`
   and of `env` (`js/process.env` without). The file is read again
   only when it changed."
  [file & [env]]
  {:file file
   :env (or env (.-env js/process))
   :cache (atom nil)})

(defn current
  "The redactions of `source` as they are now."
  [{:keys [file env cache]}]
  (let [mtime (try (.-mtimeMs (fs/statSync file)) (catch :default _ nil))
        env-map (env-secrets env)
        {cached-mtime :mtime cached-env :env pairs :pairs} @cache]
    (if (and pairs (= mtime cached-mtime) (= env-map cached-env))
      pairs
      (let [pairs (redactions (merge env-map (when mtime (read-all file))))]
        (reset! cache {:mtime mtime :env env-map :pairs pairs})
        pairs))))
