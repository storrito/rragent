(ns rragent.transcribe-test
  "The transcribe tool: the paragraphs from the words and their times,
   and a file written down against a fake OpenRouter in pieces, a few
   at a time, the times of the later pieces shifted; without ffmpeg a
   refusal."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.tools.transcribe :as transcribe]))

(def none
  "No gateway: a port that refuses at once."
  "http://127.0.0.1:1")

(defn- word
  [text start end]
  {:word text :start start :end end :confidence 0.9})

(deftest paragraphs-test
  (testing "the words cut at a pause once the paragraph is long enough"
    (is (= [{:start 0.0 :text "Hallo, Welt."} {:start 40.0 :text "Weiter geht es."}]
           (transcribe/paragraphs [(word "Hallo," 0.0 0.4) (word "Welt." 0.5 30.0)
                                   (word "Weiter" 40.0 40.3) (word "geht" 40.4 40.6) (word "es." 40.7 41.0)]
                                  "Hallo, Welt. Weiter geht es." 0))))
  (testing "a short pause early on cuts nothing"
    (is (= [{:start 0.0 :text "Hallo, Welt."}]
           (transcribe/paragraphs [(word "Hallo," 0.0 0.4) (word "Welt." 3.0 3.4)] "Hallo, Welt." 0))))
  (testing "words without times, or bare words of a punctuated text: the text as it came"
    (is (= [{:start 12 :text "Hallo, Welt."}]
           (transcribe/paragraphs [{:word "Hallo"} {:word "Welt"}] "Hallo, Welt." 12)))
    (is (= [{:start 0 :text "Hallo, Welt."}]
           (transcribe/paragraphs [(word "Hallo" 0.0 0.4) (word "Welt" 0.5 1.0)] "Hallo, Welt." 0))))
  (testing "nothing said"
    (is (= [] (transcribe/paragraphs [] "  " 0))))
  (testing "words joined with a space, none within a script written without spaces"
    (is (= "Hallo, Welt." (transcribe/joined [(word "Hallo," 0 1) (word " Welt. " 1 2) (word "" 2 2)])))
    (is (= "今日のテーマは自転車です。皆さん"
           (transcribe/joined (map #(word % 0 1) ["今" "日" "の" "テ" "ー" "マ" "は" "自" "転" "車" "で" "す" "。" "皆" "さ" "ん"]))))
    (is (= "アプリは Hello サイクリングです。" (transcribe/joined (map #(word % 0 1) ["ア" "プ" "リ" "は" "Hello" "サ" "イ" "ク" "リ" "ン" "グ" "で" "す" "。"])))
        "a word in Latin letters among them keeps its spaces")
    (is (= "สวัสดีครับ" (transcribe/joined [(word "สวัสดี" 0 1) (word "ครับ" 1 2)])))
    (is (= [{:start 0.0 :text "今日は。"}]
           (transcribe/paragraphs [(word "今" 0.0 0.2) (word "日" 0.2 0.4) (word "は" 0.4 0.5) (word "。" 0.5 0.5)] "今日は。" 0))))
  (testing "the text with the times, m:ss and h:mm:ss"
    (is (= "[0:00] Hallo\n\n[1:05:03] Welt"
           (transcribe/transcript-text [{:start 0 :text "Hallo"} {:start 3903.4 :text "Welt"}] true)))
    (is (= "Hallo\n\nWelt"
           (transcribe/transcript-text [{:start 0 :text "Hallo"} {:start 3903.4 :text "Welt"}] false))))
  (testing "words without a length, as Universal-3.6 Pro gives some: the pause after one is reckoned from its start"
    (is (= [{:start 0 :text "Eins, zwei."} {:start 27.2 :text "Drei."}]
           (transcribe/paragraphs [(word "Eins," 0 0) (word "zwei." 26 26) (word "Drei." 27.2 27.2)] "Eins, zwei. Drei." 0))))
  (testing "the words of a later piece shifted"
    (is (= [{:word "a" :start 600.5 :end 601.0 :confidence 0.9}]
           (transcribe/shifted [(word "a" 0.5 1.0)] 600)))))

(deftest cuts-test
  (testing "a file no longer than a piece is one piece"
    (is (= [[0 600]] (transcribe/cuts 600 [] 600)))
    (is (= [[0 12.5]] (transcribe/cuts 12.5 nil 600))))
  (testing "without pauses, at the full length of each piece"
    (is (= [[0 600] [600 1200] [1200 1300]] (transcribe/cuts 1300 [] 600))))
  (testing "in the middle of the longest pause that reaches into the last two minutes"
    (is (= [[0 570] [570 1007]] (transcribe/cuts 1007 [[100 104] [565 575] [598 598.5] [610 640]] 600))))
  (testing "a pause that goes on past the full length ends the piece at its full length; one before the window counts by the part inside it"
    (is (= [[0 600] [600 700]] (transcribe/cuts 700 [[590 650]] 600)))
    (is (= [[0 480] [480 900]] (transcribe/cuts 900 [[470 481] [500 500.3]] 600))))
  (testing "the next piece is reckoned from where the last one ended"
    (is (= [[0 590] [590 1180] [1180 1250]] (transcribe/cuts 1250 [[589 591] [1179 1181]] 600))))
  (testing "a remainder under a thirtieth of a piece is no piece of its own: it goes with the one before"
    (is (= [[0 600] [600 1205]] (transcribe/cuts 1205 [] 600)))
    (is (= [[0 600] [600 1200] [1200 1220]] (transcribe/cuts 1220 [] 600)) "twenty seconds are a piece")
    (is (= [[0 3] [3 3.4]] (transcribe/cuts 3.4 [] 3)) "of pieces of three seconds, 0.4 s are one")
    (is (= [[0 300.4]] (transcribe/cuts 300.4 [] 300)))
    (is (= [[0 4]] (transcribe/cuts 4 [] 600)) "a short file is its one piece"))
  (testing "the words of a piece are its text when they carry nine tenths of its letters"
    (is (transcribe/covers? [(word "Hello," 0 0.3) (word "world." 0.4 0.8)] "Hello, world."))
    (is (transcribe/covers? [(word "今日の" 0 1) (word "テーマ" 1 2)] "今日のテーマ"))
    (is (not (transcribe/covers? [(word "今日の" 0 1)] "今日のテーマは音声です")) "an utterance of several is missing")
    (is (not (transcribe/covers? [] "A text without words.")))
    (is (not (transcribe/covers? [] ""))))
  (testing "a busy or broken service and a lost request are tried again; a refusal is not"
    (is (transcribe/retry? "The transcription service answered 502: Bad Gateway"))
    (is (transcribe/retry? "The transcription service answered 429: Too many requests"))
    (is (transcribe/retry? "fetch failed"))
    (is (transcribe/retry? "The operation was aborted due to timeout"))
    (is (not (transcribe/retry? "The transcription service answered 401: wrong key")))
    (is (not (transcribe/retry? "The transcription service answered 400: Provider returned 400"))))
  (testing "without a pause in the window, where the quietest moment is; with one, the pause"
    (is (= [[0 555] [555 700]] (transcribe/cuts 700 [] 600 (fn [low full] (is (= [480 600] [low full])) 555))))
    (is (= [[0 570] [570 700]] (transcribe/cuts 700 [[565 575]] 600 (fn [_ _] 555))))))

(defn- env-of
  "An envelope of 10 ms frames from `[seconds level]` stretches."
  [& stretches]
  (js/Float32Array.from (clj->js (mapcat (fn [[seconds level]] (repeat (js/Math.round (* 100 seconds)) level)) stretches))))

(defn- rounded
  [pauses]
  (mapv (fn [[from to]] [(/ (js/Math.round (* 100 from)) 100) (/ (js/Math.round (* 100 to)) 100)]) pauses))

(deftest sound-test
  (testing "the envelope: each 10 ms frame's RMS, from the frame asked for on"
    (let [samples (js/Int16Array. 480)
          _ (dotimes [i 160] (aset samples (+ 160 i) 16384))
          env (transcribe/envelope samples (js/Float32Array. 5) 1)]
      (is (= [0 0 0.5 0 0] (vec (array-seq env))))))
  (testing "a pause is a quarter of a second or more under 8 % of loud speech, whatever the level (its edges a frame in, for the smoothing)"
    (is (= [[1.01 1.49] [2.51 3]] (rounded (transcribe/pauses (env-of [1 0.3] [0.5 0.001] [1 0.3] [0.5 0.02])))))
    (is (= [[1.01 1.49]] (rounded (transcribe/pauses (env-of [1 0.003] [0.5 0.0001] [1 0.003]))))
        "a quiet recording: 40 dB lower, the same pause")
    (is (= [[1.01 1.49]] (rounded (transcribe/pauses (env-of [1 0.3] [0.5 0.02] [1 0.3]))))
        "a hiss under it that a fixed -40 dB takes for sound")
    (is (= [] (transcribe/pauses (env-of [1 0.3] [0.2 0.001] [1 0.3]))) "0.2 s is a stop in a word, no pause")
    (is (= [[0 2]] (rounded (transcribe/pauses (env-of [2 0])))) "nothing but silence is one pause"))
  (testing "the quietest tenth of a second of a stretch, its middle"
    (is (= 1.55 (transcribe/quietest (env-of [1.5 0.3] [0.1 0.05] [1.4 0.3]) 1 3)))
    (is (= 2.5 (transcribe/quietest (env-of [1 0.3]) 2 2.5)) "a stretch past the sound: its end")))

(defn- wav
  "A WAV of `seconds`, mono, 16 kHz, 16 bit: silence, or a tone of
   440 Hz where `loud?` (a function of the second) says so."
  [seconds & [loud?]]
  (let [samples (js/Math.round (* 16000 seconds))
        data (* 2 samples)
        header (js/Buffer.alloc 44)
        sound (js/Buffer.alloc data)]
    (when loud?
      (dotimes [i samples]
        (when (loud? (/ i 16000))
          (.writeInt16LE sound (js/Math.round (* 12000 (js/Math.sin (/ (* 2 js/Math.PI 440 i) 16000)))) (* 2 i)))))
    (.write header "RIFF" 0)
    (.writeUInt32LE header (+ 36 data) 4)
    (.write header "WAVE" 8)
    (.write header "fmt " 12)
    (.writeUInt32LE header 16 16)
    (.writeUInt16LE header 1 20)
    (.writeUInt16LE header 1 22)
    (.writeUInt32LE header 16000 24)
    (.writeUInt32LE header 32000 28)
    (.writeUInt16LE header 2 32)
    (.writeUInt16LE header 16 34)
    (.write header "data" 36)
    (.writeUInt32LE header data 40)
    (js/Buffer.concat #js [header sound])))

(defn- ffmpeg?
  []
  (try
    (zero? (.-status (child/spawnSync "ffmpeg" #js ["-version"] #js {:stdio "ignore"})))
    (catch :default _
      false)))

;; `at-most`: the answers in the items' order, never more than `n` running
(deftest at-most-test
  (async done
    (let [running (atom 0)
          most (atom 0)]
      (-> (transcribe/at-most 2 [30 10 20 5 1]
                              (fn [ms i]
                                (swap! most max (swap! running inc))
                                (js/Promise. (fn [resolve]
                                               (js/setTimeout #(do (swap! running dec) (resolve [i ms])) ms)))))
          (.then (fn [answers]
                   (is (= [[0 30] [1 10] [2 20] [3 5] [4 1]] answers))
                   (is (= 2 @most))))
          (.then #(transcribe/at-most 8 [] (fn [_ _] (js/Promise.resolve 1))))
          (.then #(is (= [] %) "nothing to do"))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally done)))))

(defn- fake-openrouter
  "Answers `/audio/transcriptions` with one sentence per request, its
   words in the first second, and a cost, 150 ms later; 401 without the
   key. Under `/bare/` the words carry no punctuation; under `/late/`
   the earlier a request the later its answer, and a piece of under
   three quarters of a second says `Tail.`; under `/fail2/` the second
   request is refused with 400; under `/whole/` the text says more
   than the words. Every request is noted in `seen`, and the most at a
   time in `most`."
  [seen most]
  (let [running (atom 0)]
    (http/createServer
     (fn [^js req ^js res]
       (let [chunks #js []]
         (swap! most max (swap! running inc))
         (.on req "data" (fn [c] (.push chunks c)))
         (.on req "end" (fn []
                          (let [body (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8")) :keywordize-keys true)
                                n (count (swap! seen conj {:url (.-url req)
                                                           :auth (aget (.-headers req) "authorization")
                                                           :model (:model body)
                                                           :keys (set (keys body))
                                                           :format (get-in body [:input_audio :format])
                                                           :bytes (count (get-in body [:input_audio :data]))}))
                                in? #(str/includes? (.-url req) %)
                                bare? (in? "/bare/")
                                ;; an MP3 of a second at 48 kbit/s is 8000 characters of base64
                                tail? (and (in? "/late/") (< (:bytes (peek @seen)) 6000))
                                refused? (and (in? "/fail2/") (= 2 n))]
                            (js/setTimeout
                             (fn []
                               (swap! running dec)
                               (.writeHead res (cond
                                                 (not= "Bearer k3y" (aget (.-headers req) "authorization")) 401
                                                 refused? 400
                                                 :else 200)
                                           #js {"Content-Type" "application/json"})
                               (.end res (js/JSON.stringify
                                          (clj->js (cond
                                                     refused? {:error {:message "Provider returned 400"}}
                                                     tail? {:text "Tail."
                                                            :words [{:word "Tail." :start 0.1 :end 0.3 :confidence 0.9}]
                                                            :usage {:seconds 1 :cost 0.001}}
                                                     :else {:text (str "Piece " n "." (when (in? "/whole/") " And a good deal more that its words do not carry."))
                                                            :words [{:word "Piece" :start 0.1 :end 0.4 :confidence 0.98}
                                                                    {:word (str n (when-not bare? ".")) :start 0.5 :end 0.9 :confidence 0.9}]
                                                            :usage {:seconds 1 :cost 0.001}})))))
                             (if (in? "/late/") (max 20 (- 700 (* 110 n))) 150))))))))))

(defn- set-var!
  [k v]
  (if (nil? v) (js-delete (.-env js/process) k) (aset (.-env js/process) k v)))

(defn- message-of
  [promise]
  (.catch promise (fn [e] (.-message e))))

(defn- length-of
  "The seconds of an audio file, as ffprobe reads them."
  [file]
  (js/parseFloat (.-stdout (child/spawnSync "ffprobe" #js ["-v" "error" "-show_entries" "format=duration" "-of" "csv=p=0" file]
                                            #js {:encoding "utf8"}))))

(deftest transcribe-test
  (async done
    (let [seen (atom [])
          most (atom 0)
          server (fake-openrouter seen most)
          env (.-env js/process)
          before {:url (aget env "OPENROUTER_BASE_URL") :key (aget env "OPENROUTER_API_KEY") :path (aget env "PATH")}
          work (fs/mkdtempSync (path/join (os/tmpdir) "rragent-transcribe-"))
          dir (path/join work "artifacts")
          base #(str "http://127.0.0.1:" (.-port (.address server)) % "/v1/")]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (aset env "OPENROUTER_BASE_URL" (base ""))
             (aset env "OPENROUTER_API_KEY" "k3y")
             (fs/mkdirSync (path/join work "uploads") #js {:recursive true})
             (fs/writeFileSync (path/join work "uploads" "ab12.wav") (wav 2.5))
             (let [codemode (codemode/handle none)]
               (testing "without ffmpeg the tool refuses, and nothing goes to the model"
                 (aset env "PATH" (path/join work "no-programs"))
                 (try
                   (is (= transcribe/no-ffmpeg
                          (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav"})))))
                   (finally
                     (aset env "PATH" (:path before))))
                 (is (= [] @seen)))
               (when (ffmpeg?)
                 (testing "a file under uploads/ is written down into artifacts/transcripts/, in pieces of a second, each an MP3, the later ones' words shifted"
                   ;; one piece at a time: the fake numbers its answers by the requests' order
                   (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :piece-seconds 1
                                                                :parallel 1 :timestamps? true}))
                         file (path/join dir "transcripts" "ab12.md")]
                     (is (str/starts-with? answer "artifacts/transcripts/ab12.md: 0:02, 6 words, $0.003, model assemblyai/universal-3-6-pro"))
                     (is (str/includes? answer "the transcribe skill writes a word-exact transcript") "where the word-exact one comes from")
                     (is (= "assemblyai/universal-3-6-pro" (:model (first @seen))) "the dictation's model")
                     (is (= #{:model :input_audio :response_format :timestamp_granularities :temperature} (:keys (first @seen)))
                         "no voices asked for")
                     (is (= "Bearer k3y" (:auth (first @seen))))
                     (is (= 3 (count @seen)))
                     (is (= ["mp3" "mp3" "mp3"] (map :format @seen)))
                     (is (str/includes? answer "[0:00] Piece 1. Piece 2. Piece 3."))
                     (is (= "# ab12.wav\n\nTranscript of uploads/ab12.wav, 0:02, model assemblyai/universal-3-6-pro.\n\n[0:00] Piece 1. Piece 2. Piece 3.\n"
                            (fs/readFileSync file "utf8")))))
                 (testing "the pieces with the model a few at a time, their words in the pieces' order whatever order the answers came in"
                   (reset! seen [])
                   (reset! most 0)
                   (fs/writeFileSync (path/join work "uploads" "long.wav") (wav 5.5))
                   (aset env "OPENROUTER_BASE_URL" (base "/late"))
                   (try
                     ;; the last piece is half a second: its answer says "Tail." and comes first of the last two
                     (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/long.wav" :piece-seconds 1
                                                                  :parallel 2}))
                           text (str/trim (fs/readFileSync (path/join dir "transcripts" "long.md") "utf8"))]
                       (is (< 5 (count @seen)))
                       (is (= (dec (count @seen)) (count (re-seq #"Piece \d+\." text))) "every piece but the last")
                       (is (str/ends-with? text "Tail.") "the last piece's words last")
                       (is (= 1 (count (re-seq #"Tail\." text))))
                       (is (= 2 @most) "two at a time")
                       (is (not (str/includes? answer "words.json")) "no file of word times")
                       (is (not (fs/existsSync (path/join dir "transcripts" "long.words.json")))))
                     (finally
                       (aset env "OPENROUTER_BASE_URL" (base "")))))
                 (testing "a piece that fails is marked where it would stand, the others are written down, and the answer says it is not whole"
                   (reset! seen [])
                   (aset env "OPENROUTER_BASE_URL" (base "/fail2"))
                   (try
                     (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "gap.md"
                                                                  :piece-seconds 1 :parallel 1 :timestamps? true}))
                           text (fs/readFileSync (path/join dir "gap.md") "utf8")]
                       (is (= 3 (count @seen)) "a refusal is not tried again, and the piece after it is sent")
                       (is (str/starts-with? answer "artifacts/gap.md: 0:02, 4 words, $0.002"))
                       (is (str/includes? answer "\nNot whole: 1 of 3 pieces failed and are marked in the text (0:0"))
                       (is (re-find #"\(0:0\d to 0:0\d\): The transcription service answered 400: Provider returned 400\. Run it again for the whole of it\.\n" answer))
                       (is (re-find #"\[0:00\] Piece 1\.\n\n\[0:0\d\] \[0:0\d to 0:0\d is not written down: The transcription service answered 400: Provider returned 400\]\n\n\[0:0\d\] Piece 3\.\n$" text)))
                     (finally
                       (aset env "OPENROUTER_BASE_URL" (base "")))))
                 (testing "a piece whose words are not its text is written from the text"
                   (aset env "OPENROUTER_BASE_URL" (base "/whole"))
                   (try
                     (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "whole.md"}))]
                       (is (re-find #"\n\nPiece \d+\. And a good deal more that its words do not carry\.$" answer))
                       (is (str/starts-with? answer "artifacts/whole.md: 0:02, 13 words")))
                     (finally
                       (aset env "OPENROUTER_BASE_URL" (base "")))))
                 (testing "words without punctuation: a paragraph for every piece, at its start"
                   (aset env "OPENROUTER_BASE_URL" (base "/bare"))
                   (try
                     (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "bare.md"
                                                                  :piece-seconds 1 :parallel 1 :timestamps? true}))]
                       (is (str/starts-with? answer "artifacts/bare.md: 0:02, 6 words"))
                       (is (re-find #"\n\n\[0:00\] Piece \d+\.\n\n\[0:01\] Piece \d+\.\n\n\[0:01\] Piece \d+\.$" answer)
                           "the third piece begins at 1.8 s, in the pause"))
                     (finally
                       (aset env "OPENROUTER_BASE_URL" (base "")))))
                 (testing "the pieces end in a pause, their starts and ends where the sound was cut"
                   ;; a tone, a pause from 1.3 s to 1.7 s, a tone to 3 s; pieces of 1.6 s at most
                   (fs/writeFileSync (path/join work "uploads" "tone.wav") (wav 3 #(not (< 1.3 % 1.7))))
                   (let [temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-pieces-"))]
                     (try
                       (let [{:keys [sound seconds pieces]} (await (transcribe/planned! (path/join work "uploads" "tone.wav") temp 1.6))
                             [[_ cut] later] pieces]
                         (is (= 2 (count pieces)))
                         (is (< 1.45 cut 1.55) "the middle of the pause, not 1.6 s")
                         (is (= [cut 3] later) "the file's length is what was read")
                         (is (= 3 seconds))
                         (let [file (await (transcribe/encoded! sound 1 later))]
                           (is (str/ends-with? file "piece-0001.mp3"))
                           (is (< (js/Math.abs (- (length-of file) (- 3 cut))) 0.08) "its sound is from its start to its end")))
                       (finally
                         (fs/rmSync temp #js {:recursive true :force true})))))
                 (testing "a recording whose head says no length, as a browser's recorder writes it, is cut all the same"
                   (let [made (child/spawnSync "ffmpeg" #js ["-v" "error" "-i" (path/join work "uploads" "tone.wav") "-c:a" "libopus" "-f" "webm" "pipe:1"]
                                               #js {:maxBuffer (* 16 1024 1024)})
                         temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-pieces-"))]
                     (try
                       (when (and (zero? (.-status made)) (pos? (.-length (.-stdout made))))
                         (fs/writeFileSync (path/join work "uploads" "stream.webm") (.-stdout made))
                         (let [{:keys [pieces]} (await (transcribe/planned! (path/join work "uploads" "stream.webm") temp 1.6))]
                           (is (= 2 (count pieces)))
                           (is (< 1.4 (second (first pieces)) 1.6))
                           (is (< 2.9 (second (second pieces)) 3.1))))
                       (finally
                         (fs/rmSync temp #js {:recursive true :force true})))))
                 (testing "where `out` says, without the times, one piece"
                   (reset! seen [])
                   (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/words.md"}))]
                     (is (= 1 (count @seen)))
                     (is (str/starts-with? answer "artifacts/talk/words.md: "))
                     (is (not (str/includes? answer "[0:00]")))
                     (is (fs/existsSync (path/join dir "talk" "words.md")))))
                 (testing "corrected through the tool's api"
                   (let [api #js {:conversationId "c1"
                                  :conversation (fn [_ _] (js/Promise.resolve nil))}
                         answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/fixed.md"
                                                                :api api :names "Peace" :messages (fn [_ _] (js/Promise.reject (js/Error. "no bot")))
                                                                :complete (fn [_] (js/Promise.resolve {:text "{\"fixes\": [{\"heard\": \"Piece\", \"meant\": \"Peace\"}]}" :cost 0}))}))]
                     (is (re-find #"Corrected: Piece → Peace( \(\d×\))?\." answer))
                     (is (re-find #"\n\nPeace \d+\." answer))
                     (is (str/includes? (fs/readFileSync (path/join dir "talk" "fixed.md") "utf8") "Peace"))
                     (is (not (fs/existsSync (path/join dir "talk" "fixed.words.json"))))))
                 (testing "a bad key: the service's answer"
                   (aset env "OPENROUTER_API_KEY" "wrong")
                   (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav"}))) "answered 401")))
                 (testing "neither a gateway nor a key"
                   (js-delete env "OPENROUTER_API_KEY")
                   (is (= "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no transcript."
                          (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav"})))))))
               (testing "a file that is not there, a path above the working directory, an out above artifacts/"
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/nope.mp3"}))) "no file uploads/nope.mp3"))
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "../etc/passwd"}))) "path under the working directory"))
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "../x.md"}))) "path under artifacts/"))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (set-var! "OPENROUTER_BASE_URL" (:url before))
                      (set-var! "OPENROUTER_API_KEY" (:key before))
                      (set-var! "PATH" (:path before))
                      (fs/rmSync work #js {:recursive true :force true})
                      (.close server)
                      (sync-fetch/stop!)
                      (done)))))))
