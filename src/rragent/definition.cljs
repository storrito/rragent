(ns rragent.definition
  "The agent's definition: an `AGENT.md` (or an agentitos `SKILL.md`) in
   the Agent Skills format, a YAML frontmatter and a body. The body is
   the agent's instructions, `<skill>` and `<agent>` in it meaning the
   folder. The frontmatter carries the name and the description, and
   under `metadata` what the harness reads: `model` (an OpenRouter
   model id), `thinking` (a level), `templates` (the templates the
   agent may make projects from, `[motion, hyperframes]`; every
   installed one without); and `renamed: true` when the person named
   the bot from the page (`rragent.bots/rename!`), which the bot is
   told to respect. The same reading of the frontmatter as Storrito's
   `app.agent.skills` and pchat's `pchat.skills`."
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

(defn templates
  "The templates the metadata allows, a vector of names: `[motion,
   hyperframes]` or `motion, hyperframes`, `[]` for none; nil when the
   metadata names none at all, which means every installed one."
  [metadata]
  (when (contains? metadata "templates")
    (->> (str/split (str/replace (str (get metadata "templates")) #"[\[\]]" "") #"[,\s]+")
         (remove str/blank?)
         (vec))))

(defn from-text
  "The definition in `text`, the content of `file`, whose folder is the
   agent's: `{:name :description :metadata :model :thinking :templates
   :instructions :folder}`. Throws when the text has no frontmatter or
   no name."
  [text file]
  (let [folder (path/dirname (path/resolve file))
        {:keys [name description metadata body renamed] :as read} (frontmatter text)]
    (when-not (and read name)
      (throw (ex-info (str "The definition " file " needs a frontmatter with a name.") {:file file})))
    {:name name
     :description description
     :renamed? (= "true" (str renamed))
     :metadata metadata
     :model (or (not-empty (get metadata "model")) default-model)
     :thinking (not-empty (get metadata "thinking"))
     :templates (templates metadata)
     :instructions (-> body
                       (str/replace "<skill>" folder)
                       (str/replace "<agent>" folder))
     :folder folder}))

(defn definition
  "The definition read from `file` (`from-text`)."
  [file]
  (from-text (fs/readFileSync file "utf8") file))

(def generic-name
  "The name of the definition every new machine starts with
   (`deploy/agent/AGENT.md`): an agent that has not taken a skill or a
   job yet."
  "Assistant")

(defn generic?
  "Whether the definition still has the generic name: a bot that has
   not named itself, taken a skill or been named by the person yet."
  [{:keys [name]}]
  (= generic-name name))

(defn display-name
  "The name the page shows for the definition: its own, unless it is
   the generic one and `RRAGENT_NAME` is set, the name the provisioner
   gave the machine. Once the agent has taken a skill or a job, its
   file's name wins."
  [{:keys [name] :as definition}]
  (if (generic? definition)
    (or (not-empty (aget (.-env js/process) "RRAGENT_NAME")) name)
    name))
