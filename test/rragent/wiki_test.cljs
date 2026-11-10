(ns rragent.wiki-test
  "The machine's wiki seeded: `shared/wiki/` of the home with its index
   and log as a git repository with one commit by rragent; a folder
   that is there is left alone; the wiki one bot kept in its folder
   is moved there, its history with it."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.test-home :as test-home]
            [rragent.wiki :as wiki]))

(deftest seed-test
  (let [home (test-home/make)
        now (.getTime (js/Date. "2026-11-04T10:00:00Z"))]
    (try
      (testing "seeded: the files, the repository, one commit by rragent, unsigned"
        (is (= :seeded (wiki/seed! home now)))
        (is (= (path/join home "shared" "wiki") (wiki/dir home)))
        (is (= (wiki/dir home) (wiki/of-bot (path/join home "bots" "7"))) "found from a bot's folder")
        (let [dir (wiki/dir home)
              index (fs/readFileSync (path/join dir "index.md") "utf8")
              log (fs/readFileSync (path/join dir "log.md") "utf8")]
          (is (str/includes? index "- [log.md](log.md) — the timeline: what happened and when, append-only · 2026-11-04\n"))
          (is (str/includes? index "- [README.md](README.md)"))
          (is (str/includes? index "preferences.md"))
          (is (= ["## Wiki" "## Topics" "## Sources" "## Notes"] (re-seq #"(?m)^## .*$" index)) "the categories")
          (is (= (path/join dir "preferences.md") (wiki/preferences-file home)))
          (is (str/includes? log "## [2026-11-04] seed | the wiki begins\n"))
          (is (= ["## [2026-11-04] seed | the wiki begins"] (re-seq #"(?m)^## \[.*$" log)) "the prefix tools grep for")
          (is (str/starts-with? (fs/readFileSync (path/join dir "README.md") "utf8") "# This wiki\n"))
          (is (= 1 (wiki/commits home)))
          (let [log (child/spawnSync "git" #js ["log" "-1" "--format=%an <%ae> %s"] #js {:cwd dir :encoding "utf8"})]
            (is (= "rragent <rragent@rragent> The wiki begins" (str/trim (str (.-stdout log))))))
          (is (str/blank? (str (.-stdout (child/spawnSync "git" #js ["status" "--porcelain"] #js {:cwd dir :encoding "utf8"})))) "nothing left uncommitted")))
      (testing "a wiki that is there is left as it is"
        (fs/writeFileSync (path/join (wiki/dir home) "brand.md") "# Brand\n")
        (is (= :present (wiki/seed! home now)))
        (is (fs/existsSync (path/join (wiki/dir home) "brand.md")))
        (is (= 1 (wiki/commits home))))
      (testing "no repository: no commits"
        (is (= 0 (wiki/commits (path/join home "nowhere")))))
      (finally
        (test-home/remove! home)))))

(deftest adopt-test
  (let [home (test-home/make)
        now (.getTime (js/Date. "2026-11-04T10:00:00Z"))
        bot-dir (path/join home "bots" "2")]
    (try
      (testing "a bot without a wiki of its own: nothing to move"
        (fs/mkdirSync bot-dir #js {:recursive true})
        (is (nil? (wiki/adopt! home bot-dir)))
        (is (not (fs/existsSync (wiki/dir home)))))
      (testing "the wiki a bot kept: moved to the machine's place with its history, a link where it lay"
        ;; as the one conversation's wiki was: a repository in the bot's folder
        (wiki/seed! home now)
        (fs/renameSync (wiki/dir home) (wiki/before bot-dir))
        (fs/writeFileSync (path/join (wiki/before bot-dir) "brand.md") "# Brand\n")
        (is (= :moved (wiki/adopt! home bot-dir)))
        (is (= "# Brand\n" (fs/readFileSync (path/join (wiki/dir home) "brand.md") "utf8")))
        (is (= 1 (wiki/commits home)) "the repository came with it")
        (is (.isSymbolicLink (fs/lstatSync (wiki/before bot-dir))))
        (is (= "# Brand\n" (fs/readFileSync (path/join (wiki/before bot-dir) "brand.md") "utf8")) "the path of before still reads"))
      (testing "again: the machine has its wiki, nothing moves"
        (is (nil? (wiki/adopt! home bot-dir)))
        (is (= :present (wiki/seed! home now))))
      (finally
        (test-home/remove! home)))))
