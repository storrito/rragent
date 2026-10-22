(ns rragent.dictation
  "A message spoken instead of typed: the microphone button of the
   composer (`rragent.client`) records the person and posts the
   recording to `POST /dictation`, which has it transcribed by a
   speech-to-text model behind OpenRouter (`/audio/transcriptions`) and
   answers the text, which the browser puts into the draft. The
   recording is not kept. The request is `rragent.transcription`'s,
   the `transcribe` tool's too: the model scores every word, so the
   answer carries the words with their confidence (0 to 1) besides
   the text, for the correction (`rragent.correction`)."
  (:require [clojure.string :as str]
            [rragent.correction :as correction]
            [rragent.rframes.transit :as transit]
            [rragent.transcription :as transcription]))

(def path
  "Where the browser posts a recording."
  "/dictation")

(def model
  transcription/model)

(def max-bytes
  "The largest recording (`rragent.transcription`)."
  transcription/max-bytes)

(def formats
  "The format OpenRouter is told by the media type of the recording:
   Chrome and Firefox record WebM or Ogg with Opus, Safari MP4 with AAC."
  {"audio/webm" "webm"
   "video/webm" "webm"
   "audio/ogg" "ogg"
   "audio/mp4" "m4a"
   "video/mp4" "m4a"
   "audio/mpeg" "mp3"
   "audio/wav" "wav"
   "audio/x-wav" "wav"})

(defn- format-of
  [request]
  (-> (str (get-in request [:headers "content-type"]))
      (str/split #";")
      (first)
      (str/trim)
      (str/lower-case)
      (formats)))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn- read-body
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

(defn answer
  "The answer for the browser from OpenRouter's response: the text, the
   words with their confidence, nil where the model gave none, and
   whether a word is below the threshold of a correction
   (`rragent.correction`)."
  [response]
  (let [words (mapv (fn [{:keys [word confidence]}]
                      {:word word
                       :confidence confidence})
                    (:words response))]
    {:text (str/trim (str (:text response)))
     :words words
     :unsure? (boolean (some #(and (number? (:confidence %))
                                   (< (:confidence %) correction/unsure-below))
                             words))}))

(defn ^:async transcribe!
  "The transcription of `audio` by OpenRouter, where `codemode` knows
   it (`rragent.transcription`), as `answer`; throws with the
   service's message, or with why there is no OpenRouter."
  [codemode audio format]
  (answer (await (transcription/transcription! codemode audio format "dictation"))))

(defn ^:async handler
  "`POST /dictation`: the recording as the body with its media type,
   the text it says as the answer, by the OpenRouter `codemode` knows."
  [codemode w]
  (let [request (:ring/request w)
        format (format-of request)]
    (if-not format
      (respond w 415 {:error :not-audio})
      (let [audio (await (read-body (:body request) max-bytes))]
        (cond
          (nil? audio)
          (respond w 413 {:error :too-large})

          (zero? (.-length audio))
          (respond w 400 {:error :empty})

          :else
          (try
            (respond w 200 (await (transcribe! codemode audio format)))
            (catch :default error
              (js/console.error "[dictation]" (.-message error))
              (respond w 502 {:error :transcription-failed
                              :message (.-message error)}))))))))

(defn routes
  "The route of the agent whose REPL is `codemode`."
  [codemode]
  [{:ring/route [:post path]
    :ring/handler (fn [w] (handler codemode w))}])
