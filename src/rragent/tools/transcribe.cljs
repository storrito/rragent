(ns rragent.tools.transcribe
  "The `transcribe` tool: the words spoken in an audio or a video file
   the person attached or recorded (`uploads/<name>`, see
   `rragent.uploads`), or one the agent made under `artifacts/`,
   written down by the speech-to-text model of the dictation
   (`rragent.transcription`: RRAGENT_DICTATION_MODEL, AssemblyAI's
   Universal-3.6 Pro, which detects the language and times every
   word), through OpenRouter the way the REPL `codemode` of the agent
   knows it (`rragent.openrouter`).

   OpenRouter takes the audio inline, 25 MB at most, and gives a
   transcription little more than a minute: a file of any length is
   cut by ffmpeg into pieces of `piece-seconds` (`pieces!`: mono, 16
   kHz, 48 kbit/s MP3, under 4 MB each, the audio of a video alone),
   each written down on its own, their words joined with the times
   shifted by what came before. Without ffmpeg a file in a format the
   model reads goes whole when it is small enough, else the tool says
   that ffmpeg is needed.

   The words are corrected with what the agent knows before anything
   is written (`rragent.transcript-correction`): the names the speech
   model wrote the way they sound, by a small model with the
   conversation and the `names` the agent passes, every word keeping
   its time; the answer says what changed.

   The transcript goes under `artifacts/transcripts/<name>.md`, or
   where `out` says: paragraphs cut at the pauses of the speech
   (`paragraphs`), each with its time when `timestamps` is asked; and
   the answer carries the text, so the agent works with it at once.
   With `words`, every word with its start, end and confidence goes
   beside it as `<name>.words.json`, for cutting a video at the
   pauses."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.tools.media :as media]
            [rragent.tools.work :as work]
            [rragent.transcript-correction :as transcript-correction]
            [rragent.transcription :as transcription]))

(def piece-seconds
  "How long one piece sent to the model is."
  600)

(def formats
  "The files the model reads as they are, by extension, with the
   format OpenRouter is told; anything else (a video, an .aac, a .mkv)
   is audio once ffmpeg has made the pieces."
  {"mp3" "mp3"
   "wav" "wav"
   "m4a" "m4a"
   "ogg" "ogg"
   "webm" "webm"
   "flac" "flac"})

(def pause-seconds
  "A pause in the speech this long ends a paragraph once it is
   `min-paragraph-seconds` long."
  1.0)

(def min-paragraph-seconds
  25)

(def max-paragraph-seconds
  "A paragraph this long ends at the next pause of a third of a
   second, so a reader finds a place in a speech without pauses."
  120)

(def answer-chars
  "The most of the transcript the answer carries; the rest is in the
   file."
  40000)

(defn- extension-of
  [file]
  (str/lower-case (subs (path/extname (str file)) 1)))

(defn source-file
  "The absolute path of `file` under the working directory `work`:
   `uploads/<name>` or `artifacts/<path>`, nothing above the
   directory, and it must exist."
  [work file]
  (let [file (str/replace (str file) #"^/" "")]
    (when (or (str/blank? file) (str/includes? file ".."))
      (throw (js/Error. "The file is a path under the working directory, like uploads/ab12.mp3 or artifacts/talk.m4a.")))
    (let [absolute (path/join work file)]
      (when-not (fs/existsSync absolute)
        (throw (js/Error. (str "There is no file " file " under the working directory."))))
      absolute)))

(defn- exec
  "A promise of the stdout of `program` with `arguments`, rejected
   with its stderr (or why it did not start)."
  [program arguments]
  (js/Promise.
   (fn [resolve reject]
     (child/execFile program (clj->js arguments) #js {:timeout 1800000 :maxBuffer (* 16 1024 1024)}
                     (fn [error stdout stderr]
                       (if error
                         (reject (js/Error. (str/trim (str (if (seq (str stderr)) stderr (.-message error))))))
                         (resolve (str stdout))))))))

(defn- ffmpeg?
  "Whether ffmpeg is on the machine."
  []
  (try
    (zero? (.-status (child/spawnSync "ffmpeg" #js ["-version"] #js {:stdio "ignore" :timeout 10000})))
    (catch :default _
      false)))

(defn ^:async pieces!
  "The pieces of `file` for the model, `[{:file :format}]` in order,
   in `temp` when ffmpeg made them: mono MP3 at 16 kHz, each
   `piece-seconds` long but the last, the audio alone of a video.
   Without ffmpeg the file itself when the model reads its format and
   it is small enough, else a throw. `seconds` is the length of a
   piece, `piece-seconds` by default (a test takes shorter ones)."
  [file temp & [seconds]]
  (if (ffmpeg?)
    (do (await (exec "ffmpeg" ["-y" "-v" "error" "-i" file "-vn" "-ac" "1" "-ar" "16000" "-codec:a" "libmp3lame" "-b:a" "48k"
                               "-f" "segment" "-segment_time" (str (or seconds piece-seconds)) "-reset_timestamps" "1"
                               (path/join temp "piece-%04d.mp3")]))
        (->> (array-seq (fs/readdirSync temp))
             (filter #(re-matches #"piece-\d+\.mp3" %))
             (sort)
             (mapv (fn [name] {:file (path/join temp name) :format "mp3"}))))
    (let [format (formats (extension-of file))]
      (cond
        (nil? format)
        (throw (js/Error. (str "ffmpeg is needed for a ." (extension-of file) " file, and the machine has none.")))

        (< transcription/max-bytes (.-size (fs/statSync file)))
        (throw (js/Error. "ffmpeg is needed to cut a file of more than 25 MB into pieces, and the machine has none."))

        :else
        [{:file file :format format}]))))

(defn- clock
  "Seconds as m:ss, or h:mm:ss from an hour on."
  [seconds]
  (let [s (js/Math.floor (max 0 seconds))
        two #(.padStart (str %) 2 "0")]
    (if (< s 3600)
      (str (quot s 60) ":" (two (rem s 60)))
      (str (quot s 3600) ":" (two (quot (rem s 3600) 60)) ":" (two (rem s 60))))))

(defn- usable?
  "Whether the words make the text: every one carries its time, and
   they carry the punctuation when the text has some (a model whose
   words are bare would lose the sentences)."
  [words text]
  (and (seq words)
       (every? #(and (number? (:start %)) (number? (:end %))) words)
       (or (not (re-find #"[.,!?]" (str text)))
           (some #(re-find #"[.,!?]" (str (:word %))) words))))

(defn paragraphs
  "The paragraphs of a transcription, `[{:start :text}]`: the `words`
   with their times cut at the pauses of the speech, when they carry
   times and the text's punctuation; else the `text` as one paragraph
   starting at `offset`."
  [words text offset]
  (if (usable? words text)
    (let [closed (fn [out current]
                   (if (seq (:words current))
                     (conj out {:start (:start current)
                                :text (str/join " " (map #(str/trim (str (:word %))) (:words current)))})
                     out))]
      (loop [words words
             current nil
             out []]
        (if-let [{:keys [start end] :as word} (first words)]
          (let [pause (if current (- start (:end current)) 0)
                length (if current (- (:end current) (:start current)) 0)
                new? (and current
                          (or (and (<= min-paragraph-seconds length) (<= pause-seconds pause))
                              (and (<= max-paragraph-seconds length) (<= 0.3 pause))))]
            (if new?
              (recur (rest words) {:start start :end end :words [word]} (closed out current))
              (recur (rest words)
                     (if current
                       (-> current (assoc :end end) (update :words conj word))
                       {:start start :end end :words [word]})
                     out)))
          (closed out current))))
    (let [text (str/trim (str text))]
      (if (str/blank? text)
        []
        [{:start offset :text text}]))))

(defn shifted
  "The words with their times `offset` later."
  [words offset]
  (mapv (fn [word]
          (cond-> word
            (number? (:start word)) (update :start + offset)
            (number? (:end word)) (update :end + offset)))
        words))

(defn transcript-text
  "The transcript as markdown: the paragraphs, each after its time when
   `timestamps?`."
  [paragraphs timestamps?]
  (str/join "\n\n"
            (map (fn [{:keys [start text]}]
                   (if timestamps?
                     (str "[" (clock start) "] " text)
                     text))
                 paragraphs)))

(defn- check-out
  [out]
  (when (or (str/blank? out) (str/includes? out "..") (str/starts-with? out "/"))
    (throw (js/Error. "`out` is a path under artifacts/, like transcripts/talk.md."))))

(defn- cost-text
  [cost]
  (when (and (number? cost) (pos? cost))
    (str ", $" (.toFixed cost 3))))

(defn words-file
  "Where the words of the transcript `out` go: beside it, `.words.json`
   for its extension."
  [out]
  (str (str/replace out #"\.[^./]+$" "") ".words.json"))

(defn ^:async corrected
  "`state` with its words corrected (`rragent.transcript-correction`)
   with the messages of the tool call's conversation, read through the
   tool's `api` in the call's `context`, by `complete`; and `:fixed`,
   the line that says what changed or why nothing did. `state` as it
   is without an `api` or without words that carry their times."
  [state {:keys [api context names complete]}]
  (if (and api (usable? (:words state) (:text state)))
    (try
      (let [messages (await (transcript-correction/messages-of api context))
            {:keys [words applied cost failed]} (await (transcript-correction/correct! complete messages (:words state) names))]
        (assoc state
               :words words
               :cost (+ (:cost state) cost)
               :fixed (cond
                        (seq applied) (str "Corrected: " (transcript-correction/summary applied) ".")
                        failed (str "Not corrected: " failed ".")
                        :else "Nothing to correct.")))
      (catch :default error
        (assoc state :fixed (str "Not corrected: " (.-message error) "."))))
    state))

(defn ^:async transcribe!
  "The transcript of `file` written to `out` under `dir`
   (`artifacts/`), its words corrected through `api` (the tool's, with
   the call's `context`) when there is one, by `complete` (OpenRouter's
   unless a test gives one), and beside it the words with their times
   when `words?`; answers the line for the model with the text."
  [{:keys [codemode work dir file out timestamps? words? api context names] :as options}]
  (let [source (source-file work file)
        out (or (not-empty out) (str "transcripts/" (str/replace (path/basename source) #"\.[^.]+$" "") ".md"))
        _ (check-out out)
        target (path/join dir out)
        temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-transcribe-"))]
    (try
      (let [pieces (await (pieces! source temp (:piece-seconds options)))
            state (loop [pieces pieces
                         offset 0
                         words []
                         text []
                         cost 0]
                    (if-let [{:keys [file format]} (first pieces)]
                      (let [body (await (transcription/transcription! codemode (fs/readFileSync file) format "transcript"))
                            seconds (or (media/seconds-of file) (some-> body :duration) (:piece-seconds options) piece-seconds)]
                        (recur (rest pieces)
                               (+ offset seconds)
                               (into words (shifted (:words body) offset))
                               (conj text (str/trim (str (:text body))))
                               (+ cost (or (some-> body :usage :cost) 0))))
                      {:seconds offset :words words :text (str/join "\n\n" (remove str/blank? text)) :cost cost}))
            state (await (corrected state {:api api :context context :names names
                                           :complete (or (:complete options) (transcript-correction/openrouter-complete codemode))}))
            paragraphs (paragraphs (:words state) (:text state) 0)
            transcript (transcript-text paragraphs (boolean timestamps?))
            heading (str "# " (path/basename source) "\n\n"
                         "Transcript of " (path/relative work source) ", " (clock (:seconds state)) ", model " transcription/model ".\n\n")]
        (when (str/blank? transcript)
          (throw (js/Error. (str "The model heard no words in " (path/relative work source) "."))))
        (fs/mkdirSync (path/dirname target) #js {:recursive true})
        (fs/writeFileSync target (str heading transcript "\n"))
        (when words?
          (fs/writeFileSync (path/join dir (words-file out))
                            (js/JSON.stringify (clj->js {:file (path/relative work source)
                                                         :model transcription/model
                                                         :seconds (:seconds state)
                                                         :words (:words state)})
                                               nil 1)))
        (str "artifacts/" out ": " (clock (:seconds state)) ", " (count (str/split transcript #"\s+")) " words"
             (cost-text (:cost state)) ", model " transcription/model
             (when words? (str "; the words with their times in artifacts/" (words-file out)))
             (when (:fixed state) (str "\n" (:fixed state)))
             "\n\n"
             (if (< answer-chars (count transcript))
               (str (subs transcript 0 answer-chars) "\n\n… (the rest is in the file)")
               transcript)))
      (finally
        (fs/rmSync temp #js {:recursive true :force true})))))

(defn tool
  "The tool for the agent whose REPL is `codemode`."
  [codemode]
  (defineTool
   #js {:name "transcribe"
        :description (str "The words spoken in an audio or a video file, written down by a speech-to-text model (about a cent a minute), the language detected. "
                          "`file` is a path under the working directory: uploads/<name> for a file the person attached or recorded, artifacts/<path> for one of yours. "
                          "The transcript goes to artifacts/transcripts/<name>.md, or to `out`, and comes back as the answer; with `timestamps` every paragraph starts with its time. "
                          "Names the model misheard are corrected from our conversation and the `names` you pass (accounts, products, people), every word keeping its time; the answer says what changed. "
                          "With `words`, every word with its start and end in seconds goes beside the transcript as <name>.words.json, for cutting at the pauses. "
                          "A long file is cut into pieces of ten minutes by ffmpeg, which bash has for cutting, converting and cleaning the audio too (ffmpeg -i in.mp3 -ss 0:30 -to 2:00 out.mp3).")
        :parameters (.Object Type #js {:file (.String Type #js {:description "The audio or video file, uploads/ab12.mp3 or artifacts/talk.m4a"})
                                       :out (.Optional Type (.String Type #js {:description "Where the transcript goes under artifacts/, transcripts/<name>.md by default"}))
                                       :timestamps (.Optional Type (.Boolean Type #js {:description "True: every paragraph starts with its time, m:ss"}))
                                       :names (.Optional Type (.String Type #js {:description "Names and terms that may be spoken, comma-separated: accounts, products, people"}))
                                       :words (.Optional Type (.Boolean Type #js {:description "True: also every word with its times, <name>.words.json"}))})
        :execute (^:async fn [args api call-context]
                   (work/text (await (transcribe! {:codemode codemode
                                                   :work (work/dir api)
                                                   :dir (work/artifacts-dir api)
                                                   :file (.-file args)
                                                   :out (.-out args)
                                                   :timestamps? (true? (.-timestamps args))
                                                   :words? (true? (.-words args))
                                                   :names (.-names args)
                                                   :api api
                                                   :context call-context}))))}))
