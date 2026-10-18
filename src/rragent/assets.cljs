(ns rragent.assets
  "The scripts and the stylesheet of the page, for the head of the shim.
   Built, they lie in `public/` next to the bundle (`dist/public`, from
   `vite build --config vite.client.config.mjs`), named by the manifest
   the cljf plugin writes there, and the server serves them. Under the
   dev server (`npm run dev`, which sets RRAGENT_VITE to its address)
   they come from the dev server, which hot reloads them: its manifest
   in dist/ names them."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]))

(defn dev-origin
  "The address of the dev server when it runs the server, else nil."
  []
  (not-empty (aget (.-env js/process) "RRAGENT_VITE")))

(defn public-dir
  "Where the built client lies: RRAGENT_PUBLIC, else `public/` next to
   this program."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_PUBLIC"))
      (path/join (path/dirname (url/fileURLToPath (js* "import.meta.url"))) "public")))

(defn- manifest
  "The entry of the page's bundle in the manifest `file`, `{\"scripts\"
   [...] \"preload\" [...]}`; nil without the file."
  [file]
  (when (fs/existsSync file)
    (get (js->clj (js/JSON.parse (fs/readFileSync file "utf8"))) "rragent.client")))

(defn client
  "The page's `{:scripts :preload :css}`, URLs for the shim."
  []
  (if-let [origin (dev-origin)]
    (let [{:strs [scripts preload]} (manifest (path/resolve "dist/manifest.json"))]
      {:scripts (map #(str origin %) scripts)
       :preload (map #(str origin %) preload)
       :css (str origin "/app.css")})
    (let [{:strs [scripts preload]} (manifest (path/join (public-dir) "manifest.json"))]
      {:scripts scripts
       :preload preload
       :css "/app.css"})))

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
