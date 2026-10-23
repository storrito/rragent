(ns rragent.transcript-correction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.correction :as correction]
            [rragent.transcript-correction :as tc]))

(def words
  "The words of the recording of 2026-10-23 as AssemblyAI heard them."
  [{:word "Story" :start 4.887 :end 5.436 :confidence 0.99}
   {:word "on" :start 5.839 :end 5.871 :confidence 0.98}
   {:word "streeto_com." :start 6 :end 7.13 :confidence 0.41}
   {:word "Ask" :start 7.5 :end 7.7 :confidence 0.97}
   {:word "story" :start 7.8 :end 8.0 :confidence 0.62}
   {:word "to" :start 8.0 :end 8.1 :confidence 0.6}
   {:word "com" :start 8.1 :end 8.6 :confidence 0.58}])

(deftest prompt-test
  (let [ask (tc/prompt words "storrito_com, carfunnews")]
    (is (str/includes? ask "on [[streeto_com.]] Ask"))
    (is (str/includes? ask "<names>\nstorrito_com, carfunnews\n</names>"))
    (is (str/includes? ask "{\"fixes\": []}")))
  (is (not (str/includes? (tc/prompt words nil) "<names>")) "no names, no block"))

(deftest fixes-test
  (is (= [{:heard "streeto_com." :meant "storrito_com."}]
         (tc/fixes "{\"fixes\": [{\"heard\": \"[[streeto_com.]]\", \"meant\": \"storrito_com.\"}]}")))
  (is (= [{:heard "a" :meant "b"}] (tc/fixes "```json\n{\"fixes\": [{\"heard\": \"a\", \"meant\": \"b\"}]}\n```")) "in a code fence")
  (is (= [] (tc/fixes "{\"fixes\": [{\"heard\": \"a\", \"meant\": \"a\"}, {\"heard\": \"\", \"meant\": \"b\"}]}")) "no change, nothing heard")
  (is (nil? (tc/fixes "The transcript looks right to me.")))
  (is (nil? (tc/fixes (js/JSON.stringify (clj->js {:fixes (repeat 61 {:heard "a" :meant "b"})})))) "too many to be names"))

(deftest similar-test
  (is (tc/similar? "streeto_com." "storrito_com"))
  (is (tc/similar? "Streato." "Storrito"))
  (is (tc/similar? "story to com" "storrito_com"))
  (is (not (tc/similar? "That," "rragent")))
  (is (not (tc/similar? "will" "it")))
  (is (not (tc/similar? "underscore" "_"))))

(def names
  (tc/known "storrito_com, Car Funnews"))

(deftest apply-fixes-test
  (testing "as many words as heard: each keeps its time, the punctuation stays"
    (let [{:keys [words applied]} (tc/apply-fixes words [{:heard "streeto_com" :meant "storrito_com"}])
          fixed (nth words 2)]
      (is (= "storrito_com." (:word fixed)))
      (is (= [6 7.13] [(:start fixed) (:end fixed)]))
      (is (= "streeto_com." (:heard fixed)))
      (is (= [{:heard "streeto_com" :meant "storrito_com" :times 1}] applied))))
  (testing "three words heard as one meant: the span's time, the lowest confidence"
    (let [{:keys [words]} (tc/apply-fixes words [{:heard "story to com" :meant "storrito_com"}])]
      (is (= 5 (count words)))
      (is (= {:word "storrito_com" :start 7.8 :end 8.6 :heard "story to com" :confidence 0.58} (last words)))))
  (testing "one word heard as two, a sure one among the names: the time shared by their lengths"
    (let [{:keys [words]} (tc/apply-fixes [{:word "Karfannews" :start 1 :end 3 :confidence 0.99}] [{:heard "Karfannews" :meant "Car Funnews"}] names)]
      (is (= [["Car" 1 1.6] ["Funnews" 1.6 3]] (map (juxt :word :start :end) words)))))
  (testing "a case and the punctuation around do not hide a word; a fix that is not there changes nothing"
    (let [{:keys [words applied]} (tc/apply-fixes [{:word "Streeto_com!" :start 0 :end 1 :confidence 0.4} {:word "x" :start 1 :end 2}]
                                                  [{:heard "streeto_com" :meant "storrito_com"} {:heard "nowhere" :meant "nowhere1"}])]
      (is (= ["storrito_com!" "x"] (map :word words)))
      (is (= 1 (count applied)))))
  (testing "what is no mishearing changes nothing: a rewording, only case or punctuation, a sure word not among the names"
    (let [sure [{:word "That," :start 0 :end 1 :confidence 0.99} {:word "will" :start 1 :end 2 :confidence 0.4}
                {:word "reel." :start 2 :end 3 :confidence 0.5} {:word "Stories" :start 3 :end 4 :confidence 0.99}]
          {:keys [words applied]} (tc/apply-fixes sure [{:heard "That," :meant "rragent"} {:heard "will" :meant "it"}
                                                        {:heard "reel." :meant "Reel"} {:heard "Stories" :meant "Storrito"}]
                                                  names)]
      (is (= sure words))
      (is (= [] applied)))))

(deftest summary-test
  (is (= "streeto_com → storrito_com (2×), strito → Storrito"
         (tc/summary [{:heard "streeto_com" :meant "storrito_com" :times 1}
                      {:heard "strito" :meant "Storrito" :times 1}
                      {:heard "streeto_com" :meant "storrito_com" :times 1}]))))

(def messages
  [{:role "system" :content "You are the agent."}
   {:role "user" :content "Post it on storrito_com."}])

(defn- fake-complete
  "Answers `answers` in turn; the requests land in `seen`."
  [seen answers]
  (let [answers (atom answers)]
    (fn [request]
      (swap! seen conj request)
      (let [text (first @answers)]
        (swap! answers rest)
        (js/Promise.resolve {:text text :cost 0.001})))))

(deftest correct-test
  (async done
    (-> ((^:async fn []
           (let [seen (atom [])
                 {:keys [words applied cost failed]}
                 (await (tc/correct! (fake-complete seen ["{\"fixes\": [{\"heard\": \"streeto_com.\", \"meant\": \"storrito_com.\"}]}"])
                                     messages words "storrito_com"))
                 [system ask] (first @seen)]
             (is (= "storrito_com." (:word (nth words 2))))
             (is (= 1 (count applied)))
             (is (= 0.001 cost))
             (is (nil? failed))
             (is (= {:role "system" :content "You are the agent."} system))
             (is (str/includes? (:content ask) "Person: Post it on storrito_com."))
             (is (str/includes? (:content ask) "<names>\nstorrito_com\n</names>"))
             (testing "in pieces; a piece whose answer is no list stays as it was, and says why"
               (let [seen (atom [])
                     many (vec (for [i (range (inc tc/piece-words))] {:word (if (zero? i) "streeto" "wurd") :start i :end (+ i 0.5) :confidence 0.5}))
                     {:keys [words failed]} (await (tc/correct! (fake-complete seen ["Looks fine." "{\"fixes\": [{\"heard\": \"wurd\", \"meant\": \"word\"}]}"])
                                                                [] many nil))]
                 (is (= 2 (count @seen)))
                 (is (= (count many) (count words)) "every word still there")
                 (is (= "streeto" (:word (first words))) "the first piece as it was")
                 (is (= "word" (:word (last words))) "the second corrected")
                 (is (= "the answer was no list of fixes" failed))))
             (testing "a request that fails leaves the words, and says why"
               (let [{:keys [words failed]} (await (tc/correct! (fn [_] (js/Promise.reject (js/Error. "OpenRouter answered 402: no credits")))
                                                                messages (take 2 words) nil))]
                 (is (= 2 (count words)))
                 (is (= "OpenRouter answered 402: no credits" failed)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
