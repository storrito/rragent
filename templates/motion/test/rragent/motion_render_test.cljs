(ns rragent.motion-render-test
  "fnmotion's render tests ported: the pure parts, and a real render with
   two Chromes when a Chrome, ffmpeg and ffprobe are on the machine."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [fnmotion.core :as fm]
            [rragent.motion.render :as render]
            [rragent.programs :as programs]))

(deftest frame-times-test
  (is (= [0 0.5 1 1.5] (render/frame-times 2 2)))
  (is (= 180 (count (render/frame-times 6 30))))
  (is (= 1 (count (render/frame-times 0.04 30)))))

(deftest html-test
  (is (= "<div style=\"opacity: 0.5;\"><b>x</b></div>"
         (render/html [:div {:style {:opacity 0.5} :on {:click []} :replicant/key 1} (list [:b "x"] nil)])))
  (is (= "<p>a</p><p>b</p>" (render/html (list [:p "a"] [:p "b"])))))

(deftest audio-filter-test
  (testing "one track plays as it is, as long as the video"
    (is (= "[1:a]atrim=start=0:duration=6,asetpts=PTS-STARTPTS[a1];[a1]anull[aout]"
           (render/audio-filter [{:src "music.mp3"}] 6))))
  (testing "volume, fades, a start and a part of the file"
    (is (= "[1:a]atrim=start=2.5:duration=3,asetpts=PTS-STARTPTS,volume=0.6,afade=t=in:st=0:d=0.5,afade=t=out:st=2:d=1,adelay=1000:all=1[a1];[a1]anull[aout]"
           (render/audio-filter [{:src "music.mp3" :start 1 :duration 3 :media-start 2.5
                                  :volume 0.6 :fade-in 0.5 :fade-out 1}]
                                6))))
  (testing "two tracks are mixed"
    (is (str/ends-with? (render/audio-filter [{:src "a.mp3"} {:src "b.mp3" :start 4}] 6)
                        "[a1][a2]amix=inputs=2:duration=longest:normalize=0[aout]"))))

(deftest ffmpeg-args-test
  (let [args (render/ffmpeg-args {:fps 30 :duration 6 :out "out.mp4"
                                  :audio [{:src "music.mp3" :loop? true}]})]
    (is (= ["ffmpeg" "-y" "-loglevel" "error" "-f" "image2pipe" "-framerate" "30" "-i" "-"
            "-stream_loop" "-1" "-i" "music.mp3"]
           (take 14 args)))
    (is (= "out.mp4" (last args)))
    (is (some #{"-filter_complex"} args)))
  (testing "no audio, no mix"
    (is (not (some #{"-filter_complex"} (render/ffmpeg-args {:fps 30 :duration 6 :out "o.mp4"}))))))

(deftest page-html-test
  (let [page (render/page-html {:size [1080 1920] :head "<style>h1 {}</style>"})]
    (is (str/includes? page "width: 1080px; height: 1920px"))
    (is (str/includes? page "<style>h1 {}</style>"))
    (is (str/includes? page "window.__fnmotion"))))

(defn- tools?
  []
  (and (programs/locate "chrome") (programs/on-path "ffmpeg") (programs/on-path "ffprobe")))

(deftest render-test
  (async done
    (if-not (tools?)
      (do (println "render-test skipped: no Chrome, ffmpeg or ffprobe on this machine")
          (done))
      (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "fnmotion-render-test"))
            out (path/join dir "out.mp4")
            progress (atom [])]
        (-> ((^:async fn []
               (child/execFileSync "ffmpeg" #js ["-y" "-loglevel" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=2" (path/join dir "tone.wav")])
               (let [result (await (render/render! {:frame (fn [{:keys [color]} t]
                                                             [:div {:style {:position "absolute" :inset 0 :background color
                                                                            :opacity (fm/progress t 0 0.5)}}])
                                                    :design {:color "#ff0000"}
                                                    :duration 0.5
                                                    :fps 10
                                                    :size [160 90]
                                                    :dir dir
                                                    :workers 2
                                                    :audio [{:src "tone.wav" :volume 0.5 :fade-out 0.2}]
                                                    :out out
                                                    :on-progress (fn [done all] (swap! progress conj [done all]))}))
                     probe (str (child/execFileSync "ffprobe" #js ["-v" "error" "-show_entries" "stream=codec_type,width,height" "-of" "csv=p=0" out]))]
                 (is (= 5 (:frames result)))
                 (is (= 2 (:workers result)))
                 (is (= out (:out result)))
                 (is (= [[1 5] [2 5] [3 5] [4 5] [5 5]] @progress) "the frames in order")
                 (is (str/includes? probe "video,160,90"))
                 (is (str/includes? probe "audio"))
                 (is (not (fs/existsSync (path/join dir ".fnmotion-render.html")))))
               (testing "a frame that throws ends the render without a file"
                 (let [error (await (-> (render/render! {:frame (fn [_ _] (throw (js/Error. "no frame")))
                                                         :design {}
                                                         :duration 0.2
                                                         :fps 10
                                                         :size [160 90]
                                                         :dir dir
                                                         :workers 1
                                                         :out out})
                                        (.catch identity)))]
                   (is (instance? js/Error error))
                   (is (not (fs/existsSync out)))))))
            (.catch (fn [e] (is (nil? e) (str e))))
            (.finally (fn []
                        (fs/rmSync dir #js {:recursive true :force true})
                        (done))))))))
