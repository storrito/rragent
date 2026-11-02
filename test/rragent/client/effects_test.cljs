(ns rragent.client.effects-test
  "The webcam's effects: the settings as the browser remembers them,
   when any is on, what `recording.json` says of the webcam's file and
   the panel's word for it; the automatic light's arithmetic
   (public/effects/levels.js, the worker's) and the touch-up's face
   (public/effects/skin.js), each loaded as the module it is."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.client.effects :as effects]))

(deftest settings-test
  (testing "remembered words become settings, anything else the defaults"
    (is (= {:background :blur :auto-light? true :touch-up :off} (effects/parse "blur" "1")))
    (is (= {:background :green :auto-light? false :touch-up :smooth} (effects/parse "green" "0" "smooth")))
    (is (= {:background :off :auto-light? false :touch-up :subtle} (effects/parse nil nil "subtle")))
    (is (= {:background :off :auto-light? false :touch-up :off} (effects/parse "purple" "yes" "plaster"))))
  (testing "an effect is on when the background is not as it is, the light is automatic, or the skin is smoothed"
    (is (not (effects/active? effects/default-settings)))
    (is (not (effects/active? nil)))
    (is (effects/active? {:background :blur :auto-light? false}))
    (is (effects/active? {:background :off :auto-light? true}))
    (is (effects/active? {:background :off :auto-light? false :touch-up :subtle})))
  (testing "the worker reads the settings as strings and a boolean"
    (let [js (effects/settings-js {:background :green :auto-light? true :touch-up :smooth})]
      (is (= "green" (.-background js)))
      (is (true? (.-autoLight js)))
      (is (= "smooth" (.-touchUp js))))
    (is (= "off" (.-background (effects/settings-js nil))))
    (is (= "off" (.-touchUp (effects/settings-js nil))))))

(deftest facts-test
  (testing "nothing in recording.json without an effect"
    (is (nil? (effects/facts effects/default-settings))))
  (testing "the green to key out is named"
    (is (= {:background "green" :auto-light false :touch-up "off" :key "#00ff00"}
           (effects/facts {:background :green :auto-light? false}))))
  (testing "a blurred background with the light corrected"
    (is (= {:background "blur" :auto-light true :touch-up "off"}
           (effects/facts {:background :blur :auto-light? true}))))
  (testing "the skin smoothed alone"
    (is (= {:background "off" :auto-light false :touch-up "subtle"}
           (effects/facts {:background :off :auto-light? false :touch-up :subtle})))))

(deftest camera-and-rate-test
  (testing "the camera is asked for 720p while an effect is on, 1080p without"
    (is (= 1280 (get-in (effects/picture {:background :blur}) [:width :ideal])))
    (is (= 720 (get-in (effects/picture {:touch-up :subtle}) [:height :ideal])))
    (is (= 1920 (get-in (effects/picture effects/default-settings) [:width :ideal])))
    (is (= 30 (get-in (effects/picture nil) [:frameRate :ideal]))))
  (testing "frames a second, to a tenth"
    (is (= 9.1 (effects/fps 91 10000)))
    (is (= 30 (effects/fps 30 1000)))
    (is (nil? (effects/fps 10 0)))
    (is (nil? (effects/fps 0 nil))))
  (testing "under 20 frames a second the webcam stutters"
    (is (effects/stutters? 9.1))
    (is (not (effects/stutters? 29.7)))
    (is (not (effects/stutters? nil))))
  (testing "recording.json holds the rate of the webcam's file"
    (is (= {:background "blur" :auto-light false :touch-up "subtle" :fps 29.4}
           (effects/facts {:background :blur :touch-up :subtle} 29.4)))
    (is (nil? (:fps (effects/facts {:background :blur} nil))))))

(deftest gpu-test
  (async done
    (-> (js* "import(~{})" (str "file://" (path/resolve "public/effects/gpu.js")))
        (.then (fn [^js gpu]
                 (testing "the blur halves four times and doubles back twice: read at a quarter"
                   (let [sizes (js->clj (.pyramid gpu 1280 720) :keywordize-keys true)]
                     (is (= [[640 360 "first"] [320 180 "down"] [160 90 "down"] [80 45 "down"] [160 90 "up"] [320 180 "up"]]
                            (mapv (juxt :width :height :kind) sizes)))))
                 (testing "an odd size is rounded, never below one pixel"
                   (let [sizes (js->clj (.pyramid gpu 3 2) :keywordize-keys true)]
                     (is (every? #(and (pos? (:width %)) (pos? (:height %))) sizes))))
                 (done)))
        (.catch (fn [error]
                  (is false (str "gpu.js did not load: " error))
                  (done))))))

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

(deftest skin-test
  (async done
    (-> (js* "import(~{})" (str "file://" (path/resolve "public/effects/skin.js")))
        (.then (fn [^js skin]
                 (testing "BlazeFace's six points: the oval round the face, the holes in it"
                   (let [points [{:x 0.45 :y 0.4} {:x 0.55 :y 0.4} {:x 0.5 :y 0.47}
                                 {:x 0.5 :y 0.55} {:x 0.38 :y 0.45} {:x 0.62 :y 0.45}]
                         shapes (js->clj (.ellipses skin (clj->js points) 512 288) :keywordize-keys true)
                         oval (:oval shapes)
                         inside? (fn [{:keys [x y]}]
                                   (< (+ (js/Math.pow (/ (- (* x 512) (:x oval)) (:rx oval)) 2)
                                         (js/Math.pow (/ (- (* y 288) (:y oval)) (:ry oval)) 2))
                                      1))]
                     (is (every? inside? (take 4 points)) "the eyes, the nose and the mouth in the oval")
                     (is (= 5 (count (:holes shapes))) "the eyes, the eyebrows, the mouth")
                     (is (< (:y (nth (:holes shapes) 2)) (:y (first (:holes shapes))))
                         "an eyebrow above its eye")
                     (is (< (js/Math.abs (:angle oval)) 0.0001) "an upright face, upright")
                     (is (= (update-vals oval js/Math.round)
                            (update-vals (:oval (js->clj (.ellipses skin (clj->js (map #(update % :x (fn [x] (- 1 x))) points)) 512 288)
                                                         :keywordize-keys true))
                                         js/Math.round))
                         "the eyes the other way round, the same face")
                     (is (nil? (.ellipses skin (clj->js (take 2 points)) 512 288)) "no mouth, no skin")))
                 (testing "the two strengths, the smooth one stronger and wider"
                   (let [levels (js->clj (.-levels skin) :keywordize-keys true)]
                     (is (< (get-in levels [:subtle :strength]) (get-in levels [:smooth :strength])))
                     (is (< (get-in levels [:subtle :range]) (get-in levels [:smooth :range])))
                     (is (nil? (:off levels)))))
                 (done)))
        (.catch (fn [error]
                  (is false (str "skin.js did not load: " error))
                  (done))))))

