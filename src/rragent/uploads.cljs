(ns rragent.uploads
  "Files the person attaches to a message: a video or an audio clip, a
   picture pasted, dropped or picked. They land in `work/uploads/` of
   the agent's folder (`rragent.home`), where the agent, whose tools
   work in `work/`, reaches a file as `uploads/<name>`, and the message
   names them, one line per file, `video: uploads/ab12.mp4` (`line`); a
   picture goes to the model as an image block besides (`content`). The
   port of pchat.uploads to Node streams.

   `POST /upload` takes the file as the request body with its media
   type. A picture is content-addressed, named by the hash of its bytes,
   so the same picture twice is one file; the browser scales it before,
   `image-max-bytes` is the cap. A clip is written to disk as it comes:
   the browser sends it in pieces, `?id=<hex>&offset=<n>&total=<n>` on
   each, appended to a hidden part file and renamed when the last piece
   is in, so a lost connection costs one piece, not the clip; a clip in
   one request (no `id`) is named by its hash. `max-bytes` caps a clip
   at ten gigabytes. `GET /uploads/<name>` serves the files, with ranges
   for the players. Both are under the bot's page, `/bot/<id>`, the
   `base` the handler is given, which the URLs it answers carry."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.images :as images]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(def upload-path
  "Where the browser posts a file."
  "/upload")

(def url-prefix
  "Where the files are served."
  "/uploads")

(def max-bytes
  "The largest clip, ten gigabytes: a recording of two or three hours
   in 4K."
  (* 10 1024 1024 1024))

(def image-max-bytes
  "The largest picture; the browser scales a picture before it sends it."
  (* 10 1024 1024))

(def images
  "The media types of a picture, with the extension of the stored file."
  {"image/png" "png"
   "image/jpeg" "jpg"
   "image/webp" "webp"
   "image/gif" "gif"})

(def media
  "The media types of a clip, with the extension of the stored file and
   its kind. The browser names some of them in more than one way."
  {"video/mp4" ["mp4" :video]
   "video/quicktime" ["mov" :video]
   "video/webm" ["webm" :video]
   "video/x-matroska" ["mkv" :video]
   "audio/mpeg" ["mp3" :audio]
   "audio/mp3" ["mp3" :audio]
   "audio/wav" ["wav" :audio]
   "audio/x-wav" ["wav" :audio]
   "audio/wave" ["wav" :audio]
   "audio/vnd.wave" ["wav" :audio]
   "audio/mp4" ["m4a" :audio]
   "audio/x-m4a" ["m4a" :audio]
   "audio/aac" ["aac" :audio]
   "audio/ogg" ["ogg" :audio]
   "audio/flac" ["flac" :audio]
   "audio/x-flac" ["flac" :audio]})

(def kinds
  "The kind of a stored file by its extension."
  (into (into {} (vals media))
        (map (fn [extension] [extension :image]))
        (vals images)))

(defn- extension-of
  [file-name]
  (str/lower-case (or (second (re-find #"\.([^./]+)$" (str file-name))) "")))

(defn kind
  "`:video`, `:audio` or `:image` for the name of a stored file, else nil."
  [file-name]
  (kinds (extension-of file-name)))

(defn media-type
  "The media type of a stored file, for an image block."
  [file-name]
  (http/content-type (str file-name)))

(defn- content-type
  [request]
  (some-> (get-in request [:headers "content-type"])
          (str/split #";")
          (first)
          (str/trim)
          (str/lower-case)))

(defn query-params
  [request]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [k (or v "")]))))
        (str/split (str (:query-string request)) #"&")))

(defn respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn read-body
  "A promise of the bytes of the readable `in` as a Buffer, or of nil
   when they are more than `limit`."
  [^js in limit]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []
           length (atom 0)]
       (.on in "error" reject)
       (.on in "end" (fn [] (resolve (js/Buffer.concat chunks))))
       (.on in "data" (fn [^js chunk]
                        (if (< limit (swap! length + (.-length chunk)))
                          (do (.removeAllListeners in "data")
                              (.removeAllListeners in "end")
                              (.resume in)
                              (resolve nil))
                          (.push chunks chunk))))))))

(defn file-size
  "The bytes of `file`, 0 without it."
  [file]
  (try
    (.-size (fs/statSync file))
    (catch :default _
      0)))

(defn- remove!
  [file]
  (fs/rmSync file #js {:force true}))

(defn- stored
  "The answer for a file that is complete, its URL under `base`."
  [dir base file-name]
  {:name file-name
   :url (str base url-prefix "/" file-name)
   :kind (kind file-name)
   :size (file-size (path/join dir file-name))})

(defn- hex
  "The first 16 bytes of the digest as hex, the name of a stored file."
  [^js digest]
  (subs (.digest digest "hex") 0 32))

(defn copy-counted!
  "Appends the readable `in` to `file`, at most `limit` bytes in all, and
   answers a promise of the bytes the file holds then, or of nil when it
   would grow past `limit`; what came after the limit is dropped.
   `digest` sees every byte when given. The writer's backpressure pauses
   the reader."
  [^js in file limit ^js digest]
  (js/Promise.
   (fn [resolve reject]
     (let [out (fs/createWriteStream file #js {:flags "a"})
           length (atom (file-size file))
           settle (fn [value]
                    (.end out (fn [] (resolve value))))]
       (.on out "error" reject)
       ;; a request broken off: what came is written out before the next
       ;; piece asks how much the part file holds
       (.on in "error" (fn [error]
                         (.end out (fn [] (reject error)))))
       (.on in "end" (fn [] (settle @length)))
       (.on in "data"
            (fn [^js chunk]
              (if (< limit (+ @length (.-length chunk)))
                (do (.removeAllListeners in "data")
                    (.removeAllListeners in "end")
                    (settle nil))
                (do (swap! length + (.-length chunk))
                    (some-> digest (.update chunk))
                    (when-not (.write out chunk)
                      (.pause in)
                      (.once out "drain" (fn [] (.resume in))))))))))))

(defn- ^:async whole!
  "A file in one request, named by the hash of its bytes, at most
   `limit` bytes."
  [dir base w extension limit]
  (let [digest (crypto/createHash "sha256")
        part (path/join dir (str "." (random-uuid) ".part"))
        length (await (copy-counted! (:body (:ring/request w)) part limit digest))]
    (cond
      (nil? length)
      (do (remove! part)
          (respond w 413 {:error :too-large}))

      (zero? length)
      (do (remove! part)
          (respond w 400 {:error :empty}))

      :else
      (let [file-name (str (hex digest) "." extension)
            file (path/join dir file-name)]
        (if (http/regular-file? file)
          (remove! part)
          (fs/renameSync part file))
        (respond w 200 (stored dir base file-name))))))

(defonce ^:private writing
  ;; the ids of the clips a piece is being written to
  (atom #{}))

(defn- ^:async piece!
  "One piece of a clip sent in pieces: it must start where the part file
   ends; the last one completes the file. A piece of a clip already
   complete is answered with the file: the answer to its last piece was
   lost, and the browser sends it again. A piece of a clip whose last
   piece the server still writes waits (503, the browser tries again):
   two writers of one part file would mix their bytes."
  [dir base w extension {:strs [id offset total]}]
  (let [offset (parse-long (str offset))
        total (parse-long (str total))
        part (path/join dir (str "." id "." extension ".part"))
        file-name (str id "." extension)
        received (file-size part)]
    (cond
      (not (and (re-matches #"[0-9a-f]{16,64}" (str id)) offset total (pos? total)))
      (respond w 400 {:error :bad-piece})

      (< max-bytes total)
      (respond w 413 {:error :too-large})

      (contains? @writing id)
      (respond w 503 {:error :busy})

      (and (pos? offset)
           (http/regular-file? (path/join dir file-name))
           (= total (file-size (path/join dir file-name))))
      (respond w 200 (stored dir base file-name))

      (not= offset (if (zero? offset) 0 received))
      (respond w 409 {:error :out-of-order
                      :received received})

      :else
      (do (when (zero? offset)
            (remove! part))
          (swap! writing conj id)
          (let [length (try
                         (await (copy-counted! (:body (:ring/request w)) part total nil))
                         (finally
                           (swap! writing disj id)))]
            (cond
              (nil? length)
              (do (remove! part)
                  (respond w 400 {:error :longer-than-total}))

              (= length total)
              (do (fs/renameSync part (path/join dir file-name))
                  (respond w 200 (stored dir base file-name)))

              :else
              (respond w 200 {:received length})))))))

(defn ^:async handler
  "`POST /upload` into `dir`: a picture content-addressed, a clip as it
   comes, any other type a 415; the URLs answered under `base`."
  [dir base w]
  (let [request (:ring/request w)
        type (content-type request)
        params (query-params request)]
    (fs/mkdirSync dir #js {:recursive true})
    (cond
      (images type)
      (await (whole! dir base w (images type) image-max-bytes))

      (not (media type))
      (respond w 415 {:error :not-media})

      (get params "id")
      (await (piece! dir base w (first (media type)) params))

      :else
      (await (whole! dir base w (first (media type)) max-bytes)))))

(defn file-handler
  "`GET /uploads/<name>`: the file of `dir`, never a hidden one (a part
   file still being written)."
  [dir w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        file (when-not (re-find #"(^|/)\." subpath)
               (http/resolve-file dir subpath))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(defn routes
  "The register entries of the uploads folder `dir` under `base`: the
   POST that takes a file and the GET that serves one."
  [dir base]
  [{:ring/route [:post (str base upload-path)]
    :ring/handler (fn [w] (handler dir base w))}
   {:ring/route [:get (str base url-prefix "/*")]
    :ring/handler (fn [w] (file-handler dir w))}])

(def line-pattern
  "A line the page adds to a message for each attached file: its kind,
   then its path under the agent's working directory."
  #"(?m)^(video|audio|image): (uploads/[A-Za-z0-9]+\.[a-z0-9]+)$")

(defn line
  "The line of a message that names an attached file."
  [kind file-name]
  (str (name kind) ": uploads/" file-name))

(defn attachments
  "The files a message's text names, for the page: `[{:kind :video
   :name \"ab12.mp4\"}]`, each a file under the uploads folder."
  [text]
  (vec (for [[_ kind p] (re-seq line-pattern (str text))]
         {:kind (keyword kind)
          :name (subs p (count "uploads/"))})))

(defn without-lines
  "The text of a message without the lines that name files."
  [text]
  (str/trim (str/replace (str text) line-pattern "")))

(defn named
  "The attachments among `names` that are files of `dir`, with their
   kinds: what the composer sent that the message may name."
  [dir names]
  (vec (for [file-name names
             :let [file-name (str file-name)
                   kind (kind file-name)]
             :when (and kind
                        (re-matches #"[A-Za-z0-9]+\.[a-z0-9]+" file-name)
                        (http/regular-file? (path/join dir file-name)))]
         {:kind kind
          :name file-name})))

(defn ^:async image-block
  "The picture `file-name` of `dir` as the image block of a user
   message: as `rragent.images` prepares it for the model (a WebP
   within pi-durable's default limits), the file as it is when that
   is not possible (no ffmpeg). The file itself is untouched."
  [dir file-name]
  (let [bytes (fs/readFileSync (path/join dir file-name))
        mime-type (media-type file-name)
        image (await (images/prepare bytes mime-type))]
    {:type "image"
     :data (if image (:data image) (.toString bytes "base64"))
     :mimeType (if image (:mimeType image) mime-type)}))

(def no-vision-note
  "The line in place of the image blocks when the model takes no images."
  "(The model does not take pictures; the files above are there for the tools.)")

(defn ^:async content
  "A promise of the user message of `text` with the `attachments` of
   `dir` (what `named` answered): the text with a line per file, which
   the agent reads; and when `vision?`, the model takes images, the
   pictures as image blocks besides (`image-block`), so the model sees
   them; without, a note that it does not, and no block, which the
   transcript would carry for nothing."
  [dir text attachments vision?]
  (let [pictures (filter #(= :image (:kind %)) attachments)
        text (str/join "\n" (remove str/blank? (cons (str/trim (str text))
                                                     (map #(line (:kind %) (:name %)) attachments))))]
    (cond
      (empty? pictures) text
      vision? (into [{:type "text" :text text}]
                    (array-seq (await (js/Promise.all (into-array (map #(image-block dir (:name %)) pictures))))))
      :else (str text "\n" no-vision-note))))

(comment
  (attachments "Cut this\nvideo: uploads/ab12.mp4\nimage: uploads/ff00.png")
  (without-lines "Cut this\nvideo: uploads/ab12.mp4")
  (content "/tmp/uploads" "Cut this" [{:kind :video :name "ab12.mp4"}] true)
  )
