(ns rragent.templates
  "The project templates an artifact starts from, a folder per kind
   under `templates/` (`hyperframes`: a HyperFrames composition with a
   small Node server that shows it, see its README), copied whole into
   `work/artifacts/<name>/` by the `project` tool. Built, they lie next
   to the bundle (`dist/templates`, the build copies them there, see
   vite.config.mjs); under the dev server and in the tests the
   project's `templates/`; RRAGENT_TEMPLATES names another place."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]))

(defn dir
  "Where the templates lie."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_TEMPLATES"))
      (let [beside (path/join (path/dirname (url/fileURLToPath (js* "import.meta.url"))) "templates")]
        (if (fs/existsSync beside)
          beside
          (path/resolve "templates")))))

(defn kinds
  "The names of the templates."
  []
  (->> (try
         (array-seq (fs/readdirSync (dir) #js {:withFileTypes true}))
         (catch :default _
           []))
       (filter (fn [^js entry] (and (.isDirectory entry) (not (str/starts-with? (.-name entry) ".")))))
       (map (fn [^js entry] (.-name entry)))
       (sort)
       (vec)))

(defn valid-name?
  "A project's name: lowercase letters, digits and dashes."
  [name]
  (boolean (re-matches #"[a-z0-9][a-z0-9-]*" (str name))))

(defn copy!
  "The template `kind` copied to `target`, a directory that must not
   exist yet. Throws with the reason otherwise."
  [kind target]
  (let [source (path/join (dir) (str kind))]
    (when-not (some #{(str kind)} (kinds))
      (throw (js/Error. (str "No template " kind ": " (str/join ", " (kinds)) "."))))
    (when (fs/existsSync target)
      (throw (js/Error. (str target " exists already."))))
    (fs/cpSync source target #js {:recursive true})
    target))
