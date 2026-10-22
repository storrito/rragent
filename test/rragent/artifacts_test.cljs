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
  (is (nil? (artifacts/render-name "notes"))))

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
        (is (= ["html" " · " "1 KB"] (rest (find-tag (first rows) :span.meta))) "the extension and the size tell files apart")
        (is (= ["md" " · " "notes/" " · " "40 B"] (rest (find-tag (nth rows 4) :span.meta))) "the folder too")
        (is (some #{[:i18n :artifacts/project]} (nodes (nth rows 3))) "a project is named as such")
        (is (= {:bot "b1" :path "pool.png"} (get-in (second (find-tag (second rows) :button.row)) [:on :click 0 1 :command/data])))
        (is (= {:bot "b1" :path nil} (get-in (second (find-tag v :button.link.back)) [:on :click 0 1 :command/data])) "the way back to the list")
        (is (= ["showing"] (:class (second v))))
        (is (= "/artifacts/card.html" (:ui/src (second (find-tag v :ui/canvas)))))
        (is (= 3 (:ui/rev (second (find-tag v :ui/canvas)))))))
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
