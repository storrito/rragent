(ns rragent.bots-test
  "The bots on disk: a new bot's definition from the assistant's text,
   a bot's folder (its definition written, named, renamed in the
   file, trashed), and the migration of a home from before the bots:
   the old agent's files (the definition, the work folder, a skill's
   tools) into the first bot's folder, one rename each, the storage
   untouched, idempotent, a failure undone."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.home :as home]
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

(deftest assistant-text-test
  (let [bundled (fs/readFileSync "deploy/agent/AGENT.md" "utf8")
        skills (fs/mkdtempSync (path/join (os/tmpdir) "rragent-skills-"))
        home! (fn [text]
                (let [home (temp-home)]
                  (when text (fs/writeFileSync (bots/assistant-file home) text))
                  home))]
    (fs/mkdirSync (path/join skills ".git"))
    (fs/writeFileSync (path/join skills ".git" "config") "[remote \"origin\"]\n\turl = https://github.com/storrito/agentitos.git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n")
    (testing "versions.txt has the version of deploy/agent/AGENT.md as it is now: add its sum when it changes"
      (is (contains? (bots/known-versions) (bots/digest (bots/normalize bundled)))))
    (with-env "RRAGENT_SKILLS" skills
      (fn []
        (testing "the bundle's copy with the skills repository of the clone filled in, as the image does"
          (let [home (home! nil)]
            (try
              (let [text (bots/assistant-text home)]
                (is (str/includes? text "`github.com/storrito/agentitos`"))
                (is (not (str/includes? text "SKILLS_REPO")))
                (is (= text (fs/readFileSync (bots/assistant-file home) "utf8"))))
              (finally
                (fs/rmSync home #js {:recursive true :force true})))))
        (testing "an earlier version of it, as an image filled it in: written anew from the bundle's"
          (let [earlier (str (str/replace bundled "`github.com/SKILLS_REPO`" "`github.com/someone/skills`") "\nA paragraph of before.\n")
                home (home! earlier)]
            (try
              (with-redefs [bots/known-versions (fn [] #{(bots/digest (bots/normalize earlier))})]
                (let [text (bots/assistant-text home)]
                  (is (not (str/includes? text "A paragraph of before.")))
                  (is (str/includes? text "`github.com/storrito/agentitos`"))
                  (is (= text (fs/readFileSync (bots/assistant-file home) "utf8")))))
              (finally
                (fs/rmSync home #js {:recursive true :force true})))))
        (testing "a definition someone changed stays as it is"
          (let [mine (str bundled "\nMy own line.\n")
                home (home! mine)]
            (try
              (is (= mine (bots/assistant-text home)))
              (is (= mine (fs/readFileSync (bots/assistant-file home) "utf8")))
              (finally
                (fs/rmSync home #js {:recursive true :force true})))))))
    (fs/rmSync skills #js {:recursive true :force true})))

(deftest folder-test
  (let [home (temp-home)]
    (try
      (fs/writeFileSync (bots/assistant-file home) assistant)
      (testing "a bot's folder with its definition and work, named by its conversation"
        (let [dir (bots/write-definition! home 7 (bots/definition-text assistant {:name "Counter" :instructions "Count."}))]
          (is (= (path/join home "bots" "7") dir))
          (is (fs/existsSync (path/join dir "AGENT.md")))
          (is (fs/existsSync (path/join dir "work")))
          (is (= "Counter" (bots/name-of home 7)))
          (is (= "Counter" (:name (definition/definition (home/definition-file dir)))))
          (is (number? (bots/created-at home 7)))
          (is (nil? (bots/created-at home 8)))
          (is (nil? (bots/name-of home 8)))))
      (testing "a rename edits the name line of the definition and marks it as the person's, the body stays"
        (is (= "Counting" (bots/rename! home 7 "  Counting ")))
        (let [text (fs/readFileSync (path/join (home/bot-dir home 7) "AGENT.md") "utf8")
              definition (definition/definition (home/definition-file (home/bot-dir home 7)))]
          (is (= "Counting" (bots/name-of home 7)))
          (is (str/includes? text "## Your job\n\nCount."))
          (is (= 1 (count (re-seq #"(?m)^name:" text))))
          (is (str/includes? text "name: \"Counting\"\nrenamed: true\n") "the marker after the name")
          (is (true? (:renamed? definition)))
          (is (= "Counts" (bots/rename! home 7 "Counts")))
          (is (= 1 (count (re-seq #"(?m)^renamed:" (fs/readFileSync (path/join (home/bot-dir home 7) "AGENT.md") "utf8")))) "once"))
        (is (thrown? js/Error (bots/rename! home 7 " ")))
        (is (thrown? js/Error (bots/rename! home 9 "x")) "no definition to rename"))
      (testing "the trash takes the folder"
        (fs/writeFileSync (path/join (home/bot-dir home 7) "work" "notes.txt") "kept")
        (is (true? (bots/trash! home 7)))
        (is (not (fs/existsSync (home/bot-dir home 7))))
        (is (= "kept" (fs/readFileSync (path/join (bots/trash-dir home) "7" "work" "notes.txt") "utf8")))
        (is (nil? (bots/trash! home 7)) "gone already")
        (is (nil? (bots/trash! home "../etc")) "no folder for a bad id"))
      (testing "a skill the machine does not have is refused, one it has is checked"
        (let [skills (temp-home)]
          (fs/mkdirSync (path/join skills "reviews"))
          (fs/writeFileSync (path/join skills "reviews" "SKILL.md") "---\nname: reviews\n---\n")
          (with-env "RRAGENT_SKILLS" skills
            (fn []
              (is (thrown-with-msg? js/Error #"No skill nope in .*: reviews\." (bots/check-skill! "nope")))
              (is (nil? (bots/check-skill! "reviews")))))
          (with-env "RRAGENT_SKILLS" (path/join skills "nowhere")
            (fn []
              (is (nil? (bots/check-skill! "anything")) "without a clone the bot finds out itself")))
          (fs/rmSync skills #js {:recursive true :force true})))
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))

(defn- old-home!
  "A home of the old layout: the definition, a storage with its WAL,
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
      (let [id (bots/migrate! home)
            dir (home/bot-dir home id)]
        (testing "the definition and the work folder are the first bot's, renamed; the storage stays at the top"
          (is (= 1 id))
          (is (= ["agent.sqlite" "agent.sqlite-wal" "bots"] (top-level home)))
          (is (= test-home/definition-text (fs/readFileSync (path/join dir "AGENT.md") "utf8")))
          (is (= "<h1>" (fs/readFileSync (path/join dir "work" "artifacts" "card.html") "utf8")))
          (is (= "db" (fs/readFileSync (path/join home "agent.sqlite") "utf8")))
          (is (not (fs/existsSync (bots/assistant-file home))) "a definition that is not the generic one is no assistant"))
        (testing "a home in the new layout is left alone"
          (is (false? (bots/old-layout? home)))
          (is (nil? (bots/migrate! home)))
          (is (= ["agent.sqlite" "agent.sqlite-wal" "bots"] (top-level home)))))
      (finally
        (fs/rmSync home #js {:recursive true :force true}))))
  (testing "a skill at the top of the home moves with its definition: its tools are where <skill> names them"
    (let [home (old-home! test-home/definition-text)]
      (try
        (fs/renameSync (path/join home "AGENT.md") (path/join home "SKILL.md"))
        (fs/mkdirSync (path/join home "tools" "lib") #js {:recursive true})
        (fs/writeFileSync (path/join home "tools" "takes.mjs") "// takes")
        (fs/writeFileSync (path/join home "tools" "lib" "cut.mjs") "// cut")
        (fs/writeFileSync (path/join home "README.md") "# best-takes")
        (fs/writeFileSync (path/join home ".gitignore") "node_modules/")
        (fs/writeFileSync (path/join home "agent.sqlite-shm") "shm")
        (fs/writeFileSync (path/join home "mcp.json") "{}")
        (fs/writeFileSync (path/join home "secrets.json") "{}")
        (is (= [".gitignore" "README.md" "SKILL.md" "tools" "work"] (bots/agent-files home)))
        (let [dir (home/bot-dir home (bots/migrate! home))]
          (is (= ["agent.sqlite" "agent.sqlite-shm" "agent.sqlite-wal" "bots" "mcp.json" "secrets.json"] (top-level home))
              "the storage stays, with its WAL and shared memory, and so do the machine's MCP servers and secrets")
          (is (= "// takes" (fs/readFileSync (path/join dir "tools" "takes.mjs") "utf8")))
          (is (= "// cut" (fs/readFileSync (path/join dir "tools" "lib" "cut.mjs") "utf8")))
          (is (= "# best-takes" (fs/readFileSync (path/join dir "README.md") "utf8")))
          (is (fs/existsSync (path/join dir ".gitignore")))
          (is (= dir (:folder (definition/definition (home/definition-file dir)))) "<skill> is the bot's folder, where the tools now are"))
        (finally
          (fs/rmSync home #js {:recursive true :force true})))))
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
    (let [home (old-home! assistant)]
      (try
        ;; the assistant cannot be written: a folder with something in it has its name
        (fs/mkdirSync (bots/assistant-file home))
        (fs/writeFileSync (path/join (bots/assistant-file home) "x") "")
        (is (thrown-with-msg? js/Error #"could not be moved into bots/" (bots/migrate! home)))
        (is (= ["AGENT.md" "agent.sqlite" "agent.sqlite-wal" "assistant.md" "work"] (top-level home)) "the files back at the top, no bots/ folder left")
        (is (= "<h1>" (fs/readFileSync (path/join home "work" "artifacts" "card.html") "utf8")))
        (is (true? (bots/old-layout? home)))
        (finally
          (fs/rmSync home #js {:recursive true :force true}))))))
