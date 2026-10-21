(ns rragent.dictation
  "A message spoken instead of typed: the microphone button of the
   composer (`rragent.client`) records the person and posts the
   recording to `POST /dictation`, which has it transcribed by a
   speech-to-text model behind OpenRouter (`/audio/transcriptions`) and
   answers the text, which the browser puts into the draft. The
   recording is not kept.

   The model is RRAGENT_DICTATION_MODEL, AssemblyAI's Universal-3.6 Pro
   by default, half a second for ten seconds of speech: it scores every
   word it hears, so the answer carries the words with their confidence
   (0 to 1) besides the text. Of the models behind OpenRouter only it
   and ElevenLabs' Scribe v2 score words (October 2026, Deepgram's
   Nova-3 among those that do not); its scores are the lower ones where
   it misheard, a name it did not know at 0.2. No language is named,
   the model detects the one spoken. The request goes where the REPL
   `codemode` of the agent knows OpenRouter: through the gateway's
   connection, else with OPENROUTER_API_KEY (`rragent.openrouter`, as
   the models and the media tools have it)."
  (:require [clojure.string :as str]
            [rragent.correction :as correction]
            [rragent.openrouter :as openrouter]
            [rragent.rframes.transit :as transit]))

(def path
  "Where the browser posts a recording."
  "/dictation")

(def model
  (or (not-empty (aget (.-env js/process) "RRAGENT_DICTATION_MODEL"))
      "assemblyai/universal-3-6-pro"))

(def max-bytes
  "The largest recording, OpenRouter's cap of audio sent inline; the
   browser sends a piece of 30 seconds at most
   (`rragent.client.dictation`)."
  (* 25 1024 1024))

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

(defn request-body
  "The JSON body of the transcription request: the words with their
   timestamps and, where the model scores them, their confidence come
   only with `verbose_json`."
  [^js audio format]
  {:model model
   :input_audio {:data (.toString audio "base64")
                 :format format}
   :response_format "verbose_json"
   :timestamp_granularities ["word"]
   :temperature 0})

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
   it (`rragent.openrouter/route!`), as `answer`; throws with the
   service's message, or with why there is no OpenRouter."
  [codemode audio format]
  (let [{:keys [base-url headers]} (openrouter/route! codemode "dictation")
        response (await (js/fetch (str base-url "/audio/transcriptions")
                                  #js {:method "POST"
                                       :headers (clj->js (assoc headers "Content-Type" "application/json"))
                                       :body (js/JSON.stringify (clj->js (request-body audio format)))
                                       :signal (js/AbortSignal.timeout 90000)}))
        text (await (.text response))
        body (try (js->clj (js/JSON.parse text) :keywordize-keys true) (catch :default _ nil))]
    (when-not (.-ok response)
      (throw (js/Error. (str "The transcription service answered " (.-status response) ": "
                             (or (get-in body [:error :message]) text)))))
    (answer body)))

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
