(ns rragent.machine-experiment
  "Live runs of a real model against the machine tool, over the fake
   gateway: not a test of the suite (the name ends in experiment),
   run with its own build profile and OPENROUTER_API_KEY in the
   environment. Prints every machine the model wrote, the host's
   answer, and the model's final answer, for a person to judge."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is]]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def model
  (or (not-empty (aget (.-env js/process) "RRAGENT_EXPERIMENT_MODEL")) "openai/gpt-6.1-sol"))

(def definition
  (str "---\nname: tester\ndescription: Tests the machine tool.\nmetadata:\n  title: \"Tester\"\n  model: " model "\n---\n\n# tester\n\n"
       "You test the machine tool against a fake gateway. Answer briefly, with the facts the machine answered. Your folder is <skill>.\n"))

(def tasks
  [{:name "procedures" :text "How many procedures does the organization's Storrito API have? Name them."}
   {:name "echo" :text "Fetch /hello from the echo connection with the query q=1 and tell me which path the echo answered."}
   {:name "notes" :text "Create notes with the texts a, b and c using the create-note procedure of the Storrito API, one after the other, then remember their ids under the event \"noted\". Tell me the ids."}
   {:name "files" :text "Save the vector [1 2 3 4] as EDN into data/nums.edn. Then, in a second machine, read the file back and tell me the sum of the numbers."}
   {:name "failure" :text "Call the status-instagram-story procedure with no arguments at all and tell me what the API answered, word for word if you can."}
   {:name "composio" :text "Send an email through Composio with the tool GMAIL_SEND_EMAIL to a@example.com with the subject Hi, and tell me whether it was sent."}])

(defn- ^:async run-task!
  "`task` asked of a fresh bot: `{:answer :calls}`, the machine calls
   as `{:code :result :error?}`."
  [url {:keys [text]}]
  (let [home (test-home/make "AGENT.md" definition)
        agent (await (harness/open {:home home :storage (harness/memory-storage) :gateway url}))]
    (try
      (let [answer (await (harness/ask agent text))
            page (await (.entries (:root agent) #js {} 200 js/undefined harness/context))
            items (array-seq (.-items page))
            calls (->> items
                       (filter #(= "pi.assistant" (.-kind %)))
                       (mapcat (fn [entry]
                                 (let [message (aget (.-model entry) 0)]
                                   (keep (fn [block]
                                           (when (= "toolCall" (.-type block))
                                             {:id (.-id block) :tool (.-name block) :code (some-> block .-arguments .-code)}))
                                         (array-seq (.-content message)))))))
            results (await (tools-test/tool-results agent))
            others (->> items
                        (remove #(contains? #{"pi.user" "pi.assistant" "pi.tool-result" "pi.system" "pi.usage"} (.-kind %)))
                        (mapv (fn [entry] (str (.-kind entry) " " (subs (js/JSON.stringify (.-model entry)) 0 400)))))]
        (when (seq others)
          (println (str "---- other entries:\n" (str/join "\n" others))))
        {:answer answer
         :calls (mapv (fn [{:keys [id tool code]}]
                        (let [result (get results id)]
                          {:tool tool :code code
                           :result (when result (tools-test/result-text result))
                           :error? (when result (true? (.-isError result)))}))
                      calls)})
      (finally
        (await (harness/close agent))
        (test-home/remove! home)))))

(defn- report
  [{:keys [name]} {:keys [answer calls]}]
  (println (str "\n################ " name " (" (count calls) " tool calls, " (count (filter :error? calls)) " failed)"))
  (doseq [{:keys [tool code result error?]} calls]
    (println (str "---- " tool (when error? " FAILED")))
    (when code (println code))
    (println (str ">>>> " (subs (str result) 0 (min 1200 (count (str result)))))))
  (println (str "==== answer: " answer)))

(deftest machine-experiment
  (async done
    (-> ((^:async fn []
           (if-not (aget (.-env js/process) "OPENROUTER_API_KEY")
             (println "no OPENROUTER_API_KEY: nothing run")
             ;; without the fake's openrouter connection: the models go to
             ;; OpenRouter itself with the key of the environment
             (let [{:keys [url stop]} (await (fake-gateway/start! {:connections (remove #(= "conn_openrouter" (:id %)) fake-gateway/connections)
                                                                  :available []}))
                   chosen (let [only (aget (.-env js/process) "RRAGENT_EXPERIMENT_TASKS")]
                            (if (seq only) (filter #(str/includes? only (:name %)) tasks) tasks))]
               (aset (.-env js/process) "COMPOSIO_SESSION_ID" "trs_1")
               (try
                 (doseq [task chosen]
                   (report task (await (run-task! url task))))
                 (finally
                   (js-delete (.-env js/process) "COMPOSIO_SESSION_ID")
                   (stop)
                   (sync-fetch/stop!)))))
           (is true)))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
