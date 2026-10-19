(ns rragent.motion-player-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.motion.player :as player]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest frame-test
  (is (= [:h1 "x"] (player/frame (fn [design _] [:h1 (:title design)]) {:title "x"} 0)))
  (is (= [:div nil] (player/frame (fn [_ _] [:div (fn [] 1)]) {} 0)) "a function in a frame becomes nothing")
  (let [[tag text] (player/frame (fn [_ _] (throw (js/Error. "boom"))) {} 1.25)]
    (is (= :p.error tag))
    (is (= "The frame at 1.3 s failed: boom" text))))

(deftest coerce-test
  (is (= 12 (player/coerce "12")))
  (is (= 1.5 (player/coerce "1.5")))
  (is (= "#b45309" (player/coerce "#b45309")))
  (is (= 7 (player/coerce 7)))
  (let [db (atom {:design {:size 1}})]
    (player/design-set! db ["size"] "120")
    (player/design-set! db [:title] "Hi")
    (is (= {:size 120 :title "Hi"} (:design @db)))))

(deftest playback-test
  (async done
    (-> ((^:async fn []
           (let [db (atom {:design {} :motion/t 0})]
             (is (true? (player/toggle! db 0.3)))
             (is (true? (:motion/playing? @db)))
             (await (sleep 500))
             (testing "played to the end and stopped there"
               (is (= 0.3 (:motion/t @db)))
               (is (false? (:motion/playing? @db))))
             (testing "playing from the end starts over"
               (player/toggle! db 10)
               (is (< (:motion/t @db) 1))
               (player/toggle! db 10)
               (is (false? (:motion/playing? @db))))
             (testing "a seek pauses at the position"
               (player/seek! db 10 250)
               (is (= 2.5 (:motion/t @db)))
               (player/stop! db)
               (is (= 0 (:motion/t @db)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest controls-test
  (let [[_ button [_ range] [_ time]] (player/controls {:motion/t 3 :motion/playing? false} 6)]
    (is (= "Play" (last button)))
    (is (= 500 (:value range)))
    (is (= "0:03.0 / 0:06.0" time)))
  (is (= "Pause" (last (nth (player/controls {:motion/t 0 :motion/playing? true} 6) 1))))
  (testing "the player with a stage and audio tracks"
    (let [p (player/player {:design {:audio [{:src "m.mp3" :volume 0.6}]} :motion/t 1}
                           {:frame (fn [_ t] [:b t]) :duration 6 :size [1080 1920]})
          [_ attrs stage audio] p]
      (is (= {:data-t 1 :data-playing "false"} attrs))
      (is (= :div.motion-frame (first stage)))
      (is (= 1080 (:data-width (second stage))))
      (is (= [:b 1] (last (last stage))))
      (is (= 6 (:data-end (second (first (last audio)))))))))

(deftest watch-design-test
  (async done
    (-> ((^:async fn []
           (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-design-"))
                 file (path/join dir "design.edn")
                 db (atom {:design {:title "new"}})]
             (fs/writeFileSync file "{:title \"saved\" :size 120}")
             (let [stop (player/watch-design! db file)]
               (try
                 (testing "the file is read first"
                   (is (= {:title "saved" :size 120} (:design @db))))
                 (testing "a change is written"
                   (player/design-set! db [:title] "typed")
                   (is (= "{:title \"typed\", :size 120}" (fs/readFileSync file "utf8"))))
                 (testing "an edit from outside shows up"
                   (await (sleep 20))
                   (fs/writeFileSync file "{:title \"edited\" :size 1}")
                   (let [later (+ 2 (/ (js/Date.now) 1000))]
                     (fs/utimesSync file later later))
                   (await (sleep (+ player/design-poll-ms 200)))
                   (is (= {:title "edited" :size 1} (:design @db))))
                 (finally
                   (stop)
                   (fs/rmSync dir #js {:recursive true :force true})))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
