(ns rragent.images-test
  "The pictures the model gets: WebP, scaled to the long edge and the
   limits, transparency kept; the fallbacks. Needs an ffmpeg with
   libwebp and ffprobe: RRAGENT_FFMPEG and RRAGENT_FFPROBE in the
   environment (what `rragent.programs/locate` reads), else /usr/bin
   (Ubuntu's build has libwebp; Homebrew's on this laptop does not),
   else the PATH."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.images :as images]
            [rragent.programs :as programs]))

(def variables
  ["RRAGENT_FFMPEG" "RRAGENT_FFPROBE"])

(defn with-webp-ffmpeg
  "`thunk` run with RRAGENT_FFMPEG and RRAGENT_FFPROBE set to a build
   with libwebp when the environment does not name one, restored
   after; answers the thunk's promise."
  [thunk]
  (let [env (.-env js/process)
        before (select-keys (js->clj env) variables)]
    (doseq [[k program] (map vector variables ["ffmpeg" "ffprobe"])]
      (when-not (aget env k)
        (let [usr (str "/usr/bin/" program)]
          (aset env k (if (fs/existsSync usr) usr (programs/locate program))))))
    (-> (js/Promise.resolve (thunk))
        (.finally (fn []
                    (doseq [k variables]
                      (if-let [v (get before k)]
                        (aset env k v)
                        (js-delete env k))))))))

(defn- picture
  "A PNG of `width`x`height` made by ffmpeg: `color` alone, or with a
   transparent background when `alpha?`."
  [width height & [alpha?]]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-images-test-"))
        file (path/join dir "in.png")
        source (if alpha?
                 (str "color=c=0x00000000:s=" width "x" height ",format=rgba")
                 (str "testsrc=s=" width "x" height))]
    (child-process/execFileSync (aget (.-env js/process) "RRAGENT_FFMPEG")
                                (clj->js ["-y" "-loglevel" "error" "-f" "lavfi" "-i" source "-frames:v" "1"
                                          "-pix_fmt" (if alpha? "rgba" "rgb24") file]))
    (let [bytes (fs/readFileSync file)]
      (fs/rmSync dir #js {:recursive true :force true})
      bytes)))

(defn- webp-bytes
  [image]
  (js/Buffer.from (:data image) "base64"))

(defn- webp?
  [bytes]
  (and (= "RIFF" (.toString bytes "ascii" 0 4))
       (= "WEBP" (.toString bytes "ascii" 8 12))))

(deftest prepare-test
  (async done
    (-> (with-webp-ffmpeg
          (^:async fn []
            (testing "a large PNG becomes a WebP of the long edge, the sizes told"
              (let [image (await (images/prepare (picture 3000 1000) "image/png"))]
                (is (webp? (webp-bytes image)))
                (is (= "image/webp" (:mimeType image)))
                (is (= "image/png" (:convertedFrom image)))
                (is (= {:from {:width 3000 :height 1000} :to {:width 2000 :height 667}} (:resized image))
                    "the default limits of 2000 pixels, below max-edge")))
            (testing "a picture within the limits keeps its size and is only converted"
              (let [image (await (images/prepare (picture 640 480) "image/png"))]
                (is (nil? (:resized image)))
                (is (= "image/png" (:convertedFrom image)))))
            (testing "the model's limits cap the size too, max-edge caps the limits"
              (let [image (await (images/prepare (picture 3000 1000) "image/png" #js {:maxWidth 1000 :maxHeight 1000 :maxBytes 4000000}))]
                (is (= {:width 1000 :height 333} (:to (:resized image)))))
              (is (= {:width 2048 :height 1024} (images/target {:width 4096 :height 2048} {:maxWidth 10000 :maxHeight 10000}))))
            (testing "transparency survives: a transparent picture is a WebP with an alpha chunk"
              (let [image (await (images/prepare (picture 300 200 true) "image/png"))
                    bytes (webp-bytes image)]
                (is (webp? bytes))
                (is (or (= "VP8X" (.toString bytes "ascii" 12 16)) (= "VP8L" (.toString bytes "ascii" 12 16)))
                    "the extended or lossless container, the ones that carry alpha")))
            (testing "a byte limit too small for the quality steps shrinks the picture"
              (let [image (await (images/prepare (picture 3000 1000) "image/png" #js {:maxWidth 3000 :maxHeight 3000 :maxBytes 1200}))]
                (is (some? image))
                (is (<= (count (:data image)) 1200))
                (is (< (:width (:to (:resized image))) 2048) "below max-edge, where the quality steps alone did not fit")))
            (testing "bytes that are no picture answer nil"
              (is (nil? (await (images/prepare (js/Buffer.from "not a picture") "image/png")))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest without-ffmpeg-test
  (async done
    (let [env (.-env js/process)
          before (aget env "RRAGENT_FFMPEG")]
      (aset env "RRAGENT_FFMPEG" "/nonexistent/ffmpeg")
      (-> (images/prepare (js/Buffer.from "x") "image/png")
          (.then (fn [image] (is (nil? image) "no ffmpeg: nil, the caller keeps what it had")))
          (.finally (fn []
                      (if before (aset env "RRAGENT_FFMPEG" before) (js-delete env "RRAGENT_FFMPEG"))
                      (done)))))))
