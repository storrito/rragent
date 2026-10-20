(ns rragent.harness-test
  (:require ["@earendil-works/pi-ai/models" :refer [createModels]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
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
                     (is (= (str home "/work") (.-cwd resolved)) "the tools work in work/")))
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
               (is (false? (await (harness/sees-images? agent))) "a model without image input")
               (await (.configure (:root agent) #js {:model #js {:provider "faux" :modelId "faux-1"}} harness/context))
               (is (true? (await (harness/sees-images? agent))) "faux-1 takes images")
               (await (.configure (:root agent) #js {:model #js {:provider "faux" :modelId "unknown-9"}} harness/context))
               (is (true? (await (harness/sees-images? agent))) "a model the catalog does not know counts as one that does")
               (finally
                 (await (harness/close agent))
                 (test-home/remove! home))))))
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
                   (is (= (str "OpenRouter through the Sprites gateway (" base ")") (harness/model-route (codemode/discovery codemode))))
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
                   (is (str/starts-with? (harness/model-route (codemode/discovery codemode)) key-line))))
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
                   (is (str/starts-with? (harness/model-route (codemode/discovery (:codemode agent))) key-line))))
               (testing "the gateway comes up: the first model call after it goes through the gateway, and the REPL's worker has the connectors"
                 (reset! fake (await (fake-gateway/start! nil {:port port})))
                 (let [base (str url "/v1/gateway/openrouter/conn_openrouter/v1")
                       auth (await (.getAuth models "openrouter"))]
                   (is (= base (.. auth -auth -baseUrl)))
                   (is (= "sprite" (.. auth -auth -apiKey)))
                   (is (= base (.-baseUrl (.getProvider models "openrouter"))))
                   (is (= 5 (count (:connections (codemode/discovery (:codemode agent))))) "the REPL has the list")
                   (is (= 3 (count (get-in (codemode/snapshot (:codemode agent)) [:storrito :catalog :procedures]))) "and the Storrito catalog")
                   (is (= (str "OpenRouter through the Sprites gateway (" base ")") (harness/model-route (codemode/discovery (:codemode agent)))))
                   (is (= {:text "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" :pending []}
                          (await (codemode-worker/evaluate! (:worker agent) {:code "(storrito/generate-uuid)" :facts [] :env {}})))
                       "a worker started now works from the late discovery")))
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
                   (is (str/starts-with? (harness/model-route (codemode/discovery (:codemode agent))) key-line)))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
