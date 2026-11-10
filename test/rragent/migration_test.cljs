(ns rragent.migration-test
  "A machine of before adopted into the one loop (docs/one-loop.md §9,
   `rragent.migration`): a manager with two workers, one with a
   project, with artifacts, a schedule and a hook; at the first open
   of this version every worker is a topic and archived, its
   artifacts under the loop's topic folder, its schedule on the loop
   in message mode with the topic, its hook on the loop with its
   secret, the home backed up, the handover turn and the compaction;
   opened again, nothing twice."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.compaction :as compaction]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.machine :as machine]
            [rragent.migration :as migration]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.version :as version]))

(deftest plan-test
  (let [plan (migration/plan ["1" "7" "8" "9" "10"] "7" {"8" "Guest reviews"} {} {"1" "watcher" "8" "Template 202" "9" "Guest reviews" "10" "Never used"} #{"1" "8" "9"})]
    (is (= [{:id "1" :name "watcher" :topic "watcher" :slug "watcher"}
            {:id "8" :name "Template 202" :topic "Guest reviews" :slug "guest-reviews"}
            {:id "9" :name "Guest reviews" :topic "Guest reviews" :slug "guest-reviews"}]
           plan)
        "the project's name, else the bot's; the same name is the same topic; the loop and a bot never used left out"))
  (is (= [{:id "3" :name "" :topic "Bot 3" :slug "bot-3"}] (migration/plan ["3"] "7" {} {} {} #{"3"})) "a bot without a name")
  (is (= "x-2" (:slug (first (migration/plan ["3"] "7" {"3" "X"} {"x" {:name "Other"}} {} #{"3"})))) "the slug unique among the topics: the same word for another topic gets a number")
  (is (= "x" (:slug (first (migration/plan ["3"] "7" {"3" "X"} {"x" {:name "x"}} {} #{"3"})))) "the same name is the topic that exists"))

(defn- ^:async entries
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (reverse)
         (mapv (fn [e] {:kind (.-kind e)
                        :data (js->clj (.-data e) :keywordize-keys true)
                        :text (when (= "pi.assistant" (.-kind e))
                                (->> (array-seq (.-content (aget (.-model e) 0)))
                                     (filter #(= "text" (.-type %)))
                                     (map #(.-text %))
                                     (apply str)))})))))

(defn- ^:async until
  [pred]
  (let [started (js/Date.now)]
    (loop []
      (cond
        (await (pred)) true
        (< 20000 (- (js/Date.now) started)) (throw (js/Error. "nothing after 20 s"))
        :else (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
                  (recur))))))

(deftest a-machine-of-before-is-adopted-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (try
                 ;; a machine as the manager left it: the loop (its manager), two workers
                 (await (machine/open! {:home home :models models :provider "faux"}))
                 (let [loop (machine/loop-id)
                       harness (:harness (machine/agent))
                       ;; the loop on the faux model, kept across the opens as a pick of the person's
                       _ (bots/set-metadata! home loop "model" "faux-1")
                       {w1 :id} (await (machine/create! {:name "Template 202" :instructions "Build the 202 template."}))
                       {w2 :id} (await (machine/create! {:name "Local events" :instructions "Find the events."}))]
                   (await (.commit harness
                                   (^:async fn [tx]
                                     (await (machine/relate-in! tx w1 {:parent loop :project "Guest reviews"}))
                                     (await (machine/relate-in! tx w2 {:parent loop}))
                                     js/undefined)
                                   harness/context))
                   (await (machine/refresh-structure! harness))
                   (let [covers (path/join (home/artifacts-dir (home/work-dir (home/bot-dir home w1))) "covers")]
                     (fs/mkdirSync covers #js {:recursive true})
                     (fs/writeFileSync (path/join covers "a.png") "png"))
                   (fs/writeFileSync (path/join (home/artifacts-dir (home/work-dir (home/bot-dir home w2))) "notes.md") "notes")
                   (bots/set-metadata! home w1 "model" "faux-1")
                   (bots/set-metadata! home w2 "model" "faux-1")
                   (.setResponses faux #js [(tools-test/tool-call "schedule" {:action "set" :name "daily" :cron "0 9 * * *" :tz "UTC" :text "Post the day's story."} "s1")
                                            (tools-test/answer "Set.")])
                   (is (= "Set." (await (harness/ask-in (:conversation (machine/bot w1)) "Every day at nine."))))
                   (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                            (tools-test/answer "Made.")])
                   (is (= "Made." (await (harness/ask-in (:conversation (machine/bot w2)) "A hook for reviews."))))
                   (let [secret (.. (await (.snapshot harness hooks/Hooks (js/Number w2) harness/context)) -reviews -secret)]
                     ;; as a machine from before this version: no flag, no marker
                     (await (.commit harness
                                     (^:async fn [tx]
                                       (js-delete (await (.doc tx machine/Structure)) "migrated")
                                       js/undefined)
                                     harness/context))
                     (fs/rmSync (migration/marker home) #js {:force true})
                     (await (machine/close-all!))
                     ;; the handover's turn and the summary
                     (.setResponses faux #js [(tools-test/answer "ready")
                                              (tools-test/answer "The topics' pages hold what the workers did.")])
                     (await (machine/open! {:home home :models models :provider "faux"}))
                     (let [harness (:harness (machine/agent))
                           {:keys [topics archived bot-topics migrated]} (machine/current-structure)]
                       (testing "a topic per worker, archived, the moves recorded, the version marked"
                         (is (= {"guest-reviews" "Guest reviews" "local-events" "Local events"} (into {} (map (fn [[k v]] [k (:name v)])) topics)))
                         (is (= #{w1 w2} archived))
                         (is (= {w1 "guest-reviews" w2 "local-events"} bot-topics))
                         (is (true? (machine/bots-before?)) "which is what gives the settings their Advanced section")
                         (is (false? (machine/advanced?)) "its switch off")
                         (is (= version/version migrated))
                         (is (fs/existsSync (migration/marker home))))
                       (testing "the artifacts under the loop's topic folders"
                         (is (= "png" (fs/readFileSync (path/join (home/bot-dir home loop) "work" "artifacts" "guest-reviews" "covers" "a.png") "utf8")))
                         (is (= "notes" (fs/readFileSync (path/join (home/bot-dir home loop) "work" "artifacts" "local-events" "notes.md") "utf8")))
                         (is (not (fs/existsSync (path/join (home/bot-dir home w1) "work" "artifacts" "covers")))))
                       (testing "the schedule on the loop in message mode with the topic, the worker's gone"
                         (let [on-loop (await (harness/schedules harness (js/Number loop)))
                               daily (aget on-loop "daily")]
                           (is (some? daily))
                           (is (= "message" (.-mode daily)))
                           (is (= "guest-reviews" (.-topic daily)))
                           (is (= "0 9 * * *" (.-cron daily))))
                         (is (empty? (js/Object.keys (or (await (harness/schedules harness (js/Number w1))) #js {})))))
                       (testing "the hook on the loop with its secret and topic, the worker's gone"
                         (let [on-loop (await (.snapshot harness hooks/Hooks (js/Number loop) harness/context))
                               reviews (aget on-loop "reviews")]
                           (is (= secret (.-secret reviews)))
                           (is (= "local-events" (.-topic reviews))))
                         (is (empty? (js/Object.keys (or (await (.snapshot harness hooks/Hooks (js/Number w2) harness/context)) #js {})))))
                       (testing "the backup beside the home"
                         (let [to (migration/backup-dir home)]
                           (is (fs/existsSync (path/join to "bots" w1 "AGENT.md")))
                           (is (fs/existsSync (path/join to "agent.sqlite")))))
                       (testing "the handover: the notice in the loop's chat with the topics, the loop's ready, the compaction asked (a chat this small compacts to nothing)"
                         (let [conversation (:conversation (machine/bot loop))]
                           (await (until (fn [] (-> (compaction/running? harness (js/Number loop) harness/context)
                                                    (.then (fn [running?] (not running?)))))))
                           (await (.waitForIdle conversation harness/context))
                           (let [es (await (entries conversation))
                                 notice (first (filter #(= compaction/notice-kind (:kind %)) es))
                                 content (str (some-> notice :data))]
                             (is (= {:reason "migration"} (:data notice)))
                             (is (some #(and (= "pi.assistant" (:kind %)) (= "ready" (:text %))) es) (pr-str (map :kind es)))
                             (is (string? content)))))
                       (testing "opened again: nothing twice"
                         (await (machine/close-all!))
                         (await (machine/open! {:home home :models models :provider "faux"}))
                         (let [{:keys [topics archived]} (machine/current-structure)
                               conversation (:conversation (machine/bot loop))]
                           (is (= 2 (count topics)))
                           (is (= #{w1 w2} archived))
                           (is (= 1 (count (filter #(= compaction/notice-kind (:kind %)) (await (entries conversation)))))))))))
                 (finally
                   (await (machine/close-all!))
                   (fs/rmSync (migration/backup-dir home) #js {:recursive true :force true})
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
