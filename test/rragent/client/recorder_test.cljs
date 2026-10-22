(ns rragent.client.recorder-test
  "The screencast panel renders in every state it is in: before a
   recording, while it records with the live views shown and hidden."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.client.recorder :as recorder]))

(def labels
  {:title "Record a screencast"
   :started "started at {time}"
   :hide-live "Hide live views"
   :show-live "Show live views"
   :stop "Stop"
   :start "Start recording"})

(defn- render
  [state]
  (pr-str (recorder/panel (atom state) state labels nil)))

(deftest panel-test
  (testing "before a recording: the devices, the previews, Start"
    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen :webcam}
                                      :devices {:mics [] :cameras []}}})]
      (is (str/includes? html "Start recording"))
      (is (str/includes? html ":div.preview"))))
  (testing "while it records: the time, when it started, Stop and the live views"
    (let [started (.getTime (js/Date. 2026 9 22 15 25 22))
          html (render {:ui/recorder {:status :recording
                                      :sources #{:screen :webcam}
                                      :seconds 76
                                      :started-at started}})]
      (is (str/includes? html "1:16"))
      (is (str/includes? html (str "started at " (recorder/clock-time started))))
      (is (str/includes? html "Hide live views"))
      (is (str/includes? html ":div.preview"))))
  (testing "while it records with the live views hidden: the bar alone"
    (let [html (render {:ui/recorder {:status :recording
                                      :sources #{:screen :webcam}
                                      :seconds 3
                                      :started-at (js/Date.now)
                                      :minimized? true}})]
      (is (str/includes? html "Show live views"))
      (is (str/includes? html "Stop"))
      (is (not (str/includes? html ":div.preview"))))))
