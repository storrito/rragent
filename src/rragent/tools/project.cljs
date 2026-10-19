(ns rragent.tools.project
  "The `project` tool: an artifact with a page of its own made from a
   template (`rragent.templates`), the projects listed with their
   state (`rragent.projects`), the log of one read. The tool is made
   per agent: its description names the templates the agent may use,
   the ones its definition allows (`rragent.definition`), with their
   descriptions, so the model knows what it can make; `create` answers
   with the template's notes. The counterpart of pchat's bin/new."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.projects :as projects]
            [rragent.templates :as templates]
            [rragent.tools.work :as work]))

(def log-chars
  4000)

(defn allowed
  "The templates the agent may use: the installed ones the definition
   names, every installed one when it names none."
  [definition]
  (let [installed (templates/all)]
    (if-let [names (:templates definition)]
      (filterv #(some #{(:name %)} names) installed)
      installed)))

(defn- allowed!
  "The template `kind` when the agent may use it; throws with the
   allowed ones otherwise."
  [templates kind]
  (or (some #(when (= kind (:name %)) %) templates)
      (throw (js/Error. (if (seq templates)
                          (str "The template " kind " is not allowed for this agent, these are: " (str/join ", " (map :name templates)) ".")
                          "This agent's definition allows no template, so no project.")))))

(defn- chosen
  "The kind to make: the one named, else the only allowed one."
  [templates kind]
  (cond
    (empty? templates) (throw (js/Error. "This agent's definition allows no template, so no project."))
    (seq kind) kind
    (= 1 (count templates)) (:name (first templates))
    :else (throw (js/Error. (str "Which template? One of: " (str/join ", " (map :name templates)) ".")))))

(defn create!
  "The template's project copied to `artifacts/<name>/`; answers the
   template's notes, what the agent needs to know."
  [dir templates name kind]
  (when-not (templates/valid-name? name)
    (throw (js/Error. "A project's name is lowercase letters, digits and dashes.")))
  (let [template (allowed! templates (chosen templates kind))]
    (templates/copy! (:name template) (path/join dir name))
    (str "artifacts/" name "/ is ready, made from the template " (:name template)
         ". The notes below are in " (path/join (:dir template) "TEMPLATE.md") ".\n\n"
         (:body template))))

(defn listed
  "The projects of the folder with their state from the db."
  [dir value]
  (->> (projects/names dir)
       (map (fn [name]
              (let [{:keys [ready-at exit static?]} (get-in value [:projects name])
                    template (templates/of-project (path/join dir name))]
                (str name (when template (str " (" (:name template) ")")) ": "
                     (cond
                       static? "shown as it is"
                       exit (str "stopped (exit " exit ")")
                       ready-at "running"
                       :else "not running")))))
       (str/join "\n")))

(defn log
  "The end of the project's `.log`."
  [dir name]
  (let [file (path/join dir name ".log")]
    (if (fs/existsSync file)
      (let [text (fs/readFileSync file "utf8")]
        (if (< log-chars (count text))
          (str "… " (subs text (- (count text) log-chars)))
          text))
      (str "No log yet for " name "."))))

(defn description
  "What the tool says about itself, the templates named."
  [templates]
  (str "An artifact with a page of its own, for anything that moves or that the person should play with. "
       "`create` with a name and a kind starts one from a template in artifacts/<name>/ and answers with the template's notes on how to work with it"
       (if (seq templates)
         (str ". The templates: "
              (str/join "; " (for [{:keys [name description renders]} templates]
                               (str name ", " description
                                    (when (seq renders) (str " (renders " (str/join ", " renders) ")")))))
              ". ")
         ". This agent has no template. ")
       "rragent shows the project's page beside the chat, every saved change at once. "
       "`list` shows the projects and whether they run; `log` with the name shows the end of a project's output, for a page that shows nothing."))

(defn tool
  "The tool for an agent, its `definition` naming the templates."
  [definition]
  (let [templates (allowed definition)]
    (defineTool
     #js {:name "project"
          :description (description templates)
          :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list") (.Literal Type "log")])
                                         :name (.Optional Type (.String Type #js {:description "The project's name: lowercase letters, digits, dashes"}))
                                         :kind (.Optional Type (.String Type #js {:description (str "The template: " (str/join ", " (map :name templates)))}))})
          :execute (^:async fn [args api _call-context]
                     (let [dir (work/artifacts-dir api)
                           name (.-name args)]
                       (case (.-action args)
                         "create" (do (when (empty? name)
                                        (throw (js/Error. "create needs the name")))
                                      (work/text (create! dir templates name (.-kind args))))
                         "list" (work/text (let [s (listed dir @db/db)] (if (str/blank? s) "No projects" s)))
                         "log" (do (when (empty? name)
                                     (throw (js/Error. "log needs the name")))
                                   (work/text (log dir name))))))})))
