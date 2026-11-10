(ns rragent.migration-test
  "The bots given back what the one conversation adopted (docs/bots.md
   §8, `rragent.migration`): the plan of the bots to restore and whose
   a schedule or a hook is; a machine as the releases 0.1.56 to 0.1.58
   left it, opened by this version: the bots off the archived list,
   their schedules, hooks and artifacts theirs again, a note in each
   chat that starts no turn, the one conversation a bot like the
   others with its own schedules as messages, the wiki the machine's,
   an address of before still leading to its file, nothing twice; and
   a machine that still has its manager."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.machine :as machine]
            [rragent.migration :as migration]
            [rragent.page :as page]
            [rragent.schedule :as schedule]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.version :as version]
            [rragent.wiki :as wiki]))

(deftest plan-test
  (let [bot-topics {"3" "guest-reviews" "4" "content-week" "5" "content-week" "6" "gone" "9" "deleted-one"}
        names {"3" "Template 202" "4" "Stories A" "5" "Stories B" "6" "Old"}]
    (testing "every adopted bot that is open, in the order made; alone when no other bot was adopted as its topic"
      (is (= [{:id "3" :slug "guest-reviews" :name "Template 202" :alone? true}
              {:id "4" :slug "content-week" :name "Stories A" :alone? false}
              {:id "5" :slug "content-week" :name "Stories B" :alone? false}
              {:id "6" :slug "gone" :name "Old" :alone? true}]
             (migration/plan bot-topics ["2" "3" "4" "5" "6"] names))))
    (testing "a bot deleted since is not restored, and still counts for the topic it shared"
      (is (= [{:id "4" :slug "content-week" :name "Stories A" :alone? false}]
             (migration/plan bot-topics ["4"] names))))
    (is (= [] (migration/plan {} ["2" "3"] names)) "nothing adopted: nothing to restore")))

(deftest owner-test
  (let [plan (migration/plan {"3" "guest-reviews" "4" "content-week" "5" "content-week"} ["3" "4" "5"] {})
        id (fn [name topic mode] (:id (migration/owner plan name topic mode)))]
    (testing "what the adoption moved carries `message` and the topic: the one bot of the topic"
      (is (= "3" (id "daily" "guest-reviews" "message"))))
    (testing "a name the adoption changed, taken on the conversation: the bot whose id it ends in"
      (is (= "4" (id "weekly-4" "content-week" "message")))
      (is (= "5" (id "weekly-5" "content-week" "message"))))
    (testing "a topic two bots share and a name that tells neither: it stays"
      (is (nil? (id "weekly" "content-week" "message"))))
    (testing "what the conversation set itself stays: a run of its own, or no topic"
      (is (nil? (id "lint" nil "run")))
      (is (nil? (id "daily" "guest-reviews" "run")))
      (is (nil? (id "note" nil "message")))
      (is (nil? (id "other" "a-topic-made-since" "message"))))))

(deftest name-before-test
  (is (= "weekly" (migration/name-before "weekly-4" "4" #{})) "the id the adoption put behind it goes")
  (is (= "weekly-4" (migration/name-before "weekly-4" "4" #{"weekly"})) "unless the bot has that name again")
  (is (= "daily" (migration/name-before "daily" "4" #{})))
  (is (= "-4" (migration/name-before "-4" "4" #{})) "nothing in front of it: as it is"))

(defn- ^:async entries
  "The entries of a conversation, oldest first, as `{:kind :data :text}`,
   the text the model's of the entry."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (reverse)
         (mapv (fn [e] {:kind (.-kind e)
                        :data (js->clj (.-data e) :keywordize-keys true)
                        :text (when-let [message (some-> (.-model e) (aget 0))]
                                (let [content (.-content message)]
                                  (if (string? content)
                                    content
                                    (->> (array-seq content)
                                         (filter #(= "text" (.-type %)))
                                         (map #(.-text %))
                                         (apply str)))))})))))

(defn- notes
  [entries]
  (filterv #(= migration/restored-kind (:kind %)) entries))

(defn- touch!
  [file text]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file text))

(defn- artifacts-of
  [home id & more]
  (apply path/join (home/artifacts-dir (home/work-dir (home/bot-dir home id))) more))

(deftest a-machine-of-one-conversation-gets-its-bots-back-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (try
                 ;; the machine on disk, so that it opens again as it was left
                 (await (machine/open! {:home home :models models :provider "faux"}))
                 (let [harness (:harness (machine/agent))
                       {one :id} (await (machine/create! {:name "Assistant"}))
                       {w1 :id} (await (machine/create! {:name "Template 202" :instructions "Build the 202 template."}))
                       {w2 :id} (await (machine/create! {:name "Local events" :instructions "Find the events."}))
                       {w3 :id} (await (machine/create! {:name "Stories A" :instructions "The stories of flat A."}))
                       {w4 :id} (await (machine/create! {:name "Stories B" :instructions "The stories of flat B."}))
                       weekly (str "weekly-" w3)]
                   (doseq [id [one w1]]
                     (bots/set-metadata! home id "model" "faux-1"))
                   ;; what the one conversation held: the schedules and hooks the adoption
                   ;; moved to it and two of its own
                   (.setResponses faux #js [(tools-test/tool-call "schedule" {:action "set" :name "daily" :cron "0 9 * * *" :tz "UTC" :text "Post the day's story."} "s1")
                                            (tools-test/tool-call "schedule" {:action "set" :name weekly :cron "0 9 * * 1" :tz "UTC" :text "The week's stories."} "s2")
                                            (tools-test/tool-call "schedule" {:action "set" :name "lint" :cron "0 8 * * 1" :tz "UTC" :text "Lint the wiki."} "s3")
                                            (tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                            (tools-test/tool-call "hook" {:action "create" :name "own" :purpose "A word for the conversation"} "h2")
                                            (tools-test/answer "Set.")])
                   (is (= "Set." (await (harness/ask-in (:conversation (machine/bot one)) "Set them."))))
                   (let [secret (.. (await (.snapshot harness hooks/Hooks (js/Number one) harness/context)) -reviews -secret)]
                     ;; the machine as the adoption left it (0.1.58): the one conversation the
                     ;; structure's manager, every other bot a topic and archived, what it moved
                     ;; marked as a message with the topic
                     (await (.commit harness
                                     (^:async fn [tx]
                                       (let [^js structure (await (.doc tx machine/Structure))
                                             ^js schedules (await (.doc tx schedule/Schedules (js/Number one)))
                                             ^js hooks (await (.doc tx hooks/Hooks (js/Number one)))]
                                         (js-delete structure "restored")
                                         (aset structure "manager" (js/Number one))
                                         (aset structure "migrated" "0.1.58")
                                         (aset structure "archived" (clj->js (mapv js/Number [w1 w2 w3 w4])))
                                         (aset structure "botTopics" (clj->js {w1 "guest-reviews" w2 "local-events" w3 "content-week" w4 "content-week"}))
                                         (aset structure "topics" (clj->js {"guest-reviews" {:name "Guest reviews" :created 1 :archived false}
                                                                            "local-events" {:name "Local events" :created 2 :archived false}
                                                                            "content-week" {:name "Content week" :created 3 :archived false}
                                                                            "pinterest" {:name "Pinterest short" :created 4 :archived false}}))
                                         (doto (aget schedules "daily") (aset "mode" "message") (aset "topic" "guest-reviews"))
                                         (doto (aget schedules weekly) (aset "mode" "message") (aset "topic" "content-week"))
                                         (doto (aget hooks "reviews") (aset "mode" "message") (aset "topic" "local-events")))
                                       js/undefined)
                                     harness/context))
                     (touch! (artifacts-of home one "guest-reviews" "covers" "a.png") "png")
                     (touch! (artifacts-of home one "guest-reviews" "keep.txt") "the conversation's")
                     (touch! (artifacts-of home w1 "keep.txt") "the bot's")
                     (touch! (artifacts-of home one "local-events" "notes.md") "notes")
                     (touch! (artifacts-of home one "content-week" "x.txt") "shared")
                     (touch! (artifacts-of home one "pinterest" "pin.png") "made since")
                     ;; the wiki in the one conversation's folder, where it lay
                     (fs/renameSync (wiki/dir home) (wiki/before (home/bot-dir home one)))
                     (fs/writeFileSync (path/join (wiki/before (home/bot-dir home one)) "brand.md") "# Brand\n")
                     (await (machine/close-all!))
                     ;; this version opens it: no answer is scripted, no bot may be asked one
                     (.setResponses faux #js [])
                     (await (machine/open! {:home home :models models :provider "faux"}))
                     (let [harness (:harness (machine/agent))
                           {:keys [archived restored restored-bots bot-topics manager]} (machine/current-structure)
                           schedules-of (^:async fn [id] (js->clj (or (await (harness/schedules harness (js/Number id))) #js {})))
                           hooks-of (^:async fn [id] (js->clj (or (await (.snapshot harness hooks/Hooks (js/Number id) harness/context)) #js {})))]
                       (testing "the bots are no archived chats any more; the restore is marked, once"
                         (is (= #{} archived))
                         (is (= version/version restored))
                         (is (= one manager) "the one conversation stays the chat the machine began with")
                         (is (= {w1 "guest-reviews" w2 "local-events"} restored-bots) "the bots with a topic to themselves got their folder back")
                         (is (= 4 (count bot-topics)) "what was adopted stays on record")
                         (is (= #{false} (set (map :archived? (machine/listed))))))
                       (testing "a schedule the adoption moved is the bot's again, a run as it was; the conversation's own stays, a message as it was"
                         (let [on-one (await (schedules-of one))
                               on-w1 (await (schedules-of w1))
                               on-w3 (await (schedules-of w3))]
                           (is (= ["lint"] (keys on-one)))
                           (is (= "message" (get-in on-one ["lint" "mode"])))
                           (is (= {"cron" "0 9 * * *" "tz" "UTC" "text" "Post the day's story." "mode" "run"} (dissoc (get on-w1 "daily") "taskId")))
                           (is (some? (get-in on-w1 ["daily" "taskId"])) "with a task of the bot's that waits for the fire")
                           (is (= ["weekly"] (keys on-w3)) "of a topic two bots shared: the one whose id the name ended in, the name as it was")
                           (is (empty? (await (schedules-of w4))))))
                       (testing "a hook goes back with its secret, so its address keeps working"
                         (let [on-one (await (hooks-of one))
                               on-w2 (await (hooks-of w2))]
                           (is (= ["own"] (keys on-one)))
                           (is (= "message" (get-in on-one ["own" "mode"])))
                           (is (= secret (get-in on-w2 ["reviews" "secret"])))
                           (is (= "run" (get-in on-w2 ["reviews" "mode"])))
                           (is (= "A new review" (get-in on-w2 ["reviews" "purpose"])))))
                       (testing "the artifacts of a topic one bot had: back in the bot's folder, a name taken there left where it is"
                         (is (= "png" (fs/readFileSync (artifacts-of home w1 "covers" "a.png") "utf8")))
                         (is (= "the bot's" (fs/readFileSync (artifacts-of home w1 "keep.txt") "utf8")))
                         (is (= "the conversation's" (fs/readFileSync (artifacts-of home one "guest-reviews" "keep.txt") "utf8")))
                         (is (= "notes" (fs/readFileSync (artifacts-of home w2 "notes.md") "utf8")))
                         (is (not (fs/existsSync (artifacts-of home one "local-events"))) "the emptied folder is gone"))
                       (testing "the folder of a topic two bots shared, and of a topic made since, stays with the conversation"
                         (is (= "shared" (fs/readFileSync (artifacts-of home one "content-week" "x.txt") "utf8")))
                         (is (= "made since" (fs/readFileSync (artifacts-of home one "pinterest" "pin.png") "utf8"))))
                       (testing "an address of before still leads to its file"
                         (is (= (str "/bot/" w2 "/artifacts/notes.md") (page/moved-artifact one (machine/bot one) "artifacts/local-events/notes.md")) "into a folder that went back to its bot")
                         (is (= (str "/bot/" one "/artifacts/content-week/x.txt") (page/moved-artifact w3 (machine/bot w3) "artifacts/x.txt")) "of a bot whose folder stayed")
                         (is (nil? (page/moved-artifact w1 (machine/bot w1) "artifacts/covers/a.png")) "a file that is where its address says")
                         (is (nil? (page/moved-artifact w1 (machine/bot w1) "artifacts/nowhere.png")) "a restored bot's own miss is a miss")
                         (is (nil? (page/moved-artifact one (machine/bot one) "artifacts/pinterest/pin.png"))))
                       (testing "the wiki is the machine's, with what it held, and reads at its place of before"
                         (is (= "# Brand\n" (fs/readFileSync (path/join (wiki/dir home) "brand.md") "utf8")))
                         (is (.isSymbolicLink (fs/lstatSync (wiki/before (home/bot-dir home one)))))
                         (is (= 1 (wiki/commits home))))
                       (testing "the one conversation is a bot like the others: its file its own, its name kept"
                         (let [{:keys [name description renamed? model]} (definition/definition (path/join (home/bot-dir home one) "AGENT.md"))]
                           (is (= "Assistant" name))
                           (is (true? renamed?))
                           (is (= bots/first-chat-description description))
                           (is (= "faux-1" model) "the model the person picked for it"))
                         (is (= "Assistant" (machine/label-of one)) "by its name, generic as it is")
                         (is (= bots/first-chat-description (:description (:definition (machine/bot one)))) "read at once"))
                       (testing "a note in every restored chat and in the conversation's, which starts no turn"
                         (let [es (loop [[id & more] [one w1 w2 w3 w4]
                                         found {}]
                                    (if (nil? id)
                                      found
                                      (recur more (assoc found id (await (entries (:conversation (machine/bot id))))))))
                               note-of (fn [id] (:text (first (notes (get es id)))))]
                           (is (= [1 1 1 1 1] (map #(count (notes (get es %))) [one w1 w2 w3 w4])))
                           (is (not-any? #(= "pi.assistant" (:kind %)) (get es w1)) "no bot answered: nobody asked")
                           (is (= {:topic "guest-reviews" :from one} (:data (first (notes (get es w1))))))
                           (is (str/includes? (note-of w1) "as the topic \"Guest reviews\""))
                           (is (str/includes? (note-of w1) (str (wiki/dir home) "/topics/")))
                           (is (str/includes? (note-of w1) (str "the bot " one ": `bot transcript` reads it")))
                           (is (str/includes? (note-of w1) "Your artifacts are under artifacts/ again"))
                           (is (str/includes? (note-of w1) "the schedules daily"))
                           (is (str/includes? (note-of w2) "the hooks reviews"))
                           (is (str/includes? (note-of w3) (str "Your artifacts stayed with the bot " one ", under its artifacts/content-week/")))
                           (is (str/includes? (note-of w3) "the schedules weekly"))
                           (is (str/includes? (note-of one) "you are one of them now"))
                           (is (str/includes? (note-of one) (str "guest-reviews to Template 202 (bot " w1 "), with artifacts/guest-reviews/")))
                           (is (str/includes? (note-of one) (str "content-week to Stories A (bot " w3 ")")))
                           (is (str/includes? (note-of one) "Their schedules went with them: daily, weekly."))
                           (is (str/includes? (note-of one) (str "shared by every bot: " (wiki/dir home))))))
                       (testing "the bot reads its note at its next turn"
                         (let [asked (atom nil)]
                           (.setResponses faux #js [(fn [context]
                                                      (reset! asked (js/JSON.stringify (.-messages context)))
                                                      (tools-test/answer "Back."))])
                           (is (= "Back." (await (harness/ask-in (:conversation (machine/bot w1)) "Hello again."))))
                           (is (str/includes? @asked "A note from rragent, not from the person"))
                           (is (str/includes? @asked "Hello again."))
                           (is (< (.indexOf @asked "A note from rragent") (.indexOf @asked "Hello again.")))))
                       (testing "opened again: nothing twice"
                         (await (machine/close-all!))
                         (await (machine/open! {:home home :models models :provider "faux"}))
                         (let [harness (:harness (machine/agent))]
                           (is (= 1 (count (notes (await (entries (:conversation (machine/bot w1))))))))
                           (is (= 1 (count (notes (await (entries (:conversation (machine/bot one))))))))
                           (is (= ["daily"] (vec (js/Object.keys (await (harness/schedules harness (js/Number w1)))))))
                           (is (= "run" (.. (await (harness/schedules harness (js/Number w1))) -daily -mode)))
                           (is (= "Assistant" (machine/label-of one))))))))
                 (finally
                   (await (machine/close-all!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-machine-with-its-manager-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [models]} (tools-test/faux-models)]
             (try
               (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
               (let [harness (:harness (machine/agent))
                     {manager :id} (await (machine/create! {:name "Manager" :instructions "You manage."}))
                     {worker :id} (await (machine/create! {:name "Template 202" :instructions "Build the 202 template."}))
                     restore! (fn [] (migration/restore! {:harness harness
                                                          :home home
                                                          :structure-doc machine/Structure
                                                          :ids (map :id (machine/listed))
                                                          :names (into {} (map (juxt :id :name)) (machine/listed))
                                                          :context harness/context}))]
                 (testing "a machine opened by this version from the start: marked, nothing to restore"
                   (is (= version/version (:restored (machine/current-structure))))
                   (is (nil? (await (restore!)))))
                 ;; a machine of 0.1.55: a manager, never one conversation
                 (await (.commit harness
                                 (^:async fn [tx]
                                   (let [^js structure (await (.doc tx machine/Structure))]
                                     (js-delete structure "restored")
                                     (aset structure "manager" (js/Number manager)))
                                   js/undefined)
                                 harness/context))
                 (testing "the manager is a bot like the others: its file its own, nothing else touched"
                   (is (= {:first-chat manager :bots []} (await (restore!))))
                   (await (machine/refresh-structure! harness))
                   (is (= version/version (:restored (machine/current-structure))))
                   (is (= "Assistant" (:name (definition/definition (path/join (home/bot-dir home manager) "AGENT.md")))))
                   (is (str/includes? (fs/readFileSync (path/join (home/bot-dir home worker) "AGENT.md") "utf8") "Build the 202 template."))
                   (is (empty? (notes (await (entries (:conversation (machine/bot manager)))))) "no note: nothing was adopted"))
                 (testing "once"
                   (is (nil? (await (restore!))))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
