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

   The `transcribe` tool's model is RRAGENT_TRANSCRIBE_MODEL, the same
   Universal-3.6 Pro by default (`transcribe-model`): the tool wants
   the text, fast and cheap, and leaves the exact times of the words to
   agentitos' `transcribe` skill. Measured on 2026-11-18 on pieces of
   five minutes, eight at a time, against the references of nine
   recordings (the word error rate in lower case, without punctuation,
   hesitations and numbers, which the models write differently, 2020
   and twenty twenty): Universal-3.6 Pro $0.0050 a minute, 1 s a piece
   (2.5 s at most), 8.3 % on German (four recordings, 3761 words, the
   best on each) and 10.6 % on English (five, 9692 words; an Indian
   speaker came out partly in Devanagari, again when sent again);
   Scribe v2 $0.0037, 6 s (10 s), 9.9 % and 7.5 % (13.9 % as it first
   came: one piece came back as a list of names in Chinese, and right
   when sent again); Qwen3-ASR 1.7B $0.00045, 7 s (38 s), 10.3 % and
   10.0 %, its words without punctuation in four of 13 recordings and
   without scores. So the dictation's model is the dearest of the
   three, the fastest and the best on German, Scribe the best on
   English; the tool takes it for its German and its speed. No language is named, the model
   detects the one spoken. The request goes where the REPL `codemode`
   of the agent knows OpenRouter: through the gateway's connection,
   else with OPENROUTER_API_KEY (`rragent.openrouter`, as the models
   and the media tools have it)."
  (:require [rragent.openrouter :as openrouter]))

(def model
  "The dictation's model."
  (or (not-empty (aget (.-env js/process) "RRAGENT_DICTATION_MODEL"))
      "assemblyai/universal-3-6-pro"))

(def transcribe-model
  "The `transcribe` tool's model."
  (or (not-empty (aget (.-env js/process) "RRAGENT_TRANSCRIBE_MODEL"))
      "assemblyai/universal-3-6-pro"))

(def max-bytes
  "The largest recording, OpenRouter's cap of audio sent inline; the
   browser sends a piece of 30 seconds at most
   (`rragent.client.dictation`), the tool cuts a file into pieces of
   five minutes, 1.8 MB (`rragent.tools.transcribe`)."
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
