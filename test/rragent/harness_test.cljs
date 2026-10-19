(ns rragent.harness-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.gateway :as gateway]
            [rragent.harness :as harness]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

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
                 (let [discovery (gateway/discover url)
                       models (harness/openrouter-models discovery)
                       provider (.getProvider models "openrouter")
                       base (str url "/v1/gateway/openrouter/conn_openrouter/v1")
                       auth (await (.getAuth models "openrouter"))]
                   (is (= base (.-baseUrl provider)))
                   (is (= base (.. auth -auth -baseUrl)) "the request's base, as pi-ai applies the resolved auth")
                   (is (= "sprite" (.. auth -auth -apiKey)))
                   (is (= "the Sprites gateway, connection conn_openrouter" (.-source auth)))
                   (is (= (str "OpenRouter through the Sprites gateway (" base ")") (harness/model-route discovery)))
                   (is (seq (.getModels provider)) "OpenRouter's catalog as it is")))
               (testing "without one, OpenRouter itself with the key from the environment"
                 (let [discovery (gateway/discover "http://127.0.0.1:1")
                       provider (.getProvider (harness/openrouter-models discovery) "openrouter")]
                   (is (= "https://openrouter.ai/api/v1" (.-baseUrl provider)))
                   (is (= "OpenRouter with OPENROUTER_API_KEY" (harness/model-route discovery)))))
               (finally
                 (stop)
                 (sync-fetch/stop!))))))
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
                   (is (= "OpenRouter with OPENROUTER_API_KEY" (harness/model-route (codemode/discovery (:codemode agent))))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
