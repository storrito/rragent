(ns rragent.recordings
  "A screencast recorded in the browser (`rragent.client.recorder`):
   the screen and the webcam as two files, both with the microphone,
   so that an editor, or the agent, aligns them by the waveform (the
   idea of rawcast, github.com/maxweber/rawcast). They land in
   `artifacts/recordings/<stamp>/` of the bot: `screen.webm` and
   `webcam.webm`, and `recording.json` with the devices and when each
   recorder started.

   `POST /bot/<id>/record/<stamp>/<screen|webcam>?offset=<n>` appends
   a piece of a file as it is recorded, to a hidden part file (the
   pane lists no hidden file); the piece must start where the part
   file ends, as the pieces of an upload do (`rragent.uploads`).
   `POST /bot/<id>/record/<stamp>/finish`, with the facts of the
   recording as transit, ends it: the part files become the files,
   remuxed by ffmpeg when there is one, so that they carry their
   duration and seek (a recorder's file has neither), and the facts
   are written beside them."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.programs :as programs]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]
            [rragent.uploads :as uploads]))

(def path-prefix
  "Where the browser posts a recording, under the bot's page."
  "/record")

(def stamp-pattern
  "The folder's name, the time where the person is: 2026-10-21T11-56-03."
  #"\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}")

(def names
  #{"screen" "webcam"})

(def max-bytes
  "The largest file, ten gigabytes: hours of a screen."
  (* 10 1024 1024 1024))

(defn folder
  "Where the recording `stamp` lives under the artifacts `dir`."
  [dir stamp]
  (path/join dir "recordings" stamp))

(defn- part-file
  [dir stamp name]
  (path/join (folder dir stamp) (str "." name ".webm.part")))

(defn- file
  [dir stamp name]
  (path/join (folder dir stamp) (str name ".webm")))

(defonce ^:private writing
  ;; the files a piece is being written to
  (atom #{}))

(defn- ^:async piece!
  "One piece of a file: it must start where the part file ends. A piece
   that does not is answered with how much the file holds (409), one
   while another piece is still being written waits (503, the browser
   tries again)."
  [dir w stamp name {:strs [offset]}]
  (let [offset (parse-long (str offset))
        part (part-file dir stamp name)
        received (uploads/file-size part)]
    (cond
      (not offset)
      (uploads/respond w 400 {:error :bad-piece})

      (contains? @writing [stamp name])
      (uploads/respond w 503 {:error :busy})

      (not= offset received)
      (uploads/respond w 409 {:error :out-of-order
                              :received received})

      :else
      (do (fs/mkdirSync (folder dir stamp) #js {:recursive true})
          (swap! writing conj [stamp name])
          (let [length (try
                         (await (uploads/copy-counted! (:body (:ring/request w)) part max-bytes nil))
                         (finally
                           (swap! writing disj [stamp name])))]
            (if (nil? length)
              (uploads/respond w 413 {:error :too-large})
              (uploads/respond w 200 {:received length})))))))

(defn- remux
  "A promise of the file written by ffmpeg from the part, with its
   duration and cues; rejected when ffmpeg is not there or fails."
  [ffmpeg part out]
  (js/Promise.
   (fn [resolve reject]
     (if-not ffmpeg
       (reject (js/Error. "no ffmpeg"))
       (child-process/execFile ffmpeg
                               #js ["-y" "-loglevel" "error" "-i" part "-c" "copy" out]
                               #js {:timeout 600000}
                               (fn [error _stdout stderr]
                                 (if error
                                   (reject (js/Error. (str (.-message error) " " stderr)))
                                   (resolve out))))))))

(defn- ^:async finalize!
  "The part file as the file: through ffmpeg when it is there and
   takes it, renamed otherwise."
  [dir stamp name]
  (let [part (part-file dir stamp name)
        out (file dir stamp name)]
    (try
      (await (remux (programs/locate "ffmpeg") part out))
      (fs/rmSync part #js {:force true})
      (catch :default error
        (js/console.warn "[recordings] kept as recorded, no remux:" (str (.-message error)))
        (fs/rmSync out #js {:force true})
        (fs/renameSync part out)))))

(defn- ^:async finish!
  "The recording is over: both files finalized, the facts beside them,
   the folder answered as the agent names it."
  [dir w stamp]
  (let [facts (try
                (await (transit/read-stream (:body (:ring/request w))))
                (catch :default _
                  nil))
        missing (sort (remove #(http/regular-file? (part-file dir stamp %)) names))]
    (cond
      (not (map? facts))
      (uploads/respond w 400 {:error :bad-facts})

      (seq missing)
      (uploads/respond w 409 {:error :not-recorded
                              :missing (vec missing)})

      :else
      (do (doseq [name names]
            (await (finalize! dir stamp name)))
          (fs/writeFileSync (path/join (folder dir stamp) "recording.json")
                            (js/JSON.stringify (clj->js (assoc facts :files ["screen.webm" "webcam.webm"])) nil 2))
          (uploads/respond w 200 {:folder (str "recordings/" stamp)
                                  :files ["screen.webm" "webcam.webm"]})))))

(defn ^:async handler
  "`POST /record/<stamp>/<name>` into the artifacts `dir`: a piece of
   the file, or `finish` for the recording's end."
  [dir w]
  (let [[stamp name & more] (str/split (str (get-in w [:ring/route-params :*])) #"/")]
    (cond
      (or (not (re-matches stamp-pattern (str stamp))) (seq more))
      (uploads/respond w 400 {:error :bad-recording})

      (= "finish" name)
      (await (finish! dir w stamp))

      (names name)
      (await (piece! dir w stamp name (uploads/query-params (:ring/request w))))

      :else
      (uploads/respond w 404 {:error :no-such-file}))))
