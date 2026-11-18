(ns rragent.tools.transcribe
  "The `transcribe` tool: what was said in an audio or a video file
   the person attached or recorded (`uploads/<name>`, see
   `rragent.uploads`), or one the agent made under `artifacts/`, as
   text in paragraphs, fast and cheap: written down by a speech-to-text
   model (`rragent.transcription`: RRAGENT_TRANSCRIBE_MODEL, the
   dictation's Universal-3.6 Pro of AssemblyAI, which detects the
   language) through OpenRouter the way the REPL `codemode` of the
   agent knows it (`rragent.openrouter`).

   The word-exact transcript, for cutting a video, captions and who
   speaks, is the `transcribe` skill's of agentitos: it links the
   voices of a long recording into speakers and moves the edges of the
   words to where the sound has them. This tool keeps the words with
   the times the model gave them, not checked against the sound.

   ffmpeg reads the sound once from start to end (`planned!`: mono, 16
   kHz, raw), so its length is what was read, whatever the file's head
   says, and cuts it into pieces of `piece-seconds`, each ending in a
   pause of the speech (`cuts`, `pauses`: quiet by the recording's own
   measure) so that no word is cut in two. Each piece is an MP3 of its
   own from that sample (`encoded!`), so no tag of the file reaches the
   model (a phone's video names where it was recorded), and is written
   down as soon as it is encoded, `parallel` of them at a time, their
   words joined with the times shifted by where the piece starts.
   Without ffmpeg the tool refuses rather than send the file whole.

   The words are corrected with what the agent knows before anything
   is written (`rragent.transcript-correction`): the names the speech
   model wrote the way they sound, by a small model with the
   conversation and the `names` the agent passes, every word keeping
   its time; the answer says what changed.

   The transcript goes under `artifacts/transcripts/<name>.md`, or
   where `out` says: paragraphs cut at the pauses of the speech
   (`paragraphs`), each with its time when `timestamps` is asked; and
   the answer carries the text, so the agent works with it at once.
   With `words`, every word with its start, end and confidence as the
   model gave them goes beside it as `<name>.words.json`."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.tools.work :as work]
            [rragent.transcript-correction :as transcript-correction]
            [rragent.transcription :as transcription]))

(def piece-seconds
  "How long one piece sent to the model is at most: five minutes,
   which Universal-3.6 Pro writes down in one to two and a half
   seconds, eight at a time (2026-11-18), so that an hour is twelve to
   fifteen pieces and two rounds of them (13 for a podcast), and the
   gateway's 130 s are far."
  300)

(def pause-window
  "How far before its full length a piece may end, as a share of it,
   a minute of five, to end in a pause."
  0.2)

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

(def no-ffmpeg
  "Why the tool refuses on a machine without ffmpeg."
  (str "The transcribe tool needs ffmpeg, and this machine has none: it sends the model the sound alone, "
       "cut into pieces, never the file itself with its tags."))

(def sample-rate
  "The samples a second of the sound the pieces are cut from."
  16000)

(def ^:private raw
  "ffmpeg's options for the raw sound: signed 16 bit, mono."
  ["-f" "s16le" "-ar" (str sample-rate) "-ac" "1"])

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
   `planned!` writes it): read a hundred seconds at a time, so that an
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

(defn ^:async planned!
  "How `file` is cut for the model, `{:sound :seconds :pieces}`: the
   sound read once from start to end into `temp` (mono, 16 kHz, raw),
   its length in seconds, which is then what was read, not what the
   file's head claims (a browser's recording claims none, a raw AAC or
   an MP3 of a varying rate a wrong one, measured 14 % off), and the
   pieces `[start end]` in seconds, each ending in a pause (`cuts`,
   `pauses`) or, without one, at the quietest moment (`quietest`).
   `seconds` is the most a piece is long, `piece-seconds` by default (a
   test takes shorter ones). Throws `no-ffmpeg` without ffmpeg."
  [file temp & [seconds]]
  (when-not (ffmpeg?)
    (throw (js/Error. no-ffmpeg)))
  (let [seconds (or seconds piece-seconds)
        sound (path/join temp "sound.pcm")
        _ (await (exec "ffmpeg" (into ["-y" "-v" "error" "-i" file "-vn"] (conj raw sound))))
        duration (/ (.-size (fs/statSync sound)) 2 sample-rate)
        _ (when-not (pos? duration)
            (throw (js/Error. "The file has no sound.")))
        env (when (< seconds duration)
              (await (envelope-of sound)))]
    {:sound sound
     :seconds duration
     :pieces (cuts duration (some-> env pauses) seconds (when env #(quietest env %1 %2)))}))

(defn ^:async encoded!
  "The `n`th piece of the raw `sound` (`planned!`), from `start` to
   `end` seconds, as an MP3 of 48 kbit/s beside it: written to a file,
   whose head says how many samples the encoder put before the sound;
   the same MP3 through a pipe has no such head, and every word came 70
   ms late. Answers the file."
  [sound n [start end]]
  (let [piece (path/join (path/dirname sound) (str "piece-" (.padStart (str n) 4 "0") ".mp3"))]
    (await (exec "ffmpeg" (into ["-y" "-v" "error"]
                                (concat raw ["-ss" (.toFixed start 4) "-t" (.toFixed (- end start) 4) "-i" sound
                                             "-codec:a" "libmp3lame" "-b:a" "48k" piece]))))
    piece))

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
  "The paragraphs of a transcription, `[{:start :text}]`: the `words`
   with their times cut at the pauses of the speech, when they carry
   times and the text's punctuation; else the `text` as one paragraph
   starting at `offset`. A word without a length (its end at its start)
   makes the pause after it look longer by its length."
  [words text offset]
  (if (usable? words text)
    (let [closed (fn [out current]
                   (if (seq (:words current))
                     (conj out {:start (:start current)
                                :text (joined (:words current))})
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
                   (str (when timestamps? (str "[" (clock start) "] ")) text))
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
   `words` (of 20 at least) no length: the words file has no ends to
   cut at. Universal-3.6 Pro did so for nearly all words of an English
   recording in November 2026, and on 2026-11-18 for half the words of
   French and Spanish and next to none of English and German."
  [words]
  (let [n (count words)
        bare (without-length words)]
    (when (and (<= 20 n) (<= (/ n 3) bare))
      (str bare " of " n " words came without a length (their end at their start): "
           "the model gave no word ends, so a cut at them is a guess."))))

(def parallel
  "How many pieces are encoded and with the model at a time: an hour
   is two rounds of eight. Eight answered as fast as
   one (one to two and a half seconds for five minutes, 2026-11-18),
   and eight bodies of 2.4 MB are little for a Sprite's free memory."
  8)

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
                                                          transcription/transcribe-model)
                            (.then (fn [body] {:body body}))
                            (.catch (fn [error] {:error error}))))]
      (cond
        (:body result) (:body result)
        (and (< attempt attempts) (retry? (.-message (:error result))))
        (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve (* 2000 attempt)))))
            (recur (inc attempt)))
        :else (throw (:error result))))))

(defn ^:async at-most
  "The answers of `f` (of an item and its index, a promise) for all
   `items`, in their order, with `n` of them running at a time: each
   begins as soon as one before it is done."
  [n items f]
  (let [items (vec items)
        answers (make-array (count items))
        taken (atom -1)
        worker (^:async fn []
                 (loop []
                   (let [i (swap! taken inc)]
                     (when (< i (count items))
                       (aset answers i (await (f (nth items i) i)))
                       (recur)))))]
    (await (js/Promise.all (into-array (repeatedly (max 1 (min n (count items))) worker))))
    (vec answers)))

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
      (let [{:keys [sound seconds pieces]} (await (planned! source temp (:piece-seconds options)))
            bodies (await (at-most (or (:parallel options) parallel) pieces
                                   (^:async fn [piece n]
                                     (await (transcription-of codemode (await (encoded! sound n piece)) "mp3")))))
            parts (mapv (fn [[start] body]
                          {:start start
                           :text (str/trim (str (:text body)))
                           :words (shifted (mapv #(select-keys % [:word :start :end :confidence]) (:words body)) start)})
                        pieces bodies)
            state {:seconds seconds
                   :words (into [] (mapcat :words) parts)
                   :text (str/join "\n\n" (remove str/blank? (map :text parts)))
                   :cost (reduce + (map #(or (some-> % :usage :cost) 0) bodies))}
            unlengthed (length-warning (:words state))
            state (await (corrected state {:api api :context context :names names :messages (:messages options)
                                           :complete (or (:complete options) (transcript-correction/openrouter-complete codemode))}))
            timed? (usable? (:words state) (:text state))
            ;; without word times, a paragraph for every piece, at its start
            paragraphs (if timed?
                         (paragraphs (:words state) (:text state) 0)
                         (into [] (mapcat #(paragraphs nil (:text %) (:start %))) parts))
            transcript (transcript-text paragraphs (boolean timestamps?))
            heading (str "# " (path/basename source) "\n\n"
                         "Transcript of " (path/relative work source) ", " (clock seconds) ", model " transcription/transcribe-model ".\n\n")]
        (when (str/blank? transcript)
          (throw (js/Error. (str "The model heard no words in " (path/relative work source) "."))))
        (fs/mkdirSync (path/dirname target) #js {:recursive true})
        (fs/writeFileSync target (str heading transcript "\n"))
        (when words?
          (fs/writeFileSync (path/join dir (words-file out))
                            (js/JSON.stringify (clj->js {:file (path/relative work source)
                                                         :model transcription/transcribe-model
                                                         :seconds seconds
                                                         :words (:words state)})
                                               nil 1)))
        (str "artifacts/" out ": " (clock seconds) ", "
             ;; the words the model timed, when it timed them: a text without spaces has none to count
             (if timed?
               (count (:words state))
               (count (str/split (str/trim (transcript-text paragraphs false)) #"\s+")))
             " words"
             (cost-text (:cost state)) ", model " transcription/transcribe-model
             (when words? (str "; the words with the times the model gave them, not checked against the sound, in artifacts/" (words-file out)))
             "; for cutting, captions or who speaks, the transcribe skill writes a word-exact transcript where the machine has it"
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
        :description (str "What was said in an audio or a video file, written down fast and cheap by a speech-to-text model (half a cent a minute, an hour in under a minute), the language detected: the text in paragraphs, to read, quote or sum up. "
                          "`file` is a path under the working directory: uploads/<name> for a file the person attached or recorded, artifacts/<path> for one of yours. "
                          "The transcript goes to artifacts/transcripts/<name>.md, or to `out`, and comes back as the answer; with `timestamps` every paragraph starts with its time. "
                          "Names the model misheard are corrected from our conversation and the `names` you pass (accounts, products, people), every word keeping its time; the answer says what changed. "
                          "With `words`, every word with the start and end in seconds the model gave it goes beside the transcript as <name>.words.json; those times are not checked against the sound. "
                          "For cutting a video, captions or who speaks, use the transcribe skill when the machine has it (its SKILL.md among the skills): it is word-exact and names the speakers; this tool does neither. "
                          "ffmpeg cuts the sound into pieces of five minutes in pauses, all written down at once; without ffmpeg the tool refuses. bash has ffmpeg for cutting, converting and cleaning the audio too (ffmpeg -i in.mp3 -ss 0:30 -to 2:00 out.mp3).")
        :parameters (.Object Type #js {:file (.String Type #js {:description "The audio or video file, uploads/ab12.mp3 or artifacts/talk.m4a"})
                                       :out (.Optional Type (.String Type #js {:description "Where the transcript goes under artifacts/, transcripts/<name>.md by default"}))
                                       :timestamps (.Optional Type (.Boolean Type #js {:description "True: every paragraph starts with its time, m:ss"}))
                                       :names (.Optional Type (.String Type #js {:description "Names and terms that may be spoken, comma-separated: accounts, products, people"}))
                                       :words (.Optional Type (.Boolean Type #js {:description "True: also every word with the times the model gave it, <name>.words.json"}))})
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
