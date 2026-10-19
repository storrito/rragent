(ns rragent.version-test
  (:require [clojure.test :refer [deftest is]]
            [rragent.version :as version]))

(deftest version-test
  (is (re-matches #"\d+\.\d+\.\d+" version/version) "a release version"))
