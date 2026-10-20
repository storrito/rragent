(ns rragent.definition-test
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.definition :as definition]
            [rragent.home :as home]
            [rragent.test-home :as test-home]))

(deftest frontmatter-test
  (testing "the keys on top, one level under metadata, the body after"
    (let [read (definition/frontmatter test-home/definition-text)]
      (is (= "watcher" (:name read)))
      (is (= "Watches a page." (:description read)))
      (is (= {"title" "Page watcher" "model" "faux-1"} (:metadata read)))
      (is (str/starts-with? (:body read) "# watcher"))))
  (testing "no frontmatter: nil"
    (is (nil? (definition/frontmatter "# just text")))))

(deftest definition-test
  (let [home (test-home/make)]
    (try
      (let [{:keys [name model thinking instructions folder]} (definition/definition (home/definition-file home))]
        (is (= "watcher" name))
        (is (= "faux-1" model) "the model from the metadata")
        (is (nil? thinking))
        (is (= (path/resolve home) folder))
        (is (str/includes? instructions (str "Your folder is " folder ".")) "<skill> means the folder"))
      (finally
        (test-home/remove! home)))))

(deftest templates-test
  (testing "the templates the agent may use, nil for every installed one"
    (is (nil? (definition/templates {"model" "x"})))
    (is (= ["motion" "hyperframes"] (definition/templates {"templates" "[motion, hyperframes]"})))
    (is (= ["motion"] (definition/templates {"templates" "motion"})))
    (is (= [] (definition/templates {"templates" "[]"})))
    (is (= [] (definition/templates {"templates" ""}))))
  (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "  model: faux-1\n" "  model: faux-1\n  templates: [motion]\n"))]
    (try
      (is (= ["motion"] (:templates (definition/definition (home/definition-file home)))))
      (finally
        (test-home/remove! home)))))

(deftest skill-md-fallback-test
  (let [home (test-home/make "SKILL.md" test-home/definition-text)]
    (try
      (is (str/ends-with? (home/definition-file home) "SKILL.md") "an agentitos skill is a definition too")
      (is (= definition/default-model (:model (definition/definition (home/definition-file (test-home/make "SKILL.md" "---\nname: plain\n---\nBody\n"))))) "no model named: the default")
      (finally
        (test-home/remove! home)))))

(deftest display-name-test
  (let [env (.-env js/process)
        before (aget env "RRAGENT_NAME")]
    (try
      (js-delete env "RRAGENT_NAME")
      (is (= "Assistant" (definition/display-name {:name "Assistant"})) "no name given: the generic one")
      (aset env "RRAGENT_NAME" "Kim's machine")
      (is (= "Kim's machine" (definition/display-name {:name "Assistant"})) "the machine's name while the definition is the generic one")
      (is (= "kinetic-lyrics" (definition/display-name {:name "kinetic-lyrics"})) "a skill taken: the file's name wins")
      (finally
        (if before (aset env "RRAGENT_NAME" before) (js-delete env "RRAGENT_NAME"))))))

(deftest a-definition-without-a-name-test
  (let [home (test-home/make "AGENT.md" "---\ndescription: nameless\n---\nBody\n")]
    (try
      (is (thrown? js/Error (definition/definition (home/definition-file home))))
      (is (nil? (home/definition-file (fs/mkdtempSync (path/join home "empty-")))) "no file, no definition")
      (finally
        (test-home/remove! home)))))
