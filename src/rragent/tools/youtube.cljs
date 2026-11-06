(ns rragent.tools.youtube
  "The `youtube` tool: a YouTube video, its audio or its subtitles,
   fetched by yt-dlp (`rragent.youtube`) into the bot's `work/`: the
   video as an mp4 of h264 and aac, so that ffmpeg and the templates
   take it as it is, up to `height` lines (1080 by default), or a
   `section` of it alone; the audio as an m4a; the subtitles as a
   transcript the way the `transcribe` tool writes one
   (`rragent.tools.transcribe/paragraphs`), in paragraphs cut at the
   pauses, each with its time when asked, without a download and
   without the speech model's cents. The file goes to
   `artifacts/downloads/<id>.<ext>`, the transcript to
   `artifacts/transcripts/<id>.md`, or where `out` says; the answer
   names the file, the title, the channel and the length, and carries
   the transcript. The URL of one video (`watch?v=`, `youtu.be/`,
   `shorts/`, `live/`), never a playlist or a channel (`video-id`).

   The bytes leave through the machine's proxy, a mobile modem paid
   by the month, so they cost nothing but time; still the description
   has the bot take the section it needs, or the subtitles or the
   audio when the picture is not needed. The first call on a machine
   installs the programs, a minute or two (`rragent.youtube/ready!`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.tools.transcribe :as transcribe]
            [rragent.tools.work :as work]
            [rragent.youtube :as youtube]))

(def hosts
  "Where a video's URL points, `www.`, `m.` and `music.` aside."
  #{"youtube.com" "youtu.be" "youtube-nocookie.com"})

(defn video-id
  "The id of the one video `url` names, a throw when it names none."
  [url]
  (let [parsed (try (js/URL. (str url)) (catch :default _ nil))
        host (some-> parsed .-hostname str/lower-case (str/replace #"^(www|m|music)\." ""))
        pathname (some-> parsed .-pathname)
        id? #(when (re-matches #"[A-Za-z0-9_-]{11}" (str %)) %)
        id (cond
             (not (contains? hosts host)) nil
             (= "youtu.be" host) (id? (second (re-find #"^/([^/?#]+)" pathname)))
             (= "/watch" pathname) (id? (.get (.-searchParams parsed) "v"))
             :else (id? (second (re-find #"^/(?:shorts|live|embed|v)/([^/?#]+)" pathname))))]
    (or id
        (throw (js/Error. (str "youtube takes the URL of one video, youtube.com/watch?v=… or youtu.be/…, not " url "."))))))

(defn- check-section
  [section]
  (when-not (re-matches #"\d{1,2}(:\d{2}){1,2}-\d{1,2}(:\d{2}){1,2}" (str section))
    (throw (js/Error. "`section` is start-end as m:ss or h:mm:ss, like 10:00-12:30."))))

(defn- check-out
  [out]
  (when (or (str/blank? out) (str/includes? out "..") (str/starts-with? out "/") (str/includes? out "%"))
    (throw (js/Error. "`out` is a path under artifacts/, like downloads/talk.mp4 or transcripts/talk.md."))))

(defn- megabytes
  [bytes]
  (let [mb (/ bytes 1048576)]
    (str (.toFixed mb (if (< mb 10) 1 0)) " MB")))

(defn- line
  "The first line of an answer: the file, the title, the channel and the length."
  [file {:keys [title channel duration]}]
  (str file ": " (or (not-empty (str title)) "untitled")
       (when (seq (str channel)) (str ", " channel))
       (when (number? duration) (str ", " (transcribe/clock duration)))))

(defn ^:async media!
  "The video or the audio of `url` fetched into `dir` (`artifacts/`),
   `downloads/<id>.<ext>` or `out`; answers the line for the model."
  [{:keys [work dir url what height section out download]}]
  (when (seq section) (check-section section))
  (when (seq out) (check-out out))
  (let [template (if (seq out)
                   (path/join dir out)
                   (path/join dir "downloads" "%(id)s.%(ext)s"))
        _ (fs/mkdirSync (path/dirname template) #js {:recursive true})
        {:keys [fields]} (await (download {:url url :what what :height height :section section :out template}))
        file (:filepath fields)]
    (when-not (and (string? file) (fs/existsSync file))
      (throw (js/Error. (str "yt-dlp wrote no file: the video may be larger than " youtube/max-filesize ", or YouTube offered no format that fits."))))
    (str (line (path/relative work file) fields)
         (when (seq section) (str " (the section " section ")"))
         (when (and (= "video" what) (number? (:height fields))) (str ", " (:height fields) "p"))
         ", " (megabytes (.-size (fs/statSync file))) ".")))

(defn caption-language
  "The language of the subtitle file `file` of the video `id`,
   `<id>.<language>.<ext>`."
  [id file]
  (str/replace (subs file (inc (count id))) #"\.(json3|vtt)$" ""))

(defn caption-file
  "Which of the subtitle `files` of the video `id` to read: the one of
   `language` when one was asked; else the written one in the video's
   `own` language, else its automatic captions (`<lang>-orig`)."
  [id files language own]
  (let [files (sort files)
        by-language (group-by #(caption-language id %) files)
        pick (fn [l] (when (seq l) (first (get by-language l))))]
    (if (seq language)
      (pick language)
      (or (pick own)
          (first (filter #(str/ends-with? (caption-language id %) "-orig") files))))))

(defn- caption-files
  "The subtitle files of the video `id` in `temp`."
  [temp id]
  (filter #(and (str/starts-with? % (str id ".")) (re-find #"\.(json3|vtt)$" %))
          (array-seq (fs/readdirSync temp))))

(defn ^:async subtitles!
  "The subtitles of `url` written as a transcript into `dir`
   (`artifacts/`), `transcripts/<id>.md` or `out`, with the times when
   `timestamps?`; answers the line for the model with the text. A
   `language` asked for is fetched in one go, written or automatic.
   Without one, the automatic captions in the video's own language
   come first (`.*-orig`), which also say which language that is when
   YouTube does not; then the written subtitles in it, which are the
   better ones and win when there are some."
  [{:keys [dir url id language out timestamps? download]}]
  (let [out (or (not-empty out) (str "transcripts/" id ".md"))
        _ (check-out out)
        target (path/join dir out)
        temp (fs/mkdtempSync (path/join (os/tmpdir) "rragent-youtube-"))
        fetch (fn [languages written? automatic?]
                (download {:url url :what "subtitles" :languages languages :written? written? :automatic? automatic?
                           :out (path/join temp "%(id)s.%(ext)s")}))]
    (try
      (let [{:keys [fields]} (await (if (seq language) (fetch language true true) (fetch ".*-orig" false true)))
            own (when-not (seq language)
                  (or (not-empty (str (:language fields)))
                      (some->> (caption-files temp id)
                               (map #(caption-language id %))
                               (filter #(str/ends-with? % "-orig"))
                               first
                               (#(str/replace % #"-orig$" "")))))
            _ (when (seq own) (await (fetch own true false)))
            file (caption-file id (caption-files temp id) language own)]
        (when-not file
          (throw (js/Error. (if (seq language)
                              (str "The video has no subtitles in " language ": the transcribe tool writes them down from its audio (what: audio, then transcribe).")
                              "The video has no automatic captions and does not say its language: ask with `language`, de or en, or take the audio for transcribe."))))
        (let [lang (caption-language id file)
              words (youtube/captions (path/join temp file))
              paragraphs (transcribe/paragraphs words (str/join " " (map :word words)) 0)
              text (transcribe/transcript-text paragraphs (boolean timestamps?))
              kind (if (str/ends-with? lang "-orig")
                     (str "automatic, " (subs lang 0 (- (count lang) (count "-orig"))))
                     lang)
              heading (str "# " (or (not-empty (str (:title fields))) id) "\n\n"
                           "Subtitles of " url
                           (when (number? (:duration fields)) (str ", " (transcribe/clock (:duration fields))))
                           " (" kind ").\n\n")]
          (when (str/blank? text)
            (throw (js/Error. "The subtitles are empty.")))
          (fs/mkdirSync (path/dirname target) #js {:recursive true})
          (fs/writeFileSync target (str heading text "\n"))
          (str (line (str "artifacts/" out) fields) ", " (count (str/split (str/join " " (map :word words)) #"\s+")) " words, subtitles " kind ".\n\n"
               (if (< transcribe/answer-chars (count text))
                 (str (subs text 0 transcribe/answer-chars) "\n\n… (the rest is in the file)")
                 text))))
      (finally
        (fs/rmSync temp #js {:recursive true :force true})))))

(defn ^:async fetch!
  "What the tool does for its arguments: `media!` or `subtitles!`,
   with `download` (`rragent.youtube/download!` unless a test gives
   one)."
  [{:keys [url what download] :as options}]
  (let [id (video-id url)
        what (or (not-empty what) "video")]
    (when-not (youtube/kinds what)
      (throw (js/Error. "`what` is video, audio or subtitles.")))
    (let [options (assoc options :id id :what what :download (or download youtube/download!))]
      (if (= "subtitles" what)
        (await (subtitles! options))
        (await (media! options))))))

(def tool
  (defineTool
   #js {:name "youtube"
        :description (str "Fetches a YouTube video the person has the rights to use: `what` video, an mp4 (h264 and aac, up to `height`, 1080 by default) into artifacts/downloads/<id>.mp4; audio, an m4a into artifacts/downloads/<id>.m4a; "
                          "or subtitles, written at once and for free as a transcript into artifacts/transcripts/<id>.md, the way transcribe writes one, and answered as text. "
                          "Take what you need: `section` (10:00-12:30) fetches that part alone, the subtitles say what is spoken without a download, the audio is enough for transcribe. "
                          "The URL of one video, not a playlist or a channel. A video takes a minute or more, up to 2 GB; the first call on a machine installs the programs. "
                          "The answer names the file, the title, the channel and the length.")
        :parameters (.Object Type #js {:url (.String Type #js {:description "The video's URL, youtube.com/watch?v=… or youtu.be/…"})
                                       :what (.Optional Type (.String Type #js {:description "video (default), audio or subtitles"}))
                                       :section (.Optional Type (.String Type #js {:description "A part alone, start-end as m:ss or h:mm:ss: 10:00-12:30"}))
                                       :height (.Optional Type (.Number Type #js {:description "The most lines of the video: 1080 by default, 720 for a draft"}))
                                       :language (.Optional Type (.String Type #js {:description "subtitles: their language, de or en; the video's own automatic ones by default"}))
                                       :out (.Optional Type (.String Type #js {:description "Where the file goes under artifacts/: downloads/<id>.<ext> or transcripts/<id>.md by default"}))
                                       :timestamps (.Optional Type (.Boolean Type #js {:description "subtitles: every paragraph starts with its time, m:ss"}))})
        :execute (^:async fn [args api _call-context]
                   (work/text (await (fetch! {:work (work/dir api)
                                              :dir (work/artifacts-dir api)
                                              :url (.-url args)
                                              :what (.-what args)
                                              :section (.-section args)
                                              :height (.-height args)
                                              :language (.-language args)
                                              :out (.-out args)
                                              :timestamps? (true? (.-timestamps args))}))))}))
