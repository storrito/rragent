(ns rragent.client.recorder-test
  "What the draft says of a recording: every file with what it is, the
   microphone alone in its own words."
  (:require [clojure.test :refer [deftest is testing]]
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
             (recorder/draft-line labels "artifacts/recordings/s" ["audio.mp3" "screen.webm"]))))))
