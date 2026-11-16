(ns rragent.tools.transcribe
  "The `transcribe` tool: the words spoken in an audio or a video file
   the person attached or recorded (`uploads/<name>`, see
   `rragent.uploads`), or one the agent made under `artifacts/`,
   written down by a speech-to-text model (`rragent.transcription`:
   RRAGENT_TRANSCRIBE_MODEL, ElevenLabs' Scribe v2, which detects the
   language and times every word from its start to its end), through
   OpenRouter the way the REPL `codemode` of the agent knows it
   (`rragent.openrouter`).

   OpenRouter takes the audio inline, 25 MB at most, and the gateway
   gives a request about two minutes: a file longer than
   `piece-seconds` is cut by ffmpeg into pieces (`pieces!`: mono, 16
   kHz, 48 kbit/s MP3, 16 MB at most, the audio of a video alone),
   `parallel` of them written down at a time, their words joined with
   the times shifted by where the piece starts. The sound is read once
   from start to end, so its length and every piece's start are what
   was read, whatever the file's head says. A piece ends in a pause of
   the speech near its length (`cuts`, `pauses`: quiet by the
   recording's own measure), so that no word is cut in two, and is an
   encode of its own from that sample: pieces cut from one encode
   began a fifth of a second later than their offset said, so every
   word after the first ten minutes came that much too early. Without
   ffmpeg a file in a format the model reads goes whole when it is
   small enough, else the tool says that ffmpeg is needed.

   The model names the voice of every word, 0, 1, … within one
   request, and the words carry it as `speaker` A, B, … in the order
   they first speak. A piece is as long as a request can be, three
   quarters of an hour, because only within one request is a voice
   the same number throughout; a longer recording's pieces are each
   heard from `overlap-seconds` before their start, and a voice of
   the later piece is the person whose words it shares there with the
   piece before (`linked`). How well the model tells voices apart is
   its own: measured on 2026-11-16 against references of who speaks,
   97 to 100 % of the words right for up to five clear voices
   (interviews, debates, calls), 86 to 93 % for six or seven in a
   room, 59 % for a family of eight at a table; of words said while
   another speaks, seven in ten come back at all.

   What the model writes is kept, with three things done about it: a
   sound it named among the words (`[Applaus]`) is marked as one
   (`marked`), a word it gave no length gets the room between its
   neighbours (`fitted`), and the edges of the words are moved to
   where the sound has them (`edged`): Scribe begins a word 60 ms
   late in the middle and ends one in eight 100 ms early.

   The words are corrected with what the agent knows before anything
   is written (`rragent.transcript-correction`): the names the speech
   model wrote the way they sound, by a small model with the
   conversation and the `names` the agent passes, every word keeping
   its time; the answer says what changed.

   The transcript goes under `artifacts/transcripts/<name>.md`, or
   where `out` says: paragraphs cut at the pauses of the speech
   (`paragraphs`), each with its time when `timestamps` is asked; and
   the answer carries the text, so the agent works with it at once.
   A paragraph also ends where the speaker changes, and begins with
   the speaker's letter when more than one speaks. With `words`,
   every word with its start, end, confidence and speaker goes beside
   it as `<name>.words.json`, for cutting a video at the pauses; a
   sound among them carries `kind: event`, a word whose length is the
   room between its neighbours `by: neighbours`."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.tools.media :as media]
            [rragent.tools.work :as work]
            [rragent.transcript-correction :as transcript-correction]
            [rragent.transcription :as transcription]))

(def piece-seconds
  "How long one piece sent to the model is at most: three quarters of
   an hour, which Scribe writes down in about 70 s (36 minutes in 60 s,
   an hour in 81 s; two hours ran into the gateway's 130 s), so that
   most recordings are one request and their voices one numbering."
  2700)

(def overlap-seconds
  "How long before its own start a piece after the first is heard
   from, so that it and the piece before heard the same stretch and
   their voices can be told to be the same people (`linked`). Five
   minutes: with one minute a voice often said nothing there, and the
   speakers of two AMI meetings came out 66 and 81 % right where each
   piece on its own was 95 and 98 %; with five, 86 and 96 % of 87 and
   98 %."
  300)

(def pause-window
  "How far before its full length a piece may end, as a share of it,
   nine minutes of forty-five, to end in a pause."
  0.2)

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

(def sample-rate
  "The samples a second of the sound the pieces are cut from."
  16000)

(def ^:private frame-samples
  "The samples of one frame of the envelope, 10 ms."
  160)

(def ^:private frame-seconds
  (/ frame-samples sample-rate))

(def min-pause
  "The shortest stretch of quiet that is a pause to cut in, seconds."
  0.25)

(def quiet-share
  "A frame is quiet under this share of loud speech, the 95th
   percentile of the envelope: 22 dB below it."
  0.08)

(defn envelope
  "The loudness of `samples` (an Int16Array, mono) every 10 ms, each
   frame's RMS from 0 to 1, into `env` (a Float32Array) from its
   frame `at` on; answers `env`."
  [^js samples ^js env at]
  (let [frames (js/Math.floor (/ (.-length samples) frame-samples))]
    (dotimes [f frames]
      (let [base (* f frame-samples)]
        (loop [i 0
               sum 0]
          (if (< i frame-samples)
            (let [v (/ (aget samples (+ base i)) 32768)]
              (recur (inc i) (+ sum (* v v))))
            (aset env (+ at f) (js/Math.sqrt (/ sum frame-samples)))))))
    env))

(defn- ^:async envelope-of
  "The envelope of the raw sound `file` (mono, signed 16 bit, as
   `pieces!` writes it): read a hundred seconds at a time, so that an
   hour of sound is never whole in memory, the event loop let go
   between two readings, so that the pages go on while it reads."
  [file]
  (let [frame-bytes (* 2 frame-samples)
        frames (js/Math.floor (/ (.-size (fs/statSync file)) frame-bytes))
        env (js/Float32Array. frames)
        step 10000
        buffer (js/Buffer.alloc (* step frame-bytes))
        fd (fs/openSync file "r")]
    (try
      (loop [f 0]
        (when (< f frames)
          (let [n (min step (- frames f))]
            (fs/readSync fd buffer 0 (* n frame-bytes) (* f frame-bytes))
            (envelope (js/Int16Array. (.-buffer buffer) (.-byteOffset buffer) (* n frame-samples)) env f)
            (await (js/Promise. (fn [resolve] (js/setImmediate resolve))))
            (recur (+ f n)))))
      (finally
        (fs/closeSync fd)))
    env))

(defn- smoothed
  "The envelope `env` with every frame the mean of itself and its two
   neighbours: one frame of flicker is no boundary."
  [^js env]
  (let [n (.-length env)
        out (js/Float32Array. n)]
    (dotimes [f n]
      (aset out f (/ (+ (aget env (max 0 (dec f))) (aget env f) (aget env (min (dec n) (inc f)))) 3)))
    out))

(defn pauses
  "The pauses of the sound whose envelope is `env`, `[from to]` in
   seconds: `min-pause` or more of frames under `quiet-share` of loud
   speech. The measure is the recording's own: a fixed level (ffmpeg's
   silencedetect at -40 dB) found a pause in none of eight windows of
   a broadcast with music under it and in 9 of 15 of a recording with
   a hiss, this one in 7 and in all 15 (2026-11-16)."
  [^js env]
  (let [sound (smoothed env)
        n (.-length sound)
        sorted (.sort (js/Float32Array.from sound))
        quiet (* quiet-share (if (pos? n) (aget sorted (js/Math.floor (* 0.95 (dec n)))) 0))
        least (js/Math.round (/ min-pause frame-seconds))]
    (loop [f 0
           from nil
           out []]
      (let [quiet? (and (< f n) (<= (aget sound f) quiet))]
        (cond
          (< n f) out
          (and quiet? (nil? from)) (recur (inc f) f out)
          (and (not quiet?) (some? from)) (recur (inc f) nil (cond-> out
                                                                 (<= least (- f from)) (conj [(* from frame-seconds) (* f frame-seconds)])))
          :else (recur (inc f) from out))))))

(defn quietest
  "The quietest moment of the sound whose envelope is `env` between
   `from` and `to` seconds: the middle of the tenth of a second with
   the least sound. Where to cut when there is no pause."
  [^js env from to]
  (let [width 10
        a (max 0 (js/Math.round (/ from frame-seconds)))
        b (min (- (.-length env) width) (- (js/Math.round (/ to frame-seconds)) width))]
    (if (< b a)
      to
      (loop [f a
             sum (reduce + (map #(aget env (+ a %)) (range width)))
             best a
             least ##Inf]
        (let [[best least] (if (< sum least) [f sum] [best least])]
          (if (< f b)
            (recur (inc f) (+ sum (- (aget env (+ f width)) (aget env f))) best least)
            (* (+ best (/ width 2)) frame-seconds)))))))

(defn cuts
  "Where a file of `duration` seconds is cut into pieces of `seconds`
   at most, `[start end]` in order: each piece ends before its full
   length in the longest of the `pauses` (`[from to]`, seconds) that
   reaches into the last `pause-window` of it, as near the pause's
   middle as the window lets it; without a pause there where
   `quietest` (a function of the window's start and end) says, else at
   its full length; the last piece ends with the file."
  [duration pauses seconds & [quietest]]
  (let [window (* pause-window seconds)]
    (loop [start 0
           out []]
      (if (<= (- duration start) seconds)
        (conj out [start duration])
        (let [full (+ start seconds)
              low (- full window)
              cut (or (some->> pauses
                               (filter (fn [[from to]] (and (< from full) (> to low))))
                               (seq)
                               (apply max-key (fn [[from to]] (- (min to full) (max from low))))
                               ((fn [[from to]] (-> (/ (+ from to) 2) (max from low) (min to full)))))
                      (when quietest (quietest low full))
                      full)]
          (recur cut (conj out [start cut])))))))

(defn ^:async pieces!
  "The pieces of `file` for the model, `[{:file :format :start :end
   :heard}]` in order, the seconds of the file each begins and ends
   at, and from where it is heard: a piece after the first `overlap`
   seconds before its start (`overlap-seconds` unless said), for the
   voices (`linked`); in `temp` when ffmpeg made them. The sound is
   read once from start to end into `temp` as it is cut from (mono, 16
   kHz, raw): its length is then what was read, not what the file's
   head claims (a browser's recording claims none, a raw AAC or an MP3
   of a varying rate a wrong one, measured 14 % off), and a piece is
   heard from the very sample its words are shifted by. Each piece is
   an MP3 of 48 kbit/s from there, ended in a pause (`cuts`, `pauses`)
   or, without one, at the quietest moment (`quietest`); written to a
   file, whose head says how many samples the encoder put before the
   sound: the same MP3 through a pipe has no such head, and every word
   came 70 ms late. The envelope of the sound is the `:envelope` of
   the answer's meta, for the edges of the words (`edged`). Without
   ffmpeg the file itself when the model reads its format and it is
   small enough, else a throw. `seconds` is the most a piece is long,
   `piece-seconds` by default (a test takes shorter ones)."
  [file temp & [seconds overlap]]
  (if (ffmpeg?)
    (let [seconds (or seconds piece-seconds)
          overlap (or overlap overlap-seconds)
          sound (path/join temp "sound.pcm")
          raw ["-f" "s16le" "-ar" (str sample-rate) "-ac" "1"]
          _ (await (exec "ffmpeg" (into ["-y" "-v" "error" "-i" file "-vn"] (conj raw sound))))
          duration (/ (.-size (fs/statSync sound)) 2 sample-rate)
          _ (when-not (pos? duration)
              (throw (js/Error. "The file has no sound.")))
          env (await (envelope-of sound))
          plan (cuts duration (when (< seconds duration) (pauses env)) seconds #(quietest env %1 %2))]
      (loop [plan plan
             n 0
             out []]
        (if-let [[start end] (first plan)]
          (let [piece (path/join temp (str "piece-" (.padStart (str n) 4 "0") ".mp3"))
                heard (if (zero? n) 0 (max 0 (- start overlap)))]
            (await (exec "ffmpeg" (into ["-y" "-v" "error"]
                                        (concat raw ["-ss" (.toFixed heard 4) "-t" (.toFixed (- end heard) 4) "-i" sound
                                                     "-codec:a" "libmp3lame" "-b:a" "48k" piece]))))
            (recur (rest plan) (inc n) (conj out {:file piece :format "mp3" :start start :end end :heard heard})))
          (with-meta out {:envelope env}))))
    (let [format (formats (extension-of file))]
      (cond
        (nil? format)
        (throw (js/Error. (str "ffmpeg is needed for a ." (extension-of file) " file, and the machine has none.")))

        (< transcription/max-bytes (.-size (fs/statSync file)))
        (throw (js/Error. "ffmpeg is needed to cut a file of more than 25 MB into pieces, and the machine has none."))

        :else
        [{:file file :format format :start 0}]))))

(defn clock
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

(def ^:private unspaced
  "A character of a script written without spaces between its words:
   Japanese, Chinese, Thai, Lao, Khmer, Burmese, and their punctuation."
  (js/RegExp. "[\\u3000-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF\\u0E00-\\u0EFF\\u1000-\\u109F\\u1780-\\u17FF]"))

(defn joined
  "The `words` as text: a space between two of them, none where the
   one ends and the other begins in a script written without spaces.
   Scribe gives Japanese a character at a time, and a transcript
   joined with spaces read 今 日 の テ ー マ."
  [words]
  (let [words (remove str/blank? (map #(str/trim (str (:word %))) words))]
    (reduce (fn [text word]
              (if (and (seq text)
                       (not (and (.test unspaced (subs text (dec (count text))))
                                 (.test unspaced (subs word 0 1)))))
                (str text " " word)
                (str text word)))
            ""
            words)))

(defn paragraphs
  "The paragraphs of a transcription, `[{:start :text}]`, with the
   `:speaker` where the words carry one: the `words` with their times
   cut at the pauses of the speech and where another speaker begins (a
   sound among them, `kind: event`, is nobody's), when they carry
   times and the text's punctuation; else the `text` as one paragraph
   starting at `offset`."
  [words text offset]
  (if (usable? words text)
    (let [closed (fn [out current]
                   (if (seq (:words current))
                     (conj out (cond-> {:start (:start current)
                                        :text (joined (:words current))}
                                 (:speaker current) (assoc :speaker (:speaker current))))
                     out))
          speaker-of (fn [word]
                       (when-not (= "event" (:kind word))
                         (:speaker word)))]
      (loop [words words
             current nil
             out []]
        (if-let [{:keys [start end] :as word} (first words)]
          (let [pause (if current (- start (:end current)) 0)
                length (if current (- (:end current) (:start current)) 0)
                speaker (speaker-of word)
                new? (and current
                          (or (and (<= min-paragraph-seconds length) (<= pause-seconds pause))
                              (and (<= max-paragraph-seconds length) (<= 0.3 pause))
                              (and speaker (:speaker current) (not= speaker (:speaker current)))))]
            (if new?
              (recur (rest words) (cond-> {:start start :end end :words [word]} speaker (assoc :speaker speaker)) (closed out current))
              (recur (rest words)
                     (if current
                       (cond-> (-> current (assoc :end end) (update :words conj word))
                         (and speaker (not (:speaker current))) (assoc :speaker speaker))
                       (cond-> {:start start :end end :words [word]} speaker (assoc :speaker speaker)))
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
   `timestamps?`, and after its speaker's letter when more than one
   speaks."
  [paragraphs timestamps?]
  (let [named? (< 1 (count (distinct (keep :speaker paragraphs))))]
    (str/join "\n\n"
              (map (fn [{:keys [start text speaker]}]
                     (str (when timestamps? (str "[" (clock start) "] "))
                          (when (and named? speaker) (str speaker ": "))
                          text))
                   paragraphs))))

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

(defn ^:async bot-messages
  "The messages of the bot of the call, as the dictation's correction
   reads them (`rragent.correction`): from the bot's conversation that
   the one atom holds (`rragent.db`), which has the whole context; the
   tool's own handle of a conversation only submits and aborts. None
   when the bot is not open."
  [api call-context]
  (if-let [^js conversation (get-in @db/db (conj (await (work/bot-path api call-context)) :conversation))]
    (js->clj (.-messages (await (.context conversation BACKGROUND_CONTEXT))) :keywordize-keys true)
    []))

(defn ^:async corrected
  "`state` with its words corrected (`rragent.transcript-correction`)
   with the bot's conversation (`messages`, a function of the tool's
   `api` and the call's `context`, `bot-messages` unless a test gives
   one; without them the names alone) by `complete`; and `:fixed`, the
   line that says what changed or why nothing did. `state` as it is
   without an `api`; without words that carry their times, it says so."
  [state {:keys [api context names complete messages]}]
  (cond
    (not api) state
    (not (usable? (:words state) (:text state))) (assoc state :fixed "Not corrected: the speech model gave no word times.")
    :else
    (try
      (let [messages (try (await ((or messages bot-messages) api context))
                          (catch :default error
                            (js/console.error "[transcribe] the conversation for the correction:" (.-message error))
                            []))
            {:keys [words applied cost failed]} (await (transcript-correction/correct! complete messages (:words state) names))]
        (assoc state
               :words words
               :cost (+ (:cost state) cost)
               :fixed (cond
                        (and (seq applied) failed) (str "Corrected: " (transcript-correction/summary applied) ". Some of it not: " failed ".")
                        (seq applied) (str "Corrected: " (transcript-correction/summary applied) ".")
                        failed (str "Not corrected: " failed ".")
                        :else "Nothing to correct.")))
      (catch :default error
        (assoc state :fixed (str "Not corrected: " (.-message error) "."))))))

(defn without-length
  "How many of `words` came without a length, their end at their start
   (within 20 ms)."
  [words]
  (count (filter (fn [{:keys [start end]}]
                   (and (number? start) (or (not (number? end)) (< (- end start) 0.02))))
                 words)))

(defn length-warning
  "The line the answer carries when the model gave a third or more of
   `words` (of 20 at least) no length: a cut at their ends would be a
   guess. AssemblyAI did so from November 2026 on, and nothing said it
   until a cut lost the ends of its sentences."
  [words]
  (let [n (count words)
        bare (without-length words)]
    (when (and (<= 20 n) (<= (/ n 3) bare))
      (str bare " of " n " words came without a length (their end at their start): "
           "the model gave no word ends, so a cut at them is a guess."))))

(defn event?
  "Whether the word is a sound the model heard and named, not
   something said: Scribe writes them among the words in brackets,
   `[Applaus]`, `[keyboard clacking]`, `[musica allegra]`, 113 of them
   in 3.6 hours of 16 recordings."
  [{:keys [word kind]}]
  (or (= "event" kind)
      (boolean (re-matches #"[\[(（【].*[\])）】][.,!?…]*" (str/trim (str word))))))

(defn marked
  "The `words` with the sounds among them marked `:kind \"event\"`, as
   agentitos' skills know them: a caption shows no `[Applaus]`, and a
   cut does not take one for a word."
  [words]
  (mapv #(cond-> % (event? %) (assoc :kind "event")) words))

(def ^:private room
  "How far to either side of its time a word without a length may
   reach, seconds."
  0.15)

(defn- thousandth
  [x]
  (/ (js/Math.round (* 1000 x)) 1000))

(defn fitted
  "The `words` with those that came without a length (their end at
   their start, within 20 ms) given the room between their neighbours,
   `room` to either side of their time at most, and marked `:by
   \"neighbours\"`: Scribe squeezes about one word in a hundred to a
   point, nearly all of one or two letters between two words that
   leave them 60 to 100 ms (\"as [a] result\"), and a word without a
   length is one a caption cannot show and a cut takes for mistimed. A
   word its neighbours leave no room stays as it came. Not for a model
   that gives no lengths at all (`length-warning`): its times are no
   words' middles."
  [words]
  (let [words (vec words)]
    (vec (map-indexed
          (fn [i {:keys [start end] :as word}]
            (let [end (if (number? end) end start)]
              (if (and (number? start) (< (- end start) 0.02) (not (event? word)))
                (let [before (:end (get words (dec i)))
                      after (:start (get words (inc i)))
                      low (max 0 (if (number? before) (min start (max before (- start room))) (- start room)))
                      high (if (number? after) (max end (min after (+ end room))) (+ end room))]
                  (if (<= 0.02 (- high low))
                    (assoc word :start (thousandth low) :end (thousandth high) :by "neighbours")
                    word))
                word)))
          words))))

(defn heard-words
  "The words of the model's answer `body` as the tool keeps them:
   `:word`, `:start`, `:end`, `:confidence`, a sound the model typed
   as one (`type: audio_event`) marked `:kind \"event\"`, and the
   voice the model heard it in as `:voice`, until `linked` makes the
   speakers of it."
  [body]
  (mapv (fn [{:keys [type speaker speaker_label] :as word}]
          (let [voice (if (some? speaker) speaker speaker_label)]
            (cond-> (select-keys word [:word :start :end :confidence])
              (= "audio_event" type) (assoc :kind "event")
              (some? voice) (assoc :voice (str voice)))))
        (:words body)))

(def ^:private bare-pattern
  (js/RegExp. "[^\\p{L}\\p{N}']" "gu"))

(defn- bare
  "The word as two pieces of one recording both write it: its letters
   and digits, lower case."
  [word]
  (.replace (str/lower-case (str (:word word))) bare-pattern ""))

(defn- middle
  [{:keys [start end]}]
  (/ (+ start (if (number? end) end start)) 2))

(defn- said?
  "Whether the word tells whose voice a voice is: a word with its time
   and a voice, no sound."
  [word]
  (and (number? (:start word)) (some? (:voice word)) (not (event? word)) (seq (bare word))))

(defn- shared
  "How many words a voice of `after` shares with a person of `before`,
   `{[voice person] n}`: the same word begun within half a second in
   both, each word matched once, the nearest first. `person` is the
   person of a word of `before`."
  [before after person]
  (let [before (vec before)
        after (vec after)
        places (reduce (fn [places [i word]] (update places (bare word) (fnil conj []) i))
                       {}
                       (map-indexed vector before))
        pairs (sort-by first
                       (for [[j word] (map-indexed vector after)
                             i (places (bare word))
                             :let [apart (js/Math.abs (- (:start (before i)) (:start word)))]
                             :when (< apart 0.5)]
                         [apart i j]))]
    (loop [pairs pairs
           taken-before #{}
           taken-after #{}
           out {}]
      (if-let [[_ i j] (first pairs)]
        (if (or (taken-before i) (taken-after j))
          (recur (rest pairs) taken-before taken-after out)
          (recur (rest pairs) (conj taken-before i) (conj taken-after j)
                 (update out [(:voice (after j)) (person (before i))] (fnil inc 0))))
        out))))

(defn- letter
  "The name of the `n`th speaker: A to Z, then S27, S28, …"
  [n]
  (if (< n 26)
    (js/String.fromCharCode (+ 65 n))
    (str "S" (inc n))))

(defn linked
  "The words of a recording from those of its pieces, `parts` as
   `[{:start :words}]` in order, every part's words with the times of
   the recording and the `:voice` the model heard them in: each part's
   words from its `:start` on (what it heard before is the piece
   before's to write), the voice made the `:speaker`, A, B, … in the
   order they first speak.

   A voice is a number within one request, so the parts' voices are
   linked through the stretch two parts both heard, `overlap` seconds
   before the later one's start: a voice of the later part is the
   person it shares the most words with there (`shared`; two at least,
   a person one voice at most). One voice left without a person, when
   one person is left without a voice, is that person: someone who
   said nothing in the overlap. Any other is someone new."
  [parts overlap]
  (let [parts (vec parts)
        voices-of (fn [k] (distinct (keep :voice (filter said? (:words (parts k))))))
        people (loop [k 0
                      people {}
                      n 0]
                 (if (<= (count parts) k)
                   people
                   (let [start (:start (parts k))
                         counts (when (pos? k)
                                  (shared (filter #(and (said? %) (<= (- start overlap) (middle %))) (:words (parts (dec k))))
                                          (filter #(and (said? %) (< (middle %) start)) (:words (parts k)))
                                          #(people [(dec k) (:voice %)])))
                         found (reduce (fn [found [[voice person] times]]
                                         (if (and (<= 2 times) (some? person)
                                                  (not (contains? found voice))
                                                  (not (some #{person} (vals found))))
                                           (assoc found voice person)
                                           found))
                                       {}
                                       (sort-by (comp - val) counts))
                         free (remove #(contains? found %) (voices-of k))
                         unheard (remove (set (vals found)) (distinct (vals people)))
                         found (if (and (pos? k) (= 1 (count free)) (= 1 (count unheard)))
                                 (assoc found (first free) (first unheard))
                                 found)
                         [people n] (reduce (fn [[people n] voice]
                                              (if-let [person (found voice)]
                                                [(assoc people [k voice] person) n]
                                                [(assoc people [k voice] n) (inc n)]))
                                            [people n]
                                            (voices-of k))]
                     (recur (inc k) people n))))
        kept (vec (mapcat (fn [k {:keys [start words]}]
                            (keep (fn [word]
                                    (when (or (zero? k) (not (number? (:start word))) (<= start (middle word)))
                                      (cond-> (dissoc word :voice)
                                        (some? (:voice word)) (assoc :person (people [k (:voice word)])))))
                                  words))
                          (range)
                          parts))
        order (zipmap (distinct (keep :person kept)) (range))]
    (mapv (fn [word]
            (cond-> (dissoc word :person)
              (some? (:person word)) (assoc :speaker (letter (order (:person word))))))
          kept)))

(def edge-reach
  "How far an edge of a word is moved to where its sound begins or
   ends, seconds: past that the sound is something else, a breath, a
   laugh, another voice."
  0.4)

(def join-reach
  "How long the stretch between two words may be for them to meet in
   it when the sound runs through, seconds."
  0.5)

(def floor-times
  "A frame is also quiet under this many times the ground the sound
   never goes below around it (`quiet-levels`)."
  2)

(def floor-seconds
  "How many seconds to either side of a second its ground is taken
   from."
  3)

(defn quiet-levels
  "For every second of the smoothed envelope `sound` the level a frame
   is quiet under: `quiet-share` of loud speech, or `floor-times` the
   ground of the `floor-seconds` around (the 10th percentile), the
   larger. With noise, voices or music under the speech nothing is
   ever under 8 % of loud speech; with the ground, on a call with pink
   noise 15 dB under the speech the edges of 19 % of the words were
   more than 100 ms off where they were 32 % as the model gave them
   and 30 % with the share alone, which moved edges into the noise
   (2026-11-16)."
  [^js sound]
  (let [n (.-length sound)
        per (js/Math.round (/ 1 frame-seconds))
        loud (if (pos? n) (aget (.sort (js/Float32Array.from sound)) (js/Math.floor (* 0.95 (dec n)))) 0)
        out (js/Float32Array. (js/Math.ceil (/ n per)))]
    (dotimes [second (.-length out)]
      (let [around (.sort (js/Float32Array.from (.subarray sound (max 0 (* (- second floor-seconds) per)) (min n (* (+ second floor-seconds 1) per)))))
            ground (aget around (js/Math.floor (* 0.1 (dec (.-length around)))))]
        (aset out second (max (* quiet-share loud) (* floor-times ground)))))
    out))

(defn edged
  "The `words` with their edges where the sound whose envelope is
   `env` has them. In the stretch the model left between two words
   (and before the first, after the last): where it holds a quiet
   moment (`quiet-levels`), the word before ends where its sound stops
   and the word after begins where its sound sets in, each moved
   `edge-reach` at most; where the sound runs through it for
   `join-reach` at most, the two meet where it is weakest. No edge
   moves inward: an ending too soft to measure is not cut off.

   Scribe is late with a word's start and early with its end. Against
   the alignment of an earnings call (2164 words, 2026-11-16) 14.6 %
   of the starts were more than 100 ms late and 13.2 % of the ends
   more than 100 ms early as it gave them, 4.8 % and 4.7 % with this;
   an alignment model of its own (MMS_FA) came to 8.6 % and 18 % for
   half the recording's length of processor time. Words as they came
   without an envelope or when one of them has no time."
  [words ^js env]
  (let [words (vec words)]
    (if (or (empty? words) (nil? env) (zero? (.-length env))
            (not (every? #(and (number? (:start %)) (number? (:end %))) words)))
      words
      (let [sound (smoothed env)
            n (.-length sound)
            quiet (quiet-levels sound)
            per (js/Math.round (/ 1 frame-seconds))
            quiet? (fn [f] (<= (aget sound f) (aget quiet (min (dec (.-length quiet)) (quot f per)))))
            frame (fn [seconds] (-> (js/Math.round (/ seconds frame-seconds)) (max 0) (min n)))
            seconds (fn [f] (thousandth (* f frame-seconds)))
            reach (js/Math.round (/ edge-reach frame-seconds))
            join (js/Math.round (/ join-reach frame-seconds))]
        (loop [i 0
               words words]
          (if (< (count words) i)
            words
            (let [before (get words (dec i))
                  after (get words i)
                  from (if before (frame (:end before)) (max 0 (- (frame (:start after)) reach)))
                  to (if after (frame (:start after)) (min n (+ (frame (:end before)) reach)))
                  [first-quiet last-quiet] (loop [f from
                                                  first-quiet nil
                                                  last-quiet nil]
                                             (cond
                                               (<= to f) [first-quiet last-quiet]
                                               (quiet? f) (recur (inc f) (or first-quiet f) f)
                                               :else (recur (inc f) first-quiet last-quiet)))]
              (recur (inc i)
                     (cond
                       (< (- to from) 2)
                       words

                       (nil? first-quiet)
                       (if (and before after (<= (- to from) join))
                         (let [weakest (loop [f from
                                              weakest from]
                                         (if (<= f (min (dec n) to))
                                           (recur (inc f) (if (< (aget sound f) (aget sound weakest)) f weakest))
                                           weakest))
                               at (-> (seconds weakest) (max (:start before)) (min (:end after)))]
                           (-> words
                               (assoc-in [(dec i) :end] at)
                               (assoc-in [i :start] at)))
                         words)

                       :else
                       (cond-> words
                         (and before (< from first-quiet) (<= (- first-quiet from) reach))
                         (assoc-in [(dec i) :end] (seconds first-quiet))

                         (and after (< last-quiet (dec to)) (<= (- (dec to) last-quiet) reach))
                         (assoc-in [i :start] (seconds (inc last-quiet)))))))))))))

(def parallel
  "How many pieces are with the model at a time: a recording of two
   hours and a quarter is with it at once and takes the time of its
   slowest piece, a little over a minute; three bodies of 22 MB are
   what a small machine still carries."
  3)

(def ^:private attempts
  "How often a piece is sent when the service is busy or broken (429,
   5xx) or the request fails on the way."
  3)

(defn retry?
  "Whether the throw `message` of a transcription is one to try again:
   the service busy or broken, or the request lost on the way; never a
   refusal of the request itself (a bad key, a file it cannot read)."
  [message]
  (boolean (re-find #"answered (429|5\d\d)|fetch failed|timed out|aborted|ECONNRESET|socket hang up" (str message))))

(defn- ^:async transcription-of
  "The model's transcription of one piece, tried again up to `attempts`
   times when `retry?`, after 2 s, then 4 s: one busy minute of the
   gateway must not lose an hour's transcript."
  [codemode file format]
  (loop [attempt 1]
    (let [result (await (-> (transcription/transcription! codemode (fs/readFileSync file) format "transcript"
                                                          transcription/transcribe-model transcription/transcribe-options)
                            (.then (fn [body] {:body body}))
                            (.catch (fn [error] {:error error}))))]
      (cond
        (:body result) (:body result)
        (and (< attempt attempts) (retry? (.-message (:error result))))
        (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve (* 2000 attempt)))))
            (recur (inc attempt)))
        :else (throw (:error result))))))

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
      (let [overlap (or (:overlap-seconds options) overlap-seconds)
            pieces (await (pieces! source temp (:piece-seconds options) overlap))
            env (:envelope (meta pieces))
            ;; `parallel` pieces at a time with the model, their answers in the pieces' order
            bodies (loop [batches (partition-all (or (:parallel options) parallel) pieces)
                          out []]
                     (if-let [batch (first batches)]
                       (recur (rest batches)
                              (into out (array-seq (await (js/Promise.all
                                                           (into-array (map (fn [{:keys [file format]}]
                                                                              (transcription-of codemode file format))
                                                                            batch)))))))
                       out))
            heard (reduce (fn [state [{:keys [file start end heard]} body]]
                            (-> state
                                (assoc :seconds (or end (+ (or start 0) (or (media/seconds-of file) (some-> body :duration) 0))))
                                (update :parts conj {:start (or start 0)
                                                     :words (shifted (heard-words body) (or heard start 0))})
                                (update :text conj (str/trim (str (:text body))))
                                (update :cost + (or (some-> body :usage :cost) 0))))
                          {:seconds 0 :parts [] :text [] :cost 0}
                          (map vector pieces bodies))
            heard (-> heard
                      (assoc :words (linked (:parts heard) overlap))
                      (dissoc :parts))
            unlengthed (length-warning (:words heard))
            state (-> heard
                      (update :text #(str/join "\n\n" (remove str/blank? %)))
                      (update :words #(marked (if unlengthed % (edged (fitted %) env)))))
            state (await (corrected state {:api api :context context :names names :messages (:messages options)
                                           :complete (or (:complete options) (transcript-correction/openrouter-complete codemode))}))
            paragraphs (paragraphs (:words state) (:text state) 0)
            transcript (transcript-text paragraphs (boolean timestamps?))
            speakers (count (distinct (keep :speaker paragraphs)))
            heading (str "# " (path/basename source) "\n\n"
                         "Transcript of " (path/relative work source) ", " (clock (:seconds state)) ", model " transcription/transcribe-model
                         (when (< 1 speakers) (str ", " speakers " speakers as the model told the voices apart"))
                         ".\n\n")]
        (when (str/blank? transcript)
          (throw (js/Error. (str "The model heard no words in " (path/relative work source) "."))))
        (fs/mkdirSync (path/dirname target) #js {:recursive true})
        (fs/writeFileSync target (str heading transcript "\n"))
        (when words?
          (fs/writeFileSync (path/join dir (words-file out))
                            (js/JSON.stringify (clj->js {:file (path/relative work source)
                                                         :model transcription/transcribe-model
                                                         :seconds (:seconds state)
                                                         :words (:words state)})
                                               nil 1)))
        (str "artifacts/" out ": " (clock (:seconds state)) ", "
             ;; the words the model timed, when it timed them: a text without spaces has none to count
             (if (usable? (:words state) (:text state))
               (count (remove event? (:words state)))
               (count (str/split transcript #"\s+")))
             " words"
             (when (< 1 speakers) (str ", " speakers " speakers"))
             (cost-text (:cost state)) ", model " transcription/transcribe-model
             (when words? (str "; the words with their times in artifacts/" (words-file out)))
             (when unlengthed (str "\n" unlengthed))
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
        :description (str "The words spoken in an audio or a video file, written down by a speech-to-text model (under a cent a minute) that times every word, the language detected. "
                          "`file` is a path under the working directory: uploads/<name> for a file the person attached or recorded, artifacts/<path> for one of yours. "
                          "The transcript goes to artifacts/transcripts/<name>.md, or to `out`, and comes back as the answer; with `timestamps` every paragraph starts with its time. "
                          "Names the model misheard are corrected from our conversation and the `names` you pass (accounts, products, people), every word keeping its time; the answer says what changed. "
                          "When more than one person speaks, a paragraph begins with its speaker, A, B, … in the order they first speak: the model's telling apart of the voices, sound for a few clear voices, less so for many in one room or while people talk at once. "
                          "With `words`, every word with its start and end in seconds and its speaker goes beside the transcript as <name>.words.json, for cutting at the pauses, its edges set where the sound begins and ends; a sound the model named ([applause], [music]) is among them with kind \"event\". "
                          "A file of more than three quarters of an hour is cut into pieces, each ending in a pause, by ffmpeg, which bash has for cutting, converting and cleaning the audio too (ffmpeg -i in.mp3 -ss 0:30 -to 2:00 out.mp3).")
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
