(ns rragent.transcription
  "One request to the speech-to-text model behind OpenRouter
   (`/audio/transcriptions`), for the dictation of the composer
   (`rragent.dictation`) and the `transcribe` tool of the agent
   (`rragent.tools.transcribe`), the audio inline as base64 with its
   format.

   The model is RRAGENT_DICTATION_MODEL, AssemblyAI's Universal-3.6 Pro
   by default, half a second for ten seconds of speech: it scores every
   word it hears, so the answer carries the words with their times and
   their confidence (0 to 1) besides the text. Of the models behind
   OpenRouter only it and ElevenLabs' Scribe v2 score words (October
   2026, Deepgram's Nova-3 among those that do not); its scores are
   the lower ones where it misheard, a name it did not know at 0.2. No
   language is named, the model detects the one spoken. The request
   goes where the REPL `codemode` of the agent knows OpenRouter:
   through the gateway's connection, else with OPENROUTER_API_KEY
   (`rragent.openrouter`, as the models and the media tools have it)."
  (:require [rragent.openrouter :as openrouter]))

(def model
  (or (not-empty (aget (.-env js/process) "RRAGENT_DICTATION_MODEL"))
      "assemblyai/universal-3-6-pro"))

(def max-bytes
  "The largest recording, OpenRouter's cap of audio sent inline; the
   browser sends a piece of 30 seconds at most
   (`rragent.client.dictation`), the tool cuts a file into pieces of
   ten minutes (`rragent.tools.transcribe`)."
  (* 25 1024 1024))

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

(defn ^:async transcription!
  "OpenRouter's transcription of `audio` in `format`, where `codemode`
   knows OpenRouter (`rragent.openrouter/route!`, `what` names the
   caller for the throw when there is none): the body as it came,
   `:text`, the `:words` with their `:start`, `:end` and
   `:confidence`, the `:usage`; throws with the service's message."
  [codemode audio format what]
  (let [{:keys [base-url headers]} (openrouter/route! codemode what)
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
    body))
