(ns rragent.facts-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.facts :as facts]))

(def events
  [{:id 1 :event "review-posted" :at 1000 :rid 4711 :stars 5}
   {:id 2 :event "price-checked" :at 2000 :eur 180}
   {:id 3 :event "review-posted" :at 3000 :rid 4712 :stars 4}])

(deftest select-test
  (is (= [1 2 3] (mapv :id (facts/select events {}))) "everything, as it came")
  (is (= [1 3] (mapv :id (facts/select events {:event "review-posted"}))))
  (is (= [2 3] (mapv :id (facts/select events {:since "1970-01-01T00:00:02Z"}))) "from a time on")
  (is (thrown? js/Error (facts/select events {:since "yesterday"}))))

(deftest project-test
  (testing "a projection is Clojure over events"
    (is (= "#{4711 4712}" (facts/project (facts/select events {:event "review-posted"}) "(set (map :rid events))")))
    (is (= "2" (facts/project events "(count (filter #(= \"review-posted\" (:event %)) events))")))
    (is (= "{4711 5, 4712 4}" (facts/project events "(->> events (filter :rid) (map (juxt :rid :stars)) (into (sorted-map)))"))))
  (testing "without code the facts themselves, and an error is the agent's to read"
    (is (str/starts-with? (facts/project events nil) "[{:id 1"))
    (is (thrown? js/Error (facts/project events "(nothing-here events)")))
    (is (str/ends-with? (facts/project events "(apply str (repeat 20000 \"x\"))") "(cut at 10000 characters)"))))
