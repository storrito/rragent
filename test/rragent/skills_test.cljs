(ns rragent.skills-test
  "The person's own skills on the machine: the SKILL.md written and
   read back, the files copied beside it, the names refused, ours
   untouched; a bot writes one with the `skill` tool and another bot
   takes it."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.machine :as machine]
            [rragent.skills :as skills]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- with-env
  "`thunk` with the environment variable `k` as `v`."
  [k v thunk]
  (let [env (.-env js/process)
        before (aget env k)]
    (try
      (aset env k v)
      (thunk)
      (finally
        (if (nil? before) (js-delete env k) (aset env k before))))))

(deftest text-test
  (let [text (skills/text {:name "airbnb-review-stories"
                           :description "A story from a 5-star review."
                           :title "Review stories"
                           :ask "Which apartment, which account?"
                           :body "You turn a guest's words into a story.\nThe template is `<skill>/template.md`."})
        {:keys [name description metadata body]} (definition/frontmatter text)]
    (is (= "airbnb-review-stories" name))
    (is (= "A story from a 5-star review." description))
    (is (= {"title" "Review stories" "summary" "A story from a 5-star review." "ask" "Which apartment, which account?"} metadata) "the summary falls back to the description, no placeholder without one")
    (is (str/starts-with? body "You turn a guest's words"))
    (is (= "a \"quoted\" one" (:name (definition/frontmatter (skills/text {:name "a \"quoted\" one" :body "x"})))) "quoted the JSON way")))

(deftest write-test
  (let [home (test-home/make)
        work (home/work-dir (home/bot-dir home 1))]
    (try
      (fs/mkdirSync (path/join work "artifacts" "stories") #js {:recursive true})
      (fs/writeFileSync (path/join work "artifacts" "stories" "template.md") "# {{guest}}\n")
      (fs/writeFileSync (path/join work "notes.txt") "n")
      (testing "no skills yet"
        (is (= [] (skills/names home)))
        (is (str/starts-with? (skills/listed home) "No skills of the person's yet")))
      (testing "written with its files, listed, checked"
        (let [folder (skills/write! home {:name "review-stories" :description "Stories from reviews." :title "Review stories" :body "Do it." :files ["artifacts/stories/template.md" "notes.txt"]} work)]
          (is (= (path/join home "skills" "review-stories") folder))
          (is (= "# {{guest}}\n" (fs/readFileSync (path/join folder "artifacts" "stories" "template.md") "utf8")) "the relative path kept")
          (is (fs/existsSync (path/join folder "notes.txt")))
          (is (= ["review-stories"] (skills/names home)))
          (is (= (str "review-stories: Review stories — Stories from reviews. (" folder ")") (skills/listed home)))
          (is (nil? (skills/check! home "review-stories")))
          (is (nil? (skills/check! home nil)))))
      (testing "refused: a bad name, no instructions, a file outside the working directory or missing"
        (is (thrown-with-msg? js/Error #"lowercase letters" (skills/write! home {:name "Review Stories" :body "x"} work)))
        (is (thrown-with-msg? js/Error #"needs its instructions" (skills/write! home {:name "empty" :body " "} work)))
        (is (thrown-with-msg? js/Error #"not under your working directory" (skills/write! home {:name "outside" :body "x" :files ["../AGENT.md"]} work)))
        (is (thrown-with-msg? js/Error #"There is no nope.txt" (skills/write! home {:name "missing" :body "x" :files ["nope.txt"]} work)))
        (is (= ["review-stories"] (skills/names home)) "nothing of the refused ones stays"))
      (testing "one of ours is not written over; a name of ours or of the person's passes the check, another is refused"
        (let [ours (test-home/make)]
          (fs/mkdirSync (path/join ours "kinetic-lyrics"))
          (fs/writeFileSync (path/join ours "kinetic-lyrics" "SKILL.md") "---\nname: kinetic-lyrics\n---\n")
          (with-env "RRAGENT_SKILLS" ours
            (fn []
              (is (thrown-with-msg? js/Error #"one of ours" (skills/write! home {:name "kinetic-lyrics" :body "mine"} work)))
              (is (nil? (skills/check! home "kinetic-lyrics")))
              (is (nil? (skills/check! home "review-stories")))
              (is (thrown-with-msg? js/Error #"No skill nope" (skills/check! home "nope")))))
          (test-home/remove! ours)))
      (finally
        (test-home/remove! home)))))

(deftest a-bot-writes-a-skill-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "bash" {:command "mkdir -p artifacts/stories && echo '# {{guest}}' > artifacts/stories/template.md"} "b1")
                                      (tools-test/tool-call "skill" {:action "create"
                                                                     :name "review-stories"
                                                                     :description "A story from a 5-star review."
                                                                     :title "Review stories"
                                                                     :ask "Which apartment, which account?"
                                                                     :instructions "You turn a guest's words into a story with `<skill>/artifacts/stories/template.md`."
                                                                     :files ["artifacts/stories/template.md"]} "s1")
                                      (tools-test/tool-call "skill" {:action "list"} "s2")
                                      (tools-test/tool-call "bot" {:action "create" :name "Second flat" :instructions "The same for the second apartment." :skill "review-stories"} "c1")
                                      (tools-test/answer "Kept and handed on.")])
             (let [[bot] (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))]
               (try
                 (is (= "Kept and handed on." (await (harness/ask-in (:conversation bot) "Keep this as a how-to and make a bot for the second flat."))))
                 (let [folder (path/join home "skills" "review-stories")
                       page (await (.entries (:conversation bot) #js {} 100 js/undefined harness/context))
                       results (->> (array-seq (.-items page))
                                    (filter #(= "pi.tool-result" (.-kind %)))
                                    (map #(aget (.-model %) 0))
                                    (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))]
                   (testing "the skill is on the machine with its file"
                     (is (str/includes? (get results "s1") (str "Skill review-stories at " folder)))
                     (is (= "review-stories" (:name (definition/frontmatter (fs/readFileSync (path/join folder "SKILL.md") "utf8")))))
                     (is (fs/existsSync (path/join folder "artifacts" "stories" "template.md")))
                     (is (str/includes? (get results "s2") "review-stories: Review stories")))
                   (testing "another bot takes it"
                     (is (str/includes? (get results "c1") "is ready on its page"))
                     (let [second (second (machine/listed))
                           text (fs/readFileSync (path/join (home/bot-dir home (:id second)) "AGENT.md") "utf8")]
                       (is (str/includes? text (bots/skill-line "review-stories"))))))
                 (finally
                   (await (machine/close-all!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
