(ns rragent.transcription
  "One request to the speech-to-text model behind OpenRouter
   (`/audio/transcriptions`), for the dictation of the composer
   (`rragent.dictation`) and the `transcribe` tool of the agent
   (`rragent.tools.transcribe`), the audio inline as base64 with its
   format.

   The dictation's model is RRAGENT_DICTATION_MODEL, AssemblyAI's
   Universal-3.6 Pro by default, half a second for ten seconds of
   speech: it scores every word it hears, so the answer carries the
   words with their times and their confidence (0 to 1) besides the
   text. Of the models behind OpenRouter only it and ElevenLabs' Scribe
   v2 score words (October 2026, Deepgram's Nova-3 among those that do
   not); the scores are the lower ones where a model misheard, a name
   it did not know at 0.2.

   The `transcribe` tool's model is RRAGENT_TRANSCRIBE_MODEL, Scribe v2
   by default (`transcribe-model`): a video is cut at its words' times,
   and since November 2026 AssemblyAI gives most words no length, their
   end at their start. Measured on 2026-11-12 against the voice of a
   recording, of the 28 models behind OpenRouter that write down
   speech, Scribe v2 gave every word its length and put a phrase's end
   within 60 ms of where the voice stops (140 ms for the worst tenth),
   AssemblyAI 280 ms early, 121 of 143 words without a length. No
   language is named, the model detects the one spoken. The tool asks
   for the voices too (`transcribe-options`: `diarize`, which costs
   nothing more), and waits longer, for a piece of three quarters of
   an hour: Scribe took 81 s for an hour of talk, and the gateway ends
   a request after about 130 s (2026-11-16). The request goes where the REPL `codemode` of the agent knows OpenRouter:
   through the gateway's connection, else with OPENROUTER_API_KEY
   (`rragent.openrouter`, as the models and the media tools have it)."
  (:require [rragent.openrouter :as openrouter]))

(def model
  "The dictation's model."
  (or (not-empty (aget (.-env js/process) "RRAGENT_DICTATION_MODEL"))
      "assemblyai/universal-3-6-pro"))

(def transcribe-model
  "The `transcribe` tool's model, for the times of the words."
  (or (not-empty (aget (.-env js/process) "RRAGENT_TRANSCRIBE_MODEL"))
      "elevenlabs/scribe-v2"))

(def transcribe-options
  "What the `transcribe` tool's request carries besides the audio, and
   how long it waits: the model names the voice of every word
   (`speaker`: 0, 1, … within the one request)."
  {:body {:diarize true}
   :timeout 150000})

(def max-bytes
  "The largest recording, OpenRouter's cap of audio sent inline; the
   browser sends a piece of 30 seconds at most
   (`rragent.client.dictation`), the tool cuts a file into pieces of
   three quarters of an hour, 16 MB (`rragent.tools.transcribe`)."
  (* 25 1024 1024))

(defn request-body
  "The JSON body of the transcription request, to the dictation's
   `model` unless `with` names another: the words with their
   timestamps and, where the model scores them, their confidence come
   only with `verbose_json`; `more` is merged into it."
  [^js audio format & [with more]]
  (merge {:model (or with model)
          :input_audio {:data (.toString audio "base64")
                        :format format}
          :response_format "verbose_json"
          :timestamp_granularities ["word"]
          :temperature 0}
         more))

(defn ^:async transcription!
  "OpenRouter's transcription of `audio` in `format` by the model
   `with`, the dictation's by default, where `codemode` knows
   OpenRouter (`rragent.openrouter/route!`, `what` names the caller for
   the throw when there is none): the body as it came, `:text`, the
   `:words` with their `:start`, `:end` and `:confidence`, the
   `:usage`; throws with the service's message. `options` are more of
   the request's `:body` and its `:timeout` in ms, a minute and a half
   unless said."
  [codemode audio format what & [with options]]
  (let [{:keys [base-url headers]} (openrouter/route! codemode what)
        response (await (js/fetch (str base-url "/audio/transcriptions")
                                  #js {:method "POST"
                                       :headers (clj->js (assoc headers "Content-Type" "application/json"))
                                       :body (js/JSON.stringify (clj->js (request-body audio format with (:body options))))
                                       :signal (js/AbortSignal.timeout (or (:timeout options) 90000))}))
        text (await (.text response))
        body (try (js->clj (js/JSON.parse text) :keywordize-keys true) (catch :default _ nil))]
    (when-not (.-ok response)
      (throw (js/Error. (str "The transcription service answered " (.-status response) ": "
                             (or (get-in body [:error :message]) text)))))
    body))
