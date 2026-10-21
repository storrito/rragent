(ns rragent.machine-test
  "The bots of one process, conversations of one storage: a home from
   before the bots migrated at the open, the first bot of a fresh home,
   a bot made by another through the `bot` tool in its own commit, the
   bots listed through the storage in the order made, the conversation
   of each configured with its own cwd, the facts and the hooks of one
   bot not another's, busy across bots, one alarm for the earliest
   scheduled run across them, and a delete that hides."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.bots-test :as bots-test]
            [rragent.facts :as facts]
            [rragent.fake-sprite-env :as fake]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.machine :as machine]
            [rragent.schedule :as schedule]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.wake :as wake]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async open!
  "The machine over `home` with the faux `models`: memory storage, or
   with `:sqlite` the file in the home, which a test opens again (a
   closed memory storage cannot be)."
  [home models & [storage]]
  (await (machine/open! (cond-> {:home home
                                 :models models
                                 :provider "faux"}
                          (not= :sqlite storage) (assoc :storage (harness/memory-storage))))))

(defn- ^:async results
  "The texts of the tool results of a bot's conversation, by call id."
  [conversation]
  (let [page (await (.entries conversation #js {} 100 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(defn- ^:async cwd-of
  [conversation]
  (.-cwd (await (.agent conversation harness/context))))

(deftest migrated-and-fresh-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Paris.")])
             (try
               (testing "the agent of the old layout is the first bot, the root conversation, named by its definition, its cwd its folder's work/"
                 (let [[bot :as opened] (await (open! home models :sqlite))]
                   (is (= 1 (count opened)))
                   (is (= "1" (:id bot)))
                   (is (= [{:id "1" :name "watcher" :state :idle}] (map #(select-keys % [:id :name :state]) (machine/listed))))
                   (is (number? (:created (first (machine/listed)))))
                   (is (nil? (:used (first (machine/listed)))) "nothing said yet")
                   (is (= "1" (machine/default-id)))
                   (is (= (str home "/bots/1/work") (:work bot) (await (cwd-of (:conversation bot)))))
                   (is (= "Paris." (await (harness/ask-in (:conversation bot) "Capital of France?"))))
                   (is (number? (:used (first (machine/listed)))) "used now")
                   (is (= ["agent.sqlite" "bots"] (remove #(str/starts-with? % "agent.sqlite-") (sort (array-seq (fs/readdirSync home))))) "the one storage and the bots, no index file")
                   (await (machine/close-all!))
                   (testing "opened again on the same storage: the same bot, nothing moved twice"
                     (let [[again] (await (open! home models :sqlite))]
                       (is (= "1" (:id again)))
                       (is (fs/existsSync (path/join home "bots" "1" "AGENT.md")))))
                   (await (machine/close-all!))))
               (testing "a fresh home gets the assistant of the bundle as its first bot"
                 (let [fresh (path/join (fs/mkdtempSync (path/join (os/tmpdir) "rragent-fresh-")) "agent")
                       [bot] (await (open! fresh models))]
                   (try
                     (is (= "1" (:id bot)))
                     (is (= "Assistant" (:name (first (machine/listed)))))
                     (is (= (fs/readFileSync "deploy/agent/AGENT.md" "utf8")
                            (fs/readFileSync (path/join (home/bot-dir fresh 1) "AGENT.md") "utf8")))
                     (is (fs/existsSync (bots/assistant-file fresh)))
                     (finally
                       (await (machine/close-all!))
                       (fs/rmSync (path/dirname fresh) #js {:recursive true :force true})))))
               (finally
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest bot-tool-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 skills (fs/mkdtempSync (path/join (os/tmpdir) "rragent-skills-"))
                 env (.-env js/process)]
             (fs/writeFileSync (bots/assistant-file home) bots-test/assistant)
             (fs/mkdirSync (path/join skills "reviews"))
             (fs/writeFileSync (path/join skills "reviews" "SKILL.md") "---\nname: reviews\n---\n")
             (aset env "RRAGENT_SKILLS" skills)
             (.setResponses faux #js [(tools-test/tool-call "bot" {:action "create" :name "Counter" :instructions "Count the things."} "b1")
                                      (tools-test/tool-call "bot" {:action "create" :name "Reviewer" :instructions "Review." :skill "reviews"} "b2")
                                      (tools-test/tool-call "bot" {:action "create" :name "Nope" :instructions "x" :skill "nope"} "b3")
                                      (tools-test/tool-call "bot" {:action "list"} "b4")
                                      (tools-test/answer "Made.")])
             (try
               (let [[first-bot] (await (open! home models))
                     conversation (:conversation first-bot)]
                 (is (= "Made." (await (harness/ask-in conversation "Make a bot that counts."))))
                 (let [listed (machine/listed)
                       [_ counter reviewer] listed
                       texts (await (results conversation))]
                   (testing "two bots made as conversations of the storage, in the order made, each with the assistant's text and the instructions on top, its own cwd"
                     (is (= ["watcher" "Counter" "Reviewer"] (map :name listed)))
                     (is (= "1" (:id (first listed))))
                     (is (< 1 (js/Number (:id counter)) (js/Number (:id reviewer))) "the ids of the storage, in the order made")
                     (is (every? #(= :idle (:state %)) listed))
                     (is (= (map :id listed) (await (machine/conversations (:harness (machine/agent))))) "listed through the storage, a refused create left none behind")
                     (let [text (fs/readFileSync (path/join (home/bot-dir home (:id counter)) "AGENT.md") "utf8")]
                       (is (str/includes? text "## Your job\n\nCount the things."))
                       (is (str/includes? text "You are a new agent. Offer the skills")))
                     (is (= (str home "/bots/" (:id counter) "/work") (await (cwd-of (:conversation (machine/bot (:id counter)))))) "its own cwd")
                     (is (= "Counter" (get-in (machine/bot (:id counter)) [:definition :name])) "configured from its file")
                     (is (str/includes? (fs/readFileSync (path/join (home/bot-dir home (:id reviewer)) "AGENT.md") "utf8") (bots/skill-line "reviews")))
                     (is (= (str "The bot Counter (" (:id counter) ") is ready on its page, /bot/" (:id counter) ".") (get texts "b1")) "the creator's transcript records it"))
                   (testing "a skill the machine does not have is an error the model reads; its conversation stays unused"
                     (is (str/includes? (get texts "b3") "No skill nope"))
                     (is (= 3 (count listed))))
                   (testing "the list names them with their state: the one that lists is in its run"
                     (is (= (str "watcher (1): running\nCounter (" (:id counter) "): idle\nReviewer (" (:id reviewer) "): idle") (get texts "b4"))))))
               (finally
                 (await (machine/close-all!))
                 (js-delete env "RRAGENT_SKILLS")
                 (fs/rmSync skills #js {:recursive true :force true})
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest minds-of-their-own-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 {:keys [script log]} (fake/make!)]
             (fs/writeFileSync (bots/assistant-file home) bots-test/assistant)
             (binding [sprite/*sprite-env* script]
               (try
                 (let [[a] (await (open! home models :sqlite))
                       b (await (machine/create! {:name "Second" :instructions "Count."}))
                       id-b (:id b)
                       conversation-a (:conversation a)
                       conversation-b (:conversation (machine/bot id-b))]
                   (is (bots/valid-id? id-b))
                   (is (not= "1" id-b))
                   (testing "a fact of one bot is not another's"
                     (.setResponses faux #js [(tools-test/tool-call "remember" {:event "seen" :data {:n 1}} "r1")
                                              (tools-test/answer "Noted.")])
                     (is (= "Noted." (await (harness/ask-in conversation-a "Note it."))))
                     (is (= ["seen"] (map :event (await (facts/facts (facts/from-root conversation-a harness/context))))))
                     (is (= [] (await (facts/facts (facts/from-root conversation-b harness/context))))))
                   (testing "busy across bots: one bot's run makes the machine busy"
                     (.setResponses faux #js [(tools-test/tool-call "bash" {:command "sleep 1 && pwd"} "s1")
                                              (tools-test/answer "Slept.")])
                     (is (false? (machine/busy?)))
                     (let [asked (harness/ask-in conversation-b "Sleep a second.")]
                       (await (sleep 300))
                       (is (true? (machine/busy?)))
                       (is (false? (harness/busy? a)))
                       (is (= :running (:state (second (machine/listed)))))
                       (is (= "Slept." (await asked))))
                     (is (false? (machine/busy?)))
                     (is (str/includes? (get (await (results conversation-b)) "s1") (str home "/bots/" id-b "/work")) "the command ran in the bot's own work/"))
                   (testing "a hook of one bot: the call reaches that bot, with that secret only"
                     (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A review"} "h1")
                                              (tools-test/answer "Made.")
                                              (tools-test/answer "Posting.")])
                     (is (= "Made." (await (harness/ask-in conversation-b "Make a hook."))))
                     (let [doc (await (.snapshot (:harness (machine/agent)) hooks/Hooks (.-id conversation-b) harness/context))
                           secret (.. doc -reviews -secret)
                           receive! (fn [bot name secret headers body] (hooks/receive! bot name secret headers body harness/context))
                           {:keys [ok submission]} (await (machine/hook! receive! "reviews" secret {} "{\"stars\":5}"))]
                       (is (true? ok))
                       (await (.wait (await (.submission (:harness (machine/agent)) submission harness/context)) harness/context))
                       (is (= {:ok false} (await (machine/hook! receive! "reviews" "wrong" {} "x"))))
                       (let [kinds (fn [conversation] (-> (.entries conversation #js {} 100 js/undefined harness/context)
                                                          (.then (fn [page] (mapv #(.-kind %) (array-seq (.-items page)))))))]
                         (is (some #{"rragent.hook"} (await (kinds conversation-b))))
                         (is (not-any? #{"rragent.hook"} (await (kinds conversation-a)))))))
                   (testing "one alarm for the earliest scheduled run across the bots"
                     (let [now (js/Date.now)
                           ;; the minute two hours from now, today or past midnight
                           far (js/Date. (+ now (* 2 60 60 1000)))
                           cron-a (str (.getUTCMinutes far) " " (.getUTCHours far) " * * *")
                           updates (fn [] (filter #(str/starts-with? % "automation update rragent-wake") (fake/calls log)))]
                       (.setResponses faux #js [(tools-test/tool-call "schedule" {:action "set" :name "daily" :cron cron-a :text "Daily."} "d1")
                                                (tools-test/answer "Set.")])
                       (is (= "Set." (await (harness/ask-in conversation-a "Daily."))))
                       (let [armed-for-a (last (updates))]
                         (is (str/includes? armed-for-a (str "--cron " (wake/cron-of (* 60000 (quot (.getTime far) 60000))))) "a's run, in two hours")
                         (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick."} "t1")
                                                               (tools-test/answer "Set.")]
                                                              (repeat 30 (tools-test/answer "Tock.")))))
                         (is (= "Set." (await (harness/ask-in conversation-b "Every second."))))
                         (is (not= armed-for-a (last (updates))) "b's run is sooner: the alarm moved")
                         (is (some? (:schedules/next (machine/bot id-b))))
                         (is (contains? #{:asleep :running} (machine/state-of id-b)) "a bot that waits for its schedule, or runs it")
                         (await (sleep 1500))
                         (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "delete" :name "tick"} "t2")
                                                               (tools-test/answer "Deleted.")]
                                                              (repeat 30 (tools-test/answer "Tock.")))))
                         (is (= "Deleted." (await (harness/ask-in conversation-b "Stop that."))))
                         (await (sleep 1500))
                         (await (schedule/rearm! (js/Date.now)))
                         (is (= armed-for-a (last (updates))) "b's schedule gone: the alarm is a's again, across the bots"))))
                   (testing "delete hides: the bot closed, its folder in the trash, its conversation still in the storage; the last bot stays"
                     (is (= :deleted (await (machine/delete! id-b))))
                     (is (nil? (machine/bot id-b)))
                     (is (fs/existsSync (path/join (bots/trash-dir home) id-b "AGENT.md")))
                     (is (= ["1"] (map :id (machine/listed))))
                     (is (= ["1" id-b] (await (machine/conversations (:harness (machine/agent))))) "the conversation stays")
                     (is (= #{id-b} (await (machine/hidden (:harness (machine/agent))))))
                     (is (= :last (await (machine/delete! "1"))))
                     (is (= :missing (await (machine/delete! id-b)))))
                   (await (machine/close-all!))
                   (testing "opened again on the same storage: the hidden bot stays hidden"
                     (let [opened (await (open! home models :sqlite))]
                       (is (= ["1"] (map :id opened))))))
                 (finally
                   (await (machine/close-all!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
