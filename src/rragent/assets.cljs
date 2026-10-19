(ns rragent.assets
  "The scripts and the stylesheet of the page, for the head of the shim.
   Built, they lie in `public/` next to the bundle (`dist/public`, from
   `vite build --config vite.client.config.mjs`), named by the manifest
   the cljf plugin writes there, and the server serves them. Under the
   dev server (`npm run dev`, which sets RRAGENT_VITE to its address)
   they come from the dev server, which hot reloads them: its manifest
   in dist/ names them. A program built from the same sources (the
   motion template's) finds its own bundle the same way, `built`."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]))

(def entry
  "The namespace of the page's bundle, its name in the manifest."
  "rragent.client")

(defn dev-origin
  "The address of the dev server when it runs the server, else nil."
  []
  (not-empty (aget (.-env js/process) "RRAGENT_VITE")))

(defn beside-bundle
  "`public/` next to the program that runs, whichever bundle this
   namespace is part of."
  []
  (path/join (path/dirname (url/fileURLToPath (js* "import.meta.url"))) "public"))

(defn public-dir
  "Where the built client lies: RRAGENT_PUBLIC, else `public/` next to
   this program."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_PUBLIC"))
      (beside-bundle)))

(defn- manifest
  "The entry `name` of the manifest `file`, `{\"scripts\" [...]
   \"preload\" [...]}`; nil without the file."
  [file name]
  (when (fs/existsSync file)
    (get (js->clj (js/JSON.parse (fs/readFileSync file "utf8"))) name)))

(defn built
  "The `{:scripts :preload}` of the bundle `name` in `dir`, URLs
   relative to the origin, for the shim of a page the bundle's own
   program serves."
  [dir name]
  (let [{:strs [scripts preload]} (manifest (path/join dir "manifest.json") name)]
    {:scripts scripts
     :preload preload}))

(defn client
  "The page's `{:scripts :preload :css}`, URLs for the shim."
  []
  (if-let [origin (dev-origin)]
    (let [{:strs [scripts preload]} (manifest (path/resolve "dist/manifest.json") entry)]
      {:scripts (map #(str origin %) scripts)
       :preload (map #(str origin %) preload)
       :css (str origin "/app.css")})
    (assoc (built (public-dir) entry) :css "/app.css")))

(defn head
  "The head of the shim: the title, the stylesheet, the preloads and the
   scripts of the page."
  [{:keys [scripts preload css]}]
  (str "<title>rragent</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       "<link rel=\"stylesheet\" href=\"" css "\">"
       (apply str (for [href preload]
                    (str "<link rel=\"modulepreload\" href=\"" href "\">")))
       (apply str (for [src scripts]
                    (str "<script type=\"module\" src=\"" src "\"></script>")))))
