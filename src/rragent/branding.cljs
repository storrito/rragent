(ns rragent.branding
  "The name and the logo in the top left corner of the page. A machine
   shows \"rragent\" there until its people give it a name of their
   own, a logo, or both, in the Branding section of the settings
   (`rragent.view/branding-section`). The logo stands in front of the
   name, and alone when the machine has a logo and no name. The name
   is the title of the browser's tab too.

   Both are the machine's, not a bot's and not a browser's, so everyone
   with the link sees them. `branding.json` in the machine's folder
   holds `{\"name\" … \"logo\" …}`, and the logo's file lies in
   `branding/` beside it, named by the hash of its bytes. A browser may
   keep such a file for good, and a new logo has a new address.

   `POST /branding/logo` takes the picture as the request's body with
   its media type, and its name (`?name=`) for a file the browser types
   poorly. What the file is its bytes say, not its name (`kind-of`): a
   WebP saved as logo.png is stored as a WebP, and a file that is no
   picture is not taken. `GET /branding/logo/<file>` serves the saved
   one. Both are behind the token (`rragent.page`)."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.rframes.http :as http]
            [rragent.uploads :as uploads]))

(def default-name
  "What the corner says on a machine without a name of its own."
  "rragent")

(def max-chars
  "The longest name. The sidebar is 240 pixels wide."
  40)

(def logo-types
  "The media types of a logo, with the extension of the stored file."
  {"image/png" "png"
   "image/jpeg" "jpg"
   "image/webp" "webp"
   "image/gif" "gif"
   "image/svg+xml" "svg"})

(def accept
  "What the file picker of the settings offers."
  (str/join "," (keys logo-types)))

(def logo-max-bytes
  "The largest logo, two megabytes. The page shows it 24 pixels high."
  (* 2 1024 1024))

(def logo-path
  "Where the browser posts a logo, and below which the saved one is
   served."
  "/branding/logo")

(def ^:private logo-name
  "The name of a stored logo: the hash of its bytes and its extension."
  #"[0-9a-f]{32}\.(png|jpg|webp|gif|svg)")

(defn logo-url
  "The address of the stored logo `file-name`."
  [file-name]
  (str logo-path "/" file-name))

(defn file
  "The branding of the machine's folder `home`."
  [home]
  (path/join home "branding.json"))

(defn dir
  "Where the logo's file lies."
  [home]
  (path/join home "branding"))

(defn clean
  "The name as kept: one line, trimmed, at most `max-chars` characters;
   nil for nothing. A character is a code point, so the cut never
   leaves half an emoji."
  [s]
  (let [s (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (when (seq s)
      (str/trim (str/join (take max-chars (array-seq (js/Array.from s))))))))

(defn saved
  "What the machine's folder `home` holds: `{:name :logo}`, the name
   cleaned, the logo the name of its file in `dir`. A key is missing
   when nothing is saved for it, when the file does not say it the way
   this namespace writes it, or when the logo's file is gone."
  [home]
  (let [data (try
               (js->clj (js/JSON.parse (fs/readFileSync (file home) "utf8")))
               (catch :default _
                 nil))
        {:strs [name logo]} (when (map? data) data)
        name (when (string? name) (clean name))]
    (cond-> {}
      name
      (assoc :name name)

      (and (string? logo)
           (re-matches logo-name logo)
           (http/regular-file? (path/join (dir home) logo)))
      (assoc :logo logo))))

(defn- write!
  "`branding` as the whole file: written beside it and renamed over
   it, so that no reader sees half a file."
  [home branding]
  (let [target (file home)
        tmp (str target ".tmp-" (.-pid js/process) "-" (js/Date.now))]
    (fs/mkdirSync home #js {:recursive true})
    (fs/writeFileSync tmp (str (js/JSON.stringify (clj->js branding) nil 2) "\n"))
    (fs/renameSync tmp target)))

(defn- sweep!
  "Removes every file of the logo's folder but the one named `kept`. A
   file that does not go is left: nothing serves it (`logo-handler`),
   and the next sweep tries again."
  [home kept]
  (doseq [file-name (try
                      (array-seq (fs/readdirSync (dir home)))
                      (catch :default _
                        nil))
          :when (not= file-name kept)]
    (try
      (fs/rmSync (path/join (dir home) file-name) #js {:force true :recursive true})
      (catch :default e
        (js/console.error "[branding] could not remove" file-name (ex-message e))))))

(defn save-name!
  "The name of the corner set to `name`, cleaned; a blank one brings
   `default-name` back. Answers what is saved then."
  [home name]
  (let [name (clean name)
        branding (saved home)]
    (write! home (if name
                   (assoc branding :name name)
                   (dissoc branding :name)))
    (saved home)))

(defn save-logo!
  "`bytes`, a Buffer, as the logo: a picture stored under `extension`,
   one of `logo-types`. The logo of before goes. Answers what is saved
   then."
  [home ^js bytes extension]
  (let [hash (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex"))
        file-name (str (subs hash 0 32) "." extension)]
    (fs/mkdirSync (dir home) #js {:recursive true})
    (fs/writeFileSync (path/join (dir home) file-name) bytes)
    (write! home (assoc (saved home) :logo file-name))
    (sweep! home file-name)
    (saved home)))

(defn remove-logo!
  "The corner without a logo again. Answers what is saved then."
  [home]
  (write! home (dissoc (saved home) :logo))
  (sweep! home nil)
  (saved home))

(defn extension-for
  "The extension the media type the browser named stands for, else the
   one of the file's own name; nil when neither is a picture of
   `logo-types`. What a file says it is: `kind-of` asks its bytes."
  [type file-name]
  (or (logo-types type)
      (let [named (str/lower-case (or (second (re-find #"\.([^./]+)$" (str file-name))) ""))
            named (get {"jpeg" "jpg"} named named)]
        (some #{named} (vals logo-types)))))

(defn- starts-with-bytes?
  "Whether `bytes` holds `expected`, a sequence of numbers, at `offset`."
  [^js bytes offset expected]
  (and (<= (+ offset (count expected)) (.-length bytes))
       (every? true? (map-indexed (fn [i b] (= b (aget bytes (+ offset i)))) expected))))

(defn- ascii
  [s]
  (map #(.charCodeAt % 0) s))

(defn- signature
  "The extension of the picture whose signature `bytes` starts with:
   a PNG, a JPEG, a GIF or a WebP; nil for none of them."
  [^js bytes]
  (cond
    (starts-with-bytes? bytes 0 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]) "png"
    (starts-with-bytes? bytes 0 [0xFF 0xD8 0xFF]) "jpg"
    (or (starts-with-bytes? bytes 0 (ascii "GIF87a"))
        (starts-with-bytes? bytes 0 (ascii "GIF89a"))) "gif"
    (and (starts-with-bytes? bytes 0 (ascii "RIFF"))
         (starts-with-bytes? bytes 8 (ascii "WEBP"))) "webp"))

(defn- svg-tag?
  "Whether `bytes` has an `<svg` tag in it, as UTF-8 or as UTF-16."
  [^js bytes]
  (or (.includes bytes "<svg")
      (.includes bytes "<svg" 0 "utf16le")))

(defn kind-of
  "The extension `bytes`, a Buffer, is stored under as a logo; nil for
   a file that is no picture of `logo-types`. The bytes say what the
   file is: the signature of a PNG, a JPEG, a GIF or a WebP wins over
   the type the browser named and the file's name (`declared`, the
   extension of `extension-for`), which follow the name and are wrong
   for a WebP saved as logo.png. An SVG has no signature, so a file is
   one when it says so and has an `<svg` tag. A photo of a phone
   renamed to .jpg, a text file or nothing at all is no picture, and
   the corner would show an empty place for it."
  [^js bytes declared]
  (or (signature bytes)
      (when (and (= "svg" declared) (svg-tag? bytes))
        "svg")))

(defn- media-type
  [request]
  (some-> (get-in request [:headers "content-type"])
          (str/split #";")
          (first)
          (str/trim)
          (str/lower-case)))

(defn- file-name-param
  "The file's own name from the query, `?name=`, decoded; nil without
   one or when it does not decode."
  [request]
  (try
    (some-> (get (uploads/query-params request) "name") js/decodeURIComponent)
    (catch :default _
      nil)))

(defn ^:async upload-handler
  "`POST /branding/logo`: the request's body as the logo of the
   machine's folder `home`, stored as the kind its bytes are
   (`kind-of`). Answers what is saved then, as transit; a 413
   `:too-large` past `logo-max-bytes`, a 415 `:not-taken` for a file
   that is no picture of `logo-types`."
  [home w]
  (let [request (:ring/request w)
        declared (extension-for (media-type request) (file-name-param request))
        ^js bytes (await (uploads/read-body (:body request) logo-max-bytes))
        extension (some-> bytes (kind-of declared))]
    (cond
      (nil? bytes) (uploads/respond w 413 {:error :too-large})
      (nil? extension) (uploads/respond w 415 {:error :not-taken})
      :else (uploads/respond w 200 (save-logo! home bytes extension)))))

(def logo-headers
  "What a served logo carries besides its type. Its name is the hash of
   its bytes, so the browser keeps it. An SVG is a document that could
   run a script when someone opens its address in a tab of its own, so
   the policy forbids scripts and everything the file would load; in
   the page's `<img>` no SVG runs one anyway."
  {"Cache-Control" "private, max-age=31536000, immutable"
   "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; sandbox"
   "X-Content-Type-Options" "nosniff"})

(defn logo-handler
  "`GET /branding/logo/<file>`: the saved logo of the machine's folder
   `home`, no other file of it; a 404 for a logo of before."
  [home w]
  (let [file-name (str (get-in w [:ring/route-params :*]))]
    (assoc w :ring/response
           (if (= file-name (:logo (saved home)))
             (update (http/file-response (:ring/request w) (path/join (dir home) file-name))
                     :headers merge logo-headers)
             http/not-found))))
