(ns rragent.wiki-test
  "The manager's wiki seeded: the folder with its index and log as a
   git repository with one commit by the manager; a folder that is
   there is left alone."
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
      (testing "seeded: the files, the repository, one commit by the manager, unsigned"
        (is (= :seeded (wiki/seed! home now)))
        (let [dir (wiki/dir home)]
          (is (str/includes? (fs/readFileSync (path/join dir "index.md") "utf8") "[log.md](log.md)"))
          (is (str/includes? (fs/readFileSync (path/join dir "index.md") "utf8") "preferences.md"))
          (is (= (path/join dir "preferences.md") (wiki/preferences-file home)))
          (is (str/ends-with? (fs/readFileSync (path/join dir "log.md") "utf8") "- 2026-11-04 · the wiki begins\n"))
          (is (= 1 (wiki/commits home)))
          (let [log (child/spawnSync "git" #js ["log" "-1" "--format=%an <%ae> %s"] #js {:cwd dir :encoding "utf8"})]
            (is (= "Manager <manager@rragent> The wiki begins" (str/trim (str (.-stdout log))))))
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
