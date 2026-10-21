(ns rragent.artifact-tools-test
  "The project and render tools through a tool-calling turn of the faux
   model: the real templates copied into the agent's work folder, the
   HyperFrames render by the fake CLI, the lines of the render as the
   call's output; and the project tool refusing a template the
   definition does not allow."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.db :as db]
            [rragent.harness :as harness]
            [rragent.render-test :as render-test]
            [rragent.templates :as templates]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.tools.project :as project-tool]))

(defn- ^:async results
  "The tool results of the conversation, their text."
  [agent]
  (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(js/JSON.stringify (.-model %))))))

(deftest templates-test
  (is (= ["hyperframes" "motion"] (templates/names)) "the templates of the project, found without RRAGENT_TEMPLATES")
  (testing "what the project tool tells the model"
    (let [all (project-tool/allowed {:templates nil})
          text (project-tool/description all)]
      (is (= ["hyperframes" "motion"] (map :name all)))
      (is (str/includes? text "motion, A motion design"))
      (is (str/includes? text "hyperframes, A HyperFrames composition"))
      (is (str/includes? text "(renders mp4)"))
      (is (= ["motion"] (map :name (project-tool/allowed {:templates ["motion" "nope"]}))) "an allowed template that is not installed is not offered")
      (is (str/includes? (project-tool/description []) "no template")))))

(deftest project-and-render-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 {:keys [restore!]} (render-test/with-tools!)]
             (.setResponses faux #js [(tools-test/tool-call "project" {:action "create" :name "card" :kind "hyperframes"} "p1")
                                      (tools-test/tool-call "project" {:action "create" :name "clip" :kind "motion"} "p0")
                                      (tools-test/tool-call "project" {:action "create" :name "which"} "p4")
                                      (tools-test/tool-call "project" {:action "list"} "p2")
                                      (tools-test/tool-call "render" {:name "card"} "r1")
                                      (tools-test/tool-call "project" {:action "log" :name "card"} "p3")
                                      (tools-test/answer "The card is ready.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "The card is ready." (await (harness/ask agent "Make a card and render it."))))
                 (let [artifacts (path/join home "bots" "1" "work" "artifacts")
                       texts (await (results agent))]
                   (testing "the templates' projects are in the work folder, each package naming its template, the notes answered"
                     (is (fs/existsSync (path/join artifacts "card" "composition" "index.html")))
                     (is (not (fs/existsSync (path/join artifacts "card" "serve"))) "the template's program stays with the template")
                     (is (= "hyperframes" (aget (js/JSON.parse (fs/readFileSync (path/join artifacts "card" "package.json") "utf8")) "template")))
                     (is (some #(str/includes? % "artifacts/card/ is ready, made from the template hyperframes") texts))
                     (is (some #(str/includes? % "composition/index.html") texts) "the notes of the template")
                     (is (fs/existsSync (path/join artifacts "clip" "design.cljs")))
                     (is (= "motion" (aget (js/JSON.parse (fs/readFileSync (path/join artifacts "clip" "package.json") "utf8")) "template")))
                     (is (some #(str/includes? % "artifacts/clip/ is ready, made from the template motion") texts)))
                   (testing "no kind named with two templates allowed: the question"
                     (is (some #(str/includes? % "Which template? One of: hyperframes, motion.") texts))
                     (is (not (fs/existsSync (path/join artifacts "which")))))
                   (testing "the list names them with their templates"
                     (is (some #(and (str/includes? % "card (hyperframes): not running") (str/includes? % "clip (motion): not running")) texts)))
                   (testing "the render wrote the video through the template's script and said so"
                     (is (= "video\n" (fs/readFileSync (path/join artifacts "card.mp4") "utf8")))
                     (is (some #(str/includes? % "artifacts/card.mp4 is ready.") texts))
                     (is (= 0 (get-in @(db/pane "1") [:renders "card" :exit])) "the bot's atom knows the render"))
                   (testing "the log of a project that never ran"
                     (is (some #(str/includes? % "No log yet for card") texts))))
                 (finally
                   (restore!)
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest allowed-templates-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "  model: faux-1\n" "  model: faux-1\n  templates: [hyperframes]\n"))
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "project" {:action "create" :name "clip" :kind "motion"} "p1")
                                      (tools-test/tool-call "project" {:action "create" :name "card"} "p2")
                                      (tools-test/answer "Done.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= ["hyperframes"] (get-in agent [:definition :templates])))
                 (is (= "Done." (await (harness/ask agent "Make a clip."))))
                 (let [artifacts (path/join home "bots" "1" "work" "artifacts")
                       texts (await (results agent))]
                   (testing "a template the definition does not allow is refused, with the allowed ones"
                     (is (some #(str/includes? % "The template motion is not allowed for this agent, these are: hyperframes.") texts))
                     (is (not (fs/existsSync (path/join artifacts "clip")))))
                   (testing "the one allowed template needs no kind"
                     (is (fs/existsSync (path/join artifacts "card" "composition" "index.html")))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest no-templates-test
  (let [tool-text (project-tool/description (project-tool/allowed {:templates []}))]
    (is (str/includes? tool-text "This agent has no template"))
    (is (thrown-with-msg? js/Error #"allows no template" (project-tool/create! "/tmp/none" [] "card" nil)))))
