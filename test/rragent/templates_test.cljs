(ns rragent.templates-test
  "The registry over a folder of fake templates: the frontmatter read,
   a folder without a TEMPLATE.md skipped, a project made from one."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.templates :as templates]
            [rragent.test-templates :as test-templates]))

(deftest words-test
  (is (= ["chrome" "ffmpeg"] (templates/words "chrome, ffmpeg")))
  (is (= ["mp4" "png"] (templates/words "[mp4, png]")))
  (is (= [] (templates/words nil)))
  (is (= [] (templates/words "[]"))))

(deftest registry-test
  (let [{:keys [dir restore!]} (test-templates/install! [test-templates/fake test-templates/static])]
    (try
      (fs/mkdirSync (path/join dir "plain"))
      (fs/writeFileSync (path/join dir "plain" "README.md") "no template")
      (fs/mkdirSync (path/join dir "headless"))
      (fs/writeFileSync (path/join dir "headless" "TEMPLATE.md") "# no frontmatter")
      (testing "the templates with a TEMPLATE.md, by name"
        (is (= ["fake" "static"] (templates/names)) "a folder without a TEMPLATE.md, or one without a frontmatter, is no template"))
      (testing "the frontmatter and the body"
        (let [{:keys [name description renders needs entry body] :as template} (templates/named "fake")]
          (is (= "fake" name))
          (is (= "A fake template for the tests." description))
          (is (= ["mp4"] renders))
          (is (= ["chrome" "ffmpeg"] needs))
          (is (= "serve" entry))
          (is (str/starts-with? body "# Fake"))
          (is (= (path/join dir "fake") (:dir template)))
          (is (not (templates/static? template)))
          (is (= (path/join dir "fake" "render") (templates/render-file template)))
          (is (= (path/join dir "fake" "serve") (templates/entry-file template)))))
      (testing "the defaults: index.html as the entry, no needs"
        (let [template (templates/named "static")]
          (is (= "index.html" (:entry template)))
          (is (= [] (:needs template)))
          (is (= ["png"] (:renders template)))
          (is (templates/static? template))))
      (is (nil? (templates/named "nope")))
      (finally
        (restore!)))))

(deftest copy-test
  (let [{:keys [dir restore!]} (test-templates/install! [test-templates/fake])
        work (test-templates/temp-dir)
        target (path/join work "card")]
    (try
      (testing "the project folder copied, the package naming the template"
        (is (= "fake" (:name (templates/copy! "fake" target))))
        (is (= "hello\n" (fs/readFileSync (path/join target "hello.txt") "utf8")))
        (is (= {"name" "card" "private" true "template" "fake"}
               (js->clj (js/JSON.parse (fs/readFileSync (path/join target "package.json") "utf8")))))
        (is (= "fake" (:name (templates/of-project target))))
        (is (nil? (templates/of-project (path/join dir "fake"))) "a folder whose package names no template"))
      (testing "refused: a template that is not there, a folder that exists"
        (is (thrown? js/Error (templates/copy! "nope" (path/join work "x"))))
        (is (thrown? js/Error (templates/copy! "fake" target))))
      (testing "a project of a template that is gone"
        (fs/writeFileSync (path/join target "package.json") "{\"template\": \"gone\"}")
        (is (= {:name "gone" :missing? true} (templates/of-project target))))
      (is (templates/valid-name? "my-card-2"))
      (is (not (templates/valid-name? "My Card")))
      (finally
        (restore!)
        (fs/rmSync work #js {:recursive true :force true})))))
