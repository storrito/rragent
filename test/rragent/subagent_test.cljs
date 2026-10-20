(ns rragent.subagent-test
  "The subagent tool through a tool-calling turn of the faux model: the
   agent hands a task over, the subagent answers in a conversation of
   its own, and the answer is the call's result."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.tools.subagent :as subagent-tool]))

(deftest instructions-test
  (is (= (str "Watch it.\n\n" subagent-tool/note) (subagent-tool/instructions "Watch it.")))
  (is (= subagent-tool/note (subagent-tool/instructions nil))))

(defn- request-text
  "The whole request the faux model got, its system messages with the
   prompt and the tools among them, as JSON."
  [context]
  (js/JSON.stringify (.-messages context)))

(deftest subagent-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 requests (atom [])
                 answering (fn [text]
                             (fn [context]
                               (swap! requests conj (request-text context))
                               (tools-test/answer text)))]
             (.setResponses faux #js [(fn [context]
                                        (swap! requests conj (request-text context))
                                        (tools-test/tool-call "subagent" {:task "Count the words of work/notes.txt."} "s1"))
                                      (answering "The file has 42 words.")
                                      (answering "It has 42 words.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "It has 42 words." (await (harness/ask agent "How long are my notes?"))))
                 (let [[parent child after] @requests]
                   (testing "the agent is offered the tool, the subagent is not"
                     (is (str/includes? parent "\"subagent\""))
                     (is (not (str/includes? child "\"subagent\""))))
                   (testing "the subagent has the agent's instructions, the note after them, and the task as its message"
                     (is (str/includes? child "You watch a page."))
                     (is (str/includes? child "You are a subagent"))
                     (is (str/includes? child "Count the words of work/notes.txt."))
                     (is (not (str/includes? child "How long are my notes?")) "nothing else of the conversation"))
                   (testing "the subagent's answer is the call's result"
                     (is (str/includes? after "The file has 42 words."))))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
