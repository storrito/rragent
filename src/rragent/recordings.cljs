(ns rragent.recordings
  "A screencast recorded in the browser (`rragent.client.recorder`):
   the screen and the webcam as two files, both with the microphone,
   so that an editor, or the agent, aligns them by the waveform (the
   idea of rawcast, github.com/maxweber/rawcast). They land in
   `artifacts/recordings/<stamp>/` of the bot: `screen.webm` and
   `webcam.webm`, and `recording.json` with the devices and when each
   recorder started. The microphone alone is a recording too, for a
   voice memo the agent cuts, cleans and writes down
   (`rragent.tools.transcribe`): `audio.webm` as the browser records
   it, made into `audio.mp3` by ffmpeg at the end when there is one.

   `POST /bot/<id>/record/<stamp>/<screen|webcam|phone|audio>?offset=<n>&ext=<webm|mp4>`
   appends a piece of a file as it is recorded, to a hidden part file
   (the pane lists no hidden file); the piece must start where the
   part file ends, as the pieces of an upload do (`rragent.uploads`).
   `POST /bot/<id>/record/<stamp>/finish`, with the facts of the
   recording as transit (`:files`, name to extension, the laptop's two
   without a word), ends it: the part files become the files, remuxed
   by ffmpeg when there is one, so that they carry their duration and
   seek (a recorder's file has neither), the microphone's encoded to
   MP3, and the facts are written beside them. The phone's file comes
   from the phone itself (`rragent.phone`)."
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
  "The files of a recording: the screen and the webcam from the laptop,
   the phone's camera from the phone (`rragent.phone`), the microphone
   alone as audio."
  #{"screen" "webcam" "phone" "audio"})

(def audio-name
  "The file of the microphone alone, which ffmpeg makes an MP3 of."
  "audio")

(def extensions
  "What a recorder writes: webm in Chrome, mp4 on an iPhone."
  #{"webm" "mp4"})

(def default-files
  {"screen" "webm" "webcam" "webm"})

(def max-bytes
  "The largest file, ten gigabytes: hours of a screen."
  (* 10 1024 1024 1024))

(def test-stamp
  "The stamp of the test recording: the pieces of a test go into a
   hidden folder the pane does not list, written over by the next."
  "test")

(defn stamp?
  [stamp]
  (or (= test-stamp stamp) (boolean (re-matches stamp-pattern (str stamp)))))

(defn folder
  "Where the recording `stamp` lives under the artifacts `dir`; the
   test's folder is hidden."
  [dir stamp]
  (path/join dir "recordings" (if (= test-stamp stamp) ".test" stamp)))

(defn- part-file
  [dir stamp name ext]
  (path/join (folder dir stamp) (str "." name "." ext ".part")))

(defn- file
  [dir stamp name ext]
  (path/join (folder dir stamp) (str name "." ext)))

(defonce ^:private writing
  ;; the files a piece is being written to
  (atom #{}))

(defn- ^:async piece!
  "One piece of a file: it must start where the part file ends. A piece
   that does not is answered with how much the file holds (409), one
   while another piece is still being written waits (503, the browser
   tries again)."
  [dir w stamp name {:strs [offset ext]}]
  (let [offset (parse-long (str offset))
        ext (or ext "webm")
        part (part-file dir stamp name ext)
        received (uploads/file-size part)]
    (cond
      (not (and offset (extensions ext)))
      (uploads/respond w 400 {:error :bad-piece})

      (contains? @writing [stamp name])
      (uploads/respond w 503 {:error :busy})

      (and (pos? offset) (not= offset received))
      (uploads/respond w 409 {:error :out-of-order
                              :received received})

      :else
      (do (fs/mkdirSync (folder dir stamp) #js {:recursive true})
          ;; the first piece starts the file over: a test again, or the
          ;; first piece sent again after a lost answer
          (when (zero? offset)
            (fs/rmSync part #js {:force true}))
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
   duration and cues, the streams copied; the microphone's encoded to
   MP3 (LAME's quality 4, some 165 kbit/s, what every editor takes);
   rejected when ffmpeg is not there or fails."
  [ffmpeg part out]
  (js/Promise.
   (fn [resolve reject]
     (if-not ffmpeg
       (reject (js/Error. "no ffmpeg"))
       (child-process/execFile ffmpeg
                               (clj->js (concat ["-y" "-loglevel" "error" "-i" part]
                                                (if (str/ends-with? out ".mp3")
                                                  ["-vn" "-codec:a" "libmp3lame" "-q:a" "4"]
                                                  ["-c" "copy"])
                                                [out]))
                               #js {:timeout 600000}
                               (fn [error _stdout stderr]
                                 (if error
                                   (reject (js/Error. (str (.-message error) " " stderr)))
                                   (resolve out))))))))

(defn- ^:async finalize!
  "The part file as the file, answered as its name: through ffmpeg
   when it is there and takes it, the microphone's as an MP3 then,
   renamed as it came otherwise."
  [dir stamp name ext]
  (let [part (part-file dir stamp name ext)
        made (str name "." (if (= audio-name name) "mp3" ext))
        out (path/join (folder dir stamp) made)]
    (try
      (await (remux (programs/locate "ffmpeg") part out))
      (fs/rmSync part #js {:force true})
      made
      (catch :default error
        (js/console.warn "[recordings] kept as recorded, no remux:" (str (.-message error)))
        (fs/rmSync out #js {:force true})
        (fs/renameSync part (file dir stamp name ext))
        (str name "." ext)))))

(defn- files-of
  "The files the facts name, name to extension, the laptop's two
   without a word; nil when there is none, or a name or an extension
   is not one of ours. The phone alone is a recording too."
  [facts]
  (let [files (or (:files facts) default-files)]
    (when (and (map? files)
               (seq files)
               (every? names (keys files))
               (every? extensions (vals files)))
      files)))

(defn- ^:async finish!
  "The recording is over: every file finalized, the facts beside them,
   the folder answered as the agent names it. A recording with the
   phone's file waits for the phone to say its file is up
   (`phone-done?`, 409 until then, the laptop asks again)."
  [dir {:keys [phone-done?]} w stamp]
  (let [facts (try
                (await (transit/read-stream (:body (:ring/request w))))
                (catch :default _
                  nil))
        files (when (map? facts) (files-of facts))
        missing (sort (remove (fn [[name ext]] (http/regular-file? (part-file dir stamp name ext))) files))]
    (cond
      (not (map? facts))
      (uploads/respond w 400 {:error :bad-facts})

      (nil? files)
      (uploads/respond w 400 {:error :bad-files})

      (seq missing)
      (uploads/respond w 409 {:error :not-recorded
                              :missing (mapv first missing)})

      ;; the phone ends its own test, so the test waits for nobody
      (and (contains? files "phone") phone-done? (not= test-stamp stamp) (not (phone-done? stamp)))
      (uploads/respond w 409 {:error :phone-uploading})

      :else
      (let [made (loop [files (sort-by first files)
                        made []]
                   (if-let [[name ext] (first files)]
                     (recur (rest files) (conj made (await (finalize! dir stamp name ext))))
                     made))]
        (fs/writeFileSync (path/join (folder dir stamp) "recording.json")
                          (js/JSON.stringify (clj->js (assoc facts :files made)) nil 2))
        (uploads/respond w 200 {:folder (str "recordings/" stamp)
                                :files made})))))

(defn ^:async handler
  "`POST /record/<stamp>/<name>` into the artifacts `dir`: a piece of
   the file, or `finish` for the recording's end. `opts` has
   `:phone-done?`, whether the phone's file of a stamp is up."
  [dir opts w]
  (let [[stamp name & more] (str/split (str (get-in w [:ring/route-params :*])) #"/")]
    (cond
      (or (not (stamp? stamp)) (seq more))
      (uploads/respond w 400 {:error :bad-recording})

      (= "finish" name)
      (await (finish! dir opts w stamp))

      (names name)
      (await (piece! dir w stamp name (uploads/query-params (:ring/request w))))

      :else
      (uploads/respond w 404 {:error :no-such-file}))))

(def file-pattern
  "A finished file of a recording, as `get-handler` serves it."
  #"(screen|webcam|phone|audio)\.(webm|mp4|mp3)")

(defn get-handler
  "`GET /record/<stamp>/<file>`: a finished file of a recording, the
   test's among them, for the panel to play."
  [dir w]
  (let [[stamp file & more] (str/split (str (get-in w [:ring/route-params :*])) #"/")
        path (when (and (stamp? stamp) (re-matches file-pattern (str file)) (empty? more))
               (path/join (folder dir stamp) file))]
    (assoc w :ring/response
           (if (and path (http/regular-file? path))
             (http/file-response (:ring/request w) path)
             http/not-found))))
