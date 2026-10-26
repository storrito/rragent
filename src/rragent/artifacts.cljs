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

   The person browses the folder as one on their computer, one level
   at a time (`level`, `crumbs`): the folders of a level first, then
   its files, both by name, a folder as one row until it is opened
   (the newest change takes the screen, so the list need not say what
   is new). Nothing is
   hidden but the dot files: the agent keeps a level short by putting
   what belongs together into folders. A `README.md` in a folder is
   its first file and opens when the person enters the folder
   (`readme`); the links in it lead to the files and folders beside it
   (`rragent.markdown`). A recording (`rragent.recordings`,
   `recordings/<stamp>/`) is a folder titled by its time, with the
   length its `recording.json` says (`recording-durations`).

   A directory with a package.json is a project, an artifact with a
   process of its own whose page is shown through `rragent.projects`.
   The files under `notes/` are the agent's working notes (`note?`):
   never the newest change that takes the screen, and no card in the
   chat. The person renames a file from its row (`rename!`, the
   extension stays) or deletes it (`trash!`, into the bot's trash).

   The keys this namespace owns in the bot's map of the db
   (`rragent.db`, under the bot's `path`; the map itself for a test's
   plain atom):

       :artifacts/list       [{:path :kind :title :ext :mtime :size} ...], newest first
       :artifacts/rev        the newest modification time
       :artifacts/durations  {\"recordings/<stamp>\" seconds}, the recordings' lengths
       :artifacts/current    the path on screen
       :artifacts/picked-at  when the person picked it, to know what is newer
       :artifacts/folder     the folder the person browses, \"\" the root

   It reads `:working?` (`rragent.machine`): while a run goes the
   screen stays put, see `follow`.

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

(defn folder-of
  "The folder of a path, `card` of `card/pool.png`; \"\" at the root."
  [p]
  (let [p (str p)]
    (if-let [i (str/last-index-of p "/")]
      (subs p 0 i)
      "")))

(defn in-folder?
  "Whether `p` lies below `folder` (\"\" is the root), at any depth."
  [folder p]
  (or (str/blank? folder)
      (str/starts-with? (str p) (str folder "/"))))

(defn folder?
  "Whether `folder` is one of the list's: the root, or a folder some
   artifact lies below."
  [listed folder]
  (or (str/blank? folder)
      (boolean (some #(in-folder? folder (:path %)) listed))))

(defn readme?
  "Whether the path is a folder's README, `README.md` in any case."
  [p]
  (= "readme.md" (str/lower-case (last (str/split (str p) #"/")))))

(defn readme
  "The README of `folder` among the list, its path; nil without one."
  [listed folder]
  (some #(when (and (readme? (:path %)) (= folder (folder-of (:path %)))) (:path %))
        listed))

(def recording-pattern
  "A recording's folder, `recordings/<stamp>` as `rragent.recordings`
   names it (its `stamp-pattern`): the date and the time of day in it."
  #"^recordings/(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-\d{2}$")

(defn recording-title
  "`2026-10-21 11:56` for `recordings/2026-10-21T11-56-03`; nil for
   another folder."
  [folder]
  (when-let [[_ date hh mm] (re-matches recording-pattern (str folder))]
    (str date " " hh ":" mm)))

(defn crumbs
  "The way to `folder`, each folder on it with its name:
   `[{:path \"a\" :name \"a\"} {:path \"a/b\" :name \"b\"}]`; nil at
   the root."
  [folder]
  (when-not (str/blank? folder)
    (let [parts (str/split folder #"/")]
      (vec (map-indexed (fn [i name] {:path (str/join "/" (take (inc i) parts)) :name name}) parts)))))

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

(defn by-name
  "The order of a folder on a computer: by name, case aside, a number
   in a name as a number (`frame-2` before `frame-10`)."
  [a b]
  (.localeCompare (str a) (str b) js/undefined #js {:numeric true :sensitivity "base"}))

(defn file-name
  [p]
  (last (str/split (str p) #"/")))

(defn level
  "What is in `folder` of the list (\"\" the root): `{:folders :files}`.
   The folders by name, each `{:path :name :title :kind :count :mtime
   :size}`, `:kind :folder`, or `:recording` for a recording's, titled
   by its time, with the `:duration` `durations` (path to seconds)
   knows; the files by name (`by-name`), the folder's README first of
   all."
  [listed folder & [durations]]
  (let [prefix (if (str/blank? folder) "" (str folder "/"))
        below (filter #(str/starts-with? (:path %) prefix) listed)
        {files false deeper true} (group-by #(str/includes? (subs (:path %) (count prefix)) "/") below)
        folders (->> (group-by #(first (str/split (subs (:path %) (count prefix)) #"/")) deeper)
                     (map (fn [[name entries]]
                            (let [path (str prefix name)
                                  recording (recording-title path)]
                              (cond-> {:path path
                                       :name name
                                       :title (or recording (title name))
                                       :kind (if recording :recording :folder)
                                       :count (count entries)
                                       :mtime (reduce max 0 (map :mtime entries))
                                       :size (reduce + 0 (map :size entries))}
                                (get durations path) (assoc :duration (get durations path))))))
                     (sort-by :name by-name)
                     (vec))]
    {:folders folders
     :files (vec (sort (fn [a b]
                         (let [ra (if (readme? (:path a)) 0 1)
                               rb (if (readme? (:path b)) 0 1)]
                           (if (= ra rb)
                             (by-name (file-name (:path a)) (file-name (:path b)))
                             (- ra rb))))
                       files))}))

(defonce ^:private duration-cache
  (atom {}))

(defn recording-durations
  "The length of every recording of the list, seconds by its folder:
   the `duration` of its `recording.json`, which `rragent.recordings`
   writes at the end (an older recording has none); a file read once
   per change."
  [dir listed]
  (into {}
        (keep (fn [{:keys [path mtime]}]
                (let [folder (folder-of path)]
                  (when (and (str/ends-with? path "/recording.json") (re-matches recording-pattern folder))
                    (let [key [dir path mtime]
                          seconds (or (get @duration-cache key)
                                      (let [read (try
                                                   (let [d (aget (js/JSON.parse (fs/readFileSync (path/join dir path) "utf8")) "duration")]
                                                     (if (number? d) d :none))
                                                   (catch :default _
                                                     :none))]
                                        (swap! duration-cache (fn [cache] (assoc (if (< (count cache) 64) cache {}) key read)))
                                        read))]
                      (when (number? seconds)
                        [folder seconds]))))))
        listed))

(defn facts
  [dir]
  (let [listed (list-files dir)]
    {:artifacts/list listed
     :artifacts/rev (reduce max 0 (map :mtime listed))
     :artifacts/durations (recording-durations dir listed)}))

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
   newer came, the newest otherwise. While the agent works (`:working?`
   of the bot, `rragent.machine`) the screen stays where it is, a video
   playing or the list: the agent writes file after file then, and
   what it made takes the screen once the run is over (`refollow!`);
   only an empty screen follows the work live. A video a render still
   writes changes with every frame and does not take the screen by
   itself; it is news once the render is done. A note (`note?`) is no
   news either: it does not take the screen from a creation."
  [{:keys [artifacts/current artifacts/picked-at working?] :as value} {listed :artifacts/list}]
  (let [finished (remove #(or (writing value %) (note? (:path %))) listed)
        newest (or (first finished) (first listed))
        shown (some #(when (= current (:path %)) %) listed)]
    (cond
      (nil? newest) nil
      (and working? shown) (:path shown)
      (and working? (nil? current) picked-at) nil
      (> (:mtime newest) (or picked-at 0)) (:path newest)
      shown (:path shown)
      (and (nil? current) picked-at) nil
      :else (:path newest))))

(defn- showing
  "`value` with `current` on screen; the list browsing its folder when
   it is another artifact than before, so that the row of what is on
   screen is in sight (the person's own browsing stays while the
   screen does not change)."
  [value current]
  (cond-> (assoc value :artifacts/current current)
    (and current (not= current (:artifacts/current value))) (assoc :artifacts/folder (folder-of current))))

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
                  (showing value (follow value new-facts))))))
     new-facts)))

(defn refollow!
  "Moves the screen as after a change of the folder, for news that is no
   change of a file: a render that is done makes its video the newest
   finished file."
  ([db]
   (refollow! db []))
  ([db path]
   (swap! db db/update-at path (fn [value] (showing value (follow value value))))))

(defn pick!
  "The person picked `p`, or went back to the list (nil); the list
   browses the folder of the pick."
  ([db p]
   (pick! db [] p))
  ([db path p]
   (swap! db db/update-at path
          (fn [value]
            (-> (showing value p)
                (assoc :artifacts/picked-at (js/Date.now)))))))

(defn browse!
  "The person opened `folder` in the list (\"\" the root)."
  ([db folder]
   (browse! db [] folder))
  ([db path folder]
   (swap! db db/update-at path assoc :artifacts/folder (str folder))))

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
