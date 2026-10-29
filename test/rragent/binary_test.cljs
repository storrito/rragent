(ns rragent.binary-test
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.binary :as binary]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- noise
  "`n` bytes as a compressed stream has them, the same every run."
  [n]
  (js/Buffer.from (clj->js (map #(mod (+ 128 (* 37 %) (quot % 7)) 256) (range n)))))

(defn- file-bytes
  "A file's bytes: `head` (a string of latin-1 bytes) and noise after."
  [head n]
  (js/Buffer.concat #js [(js/Buffer.from head "latin1") (noise n)]))

(defn- decoded
  "The bytes as pi-durable's read gives them to the model."
  [bytes]
  (.decode (js/TextDecoder.) bytes))

(def pdf (file-bytes "%PDF-1.7\n%âãÏÓ\n" 6000))
(def mp4 (file-bytes "\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0002\u0000" 6000))
(def docx (file-bytes "PK\u0003\u0004\u0014\u0000\u0006\u0000" 6000))
(def mp3 (file-bytes "ID3\u0004\u0000\u0000\u0000\u0000\u0000#" 6000))

(def none
  "No command on the machine."
  (constantly false))

(def all
  "Every command on the machine."
  (constantly true))

(deftest binary-test
  (testing "a binary file decoded as text"
    (doseq [bytes [pdf mp4 docx mp3]]
      (is (binary/binary? (decoded bytes)))))
  (testing "text, in any language, with tabs and colors, is text"
    (is (not (binary/binary? (str/join "\n" (repeat 200 "Grüße aus Köln – ein Satz mit Emoji 🎬 und\tTab.")))))
    (is (not (binary/binary? (str/join "\n" (repeat 100 "\u001b[32mok\u001b[0m 12 files")))) "a command's colored output, a few escapes")
    (is (not (binary/binary? (decoded (js/Buffer.from (str/join " " (repeat 300 "Grüße aus München")) "latin1"))))
        "Latin-1 with umlauts: a few replacement characters, still text")
    (is (not (binary/binary? "a\u0000b")) "too short to say")))

(deftest what-test
  (is (= [:pdf "a PDF"] (binary/what (decoded pdf) "flyer.pdf")))
  (is (= [:video "an MP4 video"] (binary/what (decoded mp4) "clip.mp4")))
  (is (= [:office "a Word document (.docx)"] (binary/what (decoded docx) "brief.docx")) "a ZIP named as a Word document")
  (is (= [:zip "a ZIP archive"] (binary/what (decoded docx) "pack.zip")))
  (is (= [:audio "an MP3 audio file"] (binary/what (decoded mp3) nil)))
  (is (= [:pdf "a PDF"] (binary/what (str "200 OK\ncontent-type: application/pdf\n\n" (decoded pdf)) "https://x.test/a.pdf"))
      "fetch puts the status and the headers first")
  (is (= [:binary "binary data"] (binary/what (decoded (noise 3000)) "x.bin")))
  (is (binary/binary? (decoded (noise 3000))) "no signature, its control codes")
  (is (binary/binary? (str "%PDF-1.7\n" (str/join "\n" (repeat 100 "1 0 obj << /Type /Page >> endobj"))))
      "a PDF whose start is its text objects, by its signature"))

(deftest note-test
  (let [text (decoded pdf)]
    (is (= (str "artifacts/flyer.pdf is a PDF, not text: its " (count text) " characters as text are left out. "
                "This machine has no PDF tool (pdftotext of poppler-utils is not installed); say so to the person.")
           (binary/note text "artifacts/flyer.pdf" none))
        "without the tools, what is missing")
    (is (str/includes? (binary/note text "artifacts/flyer.pdf" all) "Its text: pdftotext artifacts/flyer.pdf -; its pages as pictures: pdftoppm")
        "with them, the commands")
    (is (str/includes? (binary/note text "https://x.test/files/flyer.pdf?x=1" all)
                       "Download it first: curl -sL -o flyer.pdf 'https://x.test/files/flyer.pdf?x=1'. Its text: pdftotext flyer.pdf -")
        "an address downloaded first")
    (is (str/starts-with? (binary/note text nil all) "This is a PDF") "a command's output")
    (is (str/includes? (binary/note (decoded mp4) "clip.mp4" all) "ffprobe -hide_banner clip.mp4; a picture of it: ffmpeg -ss 1 -i clip.mp4"))))

(deftest clean-result-test
  (let [call #js {:name "read" :arguments #js {:path "flyer.pdf"}}
        text #js {:type "text" :text "fine"}]
    (is (nil? (binary/clean-result call #js {:output #js [text]} all)) "text stays, no new result")
    (is (nil? (binary/clean-result call #js {} all)) "no output")
    (let [result #js {:output #js [#js {:type "text" :text (decoded pdf)}] :details #js {:x 1}}
          cleaned (binary/clean-result call result none)]
      (is (str/starts-with? (.-text (aget (.-output cleaned) 0)) "flyer.pdf is a PDF, not text"))
      (is (= 1 (.. cleaned -details -x)) "the rest of the result stays"))))

(deftest read-pdf-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 sent (atom nil)]
             (.setResponses faux #js [(tools-test/tool-call "read" {:path "flyer.pdf"} "r1")
                                      (fn [context _options _state _model]
                                        (reset! sent (js/JSON.stringify (.-messages context)))
                                        (tools-test/answer "A PDF."))])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (fs/writeFileSync (path/join (home/work-dir (home/bot-dir home 1)) "flyer.pdf") pdf)
                 (is (= "A PDF." (await (harness/ask agent "What is in flyer.pdf?"))))
                 (is (str/includes? @sent "flyer.pdf is a PDF, not text") "the model gets the line")
                 (is (not (str/includes? @sent "%PDF-1.7")) "and not the file")
                 (is (not (str/includes? @sent "\ufffd")) "nor its replacement characters")
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
