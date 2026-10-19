(ns rragent.templates
  "The templates an artifact project starts from, a folder each under
   `templates/`. The harness knows what a template is, not how its kind
   of artifact is made: a template folder has `TEMPLATE.md`, a
   frontmatter and a body. The frontmatter says `name`, `description`,
   `renders` (the media its render makes, `mp4`), `needs` (the programs
   it needs on the machine, `chrome, ffmpeg`, see `rragent.programs`)
   and `entry` (what the pane shows for a project: an `.html` file of
   the project, served as it is, `index.html` by default; or an
   executable of the template, run as `<entry> <project-dir>` with PORT
   in its environment, serving the project's page on that port, see
   `rragent.projects`). The body is what the agent needs to know to
   work with the template. Beside it: `project/`, the start of a new
   project, copied whole into `work/artifacts/<name>/` by the `project`
   tool, with a package.json that names the template; and `render`, an
   executable run as `render <project-dir> <output>` (`rragent.render`).
   The rest of the folder is the template's own: its sources, its
   build, its dependencies. A folder without a TEMPLATE.md is no
   template.

   Built, the templates lie next to the bundle (`dist/templates`, the
   build copies them there, see vite.config.mjs); under the dev server
   and in the tests the project's `templates/`; RRAGENT_TEMPLATES names
   another place."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]
            [rragent.definition :as definition]))

(defn dir
  "Where the templates lie."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_TEMPLATES"))
      (let [beside (path/join (path/dirname (url/fileURLToPath (js* "import.meta.url"))) "templates")]
        (if (fs/existsSync beside)
          beside
          (path/resolve "templates")))))

(defn words
  "A list of the frontmatter, `mp4, png` or `[mp4, png]`, as a vector."
  [s]
  (->> (str/split (str/replace (str s) #"[\[\]]" "") #"[,\s]+")
       (remove str/blank?)
       (vec)))

(defn read
  "The template in `template-dir`: `{:name :description :renders :needs
   :entry :body :dir}`; nil without a TEMPLATE.md with a frontmatter.
   The name is the frontmatter's, the folder's without one."
  [template-dir]
  (let [file (path/join template-dir "TEMPLATE.md")]
    (when (fs/existsSync file)
      (when-let [{:keys [name description renders needs entry body]} (definition/frontmatter (fs/readFileSync file "utf8"))]
        {:name (or (not-empty name) (path/basename template-dir))
         :description (str description)
         :renders (words renders)
         :needs (words needs)
         :entry (or (not-empty entry) "index.html")
         :body (str body)
         :dir (path/resolve template-dir)}))))

(defn all
  "The templates installed, sorted by name."
  []
  (->> (try
         (array-seq (fs/readdirSync (dir) #js {:withFileTypes true}))
         (catch :default _
           []))
       (filter (fn [^js entry] (and (.isDirectory entry) (not (str/starts-with? (.-name entry) ".")))))
       (keep (fn [^js entry] (read (path/join (dir) (.-name entry)))))
       (sort-by :name)
       (vec)))

(defn names
  []
  (mapv :name (all)))

(defn named
  "The template of that name, or nil."
  [name]
  (some #(when (= (str name) (:name %)) %) (all)))

(defn static?
  "Whether the pane serves a project of the template as files: its
   entry is a page of the project, not a program."
  [template]
  (boolean (re-find #"\.html?$" (str (:entry template)))))

(defn render-file
  "The template's render executable."
  [template]
  (path/join (:dir template) "render"))

(defn entry-file
  "The template's entry executable, for one that is no page."
  [template]
  (path/join (:dir template) (:entry template)))

(defn valid-name?
  "A project's name: lowercase letters, digits and dashes."
  [name]
  (boolean (re-matches #"[a-z0-9][a-z0-9-]*" (str name))))

(defn- package
  [project-dir]
  (try
    (js->clj (js/JSON.parse (fs/readFileSync (path/join project-dir "package.json") "utf8")))
    (catch :default _
      nil)))

(defn of-project
  "The template a project was made from, by the `template` of its
   package.json; `{:name :missing? true}` when that template is not
   installed; nil when the package names none."
  [project-dir]
  (when-let [kind (not-empty (str (get (package project-dir) "template")))]
    (or (named kind)
        {:name kind :missing? true})))

(defn- write-package!
  "The project's package.json names its template; one the template
   brought along keeps the rest."
  [target kind]
  (let [file (path/join target "package.json")
        existing (or (package target) {"name" (path/basename target) "private" true})]
    (fs/writeFileSync file (str (js/JSON.stringify (clj->js (assoc existing "template" kind)) nil 2) "\n"))))

(defn copy!
  "The template's `project/` copied to `target`, a directory that must
   not exist yet, with a package.json that names the template. Throws
   with the reason otherwise. Answers the template."
  [kind target]
  (let [template (or (named kind)
                     (throw (js/Error. (str "No template " kind ": " (str/join ", " (names)) "."))))
        source (path/join (:dir template) "project")]
    (when (fs/existsSync target)
      (throw (js/Error. (str target " exists already."))))
    (fs/mkdirSync target #js {:recursive true})
    (when (fs/existsSync source)
      (fs/cpSync source target #js {:recursive true}))
    (write-package! target (:name template))
    template))
