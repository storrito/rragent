(ns rragent.definition
  "The agent's definition: an `AGENT.md` (or an agentitos `SKILL.md`) in
   the Agent Skills format, a YAML frontmatter and a body. The body is
   the agent's instructions, `<skill>` and `<agent>` in it meaning the
   folder. The frontmatter carries the name and the description, and
   under `metadata` what the harness reads: `model` (an OpenRouter
   model id), `thinking` (a level). The same reading of the frontmatter
   as Storrito's `app.agent.skills` and pchat's `pchat.skills`."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def default-model
  "openai/gpt-6.1-sol")

(defn- scalar
  "A YAML scalar as a string: double quotes as JSON, single quotes with
   '' for a quote, else as it stands."
  [s]
  (let [s (str/trim s)]
    (cond
      (and (str/starts-with? s "\"") (str/ends-with? s "\"") (< 1 (count s)))
      (js/JSON.parse s)

      (and (str/starts-with? s "'") (str/ends-with? s "'") (< 1 (count s)))
      (str/replace (subs s 1 (dec (count s))) "''" "'")

      :else s)))

(defn frontmatter
  "The frontmatter of the text and the body after it:
   `{:name :description :metadata {\"title\" ...} :body}`, nil without a
   frontmatter. Reads the part of YAML skills use: `key: value` on top,
   one level of `key: value` under `metadata:`."
  [text]
  (let [[_ head body] (re-find #"(?s)^---\r?\n(.*?)\r?\n---\r?\n?(.*)$" (str text))]
    (when head
      (loop [[line & more] (str/split-lines head)
             section nil
             out {:metadata {}}]
        (cond
          (nil? line)
          (-> out
              (update :name #(some-> % not-empty))
              (assoc :body (str/trim body)))

          (or (str/blank? line) (str/starts-with? (str/trim line) "#"))
          (recur more section out)

          :else
          (let [indented? (re-find #"^\s" line)
                [k v] (str/split (str/trim line) #":\s*" 2)]
            (cond
              (and indented? (= "metadata" section))
              (recur more section (assoc-in out [:metadata k] (scalar (or v ""))))

              indented?
              (recur more section out)

              (str/blank? v)
              (recur more k out)

              :else
              (recur more nil (assoc out (keyword k) (scalar v))))))))))

(defn definition
  "The definition read from `file`, its folder the agent's:
   `{:name :description :metadata :model :thinking :instructions :folder}`.
   Throws when the file has no frontmatter or no name."
  [file]
  (let [folder (path/dirname (path/resolve file))
        {:keys [name description metadata body] :as read} (frontmatter (fs/readFileSync file "utf8"))]
    (when-not (and read name)
      (throw (ex-info (str "The definition " file " needs a frontmatter with a name.") {:file file})))
    {:name name
     :description description
     :metadata metadata
     :model (or (not-empty (get metadata "model")) default-model)
     :thinking (not-empty (get metadata "thinking"))
     :instructions (-> body
                       (str/replace "<skill>" folder)
                       (str/replace "<agent>" folder))
     :folder folder}))
