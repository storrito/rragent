(ns rragent.file-kinds
  "The files a message can carry besides pictures and clips, by
   extension: `texts`, what the agent reads as it is (notes, data,
   code, subtitles), and `documents`, what it opens with the machine's
   commands (a PDF, an Office file, an archive, a font;
   `rragent.binary/hint` tells it how). The server stores and names
   them by these lists (`rragent.uploads`), the composer's picker
   offers them (`accept`, `rragent.client`), and the chips and the
   transcript show each kind by its `icon`. Pictures and clips are the
   media types of `rragent.uploads/images` and `media`: the browser
   knows those by type, these by name."
  (:require [clojure.string :as str]))

(def texts
  "The extensions of the files the agent reads as they are."
  #{;; notes and prose
    "txt" "md" "markdown" "rst" "adoc" "org" "tex" "bib"
    ;; data and configuration
    "csv" "tsv" "json" "jsonl" "ndjson" "edn" "xml" "yaml" "yml" "toml"
    "ini" "cfg" "conf" "properties" "log" "svg"
    ;; code and pages
    "html" "htm" "css" "js" "mjs" "cjs" "ts" "tsx" "jsx"
    "clj" "cljs" "cljc" "bb" "sh" "bash" "zsh" "py" "rb" "go" "rs"
    "java" "kt" "swift" "c" "h" "cpp" "hpp" "cs" "php" "sql" "r" "ipynb"
    ;; subtitles, calendars, contacts
    "srt" "vtt" "ics" "vcf"})

(def documents
  "The extensions of the files the agent opens with the machine's
   commands: documents, archives, fonts."
  #{"pdf" "doc" "docx" "xls" "xlsx" "ppt" "pptx" "odt" "ods" "odp" "rtf" "epub"
    "zip" "tar" "gz" "tgz" "7z"
    "ttf" "otf" "woff" "woff2"})

(def types
  "The extension of a file by the type the browser sends, for a file
   that comes without its name."
  {"application/pdf" "pdf"
   "text/csv" "csv"
   "text/tab-separated-values" "tsv"
   "application/json" "json"
   "text/markdown" "md"
   "text/plain" "txt"
   "text/html" "html"
   "application/xml" "xml"
   "text/xml" "xml"
   "application/zip" "zip"
   "application/gzip" "gz"
   "application/x-tar" "tar"
   "application/msword" "doc"
   "application/vnd.openxmlformats-officedocument.wordprocessingml.document" "docx"
   "application/vnd.ms-excel" "xls"
   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" "xlsx"
   "application/vnd.ms-powerpoint" "ppt"
   "application/vnd.openxmlformats-officedocument.presentationml.presentation" "pptx"
   "application/vnd.oasis.opendocument.text" "odt"
   "application/vnd.oasis.opendocument.spreadsheet" "ods"
   "application/vnd.oasis.opendocument.presentation" "odp"
   "application/rtf" "rtf"
   "application/epub+zip" "epub"
   "font/ttf" "ttf"
   "font/otf" "otf"
   "font/woff" "woff"
   "font/woff2" "woff2"})

(defn extension-of
  "The extension of a file name, lower case; \"\" without one."
  [file-name]
  (str/lower-case (or (second (re-find #"\.([^./]+)$" (str file-name))) "")))

(defn kind
  "`:text` or `:file` for the extension of a file name, nil for one the
   agent is not handed."
  [file-name]
  (let [extension (extension-of file-name)]
    (cond
      (texts extension) :text
      (documents extension) :file)))

(defn extension
  "The extension a file is stored with: its name's when that is one of
   the lists, else the one of the type the browser sent (`types`); nil
   when neither says."
  [file-name type]
  (let [named (extension-of file-name)]
    (if (or (texts named) (documents named))
      named
      (types (str type)))))

(def accept
  "The extensions for the picker of the composer, `.pdf,.csv,…`."
  (str/join "," (map #(str "." %) (sort (into texts documents)))))

(defn icon
  "The icon of an attached file's kind, for its chip and the transcript."
  [kind]
  (case kind
    :image "🖼"
    :video "🎬"
    :audio "🎵"
    :text "📄"
    "📎"))

(comment
  (kind "Q3 report.pdf")
  (extension "notes" "text/plain")
  accept
  )
