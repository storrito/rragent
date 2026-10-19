(ns rragent.motion-design-test
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.motion.design :as design]))

(def template
  (path/resolve "templates/motion/project/design.cljs"))

(deftest template-test
  (let [{:keys [error size duration design-view controls styles]} (design/load template)]
    (is (nil? error))
    (is (= [1080 1920] size))
    (is (= 6 duration))
    (testing "the frame at a time, a pure function of the data"
      (let [frame (design-view {:title "Hi" :subtitle "there" :color "#123456" :size 100} 3)
            text (filter string? (tree-seq coll? seq frame))]
        (is (= :div (first frame)))
        (is (some #{"Hi"} text))
        (is (some #{"there"} text))
        (is (= "50%" (get-in (last frame) [1 :style :width])) "the bar at half the duration")))
    (testing "the controls write into the design"
      (let [c (controls {:title "Hi" :subtitle "s" :color "#123456" :size 100})
            inputs (filter #(and (vector? %) (not (map-entry? %)) (= :input (first %))) (tree-seq coll? seq c))]
        (is (= 4 (count inputs)))
        (is (= [:title] (get-in (second (first inputs)) [:on :input 0 1 :command/data :path])))))
    (is (string? styles))))

(deftest errors-test
  (testing "a file that does not evaluate"
    (is (str/includes? (:error (design/evaluate "(ns design) (def size [1 2]) (defn design-view [d t] (undefined-fn))")) "undefined-fn"))
    (is (str/includes? (:error (design/evaluate "(ns design) (def size [1 2")) "EOF")))
  (testing "a file without what the player needs"
    (is (= "design.cljs defines no design-view, the function of the design and the time."
           (:error (design/evaluate "(ns design) (def size [1 2]) (def duration 1)"))))
    (is (= "design.cljs defines no size, [width height] in pixels."
           (:error (design/evaluate "(ns design) (def duration 1) (defn design-view [d t] [:p])"))))
    (is (= "design.cljs defines no duration, seconds."
           (:error (design/evaluate "(ns design) (def size [1 2]) (defn design-view [d t] [:p])")))))
  (testing "a missing file"
    (is (str/includes? (:error (design/load "/tmp/none/design.cljs")) "missing")))
  (testing "an eval in the design's namespace, a redefinition read again"
    (let [loaded (design/evaluate "(ns design) (def size [2 2]) (def duration 2) (defn design-view [d t] [:p t])")]
      (is (= "4" (:value (design/eval-in loaded "(* 2 duration)"))))
      (let [{:keys [value loaded]} (design/eval-in loaded "(def duration 5)")]
        (is (= "#'design/duration" value))
        (is (= 5 (:duration loaded)))
        (is (some? (:ctx loaded))))
      (is (str/includes? (:error (design/eval-in loaded "(nope)")) "nope"))
      (is (str/includes? (:error (design/eval-in {:error "x"} "1")) "not loaded"))))
  (testing "fnmotion and JavaScript are at hand"
    (let [{:keys [design-view]} (design/evaluate "(ns design (:require [fnmotion.core :as fm] [fnmotion.timeline :as tl])) (def size [2 2]) (def duration 2) (defn design-view [d t] [:p (fm/progress t 0 2) (tl/at (tl/still 2 [:b \"s\"]) t) (js/Math.round 1.4)])")]
      (is (= [:p 0.5 [:b "s"] 1] (design-view {} 1))))))
