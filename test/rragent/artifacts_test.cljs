(ns rragent.artifacts-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.artifacts :as artifacts]
            [rragent.i18n :as i18n]
            [rragent.view :as view]))

(defn temp-dir
  [prefix]
  (fs/mkdtempSync (path/join (os/tmpdir) prefix)))

(defn touch!
  "A file with content, its modification time set to `mtime` (ms)."
  [dir relative mtime]
  (let [file (path/join dir relative)]
    (fs/mkdirSync (path/dirname file) #js {:recursive true})
    (fs/writeFileSync file (str "content of " relative))
    (fs/utimesSync file (/ mtime 1000) (/ mtime 1000))
    file))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest kind-and-title-test
  (is (= :page (artifacts/kind "guest-review_card.html")))
  (is (= :image (artifacts/kind "photos/Pool.JPG")))
  (is (= :video (artifacts/kind "story.mp4")))
  (is (= :file (artifacts/kind "review-story.clj")))
  (is (= :file (artifacts/kind "notes")))
  (is (= :markdown (artifacts/kind "youtube-title_and_description.md")))
  (is (= :text (artifacts/kind "notes/transcript.TXT")))
  (is (= :text (artifacts/kind "captions.srt")))
  (is (= "md" (artifacts/extension "a/b/notes.md")))
  (is (nil? (artifacts/extension "Makefile")))
  (is (= "Guest review card" (artifacts/title "guest-review_card.html")))
  (is (= "Pool" (artifacts/title "photos/Pool.JPG")))
  (is (= "story" (artifacts/render-name "story.mp4")))
  (is (= "story" (artifacts/render-name "story.png")) "whatever a template renders")
  (is (nil? (artifacts/render-name "card/story.png")) "a file in a project is no render")
  (is (nil? (artifacts/render-name "notes")))
  (testing "a file under notes/ is a working note"
    (is (true? (artifacts/note? "notes/checks.md")))
    (is (true? (artifacts/note? "notes/deep/progress.txt")))
    (is (false? (artifacts/note? "notes.md")))
    (is (false? (artifacts/note? "card/notes/x.md"))))
  (testing "the name the person edits, and the path renamed with the extension kept"
    (is (= "Pool" (artifacts/base-name {:path "photos/Pool.JPG" :kind :image})))
    (is (= "clip" (artifacts/base-name {:path "clip" :kind :project})))
    (is (= "my.site" (artifacts/base-name {:path "my.site" :kind :project})) "a project's dot is its own")
    (is (= "photos/Garden.JPG" (artifacts/renamed-path {:path "photos/Pool.JPG" :kind :image} " Garden ")))
    (is (= "intro" (artifacts/renamed-path {:path "clip" :kind :project} "intro")))
    (is (= "Makefile2" (artifacts/renamed-path {:path "Makefile" :kind :file} "Makefile2")) "no extension, none added")
    (is (nil? (artifacts/renamed-path {:path "card.html" :kind :page} "  ")) "blank keeps the old")
    (is (nil? (artifacts/renamed-path {:path "card.html" :kind :page} "../card")) "a name stays in its folder")
    (is (nil? (artifacts/renamed-path {:path "card.html" :kind :page} ".card")) "and does not hide the file")))

(deftest list-and-follow-test
  (let [root (temp-dir "rragent-artifacts-")
        dir (path/join root "artifacts")
        db (atom {})]
    (try
      (testing "an empty or missing folder is an empty list"
        (is (= [] (artifacts/list-files dir)))
        (is (= [] (artifacts/list-files (path/join dir "missing")))))
      (touch! dir "first.html" 1000)
      (touch! dir ".hidden/secret.html" 3000)
      (touch! dir "pictures/pool.png" 2000)
      (testing "newest first, hidden left out, the newest on screen"
        (artifacts/refresh! db dir)
        (is (= ["pictures/pool.png" "first.html"] (map :path (:artifacts/list @db))))
        (is (= 2000 (:artifacts/rev @db)))
        (is (= "pictures/pool.png" (:artifacts/current @db))))
      (testing "the person's pick holds until the agent writes something newer"
        (artifacts/pick! db "first.html")
        (artifacts/refresh! db dir)
        (is (= "first.html" (:artifacts/current @db)))
        (touch! dir "story.mp4" (+ (js/Date.now) 5000))
        (artifacts/refresh! db dir)
        (is (= "story.mp4" (:artifacts/current @db))))
      (testing "a directory with a package.json is one artifact, a project, timed by its newest file"
        (touch! dir "clip/package.json" 100)
        (touch! dir "clip/composition/index.html" 7000)
        (touch! dir "clip/.log" 9000)
        (artifacts/refresh! db dir)
        (let [listed (:artifacts/list @db)
              clip (some #(when (= "clip" (:path %)) %) listed)]
          (is (= {:path "clip" :kind :project :title "Clip" :mtime 7000} (dissoc clip :size)))
          (is (not-any? #(str/starts-with? (:path %) "clip/") listed))
          (is (= "story.mp4" (:path (first listed))) "the file from the future stays newest")))
      (testing "while the agent works the screen stays put: what is shown, or the list; the newest takes it once the run is over"
        (let [listed [{:path "new.html" :mtime 50} {:path "old.mp4" :mtime 10}]
              facts {:artifacts/list listed}]
          (is (= "old.mp4" (artifacts/follow {:artifacts/current "old.mp4" :artifacts/picked-at 20 :working? true} facts)) "a video being watched")
          (is (= "old.mp4" (artifacts/follow {:artifacts/current "old.mp4" :working? true} facts)) "shown without a pick too")
          (is (nil? (artifacts/follow {:artifacts/current nil :artifacts/picked-at 20 :working? true} facts)) "the list the person went back to")
          (is (= "new.html" (artifacts/follow {:artifacts/current nil :working? true} facts)) "an empty screen follows the work")
          (is (= "new.html" (artifacts/follow {:artifacts/current "old.mp4" :artifacts/picked-at 20 :working? false} facts)) "the run is over: what it made")))
      (testing "a pick that disappeared falls back to the newest"
        (artifacts/pick! db "first.html")
        (fs/rmSync (path/join dir "first.html"))
        (artifacts/refresh! db dir)
        (is (= "story.mp4" (:artifacts/current @db))))
      (testing "the video a render writes does not take the screen, and is news once the render is done"
        (touch! dir "story.mp4" 1500)
        (artifacts/pick! db "pictures/pool.png")
        (swap! db assoc-in [:renders "clip"] {:started (js/Date.now) :progress "30 %"})
        (touch! dir "clip.mp4" (+ (js/Date.now) 10000))
        (artifacts/refresh! db dir)
        (is (= "pictures/pool.png" (:artifacts/current @db)))
        (swap! db assoc-in [:renders "clip" :exit] 0)
        (artifacts/refollow! db)
        (is (= "clip.mp4" (:artifacts/current @db))))
      (testing "a working note does not take the screen from a creation, a creation does"
        ;; the files from the future back in the past, so that only what
        ;; is written now is news
        (touch! dir "clip.mp4" 1600)
        (artifacts/refresh! db dir)
        (artifacts/pick! db "pictures/pool.png")
        (touch! dir "notes/checks.md" (+ (js/Date.now) 20000))
        (artifacts/refresh! db dir)
        (is (= "pictures/pool.png" (:artifacts/current @db)))
        (is (some #(= "notes/checks.md" (:path %)) (:artifacts/list @db)) "it is on the list")
        (touch! dir "card.html" (+ (js/Date.now) 30000))
        (artifacts/refresh! db dir)
        (is (= "card.html" (:artifacts/current @db))))
      (testing "back to the list, a note leaves it alone"
        (touch! dir "card.html" 1700)
        (artifacts/refresh! db dir)
        (artifacts/pick! db nil)
        (touch! dir "notes/progress.md" (+ (js/Date.now) 40000))
        (artifacts/refresh! db dir)
        (is (nil? (:artifacts/current @db)))
        (touch! dir "pool2.png" (+ (js/Date.now) 50000))
        (artifacts/refresh! db dir)
        (is (= "pool2.png" (:artifacts/current @db)) "the newest creation still takes the screen"))
      (finally
        (fs/rmSync root #js {:recursive true :force true})))))

(deftest rename-and-trash-test
  (let [root (temp-dir "rragent-rename-")
        dir (path/join root "artifacts")
        trash (path/join root "trash" "1" "artifacts")]
    (try
      (touch! dir "photos/Pool.JPG" 1000)
      (touch! dir "card.html" 1000)
      (touch! dir "clip/package.json" 1000)
      (testing "a rename moves the file inside the folder"
        (is (= :renamed (artifacts/rename! dir "photos/Pool.JPG" "photos/Garden.JPG")))
        (is (fs/existsSync (path/join dir "photos/Garden.JPG")))
        (is (not (fs/existsSync (path/join dir "photos/Pool.JPG"))))
        (is (= :renamed (artifacts/rename! dir "clip" "intro")) "a project renames as a folder")
        (is (fs/existsSync (path/join dir "intro/package.json"))))
      (testing "not onto a file that is there, not out of the folder, not hidden"
        (is (= :exists (artifacts/rename! dir "photos/Garden.JPG" "card.html")))
        (is (= :outside (artifacts/rename! dir "card.html" "../card.html")))
        (is (= :outside (artifacts/rename! dir "../artifacts/card.html" "x.html")))
        (is (= :outside (artifacts/rename! dir "card.html" ".card.html")))
        (is (fs/existsSync (path/join dir "card.html"))))
      (testing "a delete moves the file into the trash under its own path, numbered when taken"
        (is (= (path/join trash "photos/Garden.JPG") (artifacts/trash! dir trash "photos/Garden.JPG")))
        (is (not (fs/existsSync (path/join dir "photos/Garden.JPG"))))
        (touch! dir "photos/Garden.JPG" 2000)
        (is (= (path/join trash "photos/Garden 2.JPG") (artifacts/trash! dir trash "photos/Garden.JPG")))
        (touch! dir "photos/Garden.JPG" 3000)
        (is (= (path/join trash "photos/Garden 3.JPG") (artifacts/trash! dir trash "photos/Garden.JPG")))
        (is (= (path/join trash "intro") (artifacts/trash! dir trash "intro")) "a project as a folder")
        (is (fs/existsSync (path/join trash "intro/package.json"))))
      (testing "nothing leaves the folder, nothing that is not there"
        (is (= :outside (artifacts/trash! dir trash "../artifacts/card.html")))
        (is (= :outside (artifacts/trash! dir trash ".hidden")))
        (is (= :missing (artifacts/trash! dir trash "gone.html")))
        (is (fs/existsSync (path/join dir "card.html"))))
      (finally
        (fs/rmSync root #js {:recursive true :force true})))))

(deftest text-of-test
  (let [dir (temp-dir "rragent-text-")]
    (touch! dir "notes.md" 1000)
    (touch! dir "pool.png" 1000)
    (testing "a text file is read for the pane, markdown and plain alike, nothing else"
      (is (= {:text "content of notes.md" :truncated? false}
             (artifacts/text-of dir {:path "notes.md" :kind :markdown :mtime 1000})))
      (is (nil? (artifacts/text-of dir {:path "pool.png" :kind :image :mtime 1000})))
      (is (nil? (artifacts/text-of dir {:path "gone.txt" :kind :text :mtime 1000})) "a file that is gone is no text"))
    (testing "a long file is cut at the cap, and the pane says so"
      (with-redefs [artifacts/text-cap 7]
        (is (= {:text "content" :truncated? true}
               (artifacts/text-of dir {:path "notes.md" :kind :markdown :mtime 1001})))))
    (testing "the list names the extension of every file"
      (touch! dir "clip/package.json" 1000)
      (is (= [["clip" nil] ["notes.md" "md"] ["pool.png" "png"]]
             (map (juxt :path :ext) (sort-by :path (artifacts/list-files dir))))))))

(deftest watch-test
  (async done
    (-> ((^:async fn []
           (let [root (temp-dir "rragent-watch-")
                 dir (path/join root "artifacts")
                 db (atom {})
                 stop (artifacts/watch! db dir)]
             (try
               (is (fs/existsSync dir) "the folder is made")
               (is (= [] (:artifacts/list @db)))
               (touch! dir "card.html" (js/Date.now))
               (await (sleep 400))
               (testing "a written file is on the list and on screen"
                 (is (= ["card.html"] (map :path (:artifacts/list @db))))
                 (is (= "card.html" (:artifacts/current @db))))
               (touch! dir "deep/pool.png" (+ (js/Date.now) 1000))
               (await (sleep 400))
               (testing "a file in a new folder below"
                 (is (= "deep/pool.png" (:artifacts/current @db))))
               (finally
                 (stop)
                 (fs/rmSync root #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- nodes
  "Every hiccup node of the tree, depth first."
  [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- tags
  [tree]
  (->> (nodes tree)
       (filter #(and (vector? %) (keyword? (first %))))
       (map first)))

(defn- find-tag
  [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(defn- strings
  [hiccup]
  (set (filter string? (tree-seq coll? seq hiccup))))

(deftest pane-test
  (let [listed [{:path "card.html" :kind :page :title "Card" :ext "html" :mtime 3 :size 1200}
                {:path "pool.png" :kind :image :title "Pool" :ext "png" :mtime 2 :size 300000}
                {:path "story.mp4" :kind :video :title "Story" :ext "mp4" :mtime 1 :size 5000000}
                {:path "clip" :kind :project :title "Clip" :mtime 0 :size 0}
                {:path "notes/plan.md" :kind :markdown :title "Plan" :ext "md" :mtime 0 :size 40}]
        pane (fn [current & [more]]
               (view/artifacts (merge {:artifacts/list listed :artifacts/current current :bot "b1"} more)))]
    (testing "a page is the live iframe under /artifacts/, the list beside it with the one on screen marked"
      (let [v (pane "card.html")
            rows (filter #(and (vector? %) (= :li (first %))) (nodes v))]
        (is (some #{:ui/canvas} (tags v)))
        (is (= 5 (count rows)))
        (is (= ["page" "current"] (:class (second (first rows)))))
        (is (= "card card.html" (:data-name (second (first rows)))) "the title and the path for the filter")
        (is (= ["html" " · " "1 KB"] (rest (find-tag (first rows) :span.meta))) "the extension and the size tell files apart")
        (is (= ["md" " · " "40 B"] (rest (find-tag (nth rows 4) :span.meta))) "a note's folder goes without saying")
        (is (some #{[:i18n :artifacts/project]} (nodes (nth rows 3))) "a project is named as such")
        (is (= {:bot "b1" :path "pool.png"} (get-in (second (find-tag (second rows) :button.row)) [:on :click 0 1 :command/data])))
        (is (= {:bot "b1" :path nil} (get-in (second (find-tag v :button.link.back)) [:on :click 0 1 :command/data])) "the way back to the list")
        (is (= ["showing"] (:class (second v))))
        (is (= "/artifacts/card.html" (:ui/src (second (find-tag v :ui/canvas)))))
        (is (= 3 (:ui/rev (second (find-tag v :ui/canvas)))))))
    (testing "the notes are folded at the end of the list, the creations before them"
      (let [v (pane "card.html")
            files (find-tag v :ui/files-filter)
            group (find-tag v :li.group)]
        (is (= ["card.html" "pool.png" "story.mp4" "clip"]
               (map #(:replicant/key (second %)) (filter #(and (vector? %) (= :li (first %))) (nodes (nth files 2))))))
        (is (= [:i18n :artifacts/notes {:n 1}] (last (find-tag group :summary))))
        (is (= "plan notes/plan.md" (:data-name (second group))) "the group hides with its rows")
        (is (= "notes/plan.md" (:replicant/key (second (find-tag group :li)))))
        (is (contains? (strings (i18n/translate v :de)) "Notizen · 1"))
        (is (nil? (find-tag (pane "card.html" {:artifacts/list (take 4 listed)}) :li.group)) "no group without notes")))
    (testing "the filter above the list once it is long, its text the bot's in the store"
      (let [short (second (find-tag (pane "card.html") :ui/files-filter))
            long (second (find-tag (pane "card.html" {:artifacts/list (concat listed (for [i (range 4)] {:path (str "more" i ".md") :kind :markdown :title (str "More " i) :ext "md" :mtime i :size 1}))})
                                   :ui/files-filter))]
        (is (= [:artifacts-filter "b1"] (:ui/store-key short)))
        (is (false? (:ui/field? short)))
        (is (true? (:ui/field? long)))
        (is (= "Liste filtern" (:ui/placeholder (second (find-tag (i18n/translate (pane "card.html") :de) :ui/files-filter)))))))
    (testing "every row has its menu: rename, and delete, which asks in the row"
      (let [v (pane "card.html")
            menus (filter #(and (vector? %) (= :details.actions (first %))) (nodes v))]
        (is (= 5 (count menus)) "the notes too")
        (is (= {:command/kind :artifacts/rename-start :command/data {:bot "b1" :path "card.html"}}
               (get-in (second (find-tag (first menus) :button.item)) [:on :click 0 1])))
        (is (= {:command/kind :artifacts/delete :command/data {:bot "b1" :path "card.html"}}
               (get-in (second (find-tag (first menus) :button.item.danger)) [:on :click 0 1]))))
      (let [v (pane "card.html" {:artifacts/deleting "pool.png"})
            row (find-tag v :div.confirm)]
        (is (contains? (strings (i18n/translate v :en)) "Delete Pool? It goes to the trash."))
        (is (= {:bot "b1" :path "pool.png"} (get-in (second (find-tag row :button.link.danger)) [:on :click 0 1 :command/data])))
        (is (= :artifacts/keep (get-in (second (find-tag row :button.link)) [:on :click 0 1 :command/kind])))
        (is (nil? (find-tag (second (filter #(and (vector? %) (= :li (first %))) (nodes v))) :button.row)) "the row asks instead"))
      (let [v (pane "card.html" {:artifacts/renaming "pool.png"})
            field (find-tag v :input.name)]
        (is (= "pool" (:value (second field))) "the file's name less the extension, not its title")
        (is (= {:bot "b1" :path "pool.png" :name :event/target.value} (get-in (second field) [:on :change 0 1 :command/data])))
        (is (= "clip" (:value (second (find-tag (pane "card.html" {:artifacts/renaming "clip"}) :input.name)))) "a project's name as it is")))
    (testing "a text file is shown as text, markdown rendered, with a note when cut"
      (let [md (pane "notes/plan.md" {:artifacts/text {:text "# Plan\n\nSoon." :truncated? false}})
            cut (pane "notes/plan.md" {:artifacts/text {:text "Pla" :truncated? true}})
            unread (pane "notes/plan.md")]
        (is (some #{:div.document} (tags md)))
        (is (some #{:h1} (tags md)))
        (is (contains? (strings (i18n/translate cut :en)) "The file is longer than this; download it from above for all of it."))
        (is (contains? (strings (i18n/translate unread :en)) "There is no preview for this file. Download it from above."))))
    (testing "no pick: the list alone, a word in the pane's place"
      (let [v (pane nil)]
        (is (nil? (find-tag v :button.link.back)))
        (is (= [] (:class (second v))))
        (is (contains? (strings (i18n/translate v :en)) "Pick a file on the left."))))
    (testing "an image and a video are shown as themselves"
      (is (some #{:img} (tags (pane "pool.png"))))
      (is (some #{:video} (tags (pane "story.mp4"))))
      (is (= "/artifacts/story.mp4?rev=1" (:src (second (find-tag (pane "story.mp4") :video))))))
    (testing "a video a render still writes keeps its src and shows the progress"
      (let [v (pane "story.mp4" {:renders {"story" {:started 77 :progress "30 %"}}})]
        (is (= "/artifacts/story.mp4?rev=77" (:src (second (find-tag v :video)))))
        (is (contains? (strings (i18n/translate v :en)) "Making the video 30 %"))))
    (testing "a project is the iframe once its process answers, a notice before, the render button beside it"
      (let [starting (pane "clip" {:projects {"clip" {:ready-at nil}}})
            ready (pane "clip" {:projects {"clip" {:ready-at 5}}})
            rendering (pane "clip" {:projects {"clip" {:ready-at 5}} :renders {"clip" {:started 1 :progress "50 %"}}})
            failed (pane "clip" {:projects {"clip" {:ready-at 5}} :renders {"clip" {:started 1 :exit 1 :error "boom"}}})
            stopped (pane "clip" {:projects {"clip" {:ready-at 5 :exit 1}}})]
        (is (some #{:div.placeholder} (tags starting)))
        (is (= "/artifacts/clip/" (:ui/src (second (find-tag ready :ui/canvas)))))
        (is (contains? (strings (i18n/translate ready :en)) "Make the video"))
        (is (contains? (strings (i18n/translate rendering :en)) "Making the video 50 %"))
        (is (contains? (strings (i18n/translate rendering :de)) "Stopp"))
        (is (some #{:p.error} (tags failed)))
        (is (contains? (strings (i18n/translate failed :en)) "The video did not come out: boom"))
        (is (contains? (strings (i18n/translate stopped :de)) "Der Prozess dieses Artefakts hat sich beendet (Exit 1). Seine .log sagt warum; er wird neu gestartet."))))
    (testing "nothing yet"
      (is (contains? (strings (i18n/translate (view/artifacts {}) :en)) "What the agent makes for you appears here.")))))
