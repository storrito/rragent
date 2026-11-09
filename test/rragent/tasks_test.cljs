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
