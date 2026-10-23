(ns rragent.client.icons-test
  "The composer's icons: each an SVG in the row's box, drawn in the
   button's color, nothing a screen reader reads."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.client.icons :as icons]))

(deftest icons-test
  (doseq [[name icon] {"paperclip" icons/paperclip
                       "camera" icons/camera
                       "microphone" icons/microphone}]
    (testing name
      (let [[tag attrs & shapes] icon]
        (is (= :svg tag))
        (is (= "0 0 24 24" (:viewBox attrs)) "the box of Lucide and Feather")
        (is (= [20 20] [(:width attrs) (:height attrs)]) "the row's size")
        (is (= ["none" "currentColor" 2] [(:fill attrs) (:stroke attrs) (:stroke-width attrs)]) "an outline in the button's color")
        (is (= "true" (:aria-hidden attrs)) "the button's title names it")
        (is (seq shapes) "something drawn")
        (is (every? #(contains? #{:path :rect} (first %)) shapes) "paths and rectangles, no text")))))
