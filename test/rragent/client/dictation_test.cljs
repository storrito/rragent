(ns rragent.client.dictation-test
  "The draft of a dictation: what was typed, then the pieces the model
   wrote down, in the order they were spoken."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.client.dictation :as dictation]))

(deftest draft-test
  (testing "the pieces after what was typed"
    (is (= "Bitte: Hallo Agent, plane morgen eine Story."
           (dictation/draft {:base "Bitte:"
                             :segments [{:id 0 :text "Hallo Agent,"}
                                        {:id 1 :text "plane morgen eine Story."}]}))))
  (testing "a later piece back before an earlier one keeps its place; the earlier one is missing until then"
    (is (= "plane morgen eine Story."
           (dictation/draft {:base ""
                             :segments [{:id 0 :pending? true}
                                        {:id 1 :text "plane morgen eine Story."}]}))))
  (testing "a piece that did not come back or is empty leaves no gap"
    (is (= "Hallo Agent Story"
           (dictation/draft {:base ""
                             :segments [{:id 0 :text "Hallo Agent"}
                                        {:id 1 :failed? true}
                                        {:id 2 :text ""}
                                        {:id 3 :text "Story"}]}))))
  (testing "a draft that ends in a line break keeps it"
    (is (= "Liste:\nerstens"
           (dictation/draft {:base "Liste:\n" :segments [{:id 0 :text "erstens"}]})))))

(deftest caret-test
  (testing "the dictation goes between the text before and after the caret"
    (let [dictation {:base "Bitte" :after "an Anna." :segments [{:id 0 :text "schick das Video"}]}]
      (is (= "Bitte schick das Video an Anna." (dictation/draft dictation)))
      (is (= (count "Bitte schick das Video") (dictation/caret dictation)))))
  (testing "no space before punctuation after the caret, none added while nothing is dictated"
    (is (= "Hallo Anna." (dictation/draft {:base "Hallo" :after "." :segments [{:id 0 :text "Anna"}]})))
    (is (= "Hallo Welt" (dictation/draft {:base "Hallo " :after "Welt" :segments [{:id 0 :pending? true}]}))))
  (testing "the typed space of a held Space goes, the caret with it"
    (is (= ["ab" 1] (dictation/without-typed-space "a b" 2)))
    (is (= ["ab" 1] (dictation/without-typed-space "ab" 1)))))

(deftest corrected-test
  (testing "the correction takes the place of the pieces"
    (is (= "Bitte: Hi the Storrito editor."
           (dictation/draft {:base "Bitte:"
                             :segments [{:id 0 :text "Hi the"} {:id 1 :text "Stuito editor."}]
                             :corrected "Hi the Storrito editor."})))))

(deftest words-to-correct-test
  (is (= [{:word "Hi"} {:word "Stuito" :confidence 0.2} {:word "editor."}]
         (dictation/words-to-correct {:segments [{:id 0 :words [{:word "Hi"}]}
                                                 {:id 1 :failed? true}
                                                 {:id 2 :unsure? true :words [{:word "Stuito" :confidence 0.2} {:word "editor."}]}]})))
  (is (nil? (dictation/words-to-correct {:segments [{:id 0 :unsure? false :words [{:word "Hi"}]}]}))))

(deftest pending-test
  (is (dictation/pending? {:segments [{:id 0 :text "a"} {:id 1 :pending? true}]}))
  (is (not (dictation/pending? {:segments [{:id 0 :text "a"} {:id 1 :failed? true}]}))))
