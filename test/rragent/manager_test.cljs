(ns rragent.manager-test
  "The manager of a machine: made at the open when the structure names
   none and kept across opens, its definition from the bundle written
   anew at every open with the person's picks kept and their
   `PREFERENCES.md` appended at the read, the landing bot; a worker it
   makes with the `bot` tool has it as its parent and a project, both
   in the structure, and its own model and thinking level; the manager
   alone reads a worker's chat, archives and deletes, a delete refused
   while something depends on the bot."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.bots-test :as bots-test]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.machine :as machine]
            [rragent.reload :as reload]
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

(deftest manager-text-test
  (let [assistant (str bots-test/assistant "\n## On this machine\n\nRule A of the machine.\n")]
    (testing "the bundle's MANAGER.md with the On this machine section of the generic definition under it"
      (let [text (bots/manager-text assistant nil)]
        (is (str/starts-with? text "---\nname: Manager\n"))
        (is (str/includes? text "# Manager\n"))
        (is (str/ends-with? text "## On this machine\n\nRule A of the machine.\n"))
        (is (str/includes? text "  model: anthropic/claude-opus-5.5\n"))))
    (testing "the model and thinking of the file as it was are kept: the person's picks"
      (let [text (bots/manager-text assistant "---\nname: Manager\nmetadata:\n  model: faux-1\n  thinking: low\n---\n\nOld body.\n")]
        (is (str/includes? text "  model: faux-1\n"))
        (is (str/includes? text "  thinking: low\n"))
        (is (not (str/includes? text "Old body")))))
    (testing "without the section: the bundle's text alone"
      (is (not (str/includes? (bots/manager-text bots-test/assistant nil) "## On this machine"))))))

(deftest with-preferences-test
  (let [home (test-home/make)
        file (home/preferences-file home)]
    (try
      (is (= "---\nname: x\n---\n\nBody.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)) "no file: the text as it is")
      (fs/writeFileSync file "  \n")
      (is (= "---\nname: x\n---\n\nBody.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)) "an empty file: the same")
      (fs/writeFileSync file "- Status on Mondays only.\n")
      (is (= "---\nname: x\n---\n\nBody.\n\n## Your preferences\n\n- Status on Mondays only.\n" (reload/with-preferences "---\nname: x\n---\n\nBody.\n" file)))
      (is (= "Body." (reload/with-preferences "Body." nil)))
      (finally
        (test-home/remove! home)))))

(deftest manager-made-at-open-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (fs/writeFileSync (bots/assistant-file home) bots-test/assistant)
             (.setResponses faux #js [(tools-test/answer "Here.")])
             (try
               (let [opened (await (open! home models :sqlite))
                     manager (machine/manager-id)]
                 (testing "the first bot and the manager, made at the open, named in the structure, the landing bot"
                   (is (bots/valid-id? manager))
                   (is (not= "1" manager))
                   (is (= ["1" manager] (map :id opened)))
                   (is (= [false true] (map :manager? (machine/listed))))
                   (is (= "Manager" (:name (second (machine/listed)))))
                   (is (= manager (machine/default-id)) "the root of the page goes to the manager")
                   (is (= {:hidden #{} :archived #{} :parents {} :projects {} :manager manager :advanced? false} (machine/current-structure)))
                   (is (= "Manager" (:label (second (machine/listed)))) "the page calls it by its name, never New bot with a time")
                   (is (str/starts-with? (fs/readFileSync (definition-file home manager) "utf8") "---\nname: Manager\n")))
                 (testing "its wiki, seeded as a git repository with the index and the log"
                   (let [dir (wiki/dir (home/bot-dir home manager))]
                     (is (fs/existsSync (path/join dir ".git")))
                     (is (str/starts-with? (fs/readFileSync (path/join dir "index.md") "utf8") "# Index\n"))
                     (is (re-find #"(?m)^- \d{4}-\d\d-\d\d · the wiki begins$" (fs/readFileSync (path/join dir "log.md") "utf8")))
                     (is (= 1 (wiki/commits (home/bot-dir home manager))))
                     (fs/writeFileSync (path/join dir "brand.md") "# Brand\n")))
                 (testing "the preferences file is appended to the definition at the read, under its heading"
                   (fs/writeFileSync (home/preferences-file (home/bot-dir home manager)) "- Status on Mondays only.\n")
                   (await (reload/reload! (:conversation (machine/bot manager)) harness/context))
                   (is (str/ends-with? (:instructions (reload/current manager)) "## Your preferences\n\n- Status on Mondays only."))
                   (is (= "Manager" (:name (reload/current manager)))))
                 (testing "the advanced switch of the settings lives in the structure"
                   (await (machine/set-advanced! (:harness (machine/agent)) true))
                   (is (true? (:advanced? (machine/current-structure))))
                   (is (true? (:advanced? (await (machine/structure (:harness (machine/agent)))))) "in the document"))
                 (testing "opened again: the same manager, its definition from the bundle anew, the person's picks kept"
                   (bots/set-metadata! home manager "thinking" "low")
                   (fs/appendFileSync (definition-file home manager) "\nA line someone added.\n")
                   (await (machine/close-all!))
                   (let [again (await (open! home models :sqlite))
                         text (fs/readFileSync (definition-file home manager) "utf8")]
                     (is (= ["1" manager] (map :id again)) "no second manager")
                     (is (= manager (machine/manager-id)))
                     (is (true? (:advanced? (machine/current-structure))) "the switch survives the open")
                     (await (machine/set-advanced! (:harness (machine/agent)) false))
                     (is (false? (:advanced? (machine/current-structure))))
                     (is (str/includes? text "  thinking: low\n"))
                     (is (not (str/includes? text "A line someone added")))
                     (is (= "low" (get-in (machine/bot manager) [:definition :thinking])))
                     (is (str/ends-with? (:instructions (reload/current manager)) "- Status on Mondays only.") "the preferences read again")
                     (is (fs/existsSync (path/join (wiki/dir (home/bot-dir home manager)) "brand.md")) "the wiki left as it was, not seeded again")
                     (is (= 1 (wiki/commits (home/bot-dir home manager)))))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest manager-tools-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (fs/writeFileSync (bots/assistant-file home) bots-test/assistant)
             (try
               (let [[watcher] (await (open! home models))
                     manager (machine/manager-id)
                     _ (bots/set-metadata! home manager "model" "faux-1")
                     conversation (:conversation (machine/bot manager))]
                 (.setResponses faux #js [(tools-test/tool-call "bot" {:action "create" :name "Template 202" :instructions "Build the 202 template." :project "Guest reviews" :model "faux-1" :thinking "low"} "c1")
                                          (tools-test/tool-call "bot" {:action "list"} "c2")
                                          (tools-test/answer "Made.")])
                 (is (= "Made." (await (harness/ask-in conversation "Make the 202 worker."))))
                 (let [texts (await (results conversation))
                       worker (first (machine/children-of manager))]
                   (testing "the worker: the manager its parent and its project in the structure, its model and thinking in its file"
                     (is (some? worker))
                     (is (= {worker manager} (:parents (machine/current-structure))))
                     (is (= {worker "Guest reviews"} (:projects (machine/current-structure))))
                     (is (= (str "The bot Template 202 (" worker ") is ready on its page, /bot/" worker ". It belongs to the project Guest reviews. You are its parent.") (get texts "c1")))
                     (let [text (fs/readFileSync (definition-file home worker) "utf8")]
                       (is (str/includes? text "  model: faux-1\n"))
                       (is (str/includes? text "  thinking: low\n"))
                       (is (str/includes? text "## Your job\n\nBuild the 202 template.")))
                     (is (= "low" (get-in (machine/bot worker) [:definition :thinking])))
                     (is (= {:parent manager :project "Guest reviews" :archived? false :manager? false}
                            (select-keys (last (machine/listed)) [:parent :project :archived? :manager?])))
                     (is (= (str "watcher (1): idle\nManager (" manager "): running · the manager\nTemplate 202 (" worker "): idle · parent " manager " · project Guest reviews") (get texts "c2"))))
                   (testing "the person talks to the worker directly; the worker makes a bot of its own"
                     (.setResponses faux #js [(tools-test/answer "Hello from the worker.")
                                              (tools-test/tool-call "bot" {:action "create" :name "Sub" :instructions "Help."} "s1")
                                              (tools-test/answer "Made a sub.")])
                     (is (= "Hello from the worker." (await (harness/ask-in (:conversation (machine/bot worker)) "Hi worker"))))
                     (is (= "Made a sub." (await (harness/ask-in (:conversation (machine/bot worker)) "Make a sub"))))
                     (is (= 1 (count (machine/children-of worker)))))
                   (let [sub (first (machine/children-of worker))]
                     (testing "delete is the manager's: another bot is refused"
                       (.setResponses faux #js [(tools-test/tool-call "bot" {:action "delete" :id worker} "d1")
                                                (tools-test/answer "Tried.")])
                       (is (= "Tried." (await (harness/ask-in (:conversation watcher) "Delete the worker."))))
                       (is (str/includes? (get (await (results (:conversation watcher))) "d1") "delete is the manager's"))
                       (is (some? (machine/bot worker))))
                     (testing "the manager reads the worker's chat, deletes only what nothing depends on, archives"
                       (.setResponses faux #js [(tools-test/tool-call "bot" {:action "transcript" :id worker :since 0} "t1")
                                                (tools-test/tool-call "bot" {:action "delete" :id manager} "d2")
                                                (tools-test/tool-call "bot" {:action "delete" :id worker} "d3")
                                                (tools-test/tool-call "bot" {:action "archive" :id sub} "a1")
                                                (tools-test/tool-call "bot" {:action "delete" :id sub} "d4")
                                                (tools-test/tool-call "bot" {:action "delete" :id worker} "d5")
                                                (tools-test/answer "Done.")])
                       (is (= "Done." (await (harness/ask-in conversation "Clean up."))))
                       (let [texts (await (results conversation))
                             transcript (get texts "t1")]
                         (is (str/includes? transcript "Person: Hi worker"))
                         (is (str/includes? transcript "Bot: Hello from the worker."))
                         (is (str/includes? transcript "(calls bot)"))
                         (is (re-find #"\(last entry \d+; read again later with since \d+\)$" transcript))
                         (is (= (str "Not deleted: Manager (" manager ") has the bots " worker " as its workers. Deal with those first, one at a time with the person: archive or delete the workers, have it drop the schedules and hooks.") (get texts "d2")) "the manager has a worker: refused for that first")
                         (is (= :manager (await (machine/delete! manager))) "and the machine never deletes the manager")
                         (is (= (str "Not deleted: Template 202 (" worker ") has the bots " sub " as its workers. Deal with those first, one at a time with the person: archive or delete the workers, have it drop the schedules and hooks.") (get texts "d3")))
                         (is (= (str "Archived Sub (" sub "): its chat is over, its files and its page stay.") (get texts "a1")))
                         (is (contains? (:archived (machine/current-structure)) sub))
                         (is (= (str "Deleted Sub (" sub "): its chat is hidden, its folder in the trash.") (get texts "d4")))
                         (is (= (str "Deleted Template 202 (" worker "): its chat is hidden, its folder in the trash.") (get texts "d5")))
                         (is (= ["1" manager] (map :id (machine/listed))))
                         (is (= [] (machine/children-of manager)) "a deleted child is none"))))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
