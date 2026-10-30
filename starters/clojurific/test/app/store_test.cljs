(ns app.store-test
  (:require [app.store :as store]
            [clojure.test :refer [deftest is testing]]))

(deftest status-test
  (let [db (store/open ":memory:")
        item {:id 1 :text "Great stay"}]
    (testing "never seen"
      (is (= :new (store/status db item))))
    (testing "seen as it is"
      (store/remember! db item)
      (is (= :known (store/status db item)))
      (is (= :known (store/status db {:text "Great stay" :id 1})) "the keys' order does not count"))
    (testing "seen with other content, then remembered so"
      (let [edited (assoc item :text "Great stay!")]
        (is (= :changed (store/status db edited)))
        (store/remember! db edited)
        (is (= :known (store/status db edited)))
        (is (= :changed (store/status db item)))))
    (testing "the ids, the oldest first"
      (store/remember! db {:id "b"})
      (is (= ["1" "b"] (store/ids db))))))
