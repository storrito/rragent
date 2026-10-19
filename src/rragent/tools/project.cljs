(ns rragent.tools.project
  "The `project` tool: an artifact with a process of its own made from
   a template (`rragent.templates`), the projects listed with their
   state (`rragent.projects`), the log of one read. The counterpart of
   pchat's bin/new."
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

(defn create!
  "The template `kind` copied to `artifacts/<name>/`; answers what to do
   next."
  [dir name kind]
  (when-not (templates/valid-name? name)
    (throw (js/Error. "A project's name is lowercase letters, digits and dashes.")))
  (templates/copy! kind (path/join dir name))
  (str "artifacts/" name "/ is ready: "
       (case kind
         "hyperframes" "composition/index.html is the design, README.md says how it works."
         "README.md says how it works.")))

(defn listed
  "The projects of the folder with their state from the db."
  [dir value]
  (->> (projects/names dir)
       (map (fn [name]
              (let [{:keys [ready-at exit]} (get-in value [:projects name])]
                (str name ": " (cond
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

(def tool
  (defineTool
   #js {:name "project"
        :description (str "An artifact with a page of its own, for anything that moves or that the person should play with: "
                          "`create` with a name and a kind copies the template into artifacts/<name>/ (kinds: hyperframes, a HyperFrames composition in composition/index.html with GSAP; its README.md says how it works). "
                          "rragent starts the project and shows its page beside the chat, every saved change at once. "
                          "`list` shows the projects and whether they run; `log` with the name shows the end of the project's output, for a page that shows nothing.")
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "list") (.Literal Type "log")])
                                       :name (.Optional Type (.String Type #js {:description "The project's name: lowercase letters, digits, dashes"}))
                                       :kind (.Optional Type (.String Type #js {:description "The template, hyperframes by default"}))})
        :execute (^:async fn [args api _call-context]
                   (let [dir (work/artifacts-dir api)
                         name (.-name args)]
                     (case (.-action args)
                       "create" (do (when (empty? name)
                                      (throw (js/Error. "create needs the name")))
                                    (work/text (create! dir name (or (not-empty (.-kind args)) "hyperframes"))))
                       "list" (work/text (let [s (listed dir @db/db)] (if (str/blank? s) "No projects" s)))
                       "log" (do (when (empty? name)
                                   (throw (js/Error. "log needs the name")))
                                 (work/text (log dir name))))))}))
