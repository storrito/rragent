(ns rragent.reload-test
  "The definition read again before every run: a rewrite of the
   AGENT.md between two asks, and the second run has the new
   instructions, the new model and the new name; an unchanged file
   transacts nothing; a schedule's run and a hook's call read the file
   too."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.db :as db]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.reload :as reload]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- instructions-seen
  "The instructions the model got with a request: the `instructions`
   section of the last system message of the context that patches it."
  [context]
  (->> (array-seq (.-messages context))
       (filter #(= "system" (.-role %)))
       (keep #(some-> (.-sections %) (aget "instructions")))
       (last)))

(defn- inputs
  "The texts of the user messages of the context; a schedule's run and
   a hook's call queued behind each other go to the model together."
  [context]
  (for [message (array-seq (.-messages context))
        :when (= "user" (.-role message))
        :let [content (.-content message)]]
    (if (string? content)
      content
      (apply str (map #(.-text %) (array-seq content))))))

(defn- recording
  "A faux answer of `text` that notes what the model saw: `{:instructions
   :inputs}` into `seen`."
  [seen text]
  (fn [context _options _state _model]
    (swap! seen conj {:instructions (instructions-seen context) :inputs (vec (inputs context))})
    (tools-test/answer text)))

(defn- run-with
  "The last request whose inputs have one that starts with `prefix`."
  [seen prefix]
  (last (filter (fn [{:keys [inputs]}] (some #(str/starts-with? % prefix) inputs)) seen)))

(defn- rewrite!
  [home text]
  (fs/writeFileSync (path/join home "AGENT.md") text))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest the-file-is-read-before-every-run-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models {:models [{:id "faux-1" :input ["text" "image"]}
                                                                         {:id "faux-2" :input ["text" "image"]}]})
                 seen (atom [])
                 commits (atom 0)]
             (.setResponses faux #js [(recording seen "One.") (recording seen "Two.") (recording seen "Three.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                   root (:root agent)
                   work (:work agent)
                   pane (db/pane work)
                   unsubscribe (.subscribeCommits (:harness agent) (fn [_ _] (swap! commits inc)))]
               (try
                 (is (= "One." (await (harness/ask agent "First?"))))
                 (is (str/includes? (:instructions (last @seen)) "You watch a page.") "the first run has the file's instructions")
                 (testing "an unchanged file transacts nothing"
                   (let [entries-before (.-length (.-items (await (.entries root #js {} 100 js/undefined harness/context))))
                         commits-before @commits]
                     (is (nil? (await (reload/reload! root work harness/context))))
                     (is (= commits-before @commits) "no commit")
                     (is (= entries-before (.-length (.-items (await (.entries root #js {} 100 js/undefined harness/context))))) "no entry")))
                 (testing "the file rewritten: the next run has the new instructions, model and name"
                   (rewrite! home "---\nname: reviewer\ndescription: Reviews code.\nmetadata:\n  model: faux-2\n---\n\nYou review code in <agent>.\n")
                   (is (= "Two." (await (harness/ask agent "Second?"))))
                   (is (str/includes? (:instructions (last @seen)) (str "You review code in " (path/resolve home) ".")) "the new instructions, the folder filled in")
                   (is (not (str/includes? (:instructions (last @seen)) "You watch a page.")) "the old ones gone")
                   (is (= "faux-2" (.. (await (.agent root harness/context)) -model -modelId)) "the model follows the file")
                   (is (= "reviewer" (:name (reload/current work))))
                   (is (= "reviewer" (get-in @pane [:agent :definition :name])) "the page's copy follows")
                   (is (= "faux-2" (get-in @pane [:agent :definition :model]))))
                 (testing "a file that does not read leaves the agent as it was"
                   (rewrite! home "---\ndescription: nameless now\n---\n\nNobody.\n")
                   (is (nil? (await (reload/reload! root work harness/context))))
                   (is (= "reviewer" (:name (reload/current work))))
                   (is (= "Three." (await (harness/ask agent "Third?"))))
                   (is (str/includes? (:instructions (last @seen)) "You review code") "the run keeps the last definition that read"))
                 (finally
                   (unsubscribe)
                   (await (harness/close agent))
                   (is (nil? (reload/current work)) "forgotten with the close")
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest a-schedule-and-a-hook-read-the-file-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 seen (atom [])]
             ;; the hook first: a tick of the schedule would take the hook's scripted answers
             (.setResponses faux (clj->js (concat [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                                   (tools-test/answer "Made.")
                                                   (tools-test/tool-call "schedule" {:action "set" :name "tick" :cron "* * * * * *" :text "Tick."} "s1")
                                                   (tools-test/answer "Set.")]
                                                  (repeat 30 (recording seen "Tock.")))))
             (binding [sprite/*sprite-env* "no-such-command-anywhere"]
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                 (try
                   (is (= "Made." (await (harness/ask agent "Make a hook for reviews."))))
                   (is (= "Set." (await (harness/ask agent "Every second, tock."))))
                   (testing "a schedule's run has the file as it is then"
                     (rewrite! home (str/replace test-home/definition-text "You watch a page." "You tock loudly."))
                     (await (sleep 2500))
                     (let [run (run-with @seen "Scheduled run")]
                       (is (some? run) "the schedule fired")
                       (is (str/includes? (:instructions run) "You tock loudly."))))
                   (testing "a hook's call too"
                     (rewrite! home (str/replace test-home/definition-text "You watch a page." "You tock softly."))
                     (let [doc (await (.snapshot (:harness agent) hooks/Hooks (.-id (:root agent)) harness/context))
                           {:keys [ok submission]} (await (hooks/receive! agent "reviews" (.. doc -reviews -secret) {} "{\"stars\":5}" harness/context))]
                       (is (true? ok))
                       (await (.wait (await (.submission (:harness agent) submission harness/context)) harness/context))
                       (let [run (run-with @seen "Hook \"reviews\"")]
                         (is (some? run) "the hook's run")
                         (is (str/includes? (:instructions run) "You tock softly.")))))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
