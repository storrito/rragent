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
  (is (= "Hi" (person/strip-name "Hi" nil))))

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
      (is (nil? (.-entry draft)))))
  (testing "from the \"New topic\" view: the label says so, the entry carries the view's token and no topic"
    (let [draft (person/draft "A sale." "Harry" {:fresh "t-1"})]
      (is (= "Harry (new topic): A sale." (.-content draft)))
      (is (= "t-1" (.. draft -entry -data -fresh)))
      (is (= ["name" "fresh"] (js->clj (js/Object.keys (.. draft -entry -data)))) "no key without a value: the entry's data is strict JSON"))
    (let [draft (person/draft "A sale." nil {:fresh "t-1"})]
      (is (= "(new topic): A sale." (.-content draft)))
      (is (= ["fresh"] (js->clj (js/Object.keys (.. draft -entry -data))))))))
