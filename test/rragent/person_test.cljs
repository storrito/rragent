(ns rragent.person-test
  "Who is talking: the name cleaned, the model's content prefixed with
   it (a string, blocks with a text block first, blocks without one),
   the prefix stripped for the page, the way back checked, and the
   draft with our entry kind."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.person :as person]))

(deftest clean-test
  (is (= "Harry" (person/clean "  Harry \n")))
  (is (= "Harry Max" (person/clean "Harry\tMax")))
  (is (nil? (person/clean "   ")))
  (is (nil? (person/clean nil)))
  (is (= 40 (count (person/clean (apply str (repeat 50 "a")))))))

(deftest with-name-test
  (is (= "Harry: Hi" (person/with-name "Hi" "Harry")))
  (is (= [{:type "text" :text "Harry: Hi"} {:type "image" :data "x"}]
         (person/with-name [{:type "text" :text "Hi"} {:type "image" :data "x"}] "Harry")))
  (is (= [{:type "text" :text "Harry:"} {:type "image" :data "x"}]
         (person/with-name [{:type "image" :data "x"}] "Harry")) "a text block of the name first")
  (is (= "Hi" (person/strip-name "Harry: Hi" "Harry")))
  (is (= "Harry: Hi" (person/strip-name "Harry: Hi" "Max")) "another name's prefix stays")
  (is (= "Hi" (person/strip-name "Hi" nil)))
  (testing "a label of the time the machine was one conversation, the topic behind the name or alone"
    (is (= "The covers, please." (person/strip-label "Lydia (Content week): The covers, please.")))
    (is (= "A Pinterest short." (person/strip-label "(new topic): A Pinterest short.")))
    (is (= "Hi: there" (person/strip-label "Harry: Hi: there")) "the first label alone")
    (is (= "No label here" (person/strip-label "No label here")))))

(deftest back-path-test
  (is (= "/bot/7" (person/back-path "/bot/7")))
  (is (= "/" (person/back-path "//evil.example")))
  (is (= "/" (person/back-path "https://evil.example/")))
  (is (= "/" (person/back-path nil))))

(deftest draft-test
  (testing "with a name: the content prefixed and the entry of our kind"
    (let [draft (person/draft "Hi" "Harry")]
      (is (= "input" (.-type draft)))
      (is (= "Harry: Hi" (.-content draft)))
      (is (= "steer" (.-whenBusy draft)))
      (is (= person/kind (.. draft -entry -kind)))
      (is (= "Harry" (.. draft -entry -data -name)))))
  (testing "without: as before"
    (let [draft (person/draft "Hi" nil)]
      (is (= "Hi" (.-content draft)))
      (is (nil? (.-entry draft))))))
