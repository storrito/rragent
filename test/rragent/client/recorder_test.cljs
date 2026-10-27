(ns rragent.client.recorder-test
  "The screencast panel: what the draft says of a recording, every file
   with what it is, the microphone alone in its own words; and the panel
   renders in every state it is in, before a recording, while it records
   with the live views shown and hidden."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.client.recorder :as recorder]))

(deftest draft-line-test
  (let [labels {:draft "New recording in {folder}: {files}; every file carries a microphone. "
                :draft-audio "New recording in {folder}: {files}, the microphone alone. "
                :file-screen "the screen"
                :file-webcam "the webcam"
                :file-audio "the microphone"}]
    (testing "the screen and the webcam"
      (is (= "New recording in artifacts/recordings/s: screen.webm (the screen), webcam.webm (the webcam); every file carries a microphone. "
             (recorder/draft-line labels "artifacts/recordings/s" ["screen.webm" "webcam.webm"]))))
    (testing "the microphone alone, as the MP3 the server made"
      (is (= "New recording in artifacts/recordings/s: audio.mp3 (the microphone), the microphone alone. "
             (recorder/draft-line labels "artifacts/recordings/s" ["audio.mp3"]))))
    (testing "the microphone beside the screen"
      (is (= "New recording in artifacts/recordings/s: audio.mp3 (the microphone), screen.webm (the screen); every file carries a microphone. "
             (recorder/draft-line labels "artifacts/recordings/s" ["audio.mp3" "screen.webm"]))))
    (testing "the webcam recorded on green says so, for the agent to key it out"
      (is (= "New recording in artifacts/recordings/s: webcam.webm (the webcam on green); every file carries a microphone. "
             (recorder/draft-line (assoc labels :file-webcam-green "the webcam on green")
                                  "artifacts/recordings/s" ["webcam.webm"] {:background :green :auto-light? false}))))
    (testing "a blurred background is the webcam as ever"
      (is (= "New recording in artifacts/recordings/s: webcam.webm (the webcam); every file carries a microphone. "
             (recorder/draft-line labels "artifacts/recordings/s" ["webcam.webm"] {:background :blur}))))
    (testing "recorded without a microphone: the files have no sound, the draft says so"
      (is (= "New recording in artifacts/recordings/s: screen.webm (the screen), webcam.webm (the webcam); no sound. "
             (recorder/draft-line (assoc labels :draft-no-mic "New recording in {folder}: {files}; no sound. ")
                                  "artifacts/recordings/s" ["screen.webm" "webcam.webm"] nil true))))))

(def labels
  {:title "Record a screencast"
   :started "started at {time}"
   :hide-live "Hide live views"
   :show-live "Show live views"
   :stop "Stop"
   :start "Start recording"
   :background "Background"
   :background-off "As it is"
   :background-blur "Blurred"
   :background-green "Removed: green"
   :background-black "Removed: black"
   :auto-light "Light and colour automatic"
   :effects-loading "Loading the model …"
   :effects-failed "The background effect did not load: {error}."
   :green-hint "Only you stay in the picture."
   :no-mic "No microphone: no sound."
   :no-camera "No camera"
   :source-webcam "Webcam"
   :source-mic "Microphone alone"})

(defn- render
  [state]
  (pr-str (recorder/panel (atom state) state labels nil)))

(deftest panel-test
  (testing "before a recording: the devices, the previews, Start"
    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen :webcam}
                                      :devices {:mics [] :cameras []}}})]
      (is (str/includes? html "Start recording"))
      (is (str/includes? html ":div.preview"))
      (is (not (str/includes? html ":div.effects")) "no effects in a browser without them")))
  (testing "the effects in a browser that has them: the background chosen, the light, the model loading"
    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen :webcam}
                                      :devices {:mics [] :cameras []}
                                      :effects-supported? true
                                      :effects {:background :green :auto-light? true}
                                      :effects-status :loading}})]
      (is (str/includes? html ":div.effects"))
      (is (str/includes? html "Removed: green"))
      (is (str/includes? html "Light and colour automatic"))
      (is (str/includes? html "Loading the model …"))
      (is (re-find #":value \"green\", :selected true" html) "the background recorded is the one chosen")))
  (testing "the model that did not load says why, the green hint shows once it is on"
    (is (str/includes? (render {:ui/recorder {:status :ready :devices {} :effects-supported? true
                                              :effects {:background :blur} :effects-status {:failed "no wasm"}}})
                       "did not load: no wasm."))
    (is (str/includes? (render {:ui/recorder {:status :ready :devices {} :effects-supported? true
                                              :effects {:background :green} :effects-status :on}})
                       "Only you stay in the picture.")))

  (testing "without a microphone: a word in its place, no meter, the microphone alone cannot be ticked"
    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen :webcam}
                                      :devices {:mics [] :cameras [{:id "c" :label "Cam"}]}}})]
      (is (str/includes? html "No microphone: no sound."))
      (is (not (str/includes? html ":span.meter")))
      (is (str/includes? html ":video.camera"))
      (is (re-find #":checked false, :disabled true, :on \{:change #object\[[^\]]*\]\}\}\] \"Microphone alone\"" html))))

  (testing "the webcam unticked: no camera live, no effects, though there is a camera"
    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen}
                                      :effects-supported? true
                                      :devices {:mics [] :cameras [{:id "c" :label "Cam"}]}}})]
      (is (not (str/includes? html ":video.camera")))
      (is (not (str/includes? html ":div.effects")))
      (is (str/includes? html "Cam") "the camera can still be chosen for later")))
  (testing "without a camera: no camera live, a word in its place"

    (let [html (render {:ui/recorder {:status :ready
                                      :sources #{:screen}
                                      :devices {:mics [{:id "m" :label "Mic"}] :cameras []}}})]
      (is (str/includes? html "No camera"))
      (is (not (str/includes? html ":video.camera")))
      (is (str/includes? html ":span.meter"))))
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
