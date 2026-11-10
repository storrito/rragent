(ns rragent.tasks-test
  "The tasks of the loop (docs/one-loop.md §2, `rragent.tasks`,
   `rragent.tools.task`): started without waiting, the call answers
   the id and the result arrives in the loop as a message from the
   task, which the loop answers; the task's conversation has a task's
   instructions, tools and the loop's work folder; with `wait`, the
   call answers the result itself and nothing is delivered twice;
   `list` names the tasks."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.machine :as machine]
            [rragent.roles :as roles]
            [rragent.tasks :as tasks]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- ^:async entries
  "The entries of a conversation, newest first, as `{:kind :data}`."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (mapv (fn [e] {:kind (.-kind e)
                   :data (js->clj (.-data e) :keywordize-keys true)
                   :text (when (= "pi.assistant" (.-kind e))
                           (->> (array-seq (.-content (aget (.-model e) 0)))
                                (filter #(= "text" (.-type %)))
                                (map #(.-text %))
                                (apply str)))})
          (array-seq (.-items page)))))

(defn- ^:async results
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(defn- ^:async until
  "Resolves once the promise of `pred` answers true, asked every 50 ms;
   rejects after 15 s."
  [pred]
  (let [started (js/Date.now)]
    (loop []
      (cond
        (await (pred)) true
        (< 15000 (- (js/Date.now) started)) (throw (js/Error. "nothing after 15 s"))
        :else (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                  (recur))))))

(deftest a-task-of-the-loop-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (try
               (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
               (let [loop (machine/loop-id)
                     _ (bots/set-metadata! home loop "model" "faux-1")
                     conversation (:conversation (machine/bot loop))
                     harness (:harness (machine/agent))
                     done-entries (fn [] (-> (entries conversation)
                                             (.then (fn [es] (filterv #(= tasks/done-kind (:kind %)) es)))))]
                 (testing "started without waiting: the id at once, the result later as a message from the task, which the loop answers"
                   (.setResponses faux #js [(tools-test/tool-call "task" {:text "Count the covers in artifacts/content-week." :topic "Content week" :model "faux-1"} "t1")
                                            (tools-test/answer "Started.")
                                            (tools-test/answer "Eight covers.")
                                            (tools-test/answer "Noted: eight.")])
                   (is (= "Started." (await (harness/ask-in conversation "How many covers?"))))
                   (is (str/starts-with? (get (await (results conversation)) "t1") "Started the task ") (get (await (results conversation)) "t1"))
                   (is (str/includes? (get (await (results conversation)) "t1") "for Content week"))
                   (await (until (fn [] (-> (done-entries) (.then (fn [es] (= 1 (count es))))))))
                   (await (.waitForIdle conversation harness/context))
                   (let [[task] (await (tasks/listed harness loop 10 harness/context))
                         [entry] (await (done-entries))
                         es (await (entries conversation))]
                     (is (= :done (:state task)))
                     (is (= "Eight covers." (:line task)))
                     (is (= "Content week" (:topic task)))
                     (is (= (:id task) (:task (:data entry))) "the entry names the task")
                     (is (= {:topic "Content week" :state "done" :line "Eight covers."} (dissoc (:data entry) :task)))
                     (is (= "Noted: eight." (:text (first es))) "the loop answered the result")
                     (testing "the task's conversation: a task's text and tools, the loop's work folder, the text as an entry"
                       (let [child (await (.conversation harness (:conversation task) harness/context))
                             agent (await (.agent child harness/context))
                             child-entries (await (entries child))]
                         (is (str/starts-with? (.-instructions agent) "# Every conversation of this machine"))
                         (is (str/includes? (.-instructions agent) "\n# A task\n"))
                         (is (= (set roles/task-tools) (set (map #(.-name %) (array-seq (.-tools agent))))))
                         (is (= (home/work-dir (home/bot-dir home loop)) (.-cwd agent)) "what it makes lands in the loop's artifacts")
                         (is (= "faux-1" (.. agent -model -modelId)))
                         (is (some #(and (= tasks/input-kind (:kind %)) (= "Content week" (:topic (:data %)))) child-entries))
                         (is (= "Eight covers." (:text (first child-entries)))))))
                   (is (= 0 (.getPendingResponseCount faux)) "every scripted answer used"))
                 (testing "with wait: the call answers the result, nothing is delivered twice"
                   (.setResponses faux #js [(tools-test/tool-call "task" {:text "Say quick." :wait true :model "faux-1"} "t2")
                                            (tools-test/answer "Quick: yes.")
                                            (tools-test/answer "Got it.")])
                   (is (= "Got it." (await (harness/ask-in conversation "Quick check."))))
                   (let [text (get (await (results conversation)) "t2")]
                     (is (str/includes? text "is done. Its result:\n\nQuick: yes.") text))
                   (await (.waitForIdle conversation harness/context))
                   (is (= 1 (count (await (done-entries)))) "no message from the task the call waited for")
                   (let [tasks (await (tasks/listed harness loop 10 harness/context))]
                     (is (= [:done :done] (map :state tasks)))
                     (is (= "Quick: yes." (:line (first tasks))))))
                 (testing "list names the tasks, newest first"
                   (.setResponses faux #js [(tools-test/tool-call "task" {:action "list"} "t3")
                                            (tools-test/answer "Listed.")])
                   (is (= "Listed." (await (harness/ask-in conversation "What runs?"))))
                   (let [text (get (await (results conversation)) "t3")
                         lines (str/split-lines text)]
                     (is (= 2 (count lines)) text)
                     (is (str/includes? (first lines) "done · Say quick. → Quick: yes."))
                     (is (str/includes? (second lines) "done · Content week · Count the covers in artifacts/content-week. → Eight covers.")))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-task-continued-failed-and-stopped-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (try
               (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
               (let [loop (machine/loop-id)
                     _ (bots/set-metadata! home loop "model" "faux-1")
                     conversation (:conversation (machine/bot loop))
                     harness (:harness (machine/agent))
                     done-entries (fn [] (-> (entries conversation)
                                             (.then (fn [es] (filterv #(= tasks/done-kind (:kind %)) es)))))
                     newest (fn [] (-> (tasks/listed harness loop 10 harness/context) (.then first)))]
                 (testing "a task that needs an answer ends with the question as its result"
                   (.setResponses faux #js [(tools-test/tool-call "task" {:text "Pick the cover colour." :model "faux-1"} "c1")
                                            (tools-test/answer "Started.")
                                            (tools-test/answer "Need an answer: blue or red?")
                                            (tools-test/answer "Blue or red, Max?")])
                   (is (= "Started." (await (harness/ask-in conversation "Pick the colour."))))
                   (await (until (fn [] (-> (done-entries) (.then (fn [es] (= 1 (count es))))))))
                   (await (.waitForIdle conversation harness/context)))
                 (let [first-task (await (newest))]
                   (is (= :done (:state first-task)))
                   (testing "continued by its id: the answer goes into the same conversation, which keeps what it knew"
                     (.setResponses faux #js [(tools-test/tool-call "task" {:id (str (:id first-task)) :text "Blue." :model "faux-1"} "c2")
                                              (tools-test/answer "Continued.")
                                              (tools-test/answer "Blue it is.")
                                              (tools-test/answer "Done: blue.")])
                     (is (= "Continued." (await (harness/ask-in conversation "Blue."))))
                     (is (str/starts-with? (get (await (results conversation)) "c2") "Started the task ") (get (await (results conversation)) "c2"))
                     (await (until (fn [] (-> (done-entries) (.then (fn [es] (= 2 (count es))))))))
                     (await (.waitForIdle conversation harness/context))
                     (let [second-task (await (newest))
                           child (await (.conversation harness (:conversation first-task) harness/context))
                           child-entries (await (entries child))]
                       (is (not= (:id first-task) (:id second-task)))
                       (is (= (:conversation first-task) (:conversation second-task)) "the earlier task's conversation")
                       (is (= :done (:state second-task)))
                       (is (= "Blue it is." (:line second-task)))
                       (is (= 2 (count (filter #(= tasks/input-kind (:kind %)) child-entries))) "both texts went in")
                       (is (= "Blue it is." (:text (first child-entries))))
                       (is (= "Done: blue." (:text (first (await (entries conversation))))) "the loop answered the result")))
                   (testing "a task of another conversation, or one that does not exist, cannot be continued"
                     (.setResponses faux #js [(tools-test/tool-call "task" {:id "999999" :text "Go on." :model "faux-1"} "c3")
                                              (tools-test/answer "No such task.")])
                     (is (= "No such task." (await (harness/ask-in conversation "Continue 999999."))))
                     (is (str/includes? (get (await (results conversation)) "c3") "No task 999999 of this conversation")))
                   (testing "a task whose phase fails still reaches the loop, as a failed result"
                     (.setResponses faux #js [(tools-test/answer "That did not run.")])
                     (await (.commit conversation
                                     (fn [tx]
                                       (.createTask tx tasks/Task
                                                    #js {:text "Go on." :continue "999999" :wait false}
                                                    #js {:ownership #js {:kind "conversation"} :conversationId (.-id conversation) :background true}))
                                     harness/context))
                     (await (until (fn [] (-> (done-entries) (.then (fn [es] (= 3 (count es))))))))
                     (await (.waitForIdle conversation harness/context))
                     (let [failed (await (newest))
                           [entry] (await (done-entries))]
                       (is (= :failed (:state failed)))
                       (is (str/starts-with? (:line failed) "did not run: No task 999999") (:line failed))
                       (is (= "failed" (:state (:data entry))))
                       (is (= (:id failed) (:task (:data entry))))
                       (is (= "That did not run." (:text (first (await (entries conversation))))) "the loop was told")))
                   (testing "stop: a task that ended, a task that is not this conversation's"
                     (.setResponses faux #js [(tools-test/tool-call "task" {:action "stop" :id (str (:id first-task))} "c4")
                                              (tools-test/answer "Ended already.")
                                              (tools-test/tool-call "task" {:action "stop" :id "999999"} "c5")
                                              (tools-test/answer "No such task.")])
                     (is (= "Ended already." (await (harness/ask-in conversation "Stop the first one."))))
                     (is (str/includes? (get (await (results conversation)) "c4") "had ended already: done"))
                     (is (= "No such task." (await (harness/ask-in conversation "Stop 999999."))))
                     (is (str/includes? (get (await (results conversation)) "c5") "No task 999999 of this conversation")))))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
