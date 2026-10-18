(ns rragent.artifact-tools-test
  "The project and render tools through a tool-calling turn of the faux
   model: the template copied into the agent's work folder, the render
   by the fake CLI, the lines of the render as the call's output."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.db :as db]
            [rragent.harness :as harness]
            [rragent.render-test :as render-test]
            [rragent.templates :as templates]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- ^:async results
  "The tool results of the conversation, their text."
  [agent]
  (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(js/JSON.stringify (.-model %))))))

(deftest templates-test
  (is (= ["hyperframes"] (templates/kinds)) "the templates of the project, found without RRAGENT_TEMPLATES")
  (is (templates/valid-name? "my-card-2"))
  (is (not (templates/valid-name? "My Card")))
  (is (thrown? js/Error (templates/copy! "nope" (path/join (render-test/temp-dir) "x")))))

(deftest project-and-render-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 {:keys [restore!]} (render-test/with-tools!)]
             (.setResponses faux #js [(tools-test/tool-call "project" {:action "create" :name "card"} "p1")
                                      (tools-test/tool-call "project" {:action "list"} "p2")
                                      (tools-test/tool-call "render" {:name "card"} "r1")
                                      (tools-test/tool-call "project" {:action "log" :name "card"} "p3")
                                      (tools-test/answer "The card is ready.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "The card is ready." (await (harness/ask agent "Make a card and render it."))))
                 (let [artifacts (path/join home "work" "artifacts")
                       texts (await (results agent))]
                   (testing "the template is in the work folder"
                     (is (fs/existsSync (path/join artifacts "card" "composition" "index.html")))
                     (is (fs/existsSync (path/join artifacts "card" "server.mjs")))
                     (is (some #(str/includes? % "artifacts/card/ is ready") texts)))
                   (testing "the list names it"
                     (is (some #(str/includes? % "card: not running") texts)))
                   (testing "the render wrote the video and said so"
                     (is (= "video\n" (fs/readFileSync (path/join artifacts "card.mp4") "utf8")))
                     (is (some #(str/includes? % "artifacts/card.mp4 is ready.") texts))
                     (is (= 0 (get-in @db/db [:renders "card" :exit])) "the pane's db knows the render"))
                   (testing "the log of a project that never ran"
                     (is (some #(str/includes? % "No log yet for card") texts))))
                 (finally
                   (restore!)
                   (swap! db/db dissoc :renders)
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
