(ns rragent.harness-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
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
