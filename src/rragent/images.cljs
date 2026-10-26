(ns rragent.images
  "Pictures made fit for the model: pi-durable's `ImageProcessor` on
   ffmpeg, which the `read` tool takes (`rragent.harness`) and the
   uploads use (`rragent.uploads`). Every picture the model gets is a
   lossy WebP of quality 80, transparency kept, scaled down to the
   long edge `max-edge` and to the limits the caller passes (the
   model's, pi-durable's `ImageLimits`: maxWidth, maxHeight, maxBytes
   of base64), never up. A screenshot or a frame then costs a hundred
   kilobytes in the context instead of a megabyte as PNG: the Sprites
   gateway forwards a request of at most 8,000,000 bytes, and pictures
   are what fills it (see `rragent.model-requests`). The file on disk
   stays as it is, only what the model sees is converted. Every
   provider takes WebP inline (OpenAI, Anthropic, Google). ffmpeg and
   ffprobe come from `rragent.programs` (RRAGENT_FFMPEG,
   RRAGENT_FFPROBE or the PATH); without them, or for bytes ffmpeg
   cannot read, `prepare` answers nil, said on stderr, and the caller
   falls back to what it had."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.programs :as programs]))

(def max-edge
  "The most pixels of the long edge, what the providers look at:
   OpenAI fits a picture into 2048, Anthropic into 1568."
  2048)

(def quality
  "WebP's quality, 0 to 100; below it the steps tried when the
   picture must still shrink to fit the byte limit."
  80)

(def lower-qualities
  [60 40])

(def default-limits
  "pi-durable's `DEFAULT_IMAGE_LIMITS`: what the uploads use, which
   know no model."
  {:maxWidth 2000 :maxHeight 2000 :maxBytes (* 4.5 1024 1024)})

(defn base64-length
  "The length of the base64 of `n` bytes."
  [n]
  (* 4 (js/Math.ceil (/ n 3))))

(defn- run
  "A promise of the stdout of `program` with `args`, rejected with
   its stderr when it fails or is not there."
  [program args]
  (js/Promise.
   (fn [resolve reject]
     (child-process/execFile program (clj->js args) #js {:timeout 60000 :maxBuffer (* 64 1024 1024)}
                             (fn [error stdout stderr]
                               (if error
                                 (reject (js/Error. (str (.-message error) " " stderr)))
                                 (resolve stdout)))))))

(defn- ^:async dimensions
  "`{:width :height}` of the picture in `file` as ffprobe reads them;
   nil for a file that is no picture."
  [ffprobe file]
  (let [out (await (run ffprobe ["-v" "error" "-select_streams" "v:0" "-show_entries" "stream=width,height" "-of" "csv=p=0" file]))
        [width height] (map #(js/parseInt % 10) (str/split (str/trim (str out)) #","))]
    (when (and (pos? width) (pos? height))
      {:width width :height height})))

(defn target
  "The size the picture takes: scaled down to fit `max-edge` and the
   limits, never up, the proportions kept."
  [{:keys [width height]} {:keys [maxWidth maxHeight]}]
  (let [scale (min 1
                   (/ (min max-edge (or maxWidth max-edge)) width)
                   (/ (min max-edge (or maxHeight max-edge)) height))]
    {:width (max 1 (js/Math.round (* width scale)))
     :height (max 1 (js/Math.round (* height scale)))}))

(defn- smaller
  "Three quarters of `size`, the step down when the bytes do not fit
   at the lowest quality."
  [{:keys [width height]}]
  {:width (max 1 (js/Math.floor (* 0.75 width)))
   :height (max 1 (js/Math.floor (* 0.75 height)))})

(defn- ^:async encode!
  "The picture `in` as a WebP of `size` and `quality` at `out`, its
   bytes. One frame: an animated GIF gives its first."
  [ffmpeg in out {:keys [width height]} quality]
  (await (run ffmpeg ["-y" "-loglevel" "error" "-i" in
                      "-frames:v" "1"
                      "-vf" (str "scale=" width ":" height ":flags=lanczos")
                      "-c:v" "libwebp" "-quality" (str quality) "-compression_level" "4"
                      "-f" "webp" out]))
  (fs/readFileSync out))

(defn- prepared
  "pi-durable's `PreparedImage` of the `encoded` bytes: the base64,
   the type, where it came from when converted, the sizes when
   resized."
  [encoded mime-type from to]
  (cond-> {:data (.toString encoded "base64")
           :mimeType "image/webp"}
    (not= "image/webp" mime-type) (assoc :convertedFrom mime-type)
    (not= from to) (assoc :resized {:from from :to to})))

(defn ^:async prepare
  "The picture of `bytes` (a Uint8Array or Buffer) of `mime-type` as
   the model gets it, pi-durable's `PreparedImage` as data (`:data`
   base64, `:mimeType`, `:convertedFrom`, `:resized`), within `limits`
   (`:maxWidth :maxHeight :maxBytes`, `default-limits` without):
   WebP at `quality`, scaled to `target`; when the base64 is still
   over `maxBytes`, the lower qualities, then three quarters of the
   size again and again. nil without ffmpeg and ffprobe, or for bytes
   that are no picture."
  [bytes mime-type & [limits]]
  (let [ffmpeg (programs/locate "ffmpeg")
        ffprobe (programs/locate "ffprobe")
        limits (merge default-limits (js->clj limits :keywordize-keys true))]
    (when (and ffmpeg ffprobe)
      (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-image-"))
            in (path/join dir "in")
            out (path/join dir "out.webp")]
        (try
          (fs/writeFileSync in bytes)
          (when-let [size (await (dimensions ffprobe in))]
            (loop [to (target size limits)
                   qualities (cons quality lower-qualities)]
              (let [encoded (await (encode! ffmpeg in out to (first qualities)))]
                (cond
                  (<= (base64-length (.-length encoded)) (:maxBytes limits))
                  (prepared encoded mime-type size to)

                  (next qualities)
                  (recur to (rest qualities))

                  (or (< 1 (:width to)) (< 1 (:height to)))
                  (recur (smaller to) [(last lower-qualities)])

                  :else nil))))
          (catch :default e
            (js/console.warn (str "[images] a picture (" mime-type ", " (.-length bytes) " bytes) could not be prepared for the model: " (.-message e)))
            nil)
          (finally
            (fs/rmSync dir #js {:recursive true :force true})))))))

(def processor
  "The `ImageProcessor` for pi-durable's `read` tool
   (`createCodingTools({images})`): `prepare` answering JS."
  #js {:prepare (fn [bytes mime-type limits]
                  (.then (prepare bytes mime-type limits)
                         (fn [image] (if image (clj->js image) js/undefined))))})

(comment
  (.then (prepare (fs/readFileSync "/tmp/frame.png") "image/png")
         (fn [image] (println (:mimeType image) (count (:data image)) (:resized image))))
  )
