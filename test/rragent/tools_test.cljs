(ns rragent.tools-test
  (:require ["node:http" :as http]
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
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]))

(deftest eval-description-test
  (testing "the tool every bot has says how a service of the person's is connected, whatever the bot's definition"
    (is (str/includes? eval-tool/description "(composio/connections [\"googlesheets\"])"))
    (is (str/includes? eval-tool/description "in the same answer in which they name the service"))
    (is (str/includes? eval-tool/description "good for ten minutes"))))

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

(deftest eval-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 home (test-home/make)
                 {:keys [faux models]} (faux-models)
                 program (str "(let [r (call \"echo\" \"/hello\" {:query {:q 1}})]"
                              "  (remember \"seen\" {:path (get-in r [:body :path]) :q (get-in r [:body :query :q])})"
                              "  (println \"status\" (:status r))"
                              "  (count (recall {:event \"seen\"})))")]
             (.setResponses faux #js [(tool-call "eval" {:code program} "e1")
                                      (tool-call "eval" {:code "(def n 5)"} "e2")
                                      (tool-call "eval" {:code "(* n 2)"} "e3")
                                      (tool-call "eval" {:code "(.-env js/process)"} "e4")
                                      (tool-call "recall" {:event "seen"} "r1")
                                      (answer "Done.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux" :gateway url}))]
               (try
                 (is (= "Done." (await (harness/ask agent "Look at the echo."))))
                 (let [results (await (tool-results agent))]
                   (testing "the program's printed output and its value, the call through the gateway, the event seen at once"
                     (is (= "status 200\n=> 1" (result-text (get results "e1")))))
                   (testing "a def stays for the next call"
                     (is (= "=> #'user/n" (result-text (get results "e2"))))
                     (is (= "=> 10" (result-text (get results "e3")))))
                   (testing "the host is out of reach, as an error the model reads"
                     (is (true? (.-isError (get results "e4"))))
                     (is (str/includes? (result-text (get results "e4")) "Unable to resolve symbol: js/process")))
                   (testing "what the program remembered is an event of the bot, recalled by the tool"
                     (is (str/includes? (result-text (get results "r1")) ":path \"/hello\""))
                     (is (str/includes? (result-text (get results "r1")) ":q \"1\""))))
                 (let [all (events/read (home/events-file (home/bot-dir home (.-id (:root agent)))))]
                   (is (= [{:event "seen" :path "/hello" :q "1"}] (mapv #(dissoc % :at) all)) "in the bot's events file"))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (stop)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest plan-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 home (test-home/make)
                 {:keys [faux models]} (faux-models)
                 good (str "(let [notes (map #(storrito/create-note {:text %}) [\"a\" \"b\"])]"
                           "  (println \"two notes\")"
                           "  (into (vec notes) [[:remember \"noted\" {:id [:plan/result 0 :id] :second [:plan/result 1 :text]}] (storrito/flaky-once)]))")
                 bad "[(storrito/create-note {}) (storrito/create-note {:text \"c\"})]"
                 wrong "(storrito/create-note {:text [:plan/result 3 :id]})"
                 query "[[:storrito/generate-uuid {}]]"]
             (.setResponses faux #js [(tool-call "eval" {:code good} "p1")
                                      (tool-call "eval" {:code bad} "p2")
                                      (tool-call "eval" {:code wrong} "p3")
                                      (tool-call "eval" {:code query} "p4")
                                      (answer "Planned.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux" :gateway url}))]
               (try
                 (is (= "Planned." (await (harness/ask agent "Make the notes."))))
                 (let [results (await (tool-results agent))]
                   (testing "the plan ran after the program, in order, the placeholders filled, a 503 tried again"
                     (is (= (str "two notes\n=> a plan of 4 actions\n"
                                 "Plan of 4 actions: 4 done.\n"
                                 "1. storrito/create-note {:text \"a\"}: done {:id \"n_a\", :text \"a\"}\n"
                                 "2. storrito/create-note {:text \"b\"}: done {:id \"n_b\", :text \"b\"}\n"
                                 "3. remember \"noted\" {:id [:plan/result 0 :id], :second [:plan/result 1 :text]}: done \"Noted noted\"\n"
                                 "4. storrito/flaky-once {}: done {:ok true}")
                            (result-text (get results "p1"))))
                     (is (not (true? (.-isError (get results "p1"))))))
                   (testing "the first failure ends the plan, an error result that says what happened"
                     (let [text (result-text (get results "p2"))]
                       (is (str/includes? text "Plan of 2 actions: 0 done, failed at action 1, the rest not run."))
                       (is (str/includes? text "1. storrito/create-note {}: failed: Storrito answered HTTP 400 to create-note:"))
                       (is (str/includes? text "2. storrito/create-note {:text \"c\"}: not run"))
                       (is (true? (.-isError (get results "p2"))))))
                   (testing "a plan that is not right runs not at all"
                     (is (str/includes? (result-text (get results "p3"))
                                        "Nothing of the plan ran, it is not right:\naction 1, storrito/create-note {:text [:plan/result 3 :id]}: the placeholder [:plan/result 3 :id] refers to action 4, which is not before this one"))
                     (is (true? (.-isError (get results "p3"))))
                     (is (str/includes? (result-text (get results "p4")) "storrito/generate-uuid is a query: call it in the program"))
                     (is (true? (.-isError (get results "p4"))))))
                 (testing "the event the plan remembered, with the results of earlier actions in it"
                   (let [all (events/read (home/events-file (home/bot-dir home (.-id (:root agent)))))]
                     (is (= [{:event "noted" :id "n_a" :second "b"}] (mapv #(dissoc % :at) all)))))
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
