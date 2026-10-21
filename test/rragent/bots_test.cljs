(ns rragent.bots-test
  "The bots on disk: a new bot's definition from the assistant's text,
   the index with its order, rename, touch and delete to the trash, and
   the migration of a home from before the bots: one rename per file,
   idempotent, a failure undone."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.test-home :as test-home]))

(def assistant
  "A generic definition like deploy/agent/AGENT.md's."
  "---\nname: Assistant\ndescription: A new agent.\nmetadata:\n  model: faux-1\n---\n\n# Assistant\n\nYou are a new agent. Offer the skills in /tmp/skills.\n")

(defn- temp-home
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-bots-")))

(defn- with-env
  "`thunk` with the environment variable `k` as `v` (nil unset)."
  [k v thunk]
  (let [env (.-env js/process)
        before (aget env k)]
    (try
      (if (nil? v) (js-delete env k) (aset env k v))
      (thunk)
      (finally
        (if (nil? before) (js-delete env k) (aset env k before))))))

(deftest definition-text-test
  (testing "the instructions on top of the assistant's body, the name in the frontmatter"
    (let [text (bots/definition-text assistant {:name "Counter" :instructions "Count the things.\n"})
          {:keys [name instructions]} (definition/from-text text "/x/AGENT.md")]
      (is (= "Counter" name))
      (is (str/starts-with? instructions "## Your job\n\nCount the things.\n\n# Assistant\n\nYou are a new agent."))
      (is (str/includes? text "metadata:\n  model: faux-1") "the rest of the frontmatter as it was")))
  (testing "a skill makes the line that has the bot take it"
    (let [text (bots/definition-text assistant {:name "Reviewer" :instructions "Review." :skill "reviews"})]
      (is (str/includes? text "## Your job\n\nReview.\n\n"))
      (is (str/includes? text (bots/skill-line "reviews")))
      (is (< (str/index-of text "Review.") (str/index-of text "Take the skill") (str/index-of text "# Assistant")))))
  (testing "nothing given: the assistant's text as it is"
    (is (= assistant (bots/definition-text assistant {}))))
  (testing "a name with a quote is quoted"
    (is (= "Kim's \"bot\"" (:name (definition/from-text (bots/definition-text assistant {:name "Kim's \"bot\""}) "/x/AGENT.md")))))
  (testing "a text without a frontmatter is refused"
    (is (thrown? js/Error (bots/definition-text "No head." {:name "x"})))))

(deftest index-test
  (let [home (temp-home)]
    (try
      (fs/writeFileSync (bots/assistant-file home) assistant)
      (is (= [] (bots/read-index home)))
      (let [a (bots/create! home {:name "Counter" :instructions "Count."})
            b (bots/create! home {})]
        (testing "a bot's folder with its definition and work, its entry in the index in the order made"
          (is (bots/valid-id? (:id a)))
          (is (= "Counter" (:name a)))
          (is (nil? (:name b)) "a bot without a name is named by its definition")
          (is (fs/existsSync (path/join (bots/bot-dir home (:id a)) "AGENT.md")))
          (is (fs/existsSync (path/join (bots/bot-dir home (:id a)) "work")))
          (is (= "Counter" (:name (definition/definition (path/join (bots/bot-dir home (:id a)) "AGENT.md")))))
          (is (= "Assistant" (:name (definition/definition (path/join (bots/bot-dir home (:id b)) "AGENT.md")))))
          (is (= [(:id a) (:id b)] (map :id (bots/read-index home))))
          (is (= a (bots/entry home (:id a)))))
        (testing "rename, touch"
          (is (= "Counting" (bots/rename! home (:id a) "  Counting ")))
          (is (= "Counting" (:name (bots/entry home (:id a)))))
          (is (thrown? js/Error (bots/rename! home (:id a) " ")))
          (bots/touch! home (:id b) 1234)
          (is (= 1234 (:used (bots/entry home (:id b)))))
          (is (nil? (:used (bots/entry home (:id a))))))
        (testing "delete: the folder in the trash, the entry gone"
          (fs/writeFileSync (path/join (bots/bot-dir home (:id a)) "work" "notes.txt") "kept")
          (is (true? (bots/delete! home (:id a))))
          (is (not (fs/existsSync (bots/bot-dir home (:id a)))))
          (is (= "kept" (fs/readFileSync (path/join (bots/trash-dir home) (:id a) "work" "notes.txt") "utf8")))
          (is (= [(:id b)] (map :id (bots/read-index home))))
          (is (false? (bots/delete! home (:id a))) "gone already")
          (is (false? (bots/delete! home "../etc")) "no folder for a bad id"))
        (testing "a skill the machine does not have is refused, one it has taken"
          (let [skills (temp-home)]
            (fs/mkdirSync (path/join skills "reviews"))
            (fs/writeFileSync (path/join skills "reviews" "SKILL.md") "---\nname: reviews\n---\n")
            (with-env "RRAGENT_SKILLS" skills
              (fn []
                (is (thrown-with-msg? js/Error #"No skill nope in .*: reviews\." (bots/create! home {:name "x" :skill "nope"})))
                (is (str/includes? (fs/readFileSync (path/join (bots/bot-dir home (:id (bots/create! home {:name "R" :skill "reviews"}))) "AGENT.md") "utf8")
                                   (bots/skill-line "reviews")))))
            (with-env "RRAGENT_SKILLS" (path/join skills "nowhere")
              (fn []
                (is (some? (bots/create! home {:name "y" :skill "anything"})) "without a clone the bot finds out itself")))
            (fs/rmSync skills #js {:recursive true :force true}))))
      (testing "an index that does not read throws rather than loses the bots"
        (fs/writeFileSync (bots/index-file home) "{:not a vector")
        (is (thrown? js/Error (bots/read-index home))))
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))

(defn- old-home!
  "A home of the old layout: the definition, a database with its WAL,
   a work folder with an artifact."
  [text]
  (let [home (test-home/make "AGENT.md" text)]
    (fs/writeFileSync (path/join home "agent.sqlite") "db")
    (fs/writeFileSync (path/join home "agent.sqlite-wal") "wal")
    (fs/mkdirSync (path/join home "work" "artifacts") #js {:recursive true})
    (fs/writeFileSync (path/join home "work" "artifacts" "card.html") "<h1>")
    home))

(defn- top-level
  [home]
  (sort (array-seq (fs/readdirSync home))))

(deftest migrate-test
  (let [home (old-home! test-home/definition-text)]
    (try
      (is (true? (bots/old-layout? home)))
      (let [{:keys [id name]} (bots/migrate! home)
            dir (bots/bot-dir home id)]
        (testing "the files are the first bot's, renamed, nothing left at the top but the index"
          (is (bots/valid-id? id))
          (is (nil? name) "named by its definition")
          (is (= ["bots" "bots.edn"] (top-level home)))
          (is (= test-home/definition-text (fs/readFileSync (path/join dir "AGENT.md") "utf8")))
          (is (= "db" (fs/readFileSync (path/join dir "bot.sqlite") "utf8")))
          (is (= "wal" (fs/readFileSync (path/join dir "bot.sqlite-wal") "utf8")) "the write-ahead log goes along")
          (is (= "<h1>" (fs/readFileSync (path/join dir "work" "artifacts" "card.html") "utf8")))
          (is (= [id] (map :id (bots/read-index home))))
          (is (not (fs/existsSync (bots/assistant-file home))) "a definition that is not the generic one is no assistant"))
        (testing "a home in the new layout is left alone"
          (is (false? (bots/old-layout? home)))
          (is (nil? (bots/migrate! home)))
          (is (= [id] (map :id (bots/read-index home))))))
      (finally
        (fs/rmSync home #js {:recursive true :force true}))))
  (testing "the generic definition of the machine becomes the assistant every new bot starts from"
    (let [home (old-home! assistant)]
      (try
        (bots/migrate! home)
        (is (= assistant (fs/readFileSync (bots/assistant-file home) "utf8")))
        (is (= assistant (bots/assistant-text home)))
        (finally
          (fs/rmSync home #js {:recursive true :force true})))))
  (testing "without one, the bundle's copy"
    (let [home (temp-home)]
      (try
        (is (= (fs/readFileSync "deploy/agent/AGENT.md" "utf8") (bots/assistant-text home)))
        (is (fs/existsSync (bots/assistant-file home)))
        (finally
          (fs/rmSync home #js {:recursive true :force true})))))
  (testing "a failure before anything moved: nothing moved, the reason said"
    (let [home (old-home! test-home/definition-text)]
      (try
        ;; a file where the folder of the bots should be
        (fs/writeFileSync (bots/bots-dir home) "in the way")
        (is (thrown-with-msg? js/Error #"could not be moved into bots/.*The old layout stays as it was\." (bots/migrate! home)))
        (is (= ["AGENT.md" "agent.sqlite" "agent.sqlite-wal" "bots" "work"] (top-level home)))
        (finally
          (fs/rmSync home #js {:recursive true :force true})))))
  (testing "a failure after the renames: undone, the old layout as it was"
    (let [home (old-home! test-home/definition-text)]
      (try
        ;; the index cannot be written: a folder with something in it has its name
        (fs/mkdirSync (bots/index-file home))
        (fs/writeFileSync (path/join (bots/index-file home) "x") "")
        (is (thrown-with-msg? js/Error #"could not be moved into bots/" (bots/migrate! home)))
        (is (= ["AGENT.md" "agent.sqlite" "agent.sqlite-wal" "bots.edn" "work"] (top-level home)) "the files back at the top, no bots/ folder left")
        (is (= "<h1>" (fs/readFileSync (path/join home "work" "artifacts" "card.html") "utf8")))
        (is (true? (bots/old-layout? home)))
        (finally
          (fs/rmSync home #js {:recursive true :force true}))))))
