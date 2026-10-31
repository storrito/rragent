(ns rragent.shares
  "A folder of a bot's artifacts behind a public link, `/s/<token>/`
   on the machine's page, outside the token (`rragent.auth`): for a
   customer, or anyone the person gives the link to. The bot makes
   one with the `share` tool (`rragent.tools.share`) for a folder
   under `artifacts/`, and the link works until the bot stops the
   share, or until the moment the share names (`until`). The folder
   is served as it is, live: every file in it by its path, never a
   hidden one or a working note (`rragent.artifacts`); the bare link
   opens the folder's `index.html`, and nothing without one. The
   shares of the machine live in the session document
   `rragent.shares`, by token, each with its bot (the id of its
   conversation), its folder and its moment; the token is the key
   and the whole secret, kept as it is, like a hook's."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.home :as home]
            [rragent.rframes.http :as http]))

(def Shares
  "The machine's shares: by token, `{bot folder createdAt until}`,
   `until` in ms when the share has a moment."
  (defineDoc #js {:kind "rragent.shares"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {})}))

(def prefix
  "/s")

(defn link-path
  "The path of a share's root on the page."
  [token]
  (str prefix "/" token "/"))

(defn url
  "A share's full address when `RRAGENT_URL` names the page's, else
   its path."
  [token]
  (str (str/replace (str (aget (.-env js/process) "RRAGENT_URL")) #"/+$" "") (link-path token)))

(defn folder-name
  "The folder as a share keeps it, relative to `artifacts/`: without
   a leading `artifacts/`, without slashes at the ends."
  [folder]
  (-> (str folder)
      str/trim
      (str/replace #"^/+" "")
      (str/replace #"^artifacts/+" "")
      (str/replace #"/+$" "")))

(defn shareable?
  "Whether `folder` (as `folder-name` gives it) is a folder of the
   artifacts in `dir` a bot may share: inside them, neither hidden
   nor the notes, and a directory."
  [dir folder]
  (and (artifacts/inside? dir folder)
       (not (artifacts/note? (str folder "/")))
       (try
         (.isDirectory (fs/statSync (path/join dir folder)))
         (catch :default _
           false))))

(defn- token!
  []
  (.toString (crypto/randomBytes 16) "base64url"))

(defn- share-of
  [token s]
  (cond-> {:token token :bot (.-bot s) :folder (.-folder s) :created (.-createdAt s)}
    (.-until s) (assoc :until (.-until s))))

(defn- entries
  [doc]
  (js/Object.entries (or doc #js {})))

(defn of
  "The share of `bot` for `folder` in `doc`: `{:token :bot :folder
   :created :until}`, nil without one."
  [doc bot folder]
  (some (fn [[token s]]
          (when (and (= (str bot) (str (.-bot s))) (= folder (.-folder s)))
            (share-of token s)))
        (entries doc)))

(defn expired?
  [share now]
  (boolean (some-> (:until share) (<= now))))

(defn lookup
  "The share of `token` in `doc`, as `of` gives it; nil for a token
   that is none's, or one past its moment at `now`."
  [doc token now]
  (when-let [s (and (seq (str token)) (aget (or doc #js {}) (str token)))]
    (let [share (share-of (str token) s)]
      (when-not (expired? share now)
        share))))

(defn listed
  "The shares of `bot` in `doc`, newest first."
  [doc bot]
  (->> (entries doc)
       (keep (fn [[token s]]
               (when (= (str bot) (str (.-bot s)))
                 (share-of token s))))
       (sort-by :created >)))

(defn line
  "What the bot reads of a share: the folder, the link, the moment."
  [share]
  (str "artifacts/" (:folder share) "/ at " (url (:token share))
       (when-let [until (:until share)]
         (str " (until " (.toISOString (js/Date. until)) ")"))))

(defn ^:async read
  "The document, as a plain copy, read through a commit of `api` (a
   tool's api, or the harness) that changes nothing."
  [api call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (js/JSON.parse (js/JSON.stringify (await (.doc tx Shares)))))
                  call-context)))

(defn ^:async create!
  "The share of `bot` for `folder`, committed through `api`: the one
   there is, its moment set to `until` (ms, or nil for none), else a
   new one. Answers the share as `of` gives it."
  [api bot folder until call-context]
  (let [made (atom nil)]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Shares))
                            token (or (:token (of doc bot folder)) (token!))
                            s (or (aget doc token) #js {:bot bot :folder folder :createdAt (js/Date.now)})]
                        (if until
                          (aset s "until" until)
                          (js-delete s "until"))
                        (aset doc token s)
                        (reset! made (share-of token s))))
                    call-context))
    @made))

(defn ^:async stop!
  "The share of `bot` for `folder` ended: true when there was one."
  [api bot folder call-context]
  (let [found (atom false)]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Shares))]
                        (when-let [share (of doc bot folder)]
                          (reset! found true)
                          (js-delete doc (:token share)))))
                    call-context))
    @found))

(def gone
  "The answer to a link that shares nothing: a token that is none's,
   a share stopped or past its moment, a file the folder has not."
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-store"
             "X-Robots-Tag" "noindex, nofollow"}
   :body (str "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
              "<title>Nothing here</title><style>body{font:16px system-ui,sans-serif;padding:2rem;color:#1a1a1a;background:#fff}"
              "@media (prefers-color-scheme:dark){body{color:#e8eaed;background:#111315}}</style></head><body>"
              "<h1>Nothing here</h1><p>This link is not shared, or not any more.</p></body></html>")})

(def public-headers
  "On every file a share serves: no search engine, no referrer, the
   type as given."
  {"X-Robots-Tag" "noindex, nofollow"
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"})

(defn root
  "The folder of `share` on disk, under the bot's artifacts in `home`."
  [home share]
  (path/join (home/bot-dir home (:bot share)) "work" "artifacts" (:folder share)))

(defn- decoded
  [subpath]
  (try
    (js/decodeURIComponent (str subpath))
    (catch :default _
      nil)))

(defn file
  "The file `subpath` of `share` names, `index.html` for the root and
   for a path that ends in a slash; nil when the folder has no such
   file, when the path leaves the folder, or when it names a hidden
   file or a working note."
  [home share subpath]
  (let [subpath (str subpath)
        subpath (if (or (str/blank? subpath) (str/ends-with? subpath "/")) (str subpath "index.html") subpath)
        plain (decoded subpath)]
    (when (and plain
               (not (artifacts/hidden? plain))
               (not (artifacts/note? plain)))
      (http/resolve-file (root home share) subpath))))

(defn ^:async handler
  "`GET /s/<token>/<path>`, and its HEAD: the file of the share,
   `public-headers` on it; the bare `/s/<token>` sent on to its
   slash, so that the page's relative links hold; `gone` for anything
   else. `shares` answers a promise of the document, `home` is the
   machine's."
  [{:keys [home shares]} w]
  (let [[token subpath] (str/split (str (get-in w [:ring/route-params :*])) #"/" 2)
        share (lookup (await (shares)) token (js/Date.now))
        file (when (and share subpath) (file home share subpath))]
    (assoc w :ring/response
           (cond
             file (update (http/file-response (:ring/request w) file) :headers merge public-headers)
             (and share (nil? subpath)) {:status 302
                                         :headers {"Location" (link-path token) "Cache-Control" "no-store"}
                                         :body ""}
             :else gone))))
