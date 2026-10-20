(ns rragent.sprite-test
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.fake-sprite-env :as fake]
            [rragent.sprite :as sprite]))

(deftest discover-test
  (testing "the sprite as `sprite-env discover` prints it"
    (let [{:keys [script log]} (fake/make! {:discover (fake/discover-json ["token:test123" "agent:7b1b" "org-4711"])})]
      (binding [sprite/*sprite-env* script]
        (is (= {:name "rragent-probe6"
                :labels ["token:test123" "agent:7b1b" "org-4711"]
                :status "running"}
               (sprite/discover)))
        (is (= ["discover"] (fake/calls log))))))
  (testing "a sprite without labels"
    (binding [sprite/*sprite-env* (:script (fake/make! {:discover "{\"sprite\":{\"name\":\"bare\",\"status\":\"running\"}}"}))]
      (is (= {:name "bare" :labels [] :status "running"} (sprite/discover)))))
  (testing "no sprite-env: nil, another kind of machine"
    (binding [sprite/*sprite-env* "no-such-command-anywhere"]
      (is (nil? (sprite/discover)))))
  (testing "an answer that is no JSON, or none at all: nil"
    (binding [sprite/*sprite-env* (:script (fake/make! {:discover "not json"}))]
      (is (nil? (sprite/discover))))
    (binding [sprite/*sprite-env* (:script (fake/make! {:discover "\"a string\""}))]
      (is (nil? (sprite/discover))))
    (binding [sprite/*sprite-env* (:script (fake/make!))]
      (is (nil? (sprite/discover)) "the fake without a discover answer prints nothing"))))

(deftest label-test
  (is (= "test123" (sprite/label ["agent:7b1b" "token:test123" "org-4711"] "token")))
  (is (= "7b1b" (sprite/label ["agent:7b1b" "token:test123"] "agent")))
  (is (= "a:b" (sprite/label ["token:a:b"] "token")) "only the first colon separates")
  (is (nil? (sprite/label ["org-4711" "composio"] "token")))
  (is (nil? (sprite/label ["token:"] "token")) "nothing after the colon is no value")
  (is (nil? (sprite/label ["mytoken:x"] "token")) "the key is the whole prefix")
  (is (nil? (sprite/label nil "token")))
  (is (nil? (sprite/label [] "token"))))
