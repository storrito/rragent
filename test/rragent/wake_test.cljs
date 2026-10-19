(ns rragent.wake-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.fake-sprite-env :as fake]
            [rragent.sprite :as sprite]
            [rragent.wake :as wake]))

(deftest cron-of-test
  (is (= "30 7 16 10 *" (wake/cron-of (.getTime (js/Date. "2026-10-16T07:30:00Z")))))
  (is (= "0 0 1 1 *" (wake/cron-of (.getTime (js/Date. "2027-01-01T00:00:00Z")))))
  (is (= 1792000080000 (wake/next-minute 1792000020001))))

(deftest arm-test
  (let [{:keys [script log]} (fake/make!)
        now (js/Date.now)
        at (+ now (* 10 60 1000))
        cron (wake/cron-of (* 60000 (quot at 60000)))]
    (binding [sprite/*sprite-env* script]
      (testing "the first alarm is made (the update of what is not there fails), then enabled"
        (is (true? (wake/arm! at now)))
        (is (= [(str "automation update rragent-wake --cron " cron " --exec --after none --timeout 60 -- /bin/true")
                (str "automation create rragent-wake --cron " cron " --exec --after none --timeout 60 -- /bin/true")
                "automation resume rragent-wake"]
               (fake/calls log))))
      (testing "the next alarm updates it"
        (is (true? (wake/arm! (+ at 60000) now)))
        (is (str/starts-with? (last (butlast (fake/calls log))) "automation update rragent-wake --cron")))
      (testing "an alarm in the past is the next minute"
        (wake/arm! (- now 60000) now)
        (is (str/includes? (last (butlast (fake/calls log))) (wake/cron-of (wake/next-minute now)))))
      (testing "no schedule: the alarm goes"
        (is (true? (wake/arm! nil now)))
        (is (= "automation delete rragent-wake" (last (fake/calls log)))))
      (testing "the daily wake is made once"
        (is (true? (wake/ensure-daily!)))
        (is (= "automation create rragent-daily --cron 17 3 * * * --exec --after none --timeout 60 -- /bin/true" (last (butlast (fake/calls log)))))
        (let [before (count (fake/calls log))]
          (is (true? (wake/ensure-daily!)))
          (is (= (inc before) (count (fake/calls log))) "only the get"))))))

(deftest without-sprite-env-test
  (binding [sprite/*sprite-env* "no-such-command-anywhere"]
    (is (not (wake/available?)))
    (is (nil? (wake/arm! (js/Date.now) (js/Date.now))) "nothing happens on a machine without sprite-env")))
