(ns rragent.client.effects-test
  "The webcam's effects: the settings as the browser remembers them,
   when any is on, what `recording.json` says of the webcam's file and
   the panel's word for it; and the automatic light's arithmetic
   (public/effects/levels.js, the worker's), loaded as the module it
   is."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.client.effects :as effects]))

(deftest settings-test
  (testing "remembered words become settings, anything else the defaults"
    (is (= {:background :blur :auto-light? true} (effects/parse "blur" "1")))
    (is (= {:background :green :auto-light? false} (effects/parse "green" "0")))
    (is (= {:background :off :auto-light? false} (effects/parse nil nil)))
    (is (= {:background :off :auto-light? false} (effects/parse "purple" "yes"))))
  (testing "an effect is on when the background is not as it is, or the light is automatic"
    (is (not (effects/active? effects/default-settings)))
    (is (not (effects/active? nil)))
    (is (effects/active? {:background :blur :auto-light? false}))
    (is (effects/active? {:background :off :auto-light? true})))
  (testing "the worker reads the settings as strings and a boolean"
    (let [js (effects/settings-js {:background :green :auto-light? true})]
      (is (= "green" (.-background js)))
      (is (true? (.-autoLight js))))
    (is (= "off" (.-background (effects/settings-js nil))))))

(deftest facts-test
  (testing "nothing in recording.json without an effect"
    (is (nil? (effects/facts effects/default-settings))))
  (testing "the green to key out is named"
    (is (= {:background "green" :auto-light false :key "#00ff00"}
           (effects/facts {:background :green :auto-light? false}))))
  (testing "a blurred background with the light corrected"
    (is (= {:background "blur" :auto-light true}
           (effects/facts {:background :blur :auto-light? true})))))

(deftest file-label-test
  (let [labels {:file-webcam "the webcam"
                :file-webcam-green "the webcam on green"
                :file-webcam-black "the webcam on black"}]
    (is (= "the webcam" (effects/file-label labels nil)))
    (is (= "the webcam" (effects/file-label labels {:background :blur})))
    (is (= "the webcam on green" (effects/file-label labels {:background :green})))
    (is (= "the webcam on black" (effects/file-label labels {:background :black})))
    (is (= "the webcam" (effects/file-label {} {:background :green})) "without the words, the plain one")))

(defn- pixels
  "RGBA bytes of `n` pixels of one colour."
  [n [r g b]]
  (let [data (js/Uint8ClampedArray. (* 4 n))]
    (dotimes [i n]
      (aset data (* 4 i) r)
      (aset data (+ 1 (* 4 i)) g)
      (aset data (+ 2 (* 4 i)) b)
      (aset data (+ 3 (* 4 i)) 255))
    data))

(deftest levels-test
  (async done
    (-> (js* "import(~{})" (str "file://" (path/resolve "public/effects/levels.js")))
        (.then (fn [^js levels]
                 (testing "a dark grey picture is measured dark, flat and colourless"
                   (let [m (js->clj (.measure levels (pixels 100 [40 40 40])) :keywordize-keys true)]
                     (is (< (js/Math.abs (- (:luma m) (/ 40 255))) 0.001))
                     (is (< (:spread m) 0.001))
                     (is (< (:saturation m) 0.001))))
                 (testing "only the pixels that weigh count"
                   (let [data (pixels 4 [255 255 255])
                         weights (js/Float32Array. #js [0 0 1 1])]
                     (aset data 0 0) (aset data 1 0) (aset data 2 0)
                     (aset data 4 0) (aset data 5 0) (aset data 6 0)
                     (is (< 0.99 (:luma (js->clj (.measure levels data weights) :keywordize-keys true))))
                     (is (nil? (.measure levels data (js/Float32Array. #js [0 0 0 0]))) "nothing weighs: no measurement")))
                 (testing "a dark picture is brightened, within bounds; a dull one coloured"
                   (let [c (js->clj (.correction levels #js {:luma 0.15 :spread 0.05 :saturation 0.1}) :keywordize-keys true)]
                     (is (= 1.9 (:brightness c)) "as far as it goes")
                     (is (<= 1 (:contrast c) 1.35))
                     (is (= 1.4 (:saturate c)))))
                 (testing "a picture in the middle is left alone"
                   (let [c (js->clj (.correction levels #js {:luma 0.47 :spread 0.25 :saturation 0.36}) :keywordize-keys true)]
                     (is (< (js/Math.abs (- 1 (:brightness c))) 0.01))
                     (is (= 1 (:contrast c)))
                     (is (< (js/Math.abs (- 1 (:saturate c))) 0.01))))
                 (testing "no measurement: no correction, which is no filter"
                   (is (= "none" (.filterOf levels (.correction levels nil)))))
                 (testing "the correction is approached slowly"
                   (let [eased (js->clj (.ease levels #js {:brightness 1 :contrast 1 :saturate 1}
                                               #js {:brightness 2 :contrast 1 :saturate 1} 0.1)
                                        :keywordize-keys true)]
                     (is (< (js/Math.abs (- 1.1 (:brightness eased))) 0.0001))))
                 (testing "the filter names the three"
                   (let [filter (.filterOf levels #js {:brightness 1.25 :contrast 1.1 :saturate 1})]
                     (is (str/starts-with? filter "brightness(1.250) contrast(1.100) saturate(1.000)"))))
                 (done)))
        (.catch (fn [error]
                  (is false (str "levels.js did not load: " error))
                  (done))))))
