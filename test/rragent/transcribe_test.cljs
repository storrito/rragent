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
  (testing "the text with the times, m:ss and h:mm:ss"
    (is (= "[0:00] Hallo\n\n[1:05:03] Welt"
           (transcribe/transcript-text [{:start 0 :text "Hallo"} {:start 3903.4 :text "Welt"}] true)))
    (is (= "Hallo\n\nWelt"
           (transcribe/transcript-text [{:start 0 :text "Hallo"} {:start 3903.4 :text "Welt"}] false))))
  (testing "the words of a later piece shifted"
    (is (= [{:word "a" :start 600.5 :end 601.0 :confidence 0.9}]
           (transcribe/shifted [(word "a" 0.5 1.0)] 600)))))

(defn- wav
  "A WAV of `seconds` of silence, mono, 16 kHz, 16 bit."
  [seconds]
  (let [samples (* 16000 seconds)
        data (* 2 samples)
        header (js/Buffer.alloc 44)]
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
    (js/Buffer.concat #js [header (js/Buffer.alloc data)])))

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
                 (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :piece-seconds 1 :timestamps? true}))
                       file (path/join dir "transcripts" "ab12.md")]
                   (is (str/starts-with? answer "artifacts/transcripts/ab12.md: 0:0"))
                   (is (str/includes? answer "model assemblyai/universal-3-6-pro"))
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
               (testing "where `out` says, without the times"
                 (let [answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/words.md"}))]
                   (is (str/starts-with? answer "artifacts/talk/words.md: "))
                   (is (not (str/includes? answer "[0:00]")))
                   (is (fs/existsSync (path/join dir "talk" "words.md")))))
               (testing "corrected through the tool's api, the words with their times beside the transcript"
                 (let [api #js {:conversationId "c1"
                                :conversation (fn [_ _] (js/Promise.resolve nil))}
                       answer (await (transcribe/transcribe! {:codemode codemode :work work :dir dir :file "uploads/ab12.wav" :out "talk/fixed.md"
                                                              :words? true :api api :names "Peace"
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
