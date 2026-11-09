(ns rragent.loop-test
  "The loop of a machine (docs/one-loop.md): made at the open when the
   structure names none and kept across opens, its definition from the
   bundle's LOOP.md written anew at every open with the person's picks
   kept and the wiki's `preferences.md` appended at the read, the
   landing bot; its model sees the loop's tools alone; the `bot` tool
   is the loop's and makes no bot any more."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.machine :as machine]
            [rragent.reload :as reload]
            [rragent.roles :as roles]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.wiki :as wiki]))

(defn- ^:async open!
  [home models & [storage]]
  (await (machine/open! (cond-> {:home home
                                 :models models
                                 :provider "faux"}
                          (not= :sqlite storage) (assoc :storage (harness/memory-storage))))))

(defn- ^:async results
  "The texts of the tool results of a conversation, by call id."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(defn- definition-file
  [home id]
  (path/join (home/bot-dir home id) "AGENT.md"))

(deftest loop-text-test
  (testing "the frontmatter of the bundle's LOOP.md alone: the instructions come from the bundle at every read"
    (let [text (bots/loop-text nil)]
      (is (str/starts-with? text "---\nname: Assistant\n"))
      (is (str/ends-with? text "---\n"))
      (is (not (str/includes? text "# The conversation")))
      (is (str/includes? text "  model: anthropic/claude-opus-5.5\n"))
      (is (str/includes? (bots/loop-body) "# The conversation of this machine\n"))
      (is (not (str/includes? (bots/loop-body) "name: Assistant")))))
  (testing "the model and thinking of the file as it was are kept: the person's picks"
    (let [text (bots/loop-text "---\nname: Assistant\nmetadata:\n  model: faux-1\n  thinking: low\n---\n\nOld body.\n")]
      (is (str/includes? text "  model: faux-1\n"))
      (is (str/includes? text "  thinking: low\n"))
      (is (not (str/includes? text "Old body")))))
  (testing "a task's instructions: the shared prompt, then the bundle's TASK.md"
    (let [text (bots/task-instructions)]
      (is (str/starts-with? text "# Every conversation of this machine"))
      (is (str/includes? text "\n# A task\n"))
      (is (str/includes? text "Your last message is your result")))))

(deftest roles-test
  (is (= ["read" "write" "edit" "bash"] roles/coding-tools))
  (is (= #{"task" "topic" "bot" "schedule" "hook" "skill" "share" "machine" "fetch"} (set (remove (set roles/coding-tools) roles/loop-tools))) "the loop: talk, judgment, the wiki, tasks and topics")
  (is (= #{"project" "render" "image" "voice" "music" "transcribe" "youtube" "machine" "fetch" "share"} (set (remove (set roles/coding-tools) roles/task-tools))) "a task: the work")
  (is (not-any? #{"remember" "recall" "report" "subagent" "music_pick"} (concat roles/loop-tools roles/task-tools)) "neither remembers, reports or nests")
  (is (nil? (roles/model-tools nil)) "a bot of before sees every tool")
  (is (= roles/task-tools (mapv #(.-name %) (array-seq (roles/model-tools :task)))) "as pi-durable takes them, objects with a name"))

(deftest with-preferences-test
  (let [home (test-home/make)
        file (path/join home "preferences.md")]
    (try
      (is (= "---\nname: x\n---\n\nBody.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)) "no file: the text as it is")
      (fs/writeFileSync file "  \n")
      (is (= "---\nname: x\n---\n\nBody.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)) "an empty file: the same")
      (fs/writeFileSync file "- Status on Mondays only.\n")
      (is (= "---\nname: x\n---\n\nBody.\n\n## Your preferences\n\n- Status on Mondays only.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)))
      (is (= "Body." (reload/with-preferences "Body." nil)))
      (finally
        (test-home/remove! home)))))

(deftest loop-made-at-open-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Here.")])
             (try
               (let [opened (await (open! home models :sqlite))
                     loop (machine/loop-id)]
                 (testing "the first bot and the loop, made at the open, named in the structure, the landing bot"
                   (is (bots/valid-id? loop))
                   (is (not= "1" loop))
                   (is (= ["1" loop] (map :id opened)))
                   (is (= [false true] (map :loop? (machine/listed))))
                   (is (= "Assistant" (:name (second (machine/listed)))))
                   (is (= loop (machine/default-id)) "the root of the page goes to the loop")
                   (is (= {:hidden #{} :archived #{} :parents {} :projects {} :topics {} :manager loop :advanced? false} (machine/current-structure)))
                   (is (= "Assistant" (:label (second (machine/listed)))) "the page calls it by its name, never New bot with a time")
                   (is (str/starts-with? (fs/readFileSync (definition-file home loop) "utf8") "---\nname: Assistant\n"))
                   (is (not (str/includes? (fs/readFileSync (definition-file home loop) "utf8") "# The conversation")) "the file is the frontmatter alone")
                   (let [instructions (:instructions (reload/current loop))]
                     (is (str/starts-with? instructions "# Every conversation of this machine") "the shared prompt first")
                     (is (str/includes? instructions "# The conversation of this machine\n") "then the loop's text from the bundle")
                     (is (str/includes? instructions (str (home/bot-dir home loop) "/wiki/preferences.md")) "its folder filled in")))
                 (testing "the loop's model sees the loop's tools alone; a bot of before every tool"
                   (let [offered (fn [agent] (set (map #(.-name %) (array-seq (.-tools agent)))))
                         loop-agent (await (.agent (:conversation (machine/bot loop)) harness/context))
                         bot-agent (await (.agent (:conversation (machine/bot "1")) harness/context))]
                     (is (= (set roles/loop-tools) (offered loop-agent)))
                     (is (contains? (offered bot-agent) "remember") "a bot of before keeps its tools")
                     (is (contains? (offered bot-agent) "task") "every tool, the loop's new ones too")))
                 (testing "its wiki, seeded as a git repository with the index and the log"
                   (let [dir (wiki/dir (home/bot-dir home loop))]
                     (is (fs/existsSync (path/join dir ".git")))
                     (is (str/starts-with? (fs/readFileSync (path/join dir "index.md") "utf8") "# Index\n"))
                     (is (re-find #"(?m)^## \[\d{4}-\d\d-\d\d\] seed \| the wiki begins$" (fs/readFileSync (path/join dir "log.md") "utf8")))
                     (is (fs/existsSync (path/join dir "README.md")))
                     (is (= 1 (wiki/commits (home/bot-dir home loop))))
                     (fs/writeFileSync (path/join dir "brand.md") "# Brand\n")))
                 (testing "the person's page of the wiki is appended to the definition at the read, under its heading"
                   (fs/writeFileSync (wiki/preferences-file (home/bot-dir home loop)) "- Status on Mondays only.\n")
                   (await (reload/reload! (:conversation (machine/bot loop)) harness/context))
                   (is (str/ends-with? (:instructions (reload/current loop)) "## Your preferences\n\n- Status on Mondays only."))
                   (is (= "Assistant" (:name (reload/current loop)))))
                 (testing "the advanced switch of the settings lives in the structure"
                   (await (machine/set-advanced! (:harness (machine/agent)) true))
                   (is (true? (:advanced? (machine/current-structure))))
                   (is (true? (:advanced? (await (machine/structure (:harness (machine/agent)))))) "in the document"))
                 (testing "opened again: the same loop, its definition from the bundle anew, the person's picks kept"
                   (bots/set-metadata! home loop "thinking" "low")
                   (fs/appendFileSync (definition-file home loop) "\nA line someone added.\n")
                   (await (machine/close-all!))
                   (let [again (await (open! home models :sqlite))
                         text (fs/readFileSync (definition-file home loop) "utf8")]
                     (is (= ["1" loop] (map :id again)) "no second loop")
                     (is (= loop (machine/loop-id)))
                     (is (true? (:advanced? (machine/current-structure))) "the switch survives the open")
                     (await (machine/set-advanced! (:harness (machine/agent)) false))
                     (is (false? (:advanced? (machine/current-structure))))
                     (is (str/includes? text "  thinking: low\n"))
                     (is (not (str/includes? text "A line someone added")))
                     (is (= "low" (get-in (machine/bot loop) [:definition :thinking])))
                     (is (str/ends-with? (:instructions (reload/current loop)) "- Status on Mondays only.") "the preferences read again")
                     (is (fs/existsSync (path/join (wiki/dir (home/bot-dir home loop)) "brand.md")) "the wiki left as it was, not seeded again")
                     (is (= 1 (wiki/commits (home/bot-dir home loop)))))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest bot-tool-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (try
               (let [[watcher] (await (open! home models))
                     loop (machine/loop-id)
                     _ (bots/set-metadata! home loop "model" "faux-1")
                     conversation (:conversation (machine/bot loop))]
                 (testing "the loop lists the bots of before and reads a chat; the loop itself is never deleted"
                   (.setResponses faux #js [(tools-test/answer "Hello from the watcher.")])
                   (is (= "Hello from the watcher." (await (harness/ask-in (:conversation watcher) "Hi watcher"))))
                   (.setResponses faux #js [(tools-test/tool-call "bot" {:action "list"} "l1")
                                            (tools-test/tool-call "bot" {:action "transcript" :id "1" :since 0} "t1")
                                            (tools-test/tool-call "bot" {:action "delete" :id loop} "d1")
                                            (tools-test/tool-call "bot" {:action "archive" :id "1"} "a1")
                                            (tools-test/answer "Done.")])
                   (is (= "Done." (await (harness/ask-in conversation "Look around."))))
                   (let [texts (await (results conversation))]
                     (is (= (str "watcher (1): idle\nAssistant (" loop "): running · this conversation") (get texts "l1")))
                     (is (str/includes? (get texts "t1") "Person: Hi watcher"))
                     (is (str/includes? (get texts "t1") "Bot: Hello from the watcher."))
                     (is (= "This conversation stays." (get texts "d1")))
                     (is (= "Archived watcher (1): its chat is over, its files and its page stay." (get texts "a1")))
                     (is (contains? (:archived (machine/current-structure)) "1"))))
                 (testing "a bot of before cannot use the tool: the actions are the loop's"
                   (.setResponses faux #js [(tools-test/tool-call "bot" {:action "list"} "l2")
                                            (tools-test/answer "Tried.")])
                   (is (= "Tried." (await (harness/ask-in (:conversation watcher) "List the bots."))))
                   (is (str/includes? (get (await (results (:conversation watcher))) "l2") "is the conversation's of this machine"))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
