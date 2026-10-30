(ns rragent.harness-test
  (:require ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-durable" :refer [UsageDoc]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
            [rragent.openrouter :as openrouter]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def key-line
  "What the route says without an openrouter connection."
  "OpenRouter with OPENROUTER_API_KEY")

(defn- ^:async with-env
  "`thunk`, a promise of its own, awaited with the environment variable
   `k` as `v` (nil unset), the variable restored after."
  [k v thunk]
  (let [env (.-env js/process)
        before (aget env k)]
    (try
      (if (nil? v) (js-delete env k) (aset env k v))
      (await (thunk))
      (finally
        (if (nil? before) (js-delete env k) (aset env k before))))))

(defn- ^:async sleep
  [ms]
  (await (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

(deftest ask-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Paris.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "watcher" (get-in agent [:definition :name])))
                 (is (= "Paris." (await (harness/ask agent "Capital of France?"))))
                 (testing "the conversation runs with the definition"
                   (let [resolved (await (.agent (:root agent) harness/context))]
                     (is (= "faux-1" (.. resolved -model -modelId)))
                     (is (= (str home "/bots/1/work") (.-cwd resolved)) "the tools work in the first bot's work/, the conversation's own cwd")
                     (is (= (str home "/bots/1/work") (:work agent)))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest sees-images-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "faux-1" "blind-1"))
                 {:keys [models]} (tools-test/faux-models {:models [{:id "blind-1" :input ["text"]}]})
                 agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
             (try
               (is (false? (await (harness/sees-images? (:models agent) (:root agent)))) "a model without image input")
               (await (.configure (:root agent) #js {:model #js {:provider "faux" :modelId "faux-1"}} harness/context))
               (is (true? (await (harness/sees-images? (:models agent) (:root agent)))) "faux-1 takes images")
               (await (.configure (:root agent) #js {:model #js {:provider "faux" :modelId "unknown-9"}} harness/context))
               (is (true? (await (harness/sees-images? (:models agent) (:root agent)))) "a model the catalog does not know counts as one that does")
               (finally
                 (await (harness/close agent))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest context-read-test
  (is (= 442343 (harness/context-read #js {:input 3 :output 6 :cacheRead 0 :cacheWrite 442340})) "what was sent, cached or not, without the answer")
  (is (= 0 (harness/context-read nil)) "no usage, nothing read"))

(deftest context-tokens-test
  (async done
    (-> ((^:async fn []
           (is (= 120000 (await (with-env "RRAGENT_CONTEXT_TOKENS" "120000" #(js/Promise.resolve (harness/context-tokens))))))
           (is (= harness/default-context-tokens (await (with-env "RRAGENT_CONTEXT_TOKENS" "lots" #(js/Promise.resolve (harness/context-tokens))))))
           (is (= harness/default-context-tokens (await (with-env "RRAGENT_CONTEXT_TOKENS" nil #(js/Promise.resolve (harness/context-tokens))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- ^:async compaction-entry
  "The `pi.compaction` entry of the conversation, waited for up to two
   seconds; nil without one."
  [conversation]
  (loop [tries 20]
    (let [view (await (.context conversation harness/context))
          entry (some #(when (= "pi.compaction" (.-kind %)) %) (array-seq (.-entries view)))]
      (if (or entry (zero? tries))
        entry
        (do (await (sleep 100))
            (recur (dec tries)))))))

(defn- ^:async compaction-entry-now
  "The `pi.compaction` entry of the conversation as it is; nil without one."
  [conversation]
  (let [view (await (.context conversation harness/context))]
    (some #(when (= "pi.compaction" (.-kind %)) %) (array-seq (.-entries view)))))

(deftest context-budget-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make "AGENT.md" (str/replace test-home/definition-text "faux-1" "wide-1"))
                 {:keys [faux models]} (tools-test/faux-models {:models [{:id "wide-1" :contextWindow 1050000}]})
                 windows (atom [])
                 asked (atom [])
                 answer (fn [text]
                          (fn [context _options _state model]
                            (swap! windows conj (.-contextWindow model))
                            (swap! asked conj (js/JSON.stringify (.-messages context)))
                            (tools-test/answer text)))
                 ;; some 25,000 tokens each, four characters a token
                 long-text (fn [word] (str/join " " (repeat 25000 word)))]
             (.setResponses faux #js [(answer "Noted.") (answer "Noted again.") (answer "The person sent two long texts.")])
             (await (with-env "RRAGENT_CONTEXT_TOKENS" "40000"
                      (^:async fn []
                        (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                          (try
                            (is (= "Noted." (await (harness/ask agent (long-text "red")))))
                            (await (sleep 200))
                            (is (nil? (await (compaction-entry-now (:root agent)))) "within the budget nothing is compacted")
                            (is (= "Noted again." (await (harness/ask agent (long-text "blue")))))
                            (let [entry (await (compaction-entry (:root agent)))]
                              (is (some? entry) "an answer that read more than the budget compacts the conversation, its model's window far off")
                              (is (str/includes? (js/JSON.stringify entry) "The person sent two long texts.") "the summary is the model's")
                              (is (str/includes? (last @asked) (str "Additional focus: " harness/compaction-focus)) "asked for the pictures' files"))
                            (is (= [1050000 1050000 1050000] @windows) "the model keeps its own window, for the answers and the summary")
                            (finally
                              (await (harness/close agent))
                              (test-home/remove! home))))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest openrouter-route-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))]
             (try
               (testing "with an openrouter connection, the provider goes through the gateway, under /v1, with a placeholder key"
                 (let [codemode (codemode/handle url)
                       models (harness/openrouter-models codemode)
                       provider (.getProvider models "openrouter")
                       base (str url "/v1/gateway/openrouter/conn_openrouter/v1")
                       auth (await (.getAuth models "openrouter"))]
                   (is (= base (.-baseUrl provider)))
                   (is (= base (.. auth -auth -baseUrl)) "the request's base, as pi-ai applies the resolved auth")
                   (is (= "sprite" (.. auth -auth -apiKey)))
                   (is (= "the Sprites gateway, connection conn_openrouter" (.-source auth)))
                   (is (= (str "OpenRouter through the Sprites gateway (" base ")") (openrouter/report (codemode/discovery codemode))))
                   (is (seq (.getModels provider)) "OpenRouter's catalog as it is")))
               (testing "without one, OpenRouter itself with the key from the environment, as on a laptop"
                 (let [codemode (codemode/handle "http://127.0.0.1:1")
                       models (harness/openrouter-models codemode)
                       provider (.getProvider models "openrouter")
                       auth (await (with-env "OPENROUTER_API_KEY" "sk-or-test" #(.getAuth models "openrouter")))]
                   (is (= "https://openrouter.ai/api/v1" (.-baseUrl provider)))
                   (is (= "sk-or-test" (.. auth -auth -apiKey)))
                   (is (nil? (.. auth -auth -baseUrl)) "OpenRouter's own base")
                   (is (= "OPENROUTER_API_KEY" (.-source auth)))
                   (is (str/starts-with? (openrouter/report (codemode/discovery codemode)) key-line))))
               (finally
                 (stop)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest gateway-retries-at-open-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start! nil {:plan ["reset" "reset"]}))
                 home (test-home/make)]
             (try
               (testing "the list answers from its third request: the agent opens with the connectors, the models through the gateway"
                 (let [agent (await (harness/open {:home home :storage (harness/memory-storage) :gateway url :retry {:delays-ms [20 20 20 20]}}))
                       base (str url "/v1/gateway/openrouter/conn_openrouter/v1")]
                   (try
                     (let [{:keys [tries error connections]} (codemode/discovery (:codemode agent))]
                       (is (= 3 tries))
                       (is (nil? error))
                       (is (= 5 (count connections))))
                     (is (str/starts-with? (codemode/report (:codemode agent)) (str "connectors at " url " after 3 tries: ")))
                     (is (= base (.-baseUrl (.getProvider (:models agent) "openrouter"))))
                     (is (= base (.. (await (.getAuth (:models agent) "openrouter")) -auth -baseUrl)))
                     (finally
                       (await (harness/close agent))))))
               (finally
                 (stop)
                 (test-home/remove! home)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest gateway-up-later-test
  (async done
    (-> ((^:async fn []
           (let [port (await (fake-gateway/free-port))
                 url (str "http://127.0.0.1:" port)
                 home (test-home/make)
                 started (js/Date.now)
                 agent (await (harness/open {:home home :storage (harness/memory-storage) :gateway url :retry {:delays-ms [1000 1000]}}))
                 models (:models agent)
                 fake (atom nil)]
             (try
               (testing "nothing listens when the agent opens: one try, no wait, the key"
                 (let [{:keys [tries error]} (codemode/discovery (:codemode agent))]
                   (is (= 1 tries))
                   (is (str/includes? (str error) "ECONNREFUSED"))
                   (is (> 1000 (- (js/Date.now) started)))
                   (is (str/includes? (codemode/report (:codemode agent)) "ECONNREFUSED"))
                   (is (not (str/includes? (codemode/report (:codemode agent)) "tries")))
                   (is (= "https://openrouter.ai/api/v1" (.-baseUrl (.getProvider models "openrouter"))))
                   (is (str/starts-with? (openrouter/report (codemode/discovery (:codemode agent))) key-line))))
               (testing "the gateway comes up: the first model call after it goes through the gateway, and the REPL's worker has the connectors"
                 (reset! fake (await (fake-gateway/start! nil {:port port})))
                 (let [base (str url "/v1/gateway/openrouter/conn_openrouter/v1")
                       auth (await (.getAuth models "openrouter"))]
                   (is (= base (.. auth -auth -baseUrl)))
                   (is (= "sprite" (.. auth -auth -apiKey)))
                   (is (= base (.-baseUrl (.getProvider models "openrouter"))))
                   (is (= 5 (count (:connections (codemode/discovery (:codemode agent))))) "the REPL has the list")
                   (is (= 3 (count (get-in (codemode/snapshot (:codemode agent)) [:storrito :catalog :procedures]))) "and the Storrito catalog")
                   (is (= (str "OpenRouter through the Sprites gateway (" base ")") (openrouter/report (codemode/discovery (:codemode agent)))))
                   (is (= {:text "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" :pending []}
                          (await (codemode-worker/evaluate! (codemode-worker/of (:codemode agent) (.-id (:root agent))) {:code "(storrito/generate-uuid)" :events [] :env {}})))
                       "a worker of a bot started now works from the late discovery")))
               (finally
                 (await (harness/close agent))
                 (when-let [{:keys [stop]} @fake] (stop))
                 (test-home/remove! home)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest late-gateway-model-call-test
  (async done
    (-> ((^:async fn []
           (let [port (await (fake-gateway/free-port))
                 url (str "http://127.0.0.1:" port)
                 codemode (codemode/handle url)
                 {:keys [faux]} (tools-test/faux-models)
                 seen (atom [])
                 answer (fn [text]
                          (fn [_context _options _state request-model]
                            (swap! seen conj (.-baseUrl request-model))
                            (tools-test/answer text)))
                 models (doto (createModels) (.setProvider (harness/gateway-provider (.-provider faux) codemode 300)))
                 home (test-home/make)
                 fake (atom nil)]
             (.setResponses faux #js [(answer "Own.") (answer "Still own.") (answer "Through.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (testing "without a gateway the provider's own auth, the faux's base"
                   (is (= "Own." (await (harness/ask agent "One?"))))
                   (is (= ["http://localhost:0"] @seen)))
                 (testing "the gateway comes up: not asked again within the interval, then the next model request goes through it"
                   (reset! fake (await (fake-gateway/start! nil {:port port})))
                   (is (= "Still own." (await (harness/ask agent "Two?"))))
                   (is (= "http://localhost:0" (last @seen)) "asked less than the interval ago")
                   (await (sleep 300))
                   (is (= "Through." (await (harness/ask agent "Three?"))))
                   (is (= (str url "/v1/gateway/openrouter/conn_openrouter/v1") (last @seen)))
                   (is (= 5 (count (:connections (codemode/discovery codemode)))) "the REPL's discovery is the refreshed one"))
                 (finally
                   (await (harness/close agent))
                   (when-let [{:keys [stop]} @fake] (stop))
                   (test-home/remove! home)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest without-gateway-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Here.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"
                                               :gateway "http://127.0.0.1:1"}))]
               (try
                 (testing "no sprite around: the agent opens and answers, the REPL has no connectors and says why"
                   (is (= "Here." (await (harness/ask agent "Anyone?"))))
                   (let [{:keys [connections error]} (codemode/discovery (:codemode agent))]
                     (is (= [] connections))
                     (is (string? error)))
                   (is (str/starts-with? (codemode/report (:codemode agent)) "no gateway at http://127.0.0.1:1"))
                   (is (= 1 (:tries (codemode/discovery (:codemode agent)))) "a port that refuses at once is tried once")
                   (is (str/starts-with? (openrouter/report (codemode/discovery (:codemode agent))) key-line)))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest usage-cost-test
  (is (= 3.5 (harness/usage-cost #js {:models #js {"openrouter/m" #js {:cost #js {:total 3}}}
                                      :tools #js {"image" #js {:cost #js {:total 0.5}}}}))
      "the models' and the tools' buckets")
  (is (= 0 (harness/usage-cost nil))))

(deftest spend-test
  (async done
    (-> ((^:async fn []
           (testing "a conversation and every one it owns, theirs too"
             (let [usage {1 3, 5 1, 9 0.25, 12 0.5}
                   children {1 [5 12], 5 [9], 9 [], 12 []}
                   fake #js {:snapshot (fn [_doc id _context]
                                         (js/Promise.resolve (when-let [cost (usage id)]
                                                               #js {:models #js {"m" #js {:cost #js {:total cost}}} :tools #js {}})))
                             :commit (fn [change _context]
                                       (js/Promise.resolve
                                        (change #js {:scanConversations (fn [query _limit _cursor]
                                                                          #js {:items (into-array (map (fn [id] #js {:id id})
                                                                                                       (children (.-ownerConversationId query))))})})))}]
               (is (= 4.75 (await (harness/spend fake 1))))))
           (testing "through pi-durable's storage: the ledger of a conversation that owns none"
             (let [home (test-home/make)
                   {:keys [faux models]} (tools-test/faux-models)]
               (.setResponses faux #js [(tools-test/answer "Paris.")])
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                     id (.-id (:root agent))]
                 (try
                   (await (harness/ask agent "Capital of France?"))
                   (is (= [] (await (harness/owned (:harness agent) id))) "no subagent, no run")
                   (is (some? (await (.snapshot (:harness agent) UsageDoc id harness/context))) "the answer noted in pi.usage")
                   (is (= 0 (await (harness/spend (:harness agent) id))) "the faux model costs nothing")
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
