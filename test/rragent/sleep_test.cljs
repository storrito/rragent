(ns rragent.sleep-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rragent.sleep :as sleep]))

(deftest step-test
  (testing "work resets the idle time"
    (is (= {:idle-since nil :stop? false} (sleep/step 5000 true 9000 1000))))
  (testing "idling is counted from its first round, and the stop comes with the time"
    (is (= {:idle-since 1000 :stop? false} (sleep/step nil false 1000 600000)))
    (is (= {:idle-since 1000 :stop? false} (sleep/step 1000 false 500000 600000)))
    (is (= {:idle-since 1000 :stop? true} (sleep/step 1000 false 601000 600000)))))

(deftest watch-test
  (async done
    (let [busy (atom true)
          stops (atom 0)
          end! (sleep/watch! (fn [] @busy)
                             {:every-ms 10
                              :after-ms 100
                              :available? (constantly true)
                              :stop! (fn [] (swap! stops inc) true)})
          sleep (fn [ms] (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))]
      (-> ((^:async fn []
             (await (sleep 250))
             (is (= 0 @stops) "busy: no stop")
             (reset! busy false)
             (await (sleep 50))
             (is (= 0 @stops) "idle, but not for long enough")
             (await (sleep 400))
             (is (<= 2 @stops 5) "idle for the time: a stop, and more as the idle time ran out afresh")
             (reset! busy true)
             (let [n @stops]
               (await (sleep 250))
               (is (= n @stops) "busy again: no more stops"))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (end!) (done)))))))
