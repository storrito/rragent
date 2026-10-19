(ns rragent.onboarding-test
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.onboarding :as onboarding]))

(def metadata
  {"title" "Kinetic shorts"
   "title-de" "Kinetic Shorts"
   "ask" "Paste the link of a YouTube video."
   "ask-de" "Füge den Link eines YouTube-Videos ein."
   "placeholder" "Paste a YouTube link"
   "placeholder-de" "YouTube-Link einfügen"})

(deftest onboarding-test
  (testing "the texts in English"
    (is (= {:title "Kinetic shorts"
            :ask "Paste the link of a YouTube video."
            :placeholder "Paste a YouTube link"}
           (onboarding/onboarding metadata :en))))
  (testing "the German variants for German"
    (is (= {:title "Kinetic Shorts"
            :ask "Füge den Link eines YouTube-Videos ein."
            :placeholder "YouTube-Link einfügen"}
           (onboarding/onboarding metadata :de))))
  (testing "a text without a German variant stays English, an empty one is none"
    (is (= {:title "T" :ask "A" :placeholder nil}
           (onboarding/onboarding {"title" "T" "ask" "A" "ask-de" "" "placeholder" ""} :de))))
  (testing "without an ask there is no onboarding"
    (is (nil? (onboarding/onboarding {"title" "T"} :en)))
    (is (nil? (onboarding/onboarding nil :en)))))
