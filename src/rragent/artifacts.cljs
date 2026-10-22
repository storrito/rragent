(ns rragent.artifacts
  "The things the agent makes, beside the chat: the files under
   `work/artifacts/` of the agent's folder (`rragent.home`), shown by
   kind. A page in an iframe that reloads when its file changes
   (rchat's canvas alias, `rragent.client`), an image, a video, an audio
   file as themselves, markdown rendered, a text file as it is
   (`text-of`), anything else as a download. The newest change the
   agent makes becomes the one on screen, unless the person picked
   another one since; a pick of nil is the list alone. The port of pchat.artifacts to one folder: an
   agent has one conversation and one pane, no chats.

   A directory with a package.json is a project, an artifact with a
   process of its own whose page is shown through `rragent.projects`.
   The files under `notes/` are the agent's working notes (`note?`):
   listed apart, and never the newest change that takes the screen.
   The person renames a file from its row (`rename!`, the extension
   stays) or deletes it (`trash!`, into the bot's trash).

   The keys this namespace owns in the bot's map of the db
   (`rragent.db`, under the bot's `path`; the map itself for a test's
   plain atom):

       :artifacts/list       [{:path :kind :title :ext :mtime :size} ...], newest first
       :artifacts/rev        the newest modification time
       :artifacts/current    the path on screen
       :artifacts/picked-at  when the person picked it, to know what is newer

   It reads `:renders` (`rragent.render`): a file a render still
   writes is shown as is, with its progress, and does not take the
   screen. The files are served under `/artifacts/`."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.rframes.http :as http]))

(def url-prefix
  "Where the files are served."
  "/artifacts")

(def kinds
  {"html" :page "htm" :page
   "png" :image "jpg" :image "jpeg" :image "gif" :image "webp" :image "svg" :image
   "mp4" :video "webm" :video "mov" :video
   "mp3" :audio "wav" :audio "m4a" :audio "ogg" :audio
   "md" :markdown "markdown" :markdown
   "txt" :text "json" :text "csv" :text "srt" :text "vtt" :text "log" :text
   "edn" :text "yaml" :text "yml" :text "xml" :text})

(defn extension
  "The extension of the file, lowercase, nil without one."
  [p]
  (let [name (last (str/split (str p) #"/"))]
    (when (str/includes? name ".")
      (str/lower-case (last (str/split name #"\."))))))

(defn kind
  [p]
  (get kinds (extension p) :file))

(def text-kinds
  "The kinds shown as text: markdown rendered, the rest as it is."
  #{:markdown :text})

(def text-cap
  "How much of a text file the pane shows."
  (* 256 1024))

(defonce ^:private text-cache
  (atom {}))

(defn text-of
  "The text of the artifact for the pane, `{:text :truncated?}`, for
   one of the text kinds; nil for the other kinds or when the file
   cannot be read. The first `text-cap` bytes, since the frame carries
   it: a transcript is read once per change, not once per frame."
  [dir {:keys [path kind mtime]}]
  (when (text-kinds kind)
    (let [key [dir path mtime]]
      (or (get @text-cache key)
          (when-let [value (try
                             (let [file (path/join dir path)
                                   size (.-size (fs/statSync file))
                                   fd (fs/openSync file "r")
                                   buffer (js/Buffer.alloc (min size text-cap))
                                   read (try (fs/readSync fd buffer 0 (.-length buffer) 0)
                                             (finally (fs/closeSync fd)))]
                               {:text (.toString (.subarray buffer 0 read) "utf8")
                                :truncated? (> size text-cap)})
                             (catch :default _
                               nil))]
            (swap! text-cache (fn [cache]
                                (assoc (if (< (count cache) 16) cache {}) key value)))
            value)))))

(defn title
  "What a person calls the file: `guest-review_card.html` is `Guest review card`."
  [p]
  (let [base (-> (last (str/split (str p) #"/"))
                 (str/replace #"\.[^.]+$" "")
                 (str/replace #"[-_]+" " ")
                 (str/trim))]
    (if (seq base)
      (str (str/upper-case (subs base 0 1)) (subs base 1))
      (str p))))

(defn hidden?
  "Whether a relative path has a part that starts with a dot."
  [relative]
  (boolean (some #(str/starts-with? % ".") (str/split (str relative) #"/"))))

(def notes-folder
  "Where the agent's working notes go, apart from what it makes for
   the person."
  "notes")

(defn note?
  "Whether the path is a working note, a file under `notes/`."
  [p]
  (str/starts-with? (str p) (str notes-folder "/")))

(defn inside?
  "Whether `relative` names something inside `dir`: not blank, no
   hidden part, and resolving below the folder, not beside or above it."
  [dir relative]
  (let [root (path/resolve dir)
        relative (str relative)]
    (and (not (str/blank? relative))
         (not (hidden? relative))
         (str/starts-with? (path/resolve root relative) (str root path/sep)))))

(defn- directory?
  [p]
  (try
    (.isDirectory (fs/statSync p))
    (catch :default _
      false)))

(defn project?
  "A directory with a package.json is an artifact with a process of its
   own, see `rragent.projects`."
  [dir]
  (http/regular-file? (path/join dir "package.json")))

(defn files
  "Every regular file below `dir`, `{:path relative :mtime :size}`, the
   time in milliseconds; hidden names left out, with what is below
   them."
  [dir]
  (let [out (atom [])]
    ((fn step [sub]
       (doseq [^js entry (try
                           (fs/readdirSync (path/join dir sub) #js {:withFileTypes true})
                           (catch :default _
                             #js []))]
         (let [name (.-name entry)
               relative (if (str/blank? sub) name (str sub "/" name))]
           (when-not (str/starts-with? name ".")
             (cond
               (.isDirectory entry)
               (step relative)

               (.isFile entry)
               (when-let [stat (try (fs/statSync (path/join dir relative)) (catch :default _ nil))]
                 (swap! out conj {:path relative
                                  :mtime (js/Math.round (.-mtimeMs stat))
                                  :size (.-size stat)})))))))
     "")
    @out))

(defn newest-mtime
  "The newest file of the project; the directory's own time only when it
   has none."
  [dir]
  (let [times (map :mtime (files dir))]
    (if (seq times)
      (reduce max times)
      (js/Math.round (.-mtimeMs (fs/statSync dir))))))

(defn list-files
  "The artifacts of the directory, newest first: the files, and a project
   directory as one artifact whose time is its newest file's."
  [dir]
  (if (directory? dir)
    (let [projects (->> (array-seq (fs/readdirSync dir #js {:withFileTypes true}))
                        (filter (fn [^js entry]
                                  (and (.isDirectory entry)
                                       (not (str/starts-with? (.-name entry) "."))
                                       (project? (path/join dir (.-name entry))))))
                        (map (fn [^js entry]
                               (let [name (.-name entry)]
                                 {:path name
                                  :kind :project
                                  :title (title name)
                                  :mtime (newest-mtime (path/join dir name))
                                  :size 0}))))
          project-names (set (map :path projects))
          plain (->> (files dir)
                     (remove #(project-names (first (str/split (:path %) #"/"))))
                     (map (fn [{:keys [path] :as file}]
                            (assoc file :kind (kind path) :title (title path) :ext (extension path)))))]
      (->> (concat projects plain)
           (sort-by :mtime >)
           (vec)))
    []))

(defn facts
  [dir]
  (let [listed (list-files dir)]
    {:artifacts/list listed
     :artifacts/rev (reduce max 0 (map :mtime listed))}))

(defn render-name
  "The project whose render writes the file: `story.mp4` is `story`'s
   (see `rragent.render/output-file`); nil for a path with no
   extension or one in a folder."
  [p]
  (when-let [[_ name] (re-matches #"([^/]+)\.[^./]+" (str p))]
    name))

(defn writing
  "The render that is still writing the artifact (`rragent.render`: the
   file of a project while the render runs), or nil. Such a file is no
   news, it changes with every frame."
  [{:keys [renders]} {:keys [path]}]
  (when-let [name (render-name path)]
    (let [render (get renders name)]
      (when (and (:started render) (nil? (:exit render)))
        render))))

(defn follow
  "The artifact on screen after the folder changed: the newest finished
   file when it is newer than the person's pick, what they picked while
   it exists, the list alone when they went back to it and nothing
   newer came, the newest otherwise. A video a render still writes
   changes with every frame and does not take the screen by itself; it
   is news once the render is done (`refollow!`). A note (`note?`) is
   no news either: it does not take the screen from a creation."
  [{:keys [artifacts/current artifacts/picked-at] :as value} {listed :artifacts/list}]
  (let [finished (remove #(or (writing value %) (note? (:path %))) listed)
        newest (or (first finished) (first listed))
        shown (some #(when (= current (:path %)) %) listed)]
    (cond
      (nil? newest) nil
      (> (:mtime newest) (or picked-at 0)) (:path newest)
      shown (:path shown)
      (and (nil? current) picked-at) nil
      :else (:path newest))))

(defn refresh!
  "Puts the facts of the folder into the bot's map at `path` of the db
   when they changed, and moves the screen to the newest change."
  ([db dir]
   (refresh! db [] dir))
  ([db path dir]
   (let [new-facts (facts dir)]
     (when (not= (:artifacts/list new-facts) (:artifacts/list (db/at @db path)))
       (swap! db db/update-at path
              (fn [value]
                (let [value (merge value new-facts)]
                  (assoc value :artifacts/current (follow value new-facts))))))
     new-facts)))

(defn refollow!
  "Moves the screen as after a change of the folder, for news that is no
   change of a file: a render that is done makes its video the newest
   finished file."
  ([db]
   (refollow! db []))
  ([db path]
   (swap! db db/update-at path (fn [value] (assoc value :artifacts/current (follow value value))))))

(defn pick!
  ([db p]
   (pick! db [] p))
  ([db path p]
   (swap! db db/update-at path assoc
          :artifacts/current p
          :artifacts/picked-at (js/Date.now))))

(defn base-name
  "What the person edits when renaming: the name of the file without
   its extension (`Pool.JPG` is `Pool`); a project's name as it is."
  [{:keys [path kind]}]
  (let [name (last (str/split (str path) #"/"))]
    (if (= :project kind)
      name
      (str/replace name #"\.[^.]+$" ""))))

(defn renamed-path
  "The path of the artifact renamed to the base name `name`: in its
   folder, with its extension as it was (a project is a folder, it has
   none); nil for a blank name or one that would leave the folder or
   hide the file."
  [{:keys [path kind] :as artifact} name]
  (let [name (str/trim (str name))
        folder (subs path 0 (inc (or (str/last-index-of path "/") -1)))
        suffix (when (not= :project kind)
                 (subs (last (str/split path #"/")) (count (base-name artifact))))
        to (str folder name suffix)]
    (when (and (seq name)
               (not (str/includes? name "/"))
               (not (hidden? to)))
      to)))

(defn rename!
  "The artifact `from` of the folder moved to `to`, both relative. Answers
   `:renamed`, or `:outside` when either leaves the folder, `:exists`
   when `to` is taken."
  [dir from to]
  (cond
    (not (and (inside? dir from) (inside? dir to))) :outside
    (fs/existsSync (path/join dir to)) :exists
    :else (do (fs/renameSync (path/join dir from) (path/join dir to))
              :renamed)))

(defn- free-name
  "`target` when nothing is there, else the first of `name 2.ext`,
   `name 3.ext` and so on that is free."
  [target]
  (let [{:keys [dir name ext]} (js->clj (path/parse target) :keywordize-keys true)]
    (->> (cons target (map #(path/join dir (str name " " % ext)) (iterate inc 2)))
         (remove fs/existsSync)
         (first))))

(defn trash!
  "The artifact `relative` of `dir` moved into `trash` under its own
   path, with a number behind its name when that is taken, the folders
   on the way made. Answers where it went, or `:outside` for a path
   that leaves the folder, `:missing` for one that is not there."
  [dir trash relative]
  (cond
    (not (inside? dir relative)) :outside
    (not (fs/existsSync (path/join dir relative))) :missing
    :else (let [target (free-name (path/join trash relative))]
            (fs/mkdirSync (path/dirname target) #js {:recursive true})
            (fs/renameSync (path/join dir relative) target)
            target)))

(defn file-handler
  "GET /artifacts/<path>: the file, never a hidden one."
  [dir w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        file (when-not (hidden? subpath)
               (http/resolve-file dir subpath))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(def debounce-ms
  "A tool writes several files in a row: one look after the last."
  100)

(def poll-ms
  "A look at the folder besides the watcher's reports, for what a
   recursive watch misses on some file systems."
  2000)

(defn watch!
  "The bot's map at `path` of the db follows the folder: a recursive
   watch reports every change, the folder is read again after a short
   pause, and every two seconds besides. Answers a function that ends
   the watch."
  ([db dir]
   (watch! db [] dir))
  ([db path dir]
  (fs/mkdirSync dir #js {:recursive true})
  (refresh! db path dir)
  (let [pending (atom nil)
        refresh (fn []
                  (reset! pending nil)
                  (try
                    (refresh! db path dir)
                    (catch :default e
                      (js/console.error "[artifacts]" (str e)))))
        soon (fn []
               (when-not @pending
                 (reset! pending (js/setTimeout refresh debounce-ms))))
        watcher (try
                  (fs/watch dir #js {:recursive true :persistent false} (fn [_ _] (soon)))
                  (catch :default e
                    (js/console.error "[artifacts] no watch on" dir (str e))
                    nil))
        timer (js/setInterval refresh poll-ms)]
    (.unref timer)
    (fn []
      (some-> watcher .close)
      (js/clearInterval timer)
      (some-> @pending js/clearTimeout)))))
