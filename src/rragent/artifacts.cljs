(ns rragent.artifacts
  "The things the agent makes, beside the chat: the files under
   `work/artifacts/` of the agent's folder (`rragent.home`), shown by
   kind. A page in an iframe that reloads when its file changes
   (rchat's canvas alias, `rragent.client`), an image, a video, an audio
   file as themselves, anything else as a download. The newest change
   the agent makes becomes the one on screen, unless the person picked
   another one since. The port of pchat.artifacts to one folder: an
   agent has one conversation and one pane, no chats.

   A directory with a package.json is a project, an artifact with a
   process of its own whose page is shown through `rragent.projects`.

   The keys this namespace owns in the db (`rragent.db`):

       :artifacts/list       [{:path :kind :title :mtime :size} ...], newest first
       :artifacts/rev        the newest modification time
       :artifacts/current    the path on screen
       :artifacts/picked-at  when the person picked it, to know what is newer

   It reads `:renders` (`rragent.render`): a file a render still
   writes is shown as is, with its progress, and does not take the
   screen. The files are served under `/artifacts/`."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.rframes.http :as http]))

(def url-prefix
  "Where the files are served."
  "/artifacts")

(def kinds
  {"html" :page "htm" :page
   "png" :image "jpg" :image "jpeg" :image "gif" :image "webp" :image "svg" :image
   "mp4" :video "webm" :video "mov" :video
   "mp3" :audio "wav" :audio "m4a" :audio "ogg" :audio})

(defn kind
  [p]
  (let [name (last (str/split (str p) #"/"))
        extension (when (str/includes? name ".")
                    (str/lower-case (last (str/split name #"\."))))]
    (get kinds extension :file)))

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
                            (assoc file :kind (kind path) :title (title path)))))]
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
   it exists, the newest otherwise. A video a render still writes
   changes with every frame and does not take the screen by itself; it
   is news once the render is done (`refollow!`)."
  [{:keys [artifacts/current artifacts/picked-at] :as value} {listed :artifacts/list}]
  (let [finished (remove (partial writing value) listed)
        newest (or (first finished) (first listed))
        current (some #(when (= current (:path %)) %) listed)]
    (cond
      (nil? newest) nil
      (nil? current) (:path newest)
      (> (:mtime newest) (or picked-at 0)) (:path newest)
      :else (:path current))))

(defn refresh!
  "Puts the facts of the folder into the db when they changed, and moves
   the screen to the newest change."
  [db dir]
  (let [new-facts (facts dir)]
    (when (not= (:artifacts/list new-facts) (:artifacts/list @db))
      (swap! db (fn [value]
                  (let [value (merge value new-facts)]
                    (assoc value :artifacts/current (follow value new-facts))))))
    new-facts))

(defn refollow!
  "Moves the screen as after a change of the folder, for news that is no
   change of a file: a render that is done makes its video the newest
   finished file."
  [db]
  (swap! db (fn [value] (assoc value :artifacts/current (follow value value)))))

(defn pick!
  [db p]
  (swap! db assoc
         :artifacts/current p
         :artifacts/picked-at (js/Date.now)))

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
  "The db follows the folder: a recursive watch reports every change,
   the folder is read again after a short pause, and every two seconds
   besides. Answers a function that ends the watch."
  [db dir]
  (fs/mkdirSync dir #js {:recursive true})
  (refresh! db dir)
  (let [pending (atom nil)
        refresh (fn []
                  (reset! pending nil)
                  (try
                    (refresh! db dir)
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
      (some-> @pending js/clearTimeout))))
