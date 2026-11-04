(ns rragent.status-test
  "The status of a bot: the document `rragent.status`, initial for a
   bot nothing was read for, stale when the chat has entries past it;
   the manager's `bot status`, which reads a worker's chat since the
   entry the status covers, asks the status model in a conversation
   of the call's own, writes the document and refreshes the bot's
   map; nothing read and no model asked for a bot whose chat has
   nothing new; an answer that is no status leaves the status as it
   was; the action is the manager's alone."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.bots-test :as bots-test]
            [rragent.harness :as harness]
            [rragent.machine :as machine]
            [rragent.status :as status]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(deftest initial-and-stale-test
  (is (= {:summary "" :needs [] :version nil :as-of-entry 0 :at 0} status/initial))
  (is (false? (status/stale? status/initial nil)) "a chat without entries")
  (is (true? (status/stale? status/initial 1)))
  (is (false? (status/stale? {:as-of-entry 12} 12)) "read up to the newest entry")
  (is (true? (status/stale? {:as-of-entry 12} 13))))

(deftest parse-test
  (is (= {:summary "Made v1." :needs ["Approve v1"] :version "v1"}
         (status/parse "{\"summary\": \"Made v1.\", \"needs\": [\"Approve v1\"], \"version\": \"v1\"}")))
  (is (= {:summary "Made v1." :needs [] :version nil}
         (status/parse "```json\n{\"summary\": \"Made v1.\", \"needs\": [], \"version\": null}\n```"))
      "a code fence around it")
  (is (= {:summary "x" :needs [] :version nil} (status/parse "Here it is: {\"summary\": \"x\"} done.")) "prose around the object")
  (is (= {:summary "x" :needs ["a" "3"] :version nil} (status/parse "{\"summary\": \"x\", \"needs\": [\"a\", \"\", 3]}")) "an empty need dropped, a number kept as text")
  (is (nil? (status/parse "I cannot tell.")))
  (is (nil? (status/parse "{\"needs\": []}")) "without a summary"))

(deftest prompt-test
  (is (= "The status as it was:\n(none yet)\n\nThe chat since then:\n[3] Person: Hi" (status/prompt status/initial "[3] Person: Hi")))
  (is (= "The status as it was:\nv1 is built.\nWaiting for the person: Approve v1; Pick a color\nVersion: v1\n\nThe chat since then:\n(nothing)"
         (status/prompt {:summary "v1 is built." :needs ["Approve v1" "Pick a color"] :version "v1"} ""))))

(defn- ^:async results
  "The texts of the tool results of a conversation, by call id."
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(deftest bot-status-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 model-before status/*model*]
             (fs/writeFileSync (bots/assistant-file home) bots-test/assistant)
             (set! status/*model* "faux-1")
             (try
               (let [[watcher] (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
                     manager (machine/manager-id)
                     _ (bots/set-metadata! home manager "model" "faux-1")
                     conversation (:conversation (machine/bot manager))
                     harness (:harness (machine/agent))]
                 (testing "at the open: the initial status in the document, the bot's map and the list"
                   (is (= status/initial (await (status/snapshot harness manager))))
                   (is (= status/initial (:status (machine/bot manager))))
                   (is (= status/initial (:status (first (machine/listed))))))
                 (.setResponses faux #js [(tools-test/tool-call "bot" {:action "create" :name "Template 202" :instructions "Build the 202 template." :project "Guest reviews"} "c1")
                                          (tools-test/answer "Made.")])
                 (is (= "Made." (await (harness/ask-in conversation "Make the 202 worker."))))
                 (let [worker (first (machine/children-of manager))
                       worker-conversation (:conversation (machine/bot worker))]
                   (testing "the person chats with the worker: its chat is stale"
                     (is (= status/initial (:status (machine/bot worker))))
                     (.setResponses faux #js [(tools-test/answer "Hallo, v1 ist gebaut. Passt die Farbe?")])
                     (is (= "Hallo, v1 ist gebaut. Passt die Farbe?" (await (harness/ask-in worker-conversation "Bau die 202."))))
                     (is (number? (machine/newest-entry worker)))
                     (is (status/stale? (:status (machine/bot worker)) (machine/newest-entry worker))))
                   (testing "the manager's bot status: the status model reads the worker's chat, the document and the map hold what it answered, the result names the bot"
                     (let [requests (atom [])]
                       (.setResponses faux #js [(tools-test/tool-call "bot" {:action "status"} "s1")
                                                (fn [context options _state model]
                                                  (swap! requests conj {:text (js/JSON.stringify (.-messages context))
                                                                        :tools (array-seq (or (some-> options .-tools) #js []))
                                                                        :model (.-id model)})
                                                  (tools-test/answer "```json\n{\"summary\": \"v1 der Vorlage 202 ist gebaut.\", \"needs\": [\"Passt die Farbe?\"], \"version\": \"v1\"}\n```"))
                                                (tools-test/answer "Read.")])
                       (is (= "Read." (await (harness/ask-in conversation "Where do things stand?"))))
                       (let [texts (await (results conversation))
                             newest (machine/newest-entry worker)
                             written (await (status/snapshot harness worker))
                             {:keys [text tools model]} (first @requests)]
                         (is (= (str "Template 202 (" worker "): v1 der Vorlage 202 ist gebaut. · version v1 · needs: Passt die Farbe?") (get texts "s1")))
                         (is (= "v1 der Vorlage 202 ist gebaut." (:summary written)))
                         (is (= ["Passt die Farbe?"] (:needs written)))
                         (is (= "v1" (:version written)))
                         (is (= newest (:as-of-entry written)) "the last entry read")
                         (is (pos? (:at written)))
                         (is (= written (:status (machine/bot worker))) "the bot's map follows the write")
                         (is (= written (:status (last (machine/listed)))))
                         (is (not (status/stale? written newest)))
                         (is (= 0 (.getPendingResponseCount faux)) "the manager's two answers and the status model's one")
                         (is (= 1 (count @requests)))
                         (is (= "faux-1" model) "the status model")
                         (is (empty? tools) "no tool offered to the reading")
                         (is (str/includes? text "Answer ONLY a JSON object") "the status instructions")
                         (is (str/includes? text "(none yet)") "the status as it was")
                         (is (str/includes? text "Person: Bau die 202."))
                         (is (str/includes? text "Bot: Hallo, v1 ist gebaut. Passt die Farbe?"))
                         (is (not (str/includes? text "Where do things stand?")) "nothing of the manager's chat"))))
                   (testing "nothing new: no model asked, the status as it is"
                     (.setResponses faux #js [(tools-test/tool-call "bot" {:action "status" :id worker} "s2")
                                              (tools-test/tool-call "bot" {:action "status"} "s3")
                                              (tools-test/answer "Same.")])
                     (is (= "Same." (await (harness/ask-in conversation "And now?"))))
                     (let [texts (await (results conversation))]
                       (is (= (str "Template 202 (" worker "): (nothing changed since the last status) v1 der Vorlage 202 ist gebaut. · version v1 · needs: Passt die Farbe?") (get texts "s2")))
                       (is (= "(nothing changed since the last status)" (get texts "s3")) "no worker with something new")
                       (is (= 0 (.getPendingResponseCount faux)) "the manager's three answers alone")))
                   (testing "an answer that is no status: the status stays as it was, the chat stays stale, the result says so"
                     (.setResponses faux #js [(tools-test/answer "Ok, blau: v2.")])
                     (is (= "Ok, blau: v2." (await (harness/ask-in worker-conversation "Mach es blau."))))
                     (.setResponses faux #js [(tools-test/tool-call "bot" {:action "status" :id worker} "s4")
                                              (tools-test/answer "I cannot tell.")
                                              (tools-test/answer "Hm.")])
                     (is (= "Hm." (await (harness/ask-in conversation "Status?"))))
                     (let [texts (await (results conversation))
                           kept (:status (machine/bot worker))]
                       (is (= (str "Template 202 (" worker "): (the status model gave no status, it stays as it was) v1 der Vorlage 202 ist gebaut. · version v1 · needs: Passt die Farbe?") (get texts "s4")))
                       (is (= "v1" (:version kept)))
                       (is (status/stale? kept (machine/newest-entry worker)) "still to read")
                       (is (= 0 (.getPendingResponseCount faux)))))
                   (testing "status is the manager's"
                     (.setResponses faux #js [(tools-test/tool-call "bot" {:action "status"} "n1")
                                              (tools-test/answer "Tried.")])
                     (is (= "Tried." (await (harness/ask-in (:conversation watcher) "The status of the bots."))))
                     (is (str/includes? (get (await (results (:conversation watcher))) "n1") "status is the manager's")))))
               (finally
                 (set! status/*model* model-before)
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
