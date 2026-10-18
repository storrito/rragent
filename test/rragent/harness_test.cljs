(ns rragent.harness-test
  (:require [clojure.test :refer [async deftest is testing]]
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
