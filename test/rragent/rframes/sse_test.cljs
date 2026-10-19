(ns rragent.rframes.sse-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.sse :as sse]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest watch-test
  (async done
    (-> ((^:async fn []
           (let [db (atom 0)
                 renders (atom 0)
                 before (sse/connections)
                 handler (sse/stream-handler {:render/fn (fn [_] (swap! renders inc) nil)} identity)
                 {:keys [status headers on-open on-close]} (:ring/response (handler {:ring/request {:headers {"accept-encoding" "gzip"}}}))]
             (testing "the stream's head"
               (is (= 200 status))
               (is (= "text/event-stream" (get headers "Content-Type")))
               (is (= "gzip" (get headers "Content-Encoding"))))
             ;; one connection, rendered once at open
             (on-open {:send! (fn [_])})
             (is (= (inc before) (sse/connections)))
             (sse/watch! db)
             (dotimes [_ 5]
               (swap! db inc))
             (await (sleep 300))
             (testing "changes in a row are one refresh"
               (is (= 2 @renders)))
             (swap! db identity)
             (await (sleep 300))
             (testing "a swap that leaves the value is no refresh"
               (is (= 2 @renders)))
             (swap! db inc)
             (await (sleep 300))
             (testing "the next change is the next refresh"
               (is (= 3 @renders)))
             (on-close)
             (is (= before (sse/connections)) "a closed connection is gone"))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
