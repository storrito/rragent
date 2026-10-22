(ns rragent.client.memo-test
  "The file of a voice memo: the container the browser records, its
   extension and its name."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.client.memo :as memo]))

(deftest file-test
  (testing "the first container the recorder supports, Opus in WebM before AAC in MP4"
    (is (= "audio/webm;codecs=opus" (memo/mime-type (constantly true))))
    (is (= "audio/mp4" (memo/mime-type #{"audio/mp4"})))
    (is (nil? (memo/mime-type (constantly false)))))
  (testing "the extension of the container"
    (is (= "webm" (memo/extension-of "audio/webm")))
    (is (= "m4a" (memo/extension-of "audio/mp4")))
    (is (= "ogg" (memo/extension-of "audio/ogg")))
    (is (= "webm" (memo/extension-of nil))))
  (testing "the name carries the time it was recorded"
    (is (= "memo-20261022-0905.webm" (memo/file-name (js/Date. 2026 9 22 9 5) "audio/webm")))))
