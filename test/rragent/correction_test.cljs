(ns rragent.correction-test
  "A dictated piece corrected with the agent's context: the request is
   the conversation's messages with one asking for the piece after
   them, in the conversation's provider session; an answer that does
   not look like the piece is refused."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.correction :as correction]))

(def words
  [{:word "Hi" :confidence 0.99}
   {:word "the" :confidence 0.97}
   {:word "Stuito" :confidence 0.21}
   {:word "editor." :confidence 0.95}])

(deftest marked-test
  (is (= "Hi the [[Stuito]] editor." (correction/marked words)))
  (testing "a word without a confidence is not marked"
    (is (= "a b" (correction/marked [{:word "a"} {:word "b" :confidence nil}])))))

(deftest prompt-test
  (let [text (correction/prompt words "Bitte:")]
    (is (str/includes? text "<part>\nHi the [[Stuito]] editor.\n</part>"))
    (is (str/includes? text "<before>\nBitte:\n</before>")))
  (is (not (str/includes? (correction/prompt words "  ") "<before>"))))

(deftest accepted-test
  (is (= "Hi the Storrito editor." (correction/accepted words "Hi the Storrito editor.")))
  (testing "leftover brackets and tags go"
    (is (= "Hi the Storrito editor." (correction/accepted words "<part>Hi the [[Storrito]] editor.</part>"))))
  (testing "an answer instead of the piece is refused"
    (is (nil? (correction/accepted words "Sure! I fixed the transcript for you: the Storrito editor is the tool that edits stories.")))
    (is (nil? (correction/accepted words "OK")))
    (is (nil? (correction/accepted words nil)))))

(deftest answer-text-test
  (is (= "Hi" (correction/answer-text #js {:content #js [#js {:type "thinking" :thinking "hm"}
                                                         #js {:type "text" :text " Hi "}]})))
  (is (nil? (correction/answer-text #js {:content #js [#js {:type "text" :text "I'll look"}
                                                       #js {:type "toolCall" :name "read"}]}))))

(defn- fake-agent
  "A conversation of two messages in provider session s1, whose models
   answer `text`; the request one got lands in `seen`."
  [seen text]
  (let [context #js [#js {:role "system" :content "You are the agent."}
                     #js {:role "user" :content #js [#js {:type "text" :text "Edit the Storrito story."}]}]]
    {:root #js {:id "c1"
                :agent (fn [_] (js/Promise.resolve #js {:model #js {:provider "openrouter" :modelId "openai/m"}}))
                :context (fn [_] (js/Promise.resolve #js {:messages context}))}
     :harness #js {:snapshot (fn [_doc id _] (js/Promise.resolve (when (= "c1" id) #js {:sessionId "s1"})))}
     :models #js {:getModel (fn [provider id] #js {:provider provider :id id})
                  :completeSimple (fn [model request options]
                                    (reset! seen {:model model :request request :options options})
                                    (js/Promise.resolve #js {:stopReason "stop"
                                                             :content #js [#js {:type "text" :text text}]
                                                             :usage #js {:input 1200 :cacheRead 1024}}))}}))

(deftest correct-test
  (async done
    (-> ((^:async fn []
           (let [seen (atom nil)
                 {:keys [text usage]} (await (correction/correct! (fake-agent seen "Hi the Storrito editor.") words "Bitte:" nil))
                 {:keys [model request options]} @seen
                 messages (.-messages request)]
             (is (= "Hi the Storrito editor." text))
             (is (= 1024 (:cacheRead usage)))
             (is (= correction/model-id (.-id model)) "the correction's model, at the conversation's provider")
             (is (= 3 (.-length messages)) "the conversation's messages, then the one asking")
             (is (= "You are the agent." (.-content (aget messages 0))))
             (is (str/includes? (.-text (aget (.-content (aget messages 2)) 0)) "[[Stuito]]"))
             (is (= "s1" (.-sessionId options)) "the conversation's provider session")
             (testing "an answer that is no correction leaves the piece"
               (is (nil? (:text (await (correction/correct! (fake-agent (atom nil) "Done, I fixed it and also planned your story for tomorrow at nine.") words "" nil)))))))))
         (.catch (fn [e] (is (nil? e) (str e))))
         (.finally done))))
