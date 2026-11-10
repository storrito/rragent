(ns rragent.machine-test
  "The bots of a machine (docs/bots.md): every bot of a kind, none made
   by the machine and none it lands on by rank; a new bot on the
   default model; a bot's instructions the bundle's two texts, its
   job, and the person's preferences from the machine's wiki; what a
   bot's model sees, a run's and a task's; the `bot` tool, by which a
   bot makes another, messages it and reads its chat."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.machine :as machine]
            [rragent.models :as models]
            [rragent.roles :as roles]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.wiki :as wiki]))

(defn- ^:async results
  "The texts of the tool results of a conversation, by call id."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(defn- by-bot
  "A faux response that answers by who asks: the next of `queues`
   (an atom, a word of the asker's instructions to its responses) for
   the conversation whose request holds the word. Two bots that
   answer at the same time each get their own."
  [queues]
  (fn [context]
    (let [request (js/JSON.stringify (.-messages context))
          word (some #(when (str/includes? request %) %) (keys @queues))
          response (first (get @queues word))]
      (swap! queues update word rest)
      (or response (tools-test/answer (str "(no answer scripted for " (pr-str word) ")"))))))

(defn- ^:async tools-of
  "The names of the tools the model of `conversation` is offered."
  [conversation]
  (set (map #(.-name %) (array-seq (.-tools (await (.agent conversation harness/context)))))))

(deftest roles-test
  (is (= ["read" "write" "edit" "bash"] roles/coding-tools))
  (is (= #{"project" "render" "image" "voice" "music" "transcribe" "youtube" "machine" "fetch"} (set (remove (set roles/coding-tools) roles/task-tools))) "a task: the work")
  (is (not-any? #{"remember" "recall" "report" "subagent" "task" "bot" "schedule" "hook"} roles/task-tools) "which neither remembers, reports nor nests")
  (is (= roles/task-tools (mapv #(.-name %) (array-seq (roles/model-tools :task)))) "as pi-durable takes them, objects with a name")
  (is (= ["subagent"] (mapv #(.-name %) (array-seq (.-remove (roles/model-tools :bot))))) "a bot: every tool but the subagent's")
  (is (= ["task"] (mapv #(.-name %) (array-seq (.-remove (roles/model-tools :run))))) "a run: every tool but the task's")
  (is (nil? (roles/model-tools nil)) "no role: every tool"))

(deftest the-default-model-test
  (is (= "anthropic/claude-opus-5.5" definition/default-model))
  (is (= definition/default-model (:id (first models/choices))) "the first of the page's choices")
  (is (str/includes? (bots/definition-text {}) "  model: anthropic/claude-opus-5.5\n") "a new bot's file names it")
  (is (str/includes? (bots/definition-text {:model "openai/gpt-6.1-sol"}) "  model: openai/gpt-6.1-sol\n") "unless the maker named another"))

(deftest first-chat-text-test
  (testing "the file of the chat the machine began with: its name kept as the person's, the model and thinking of the file as it was"
    (let [text (bots/first-chat-text "---\nname: Assistant\ndescription: The one conversation of this machine.\nmetadata:\n  model: faux-1\n  thinking: low\n---\n")
          definition (definition/from-text text "/x/bots/2/AGENT.md")]
      (is (= "Assistant" (:name definition)))
      (is (true? (:renamed? definition)) "so the bot leaves the name")
      (is (= "faux-1" (:model definition)))
      (is (= "low" (:thinking definition)))
      (is (= bots/first-chat-description (:description definition)))
      (is (= "" (:instructions definition)) "no job of its own")))
  (testing "without a file: the defaults"
    (let [definition (definition/from-text (bots/first-chat-text nil) "/x/bots/2/AGENT.md")]
      (is (= definition/default-model (:model definition)))
      (is (= definition/default-thinking (:thinking definition))))))

(deftest a-machine-of-bots-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (try
               (let [[bot] (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
                     id (:id bot)
                     conversation (:conversation bot)]
                 (testing "the machine makes no bot of its own: the first bot alone, which the page lands on"
                   (is (= [id] (map :id (machine/listed))))
                   (is (nil? (machine/first-chat-id)) "no chat that stands over the others")
                   (is (= id (machine/default-id)))
                   (is (= "watcher" (machine/label-of id))))
                 (testing "the machine's wiki is seeded where every bot finds it"
                   (is (fs/existsSync (path/join (wiki/dir home) "index.md")))
                   (is (= (path/join home "shared" "wiki") (wiki/dir home))))
                 (testing "a bot's instructions: the shared prompt, what a bot is, its job; the wiki's place filled in"
                   (let [instructions (.-instructions (await (.agent conversation harness/context)))]
                     (is (str/starts-with? instructions "# Every conversation of this machine"))
                     (is (str/includes? instructions "\n# A bot of this machine\n"))
                     (is (str/includes? instructions "You watch a page."))
                     (is (< (.indexOf instructions "# A bot of this machine") (.indexOf instructions "You watch a page.")) "the job after the bundle's texts")
                     (is (str/includes? instructions (str "`" (wiki/dir home) "/` is what the bots of this machine know")))
                     (is (not (str/includes? instructions "<wiki>")))
                     (is (not (str/includes? instructions "\n## Your preferences\n")) "no preferences yet: the heading is not there")))
                 (testing "what the person asked of their bots, preferences.md of the wiki, reaches every bot at its next turn"
                   (fs/writeFileSync (wiki/preferences-file home) "- Call me Lydia.\n")
                   (.setResponses faux #js [(tools-test/answer "Hello, Lydia.")])
                   (is (= "Hello, Lydia." (await (harness/ask-in conversation "Hello."))))
                   (is (str/includes? (.-instructions (await (.agent conversation harness/context))) "\n## Your preferences\n\n- Call me Lydia.")))
                 (testing "a bot's model sees its tools, the task's among them, not the subagent's"
                   (let [tools (await (tools-of conversation))]
                     (is (contains? tools "task"))
                     (is (contains? tools "bot"))
                     (is (contains? tools "remember"))
                     (is (contains? tools "schedule"))
                     (is (not (contains? tools "subagent")))
                     (is (not (contains? tools "topic")) "no such tool any more")))
                 (testing "the bot tool: a bot makes another, on the default model, with itself as the parent"
                   (.setResponses faux #js [(tools-test/tool-call "bot" {:action "create" :name "Second flat" :instructions "The same for the second apartment." :project "Flats"} "c1")
                                            (tools-test/tool-call "bot" {:action "create" :name "Third" :instructions "Another." :model "anthropic/claude-opus-4.1"} "c2")
                                            (tools-test/tool-call "bot" {:action "list"} "l1")
                                            (tools-test/answer "Made.")])
                   (is (= "Made." (await (harness/ask-in conversation "Make a bot for the second flat."))))
                   (let [found (await (results conversation))
                         [_ second-bot :as bots] (machine/listed)
                         text (fs/readFileSync (path/join (home/bot-dir home (:id second-bot)) "AGENT.md") "utf8")]
                     (is (= 2 (count bots)) "the model the page does not offer made none")
                     (is (str/includes? (get found "c1") "is ready on its page"))
                     (is (str/includes? (get found "c2") "No model anthropic/claude-opus-4.1 on this machine"))
                     (is (= {:name "Second flat" :parent id :project "Flats"} (select-keys second-bot [:name :parent :project])))
                     (is (str/includes? text "  model: anthropic/claude-opus-5.5\n"))
                     (is (str/includes? text "## Your job\n\nThe same for the second apartment."))
                     (is (str/includes? (get found "l1") "Second flat (" ))
                     (is (str/includes? (get found "l1") (str "parent " id " · project Flats")))
                     (is (not (str/includes? (get found "l1") "manager")))
                     (testing "a message to it arrives in its chat, its answer comes back as a message from that bot"
                       (bots/set-metadata! home (:id second-bot) "model" "faux-1")
                       ;; the two bots answer side by side: each its own words
                       (let [queues (atom {"You watch a page." [(tools-test/tool-call "bot" {:action "message" :id (:id second-bot) :text "How many flats?"} "m1")
                                                                (tools-test/answer "Asked.")
                                                                (tools-test/answer "It says two.")]
                                           "The same for the second apartment." [(tools-test/answer "Two flats.")]})]
                         (.setResponses faux (into-array (repeat 4 (by-bot queues))))
                         (is (= "Asked." (await (harness/ask-in conversation "Ask it how many flats."))))
                         (let [started (js/Date.now)]
                           (loop []
                             (when (and (pos? (.getPendingResponseCount faux)) (< (- (js/Date.now) started) 15000))
                               (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                               (recur))))
                         (await (.waitForIdle (:conversation (machine/bot (:id second-bot))) harness/context))
                         (await (.waitForIdle conversation harness/context))
                         (is (= 0 (.getPendingResponseCount faux)) "both bots answered")
                         (is (every? empty? (vals @queues)) "each with its own words")))
                     (testing "a bot reads another's chat, folded"
                       (.setResponses faux #js [(tools-test/tool-call "bot" {:action "transcript" :id (:id second-bot) :since 0} "t1")
                                                (tools-test/tool-call "bot" {:action "delete" :id (:id second-bot)} "d1")
                                                (tools-test/answer "Read.")])
                       (is (= "Read." (await (harness/ask-in conversation "What did it say?"))))
                       (let [found (await (results conversation))]
                         (is (str/includes? (get found "t1") "How many flats?"))
                         (is (str/includes? (get found "t1") "Two flats."))
                         (is (str/includes? (get found "t1") "(last entry "))
                         (is (= 2 (count (machine/listed))) "no bot deletes another: that is the person's, on the page")))
                     (testing "the page lands on the bot used last; any bot but the last one can be deleted"
                       (is (= id (machine/default-id)))
                       (is (= :deleted (await (machine/delete! (:id second-bot)))))
                       (is (= :last (await (machine/delete! id))))))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
