(ns rragent.binary
  "A tool's text that is a binary file decoded as text, left out. The
   `read` tool takes every file that is no picture for text, UTF-8
   decoded without failing, so a PDF, a video or a .docx comes back as
   up to 50 KB of replacement characters and control codes; `cat` in
   bash or a `fetch` of a PDF does the same. The model can do nothing
   with it and pays some 15,000 tokens for it, in every request until
   the result is masked (`rragent.observations`). After every tool call
   (pi-durable's `afterTool`, before the result is committed) a text
   that is mostly such characters (`binary?`) is replaced by a line
   that says what the file is, by its first bytes or its name, and how
   to get at it with the commands this machine has (`hint`)."
  (:require ["@earendil-works/pi-durable" :refer [ToolTask defineExtension hook]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def sample-chars
  "How much of a text `binary?` counts, from its start."
  16384)

(def control-share
  "The share of control codes from which a text is binary: text has
   none but tabs, line and page breaks and the escapes of colors, a
   video's first bytes are half zeros, a PDF's first thousands one in a
   hundred."
  0.01)

(def replacement-share
  "The share of replacement characters from which a text is binary:
   text in another encoding than UTF-8 has some (Latin-1 with umlauts,
   one in six at most), a ZIP or a compressed PDF a third to a half."
  0.3)

(defn- control?
  "Whether the char code `c` is a control code no text has: not a tab,
   a line or a page break, a carriage return or an escape."
  [c]
  (or (and (< c 32) (not (#{9 10 12 13 27} c)))
      (= c 0x7F)))

(def signatures
  "What a binary file is by the characters UTF-8 decoding leaves of its
   first bytes (those past ASCII as replacement characters): a regex
   on its start, the kind and its name."
  [[#"^%PDF-" :pdf "a PDF"]
   [#"^[\s\S]{0,8}ftypqt  " :video "a QuickTime video"]
   [#"^[\s\S]{0,8}ftypM4[AB] " :audio "an M4A audio file"]
   [#"^[\s\S]{0,8}ftyp" :video "an MP4 video"]
   [#"^\u001aE\ufffd\ufffd" :video "a WebM or Matroska video"]
   [#"^RIFF[\s\S]{4}WAVE" :audio "a WAV audio file"]
   [#"^RIFF[\s\S]{4}AVI " :video "an AVI video"]
   [#"^OggS" :audio "an Ogg audio file"]
   [#"^fLaC" :audio "a FLAC audio file"]
   [#"^ID3" :audio "an MP3 audio file"]
   [#"^PK\u0003\u0004" :zip "a ZIP archive"]
   [#"^SQLite format 3\u0000" :sqlite "an SQLite database"]
   [#"^\u001f\ufffd\u0008" :gzip "a gzip archive"]])

(defn- starts
  "Where a file's bytes may begin in `text`: at its start, and after
   the first blank line in its first 2,048 characters, as fetch puts the
   status and the headers before the body."
  [text]
  (let [blank (.indexOf text "\n\n")]
    (cond-> [text]
      (< -1 blank 2048) (conj (subs text (+ blank 2))))))

(defn signature
  "`[kind name]` of the file whose bytes `text` begins with
   (`signatures`), nil for none."
  [text]
  (some (fn [start]
          (some (fn [[re kind label]] (when (re-find re start) [kind label])) signatures))
        (starts text)))

(defn binary?
  "Whether `text` is a binary file decoded as text: it begins with one
   (`signature`), or its first `sample-chars` hold more than
   `control-share` control codes or more than `replacement-share`
   replacement characters; at least 16 characters, so that a short
   line with a control code is text."
  [text]
  (let [sample (subs text 0 (min (count text) sample-chars))
        n (count sample)]
    (and (<= 16 n)
         (or (some? (signature text))
             (let [codes (map #(.charCodeAt sample %) (range n))]
               (or (< control-share (/ (count (filter control? codes)) n))
                   (< replacement-share (/ (count (filter #(= 0xFFFD %) codes)) n))))))))

(def office
  "Office files are ZIP archives; their name tells which."
  {"docx" "a Word document (.docx)"
   "xlsx" "an Excel workbook (.xlsx)"
   "pptx" "a PowerPoint deck (.pptx)"
   "odt" "an OpenDocument text (.odt)"})

(defn what
  "`[kind name]` of the binary `text`, read from `file` when there was
   one: by its first characters (`signature`), an office file by the
   extension of `file`; `[:binary \"binary data\"]` when unknown."
  [text file]
  (let [extension (let [e (path/extname (str file))]
                    (when (seq e) (str/lower-case (subs e 1))))
        [kind label] (signature text)]
    (cond
      (and (= :zip kind) (office extension)) [:office (office extension)]
      kind [kind label]
      :else [:binary "binary data"])))

(defn on-path?
  "Whether the command `command` is on the PATH of this process."
  [command]
  (boolean
   (some (fn [dir]
           (try
             (fs/accessSync (path/join dir command) (.-X_OK fs/constants))
             true
             (catch :default _ false)))
         (remove str/blank? (str/split (str (aget (.-env js/process) "PATH")) #":")))))

(defn- download
  "The file name a download of `url` gets: the last part of its path,
   else `download`."
  [url]
  (let [pathname (or (some-> (try (js/URL. url) (catch :default _ nil)) .-pathname) "")
        last-part (last (str/split pathname #"/"))]
    (if (re-matches #"[A-Za-z0-9._-]+" (str last-part)) last-part "download")))

(defn hint
  "How to get at a file of `kind` at `file` with the commands `has?`
   says this machine has; without them, which are missing. An address
   is downloaded first; a command's output, `file` nil, written to a
   file."
  [kind file has?]
  (cond
    (nil? file)
    "Write it to a file instead and look at it there."

    (re-find #"^https?://" file)
    (let [local (download file)]
      (str "Download it first: curl -sL -o " local " '" file "'. " (hint kind local has?)))

    :else
    (case kind
      :pdf (if (has? "pdftotext")
             (str "Its text: pdftotext " file " -"
                  (when (has? "pdftoppm") (str "; its pages as pictures: pdftoppm -png -r 100 " file " page"))
                  ".")
             "This machine has no PDF tool (pdftotext of poppler-utils is not installed); say so to the person.")
      (:video :audio) (if (has? "ffprobe")
                        (str "What it holds: ffprobe -hide_banner " file
                             (when (= :video kind) (str "; a picture of it: ffmpeg -ss 1 -i " file " -frames:v 1 frame.png, then read frame.png"))
                             "; its words: the transcribe tool.")
                        "Its words: the transcribe tool.")
      :office (if (has? "unzip")
                (str "Its parts: unzip -l " file "; a part as text: unzip -p " file " <part>.")
                "It is a ZIP archive, and unzip is not installed.")
      :zip (if (has? "unzip")
             (str "Its files: unzip -l " file ".")
             "unzip is not installed.")
      (if (has? "file") (str "What it is: file " file ".") ""))))

(defn note
  "The line that stands for the binary `text` of a call that read
   `file` (nil when the call named none)."
  [text file has?]
  (let [[kind label] (what text file)]
    (str/trim
     (str (if file (str file " is " label) (str "This is " label))
          ", not text: its " (count text) " characters as text are left out. "
          (hint kind file has?)))))

(defn file-of
  "The file or address a tool call read: `path` of read, `url` of
   fetch; nil for others."
  [^js call]
  (let [args (.-arguments call)]
    (or (some-> args (aget "path") str)
        (some-> args (aget "url") str))))

(defn clean-result
  "The tool `result` with every binary text of its output as a `note`;
   nil when it had none."
  [^js call ^js result has?]
  (when-let [output (.-output result)]
    (let [blocks (vec (array-seq output))
          cleaned (mapv (fn [^js b]
                          (if (and (= "text" (.-type b)) (binary? (.-text b)))
                            #js {:type "text" :text (note (.-text b) (file-of call) has?)}
                            b))
                        blocks)]
      (when-not (every? true? (map identical? blocks cleaned))
        (js/Object.assign #js {} result #js {:output (into-array cleaned)})))))

(def extension
  "The extension that leaves binary text out of every tool's result
   (`clean-result`), with the commands of this machine's PATH."
  (defineExtension #js {:name "rragent-binary"
                        :hooks #js [(hook ToolTask #js {:afterTool (fn [call result _api _context]
                                                                      (or (clean-result call result on-path?)
                                                                          js/undefined))})]}))
