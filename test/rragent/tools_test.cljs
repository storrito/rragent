(ns rragent.tools-test
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/faux" :refer [fauxAssistantMessage fauxProvider fauxText fauxToolCall]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.events :as events]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.secrets :as secrets]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools.machine :as machine-tool]
            [rragent.tools.fetch :as fetch-tool]))

(deftest machine-description-test
  (testing "the tool every bot has says how a service of the person's is connected, whatever the bot's definition"
    (is (str/includes? machine-tool/description "(composio/connections [\"googlesheets\"]) under :next"))
    (is (str/includes? machine-tool/description "in the same answer in which they name the service"))
    (is (str/includes? machine-tool/description "good for ten minutes"))
    (is (str/includes? machine-tool/description "Nothing runs inside the program; a call is data"))))

(defn faux-models
  "The faux provider playing the model, its answers set with `set-responses!`;
   `options` as `fauxProvider` takes them, `:models` for models beside
   `faux-1`."
  ([] (faux-models nil))
  ([options]
   (let [faux (fauxProvider (if options (clj->js options) js/undefined))
         models (doto (createModels) (.setProvider (.-provider faux)))]
     {:faux faux :models models})))

(defn tool-call
  [name args id]
  (fauxAssistantMessage #js [(fauxToolCall name (clj->js args) #js {:id id})] #js {:stopReason "toolUse"}))

(defn answer
  [text]
  (fauxAssistantMessage #js [(fauxText text)]))

(deftest events-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (faux-models)]
             (.setResponses faux #js [(tool-call "remember" {:event "review-posted" :data {:id 4711 :stars 5}} "r1")
                                      (tool-call "remember" {:event "review-posted" :data {:id 4712 :stars 4}} "r2")
                                      (tool-call "remember" {:event "price-checked" :data {:eur 180}} "r3")
                                      (tool-call "recall" {:code "(->> events (filter #(= \"review-posted\" (:event %))) (map :id) set)"} "q1")
                                      (tool-call "recall" {:event "price-checked"} "q2")
                                      (answer "Noted.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Noted." (await (harness/ask agent "Remember the reviews."))))
                 (let [all (events/read (home/events-file (home/bot-dir home (.-id (:root agent)))))]
                   (is (= ["review-posted" "review-posted" "price-checked"] (mapv :event all)) "three events in the bot's file, oldest first")
                   (is (= 4711 (:id (first all))))
                   (is (every? #(number? (:at %)) all)))
                 (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                       results (->> (array-seq (.-items page))
                                    (filter #(= "pi.tool-result" (.-kind %)))
                                    (map #(js/JSON.stringify (.-model %))))]
                   (is (some #(str/includes? % "#{4711 4712}") results) "the projection the model wrote, over the events")
                   (is (some #(str/includes? % ":eur 180") results) "the events of one kind, as they are"))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest fetch-test
  (async done
    (let [server (http/createServer (fn [req res]
                                      (.setHeader res "Content-Type" "text/plain")
                                      (.end res (str "hello from " (.-url req)))))]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (let [port (.-port (.address server))
                   {:keys [status headers text truncated]} (await (fetch-tool/request {:url (str "http://127.0.0.1:" port "/page")}))]
               (is (= 200 status))
               (is (= "text/plain" (get headers "content-type")))
               (is (= "hello from /page" text))
               (is (false? truncated)))
             (testing "only http and https"
               (let [e (try (await (fetch-tool/request {:url "file:///etc/passwd"})) nil (catch :default e e))]
                 (is (some? e))
                 (is (str/includes? (.-message e) "http or https"))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))

(defn ^:async tool-results
  "The tool results of the root conversation by call id, as the model
   got them."
  [agent]
  (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) r]))))))

(defn result-text
  [result]
  (apply str (map #(.-text %) (array-seq (.-content result)))))

(deftest machine-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 home (test-home/make)
                 {:keys [faux models]} (faux-models)
                 one-shot "{:next {:call (call \"echo\" \"/hello\" {:query {:q 1}}) :as :r}}"
                 loop-program (str "(defn step [s]"
                                   "  (let [s (if (contains? s :last) (-> s (update :ids conj (:id (:last s))) (dissoc :last)) s)]"
                                   "    (cond"
                                   "      (seq (:todo s)) (assoc s :todo (rest (:todo s)) :next {:call (storrito/create-note {:text (first (:todo s))}) :as :last})"
                                   "      (not (:noted s)) (assoc s :noted true :next {:call (remember \"noted\" {:ids (:ids s)}) :as :note})"
                                   "      :else (assoc s :done (count (:ids s))))))"
                                   "{:todo [\"a\" \"b\"] :ids []}")
                 stuck "{:next {:call (storrito/status-instagram-story {}) :as :r}}"
                 handled (str "(defn step [s] (if (:error (:r s)) (assoc s :done :skipped) s))"
                              "{:next {:call (storrito/status-instagram-story {}) :as :r}}")
                 files (str "(defn step [s] (case (:phase s)"
                            "  :write (assoc s :phase :read :next {:call (slurp \"data/n.edn\") :as :text})"
                            "  :read (assoc s :done (* 2 (:n (edn/read-string (:text s)))))))"
                            "{:phase :write :next {:call (spit \"data/n.edn\" {:n 5}) :as :path}}")
                 to-file "{:next {:call (call \"echo\" \"/bytes\" {:to-file \"data/echo.json\"}) :as :r}}"
                 recall "{:next {:call (recall {:event \"noted\"}) :as :events}}"
                 no-map "(+ 1 2)"
                 help "(help)"]
             (.setResponses faux #js [(tool-call "machine" {:code one-shot} "m1")
                                      (tool-call "machine" {:code loop-program} "m2")
                                      (tool-call "machine" {:code stuck} "m3")
                                      (tool-call "machine" {:code handled} "m4")
                                      (tool-call "machine" {:code files} "m5")
                                      (tool-call "machine" {:code to-file} "m6")
                                      (tool-call "machine" {:code recall} "m7")
                                      (tool-call "machine" {:code no-map} "m8")
                                      (tool-call "machine" {:code help} "m9")
                                      (answer "Done.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux" :gateway url}))]
               (try
                 (is (= "Done." (await (harness/ask agent "Run the machines."))))
                 (let [results (await (tool-results agent))
                       text (fn [id] (result-text (get results id)))]
                   (testing "a one-shot read: the call made by the host, its answer under :as, the state shown"
                     (let [t (text "m1")]
                       (is (str/starts-with? t "After 1 call.\nState: {:r {:status 200, :headers {"))
                       (is (str/includes? t ":content-type \"application/json\""))
                       (is (str/includes? t ":body {:id \"conn_echo\", :method \"GET\", :path \"/hello\", :query {:q \"1\"}"))))
                   (testing "a step machine: one call per step, the loop over the rows, the event noted, done"
                     (is (= "After 3 calls.\nDone: 2\nState: {:todo (), :ids [\"n_a\" \"n_b\"], :noted true, :note \"Noted noted\"}"
                            (text "m2"))))
                   (testing "a failed call the machine does nothing about: an error result that says so"
                     (let [t (text "m3")]
                       (is (str/starts-with? t "The call under :r failed and the machine neither handled it nor asked for anything else."))
                       (is (str/includes? t ":error \"Storrito answered HTTP 400 to status-instagram-story"))
                       (is (str/includes? t ":status 400"))
                       (is (true? (.-isError (get results "m3"))))))
                   (testing "a failed call the step handles"
                     (is (= "After 1 call.\nDone: :skipped\nState: {:r {:error \"Storrito answered HTTP 400 to status-instagram-story: {\\\"errorMessage\\\":\\\"invalid params for procedure: status-instagram-story\\\",\\\"validationErrorExplanation\\\":{\\\"storyPostUuid\\\":[\\\"missing required key\\\"]}}\", :status 400, :body {:errorMessage \"invalid params for procedure: status-instagram-story\", :validationErrorExplanation {:storyPostUuid [\"missing required key\"]}}}}"
                            (text "m4")))
                     (is (not (true? (.-isError (get results "m4"))))))
                   (testing "spit and slurp: a file of the bot's work folder between steps"
                     (is (= "After 2 calls.\nDone: 10\nState: {:phase :read, :path \"data/n.edn\", :text \"{:n 5}\"}" (text "m5")))
                     (is (= "{:n 5}" (fs/readFileSync (str (:work agent) "/data/n.edn") "utf8"))))
                   (testing ":to-file: the body goes to a file, the state gets the file"
                     (let [t (text "m6")]
                       (is (str/includes? t ":file \"data/echo.json\""))
                       (is (str/includes? t ":status 200"))
                       (is (not (str/includes? t ":body")))
                       (is (str/includes? (fs/readFileSync (str (:work agent) "/data/echo.json") "utf8") "\"path\":\"/bytes\""))))
                   (testing "recall: the events as data"
                     (is (str/starts-with? (text "m7") "After 1 call.\nState: {:events [{:ids [\"n_a\" \"n_b\"], :event \"noted\", :at ")))
                   (testing "a program whose value is no map is refused"
                     (is (str/includes? (text "m8") "A program's value is a map, the state of the machine"))
                     (is (true? (.-isError (get results "m8")))))
                   (testing "(help) is a machine that runs nothing"
                     (is (str/starts-with? (text "m9") "State: {:help \"A machine: Clojure with the organization's connectors as calls"))))
                 (let [all (events/read (home/events-file (home/bot-dir home (.-id (:root agent)))))]
                   (is (= [{:event "noted" :ids ["n_a" "n_b"]}] (mapv #(dissoc % :at) all)) "in the bot's events file"))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (stop)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest redaction-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (faux-models)
                 file (secrets/file home)]
             (secrets/save! file "STORRITO_TOKEN" "sk_live_0123456789")
             (.setResponses faux #js [(tool-call "bash" {:command (str "cat " file "; echo; echo plain")} "b1")
                                      (tool-call "bash" {:command "echo sk_live_0123456789 >&2; exit 3"} "b2")
                                      (answer "Done.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Done." (await (harness/ask agent "Show me the secrets."))))
                 (let [results (await (tool-results agent))
                       read (result-text (get results "b1"))
                       failed (result-text (get results "b2"))]
                   (testing "a secret's value in a tool's output reaches the model as its name"
                     (is (str/includes? read "\"STORRITO_TOKEN\": \"«STORRITO_TOKEN»\""))
                     (is (str/includes? read "plain"))
                     (is (not (str/includes? read "sk_live_0123456789"))))
                   (testing "a failing call's output too"
                     (is (str/includes? failed "«STORRITO_TOKEN»"))
                     (is (not (str/includes? failed "sk_live_0123456789")))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn ^:async wait-for
  "`ready?` asked every 50 ms until it answers true, at most `ms`:
   whether it did."
  [ready? ms]
  (let [until (+ (js/Date.now) ms)]
    (loop []
      (cond (ready?) true
            (> (js/Date.now) until) false
            :else (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                      (recur))))))

(deftest machine-resume-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 home (test-home/make)
                 ;; a write, a slow read, a write: the agent closes while
                 ;; the read is in flight
                 program (str "(defn step [s] (case (:phase s)"
                              "  :start (assoc s :phase :slow :next {:call (remember \"started\" {:n 1}) :as :started})"
                              "  :slow (assoc s :phase :finish :next {:call (call \"echo\" \"/slow\" {:query {:delay 4000}}) :as :slow})"
                              "  :finish (assoc s :phase :end :next {:call (remember \"finished\" {:n 2}) :as :finished})"
                              "  :end (assoc s :done (:status (:slow s)))))"
                              "{:phase :start}")
                 events-of (fn [agent]
                             (let [file (home/events-file (home/bot-dir home (.-id (:root agent))))]
                               (if (fs/existsSync file) (mapv :event (events/read file)) [])))
                 draft #js {:type "input" :content "Run the machine." :requestId "resume-1"}]
             (try
               (testing "closed in the middle of a machine: the state kept with the calls made so far"
                 (let [{:keys [faux models]} (faux-models)
                       _ (.setResponses faux #js [(tool-call "machine" {:code program} "m1")])
                       agent (await (harness/open {:home home :models models :provider "faux" :gateway url}))]
                   (await (harness/submit (:root agent) draft))
                   (is (await (wait-for #(= ["started"] (events-of agent)) 15000)) "the first call, a write, is done; the second, a slow read, is in flight")
                   (await (harness/close agent))
                   (is (= ["started"] (events-of agent)) "closed while the read was in flight, the machine unfinished")))
               (testing "opened again: the machine goes on where it was, the write before the close not made again"
                 (let [{:keys [faux models]} (faux-models)
                       _ (.setResponses faux #js [(answer "Resumed.")])
                       agent (await (harness/open {:home home :models models :provider "faux" :gateway url}))]
                   (try
                     (let [submission (await (harness/submit (:root agent) draft))
                           settled (await (.wait submission harness/context))]
                       (is (= "done" (.-status settled)) "the same submission, by its request id, answered")
                       (let [t (result-text (get (await (tool-results agent)) "m1"))]
                         (is (str/starts-with? t "After 3 calls.\nDone: 200\n") t)
                         (is (str/includes? t ":started \"Noted started\"") "the answer of the call before the close, from its nested call")
                         (is (str/includes? t ":finished \"Noted finished\"")))
                       (is (= ["started" "finished"] (events-of agent)) "each write once"))
                     (finally
                       (await (harness/close agent))))))
               (finally
                 (test-home/remove! home)
                 (stop)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
