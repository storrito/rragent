(ns rragent.update
  "A newer rragent, taken from the releases on GitHub. At start, every
   hour and when the person asks, the newest release is looked up at
   `releases/latest` of GitHub's API, without a token; a release newer
   than the running version (`rragent.version`, the tag's) is noted in
   the db the page renders from, and the page shows a banner with the
   version, the first line of the release's notes and an Update button.
   A lookup that fails is said in one line on stderr and that is all.
   The click applies the release; so does the idle watch, when the
   agent is idle the way `rragent.sleep` sees it (no run, no render,
   no page connected) and the process is started again after an exit
   (`RRAGENT_UPDATE=auto`, which deploy/start.sh sets): an agent nobody
   opens profits too. Nothing is applied while a run goes.
   `RRAGENT_UPDATE=off` switches the lookup off.

   Applying: `rragent.tar.gz` and its `.sha256` are downloaded from
   the release into a temp folder of the install folder (the same
   file system, so the moves below are renames), the checksum is
   verified, the tarball unpacked into `dist.new` (it holds the content
   of `dist/`, as `npm run release` packs it), `dist` moved to
   `dist.old`, `dist.new` to `dist`, `dist.old` removed, and the process
   ends with code 75: start.sh starts it again on that code, from the
   new `dist/`. A failed download or checksum leaves `dist` as it was,
   the banner says what went wrong. The install folder is the parent
   of the folder the running bundle lies in (`import.meta.url`:
   `/home/sprite/rragent/dist/rragent.js` → `/home/sprite/rragent`),
   not the working directory, so a checkout on a laptop replaces its
   own `dist/` too; under the dev server nothing is applied.

   The db key this namespace owns:

       :update {:current \"0.1.2\" :auto? true
                :version \"0.1.3\" :tarball-url … :sha256-url … :notes \"…\"
                :state :known | :applying | :failed, :error \"…\"
                :checked :found | :newest | :failed}

   `:checked` is the answer of a lookup the person asked for."
  (:require ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]
            [rragent.assets :as assets]
            [rragent.version :as version]))

(def exit-code
  "What the process ends with once the new `dist/` is in place:
   start.sh starts it again on this code and ends on any other."
  75)

(def latest-url
  "https://api.github.com/repos/storrito/rragent/releases/latest")

(def check-every-ms
  "How often the newest release is looked up: once an hour."
  (* 60 60 1000))

(def idle-every-ms
  "How often the idle watch looks whether a known update may go in."
  30000)

(def tarball-name
  "rragent.tar.gz")

(def checksum-name
  "rragent.tar.gz.sha256")

(defn parse-version
  "`v0.1.3` → `[0 1 3]`: the `v` dropped, a part that is no number 0."
  [version]
  (mapv (fn [part]
          (let [n (js/parseInt part 10)]
            (if (js/isNaN n) 0 n)))
        (str/split (str/replace (str version) #"^v" "") #"[.-]")))

(defn newer?
  "Whether `candidate` is a higher version than `current`."
  [candidate current]
  (let [a (parse-version candidate)
        c (parse-version current)
        n (max (count a) (count c))
        pad (fn [v] (into v (repeat (- n (count v)) 0)))]
    (pos? (compare (pad a) (pad c)))))

(defn first-line
  "The first line of the release's notes that says something: not a
   heading, its list marker dropped, cut at 160 characters; nil
   without one."
  [notes]
  (when-let [line (->> (str/split-lines (str notes))
                       (map str/trim)
                       (remove #(or (str/blank? %) (str/starts-with? % "#")))
                       (first))]
    (let [line (str/replace line #"^[-*+]\s+" "")]
      (if (< 160 (count line))
        (str (subs line 0 160) " …")
        line))))

(defn release-of
  "The release as the page needs it, from GitHub's JSON of it:
   `{:version :tarball-url :sha256-url :notes}`; nil when it has no
   tag or lacks the tarball or its checksum (a release still being
   built)."
  [{:keys [tag_name body assets]}]
  (let [url-of (fn [name]
                 (some #(when (= name (:name %)) (not-empty (:browser_download_url %))) assets))
        version (some-> tag_name (str/replace #"^v" ""))
        tarball (url-of tarball-name)
        sha256 (url-of checksum-name)]
    (when (and (seq version) tarball sha256)
      {:version version
       :tarball-url tarball
       :sha256-url sha256
       :notes (first-line body)})))

(defn install-dir
  "The folder the running bundle's `dist/` is in: the parent of the
   bundle's own folder, never the working directory."
  []
  (path/dirname (path/dirname (url/fileURLToPath (js* "import.meta.url")))))

(defn off?
  "`RRAGENT_UPDATE=off`: no lookup at all."
  []
  (= "off" (aget (.-env js/process) "RRAGENT_UPDATE")))

(defn auto?
  "`RRAGENT_UPDATE=auto`: a known update goes in by itself once the
   agent is idle, since something starts the process again."
  []
  (= "auto" (aget (.-env js/process) "RRAGENT_UPDATE")))

(defn- say
  [& words]
  (js/console.error "[update]" (str/join " " words)))

(def ^:private request-headers
  #js {"Accept" "application/vnd.github+json"
       "User-Agent" (str "rragent/" version/version)})

(defn- ^:async fetch-response
  "`url` fetched; a status other than 2xx is the error."
  [url timeout-ms]
  (let [response (await (js/fetch url #js {:headers request-headers
                                           :redirect "follow"
                                           :signal (js/AbortSignal.timeout timeout-ms)}))]
    (if (.-ok response)
      response
      (throw (js/Error. (str "HTTP " (.-status response) " from " url))))))

(defn ^:async lookup
  "The newest release at `latest-url`: `{:release …}`, `:release` nil
   when it is none we can take, or `{:error \"…\"}`."
  [latest-url]
  (try
    (let [response (await (fetch-response latest-url 10000))]
      {:release (release-of (js->clj (await (.json response)) :keywordize-keys true))})
    (catch :default e
      {:error (str (or (.-message e) e))})))

(defn- note!
  "What the lookup found, into the db: the release when it is newer,
   else only what the page shows anyway; an update being applied stays
   as it is."
  [db release]
  (swap! db update :update
         (fn [{:keys [state] :as known}]
           (cond
             (= :applying state) known
             release (-> (select-keys known [:current :auto?])
                         (merge release)
                         (assoc :state :known))
             :else (select-keys known [:current :auto?])))))

(defn ^:async check!
  "The newest release looked up and noted in the db: `:found` when it
   is newer than the running version, `:newest` when it is not,
   `:failed` when the lookup failed, said in one line; the db keeps
   what it had then."
  [{:keys [db latest-url current]}]
  (let [{:keys [release error]} (await (lookup latest-url))]
    (cond
      error (do (say "no lookup of the newest release:" error)
                (swap! db update :update dissoc :checked)
                :failed)
      (and release (newer? (:version release) current)) (do (note! db release)
                                                             :found)
      :else (do (note! db nil)
                :newest))))

(defn ^:async check-now!
  "The person asks: `check!`, its answer kept in the db as `:checked`,
   so that the banner can say that there is nothing newer, or that the
   lookup failed. Answers what `check!` answers."
  [{:keys [db] :as handle}]
  (let [result (await (check! handle))]
    (swap! db assoc-in [:update :checked] result)
    result))

(defn- ^:async download!
  "`url` saved as `file`."
  [url file]
  (let [response (await (fetch-response url 600000))]
    (fs/writeFileSync file (js/Buffer.from (await (.arrayBuffer response))))
    file))

(defn sha256-of
  "The SHA-256 of `file`, hex."
  [file]
  (-> (crypto/createHash "sha256")
      (.update (fs/readFileSync file))
      (.digest "hex")))

(defn expected-sha256
  "The hash in a `.sha256` file as sha256sum writes it: its first word."
  [text]
  (first (str/split (str/trim (str text)) #"\s+")))

(defn- verify!
  "The tarball's hash is the one of the checksum file, or the error."
  [tarball checksum-file]
  (let [expected (expected-sha256 (fs/readFileSync checksum-file "utf8"))
        actual (sha256-of tarball)]
    (when-not (and (seq expected) (= expected actual))
      (throw (js/Error. (str "the checksum of " tarball-name " does not match: " actual " instead of " expected))))))

(defn- unpack!
  "`tarball` unpacked into `dir`, which is made; it has to hold
   `rragent.js` at its root, as `npm run release` packs `dist/`."
  [tarball dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [result (child/spawnSync "tar" #js ["-xzf" tarball "-C" dir] #js {:encoding "utf8"})]
    (when-not (= 0 (.-status result))
      (throw (js/Error. (str "tar failed: " (str/trim (str (.-stderr result) " " (some-> (.-error result) .-message)))))))
    (when-not (fs/existsSync (path/join dir "rragent.js"))
      (throw (js/Error. (str tarball-name " holds no rragent.js"))))))

(defn swap-in!
  "`dist.new` of `install-dir` made its `dist`: the old one moved
   aside and removed. The one step that touches the running bundle."
  [install-dir]
  (let [dist (path/join install-dir "dist")
        old (path/join install-dir "dist.old")
        new (path/join install-dir "dist.new")]
    (fs/rmSync old #js {:recursive true :force true})
    (when (fs/existsSync dist)
      (fs/renameSync dist old))
    (fs/renameSync new dist)
    (fs/rmSync old #js {:recursive true :force true})))

(defn applicable?
  "Whether there is a `dist/` here to replace: not under the dev
   server, and the bundle runs from `<install-dir>/dist/rragent.js`."
  [{:keys [install-dir]}]
  (and (nil? (assets/dev-origin))
       (fs/existsSync (path/join install-dir "dist" "rragent.js"))))

(defn- fail!
  [db message]
  (swap! db update :update assoc :state :failed :error message))

(defn- remove!
  [dir]
  (when dir
    (fs/rmSync dir #js {:recursive true :force true})))

(defn- ^:async install!
  "The steps of `apply!` once the db says `:applying`: the downloads
   into a temp folder of the install folder, the checksum, the unpack
   into `dist.new`, the swap, the exit. A step that fails undoes what
   was made and notes the error."
  [{:keys [db install-dir exit!]} {:keys [version tarball-url sha256-url]}]
  (let [new (path/join install-dir "dist.new")
        tmp (atom nil)]
    (try
      (reset! tmp (fs/mkdtempSync (path/join install-dir ".update-")))
      (await (download! tarball-url (path/join @tmp tarball-name)))
      (await (download! sha256-url (path/join @tmp checksum-name)))
      (verify! (path/join @tmp tarball-name) (path/join @tmp checksum-name))
      (remove! new)
      (unpack! (path/join @tmp tarball-name) new)
      (swap-in! install-dir)
      (remove! @tmp)
      (say "rragent" version "is in place, the process ends with" exit-code "to be started again")
      (exit! exit-code)
      (catch :default e
        (remove! new)
        (remove! @tmp)
        (say "the update to" version "did not work:" (str (or (.-message e) e)))
        (fail! db (str (or (.-message e) e)))))))

(defn ^:async apply!
  "The update known in the db taken: downloaded, verified, unpacked
   and swapped in, then the process ended with `exit-code`; the db
   says `:applying` meanwhile, `:failed` with the error when a step
   did not work, `dist/` untouched then. Nothing when no update is
   known or while one is being applied; `:failed` without a `dist/`
   to replace here (a checkout under the dev server: `git pull`). The
   promise ends with the work."
  [{:keys [db install-dir] :as handle}]
  (let [{:keys [version state] :as known} (:update @db)]
    (cond
      (or (nil? version) (= :applying state))
      nil

      (not (applicable? handle))
      (fail! db (str "no dist/ to replace in " install-dir "; a checkout updates with git pull"))

      :else
      (do (swap! db update :update assoc :state :applying :error nil)
          (await (install! handle known))))))

(defn handle
  "`{:db the db, :current the running version, :latest-url,
   :install-dir, :auto? whether a known update goes in by itself once
   the agent is idle, :exit! what ends the process, :apply! what the
   Update button runs}`; everything but `:db` has a default: the
   version of `rragent.version`, GitHub's API, the folder of this
   bundle, `RRAGENT_UPDATE=auto`, `process.exit`, `apply!`. The
   running version is on the page from the first frame on."
  [opts]
  (let [handle (merge {:current version/version
                       :latest-url latest-url
                       :install-dir (install-dir)
                       :auto? (auto?)
                       :exit! (fn [code] (.exit js/process code))
                       :apply! apply!}
                      opts)]
    (swap! (:db handle) update :update assoc
           :current (:current handle)
           :auto? (boolean (:auto? handle)))
    handle))

(defn watch!
  "The lookup at start and then every hour, and the idle watch: every
   half minute, a known update applied once `busy?` answers false,
   when the handle says `:auto?`. Nothing with `RRAGENT_UPDATE=off`.
   Answers a function that ends both."
  ([handle busy?]
   (watch! handle busy? {}))
  ([{:keys [db auto?] :as handle} busy? {:keys [check-every-ms idle-every-ms off?]
                                         :or {check-every-ms check-every-ms idle-every-ms idle-every-ms off? off?}}]
   (if (off?)
     (fn [])
     (let [check (fn []
                   (-> (check! handle)
                       (.catch (fn [e] (say "no lookup of the newest release:" (str e))))))
           checker (js/setInterval check check-every-ms)
           idler (js/setInterval (fn []
                                   (when (and auto?
                                              (= :known (get-in @db [:update :state]))
                                              (not (busy?)))
                                     (apply! handle)))
                                 idle-every-ms)]
       (.unref checker)
       (.unref idler)
       (check)
       (fn []
         (js/clearInterval checker)
         (js/clearInterval idler))))))

(defn report
  "One line for the start: the running version and what happens with
   a newer release."
  [{:keys [current auto?]}]
  (cond
    (off?) (str "rragent " current ", no lookup of newer releases (RRAGENT_UPDATE=off)")
    auto? (str "rragent " current ", a newer release is looked up hourly and taken once the agent is idle")
    :else (str "rragent " current ", a newer release is looked up hourly and offered on the page")))

(comment
  (newer? "v0.1.3" "0.1.2")
  (first-line "## What's Changed\n* Agents: budgets by @max in https://github.com/storrito/rragent/pull/33\n")
  (lookup latest-url)
  )
