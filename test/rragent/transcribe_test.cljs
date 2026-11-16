(ns rragent.transcribe-test
  "The transcribe tool: the paragraphs from the words and their times,
   and a file written down against a fake OpenRouter, in pieces when
   ffmpeg is on the machine, the times of the later pieces shifted."
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
  (testing "words without a length: counted, and said once they are a third of twenty or more"
    (let [timed (vec (for [i (range 30)] (word "w" i (+ i 0.3))))
          bare (vec (for [i (range 30)] (word "w" i i)))]
      (is (= 0 (transcribe/without-length timed)))
      (is (= 30 (transcribe/without-length bare)))
      (is (= 1 (transcribe/without-length [{:word "x" :start 1}])) "no end at all")
      (is (nil? (transcribe/length-warning timed)))
      (is (nil? (transcribe/length-warning (subvec bare 0 10))) "too few words to tell")
      (is (nil? (transcribe/length-warning (into (subvec timed 0 21) (subvec bare 0 9)))))
      (is (= "10 of 30 words came without a length (their end at their start): the model gave no word ends, so a cut at them is a guess."
             (transcribe/length-warning (into (subvec timed 0 20) (subvec bare 0 10)))))))
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

(deftest words-test
  (testing "a sound the model named among the words is marked, a word is not"
    (is (= [{:word "[Applaus]" :start 1 :end 2 :kind "event"} {:word "Danke." :start 2 :end 2.4}
            {:word "(laughs)" :start 3 :end 3.5 :kind "event"} {:word "[música]," :start 4 :end 5 :kind "event"}
            {:word "[sic" :start 6 :end 6.2}]
           (transcribe/marked [{:word "[Applaus]" :start 1 :end 2} {:word "Danke." :start 2 :end 2.4}
                               {:word "(laughs)" :start 3 :end 3.5} {:word "[música]," :start 4 :end 5}
                               {:word "[sic" :start 6 :end 6.2}]))))
  (testing "a word without a length takes the room between its neighbours, 0.15 s to either side at most"
    (is (= [(word "as" 103.16 103.24) (assoc (word "a" 103.24 103.32) :by "neighbours") (word "result" 103.32 103.72)]
           (transcribe/fitted [(word "as" 103.16 103.24) (word "a" 103.26 103.26) (word "result" 103.32 103.72)])))
    (is (= [(word "And," 699.0 699.24) (assoc (word "you" 699.77 699.98) :by "neighbours") (word "know," 699.98 700.02)]
           (transcribe/fitted [(word "And," 699.0 699.24) (word "you" 699.92 699.92) (word "know," 699.98 700.02)])))
    (is (= [(assoc (word "Oh" 0 0.15) :by "neighbours")]
           (transcribe/fitted [(word "Oh" 0 0)]))
        "the first and only word: none before the start of the file")
    (is (= [(word "a" 1 1.5) (word "b" 1.2 1.2) (word "c" 1.1 1.6)]
           (transcribe/fitted [(word "a" 1 1.5) (word "b" 1.2 1.2) (word "c" 1.1 1.6)]))
        "neighbours that overlap it leave no room")
    (is (= [(word "a" 1 1.4) (word "b" 1.5 1.9)]
           (transcribe/fitted [(word "a" 1 1.4) (word "b" 1.5 1.9)]))
        "words with a length stay")
    (is (= [{:word "[Jingle]" :start 2 :end 2}]
           (transcribe/fitted [{:word "[Jingle]" :start 2 :end 2}]))
        "a sound is no word")))


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

(defn- fake-openrouter
  "Answers `/audio/transcriptions` with one sentence per request, its
   words in the first second, and a cost; 401 without the key. Every
   request is noted in `seen`."
  [seen]
  (http/createServer
   (fn [^js req ^js res]
     (let [chunks #js []]
       (.on req "data" (fn [c] (.push chunks c)))
       (.on req "end" (fn []
                        (let [body (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8")) :keywordize-keys true)
                              n (count (swap! seen conj {:url (.-url req)
                                                         :auth (aget (.-headers req) "authorization")
                                                         :model (:model body)
                                                         :format (get-in body [:input_audio :format])
                                                         :bytes (count (get-in body [:input_audio :data]))}))]
                          (.writeHead res (if (= "Bearer k3y" (aget (.-headers req) "authorization")) 200 401)
                                      #js {"Content-Type" "application/json"})
                          (.end res (js/JSON.stringify
                                     (clj->js {:text (str "Piece " n ".")
                                               :words [{:word "Piece" :start 0.1 :end 0.4 :confidence 0.98}
                                                       {:word (str n ".") :start 0.5 :end 0.9 :confidence 0.9}]
                                               :usage {:seconds 1 :cost 0.001}}))))))))))

(defn- set-var!
  [k v]
  (if (nil? v) (js-delete (.-env js/process) k) (aset (.-env js/process) k v)))

(defn- message-of
  [promise]
  (.catch promise (fn [e] (.-message e))))

(deftest transcribe-test
  (async done
    (let [seen (atom [])
          server (fake-openrouter seen)
          env (.-env js/process)
          before {:url (aget env "OPENROUTER_BASE_URL") :key (aget env "OPENROUTER_API_KEY")}
          work (fs/mkdtempSync (path/join (os/tmpdir) "rragent-transcribe-"))
          dir (path/join work "artifacts")]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (aset env "OPENROUTER_BASE_URL" (str "http://127.0.0.1:" (.-port (.address server)) "/v1/"))
             (aset env "OPENROUTER_API_KEY" "k3y")
             (fs/mkdirSync (path/join work "uploads") #js {:recursive true})
             (fs/writeFileSync (path/join work "uploads" "ab12.wav") (wav 2.5))
             (let [codemode (codemode/handle none)
                   ffmpeg? (ffmpeg?)]
               (testing "a file under uploads/ is written down into artifacts/transcripts/"
                 ;; one piece at a time: the fake numbers its answers by the requests' order
                 (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :piece-seconds 1 :parallel 1 :timestamps? true}))
                       file (path/join dir "transcripts" "ab12.md")]
                   (is (str/starts-with? answer "artifacts/transcripts/ab12.md: 0:0"))
                   (is (str/includes? answer "model elevenlabs/scribe-v2"))
                   (is (= "elevenlabs/scribe-v2" (:model (first @seen))) "the tool's model, not the dictation's")
                   (is (fs/existsSync file))
                   (is (str/starts-with? (fs/readFileSync file "utf8") "# ab12.wav\n\nTranscript of uploads/ab12.wav, 0:0"))
                   (is (= "Bearer k3y" (:auth (first @seen))))
                   (if ffmpeg?
                     (testing ", in pieces of a second, each an MP3, the later ones' words shifted"
                       (is (= 3 (count @seen)))
                       (is (= ["mp3" "mp3" "mp3"] (map :format @seen)))
                       (is (str/includes? answer "[0:00] Piece 1. Piece 2. Piece 3.")))
                     (testing ", whole without ffmpeg"
                       (is (= 1 (count @seen)))
                       (is (= ["wav"] (map :format @seen)))
                       (is (str/includes? answer "[0:00] Piece 1."))))))
               (when ffmpeg?
                 (testing "the pieces end in a pause, their starts and ends where the sound was cut"
                   ;; a tone, a pause from 1.3 s to 1.7 s, a tone to 3 s; pieces of 1.6 s at most
                   (fs/writeFileSync (path/join work "uploads" "tone.wav") (wav 3 #(not (< 1.3 % 1.7))))
                   (let [temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-pieces-"))]
                     (try
                       (let [pieces (await (transcribe/pieces! (path/join work "uploads" "tone.wav") temp 1.6))]
                         (is (= 2 (count pieces)))
                         (is (= 0 (:start (first pieces))))
                         (is (< 1.45 (:end (first pieces)) 1.55) "the middle of the pause, not 1.6 s")
                         (is (= (:end (first pieces)) (:start (second pieces))))
                         (is (= 3 (:end (second pieces))) "the file's length is what was read")
                         (is (every? #(fs/existsSync (:file %)) pieces)))
                       (finally
                         (fs/rmSync temp #js {:recursive true :force true})))))
                 (testing "a recording whose head says no length, as a browser's recorder writes it, is cut all the same"
                   (let [made (child/spawnSync "ffmpeg" #js ["-v" "error" "-i" (path/join work "uploads" "tone.wav") "-c:a" "libopus" "-f" "webm" "pipe:1"]
                                               #js {:maxBuffer (* 16 1024 1024)})
                         temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-pieces-"))]
                     (try
                       (when (and (zero? (.-status made)) (pos? (.-length (.-stdout made))))
                         (fs/writeFileSync (path/join work "uploads" "stream.webm") (.-stdout made))
                         (let [pieces (await (transcribe/pieces! (path/join work "uploads" "stream.webm") temp 1.6))]
                           (is (= 2 (count pieces)))
                           (is (< 1.4 (:end (first pieces)) 1.6))
                           (is (< 2.9 (:end (second pieces)) 3.1))))
                       (finally
                         (fs/rmSync temp #js {:recursive true :force true}))))))
               (testing "where `out` says, without the times"
                 (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/words.md"}))]
                   (is (str/starts-with? answer "artifacts/talk/words.md: "))
                   (is (not (str/includes? answer "[0:00]")))
                   (is (fs/existsSync (path/join dir "talk" "words.md")))))
               (testing "corrected through the tool's api, the words with their times beside the transcript"
                 (let [api #js {:conversationId "c1"
                                :conversation (fn [_ _] (js/Promise.resolve nil))}
                       answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/fixed.md"
                                                              :words? true :api api :names "Peace" :messages (fn [_ _] (js/Promise.reject (js/Error. "no bot")))
                                                              :complete (fn [_] (js/Promise.resolve {:text "{\"fixes\": [{\"heard\": \"Piece\", \"meant\": \"Peace\"}]}" :cost 0}))}))
                       saved (js->clj (js/JSON.parse (fs/readFileSync (path/join dir "talk" "fixed.words.json") "utf8")) :keywordize-keys true)]
                   (is (str/includes? answer "the words with their times in artifacts/talk/fixed.words.json"))
                   (is (re-find #"Corrected: Piece → Peace( \(\d×\))?\." answer))
                   (is (re-find #"\n\nPeace \d+\." answer))
                   (is (= "uploads/ab12.wav" (:file saved)))
                   (is (= {:word "Peace" :start 0.1 :end 0.4 :confidence 0.98 :heard "Piece"} (first (:words saved))))))
               (testing "a file that is not there, a path above the working directory, an out above artifacts/"
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/nope.mp3"}))) "no file uploads/nope.mp3"))
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "../etc/passwd"}))) "path under the working directory"))
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "../x.md"}))) "path under artifacts/")))
               (testing "a bad key: the service's answer"
                 (aset env "OPENROUTER_API_KEY" "wrong")
                 (is (str/includes? (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav"}))) "answered 401")))
               (testing "neither a gateway nor a key"
                 (js-delete env "OPENROUTER_API_KEY")
                 (is (= "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no transcript."
                        (await (message-of (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav"})))))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (set-var! "OPENROUTER_BASE_URL" (:url before))
                      (set-var! "OPENROUTER_API_KEY" (:key before))
                      (fs/rmSync work #js {:recursive true :force true})
                      (.close server)
                      (sync-fetch/stop!)
                      (done)))))))
