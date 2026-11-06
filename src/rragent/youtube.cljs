(ns rragent.youtube
  "YouTube on the machine: a video, its audio or its subtitles fetched
   by yt-dlp for the `youtube` tool (`rragent.tools.youtube`), with
   what yt-dlp needs against YouTube's bot checks, all of it installed
   by rragent itself. The folder is rragent's own,
   `~/.local/share/rragent/youtube` (under XDG_DATA_HOME), so that it
   outlives a release, which swaps `dist/` alone and never the image
   of a Sprite (`rragent.update`): the standalone `yt-dlp` of the
   latest release (a PyInstaller build, no Python needed, the
   challenge solver bundled), the bgutil PO token provider as a
   plugin of yt-dlp (`plugins/bgutil-ytdlp-pot-provider.zip`) and its
   server (`server/`, the release's source built with npm), which the
   plugin runs with Node once per token (the script option: no
   daemon, no port). The Node that runs rragent is the JavaScript
   runtime of yt-dlp (`--js-runtimes node:<execPath>`, which solves
   YouTube's challenges) and of the token script, so no Deno. Missing,
   the programs are installed at the first call, a minute or two;
   yt-dlp updates itself (`yt-dlp -U`) once a day, since YouTube
   changes every few weeks and the image of a Sprite never does.
   `RRAGENT_YOUTUBE_PROGRAMS` names a folder of someone else's with
   the same layout, in which nothing is installed or updated (a
   laptop's own, a test's fake yt-dlp).

   Every request leaves through `RRAGENT_YOUTUBE_PROXY` when it is
   set: the page, the token and the stream, since the stream's URLs
   are bound to the IP that asked for them. A Sprite's own address is
   a datacenter's, which YouTube answers with its bot check, so the
   provisioner writes the URL of a mobile proxy into the `.env` (a
   GlobalByte modem in the USA, see the README); a laptop needs none.
   The URL carries a password: `rragent.secrets` redacts it, and
   nothing this namespace says carries it either (`redact`).

   `arguments` is the command line, `printed` reads the fields the
   `--print` lines carry, `failure` turns yt-dlp's stderr into the
   sentence for the model, `captions` reads a subtitle file as words
   or cues with their times; `download!` runs it all."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:stream" :as stream]
            ["node:stream/promises" :as stream-promises]
            [clojure.string :as str]
            [rragent.programs :as programs]))

(def plugin-repo
  "The PO token provider on GitHub: maintained by a yt-dlp maintainer."
  "Brainicism/bgutil-ytdlp-pot-provider")

(def plugin-zip
  "The plugin among the release's assets, a package yt-dlp loads as it is."
  "bgutil-ytdlp-pot-provider.zip")

(def kinds
  "What the tool fetches."
  #{"video" "audio" "subtitles"})

(def fields
  "What yt-dlp prints once it is done, one JSON value a line, in this
   order (`channel,uploader`: the first that is there; `language`: the
   video's own, when YouTube says it)."
  ["id" "title" "channel,uploader" "duration" "height" "language" "filepath"])

(def timeout-ms
  "How long one download may take."
  (* 30 60 1000))

(def install-timeout-ms
  "How long one step of the install may take."
  (* 10 60 1000))

(def update-every-ms
  "How often yt-dlp looks for a newer release of itself."
  (* 24 60 60 1000))

(def max-filesize
  "The most a whole video may be, as `--max-filesize` takes it; a
   section is cut out of a video of any size."
  "2G")

(defn- env
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn proxy
  "The proxy every request leaves through, or nil."
  []
  (env "RRAGENT_YOUTUBE_PROXY"))

(defn own?
  "Whether the folder of the programs is rragent's own, where it
   installs and updates; `RRAGENT_YOUTUBE_PROGRAMS` names another."
  []
  (nil? (env "RRAGENT_YOUTUBE_PROGRAMS")))

(defn programs-dir
  "The folder of the programs."
  []
  (or (env "RRAGENT_YOUTUBE_PROGRAMS")
      (path/join (or (env "XDG_DATA_HOME") (path/join (os/homedir) ".local" "share")) "rragent" "youtube")))

(defn paths
  "The programs of the folder `dir`: `yt-dlp`, `plugins/` with the
   provider's zip, `server/` with its script, and the stamp of the
   last update."
  [dir]
  {:dir dir
   :yt-dlp (path/join dir "yt-dlp")
   :plugins (path/join dir "plugins")
   :server (path/join dir "server")
   :stamp (path/join dir "yt-dlp.updated")})

(defn- file?
  [file]
  (try (.isFile (fs/statSync file)) (catch :default _ false)))

(defn installed?
  "Whether the three programs are there."
  [{:keys [yt-dlp plugins server]}]
  (and (file? yt-dlp)
       (file? (path/join plugins plugin-zip))
       (file? (path/join server "build" "generate_once.js"))))

(defn- say
  [& parts]
  (js/console.error (str "[youtube] " (str/join " " parts))))

(defn- last-line
  [text]
  (str/trim (str (last (remove str/blank? (str/split-lines (str text)))))))

;;; Running programs

(defn- environment
  "The environment of a program: the machine's, with the folder of
   this Node first on the PATH (`rragent.programs`), so that the token
   script and ffmpeg's helpers find it."
  []
  (js/Object.assign #js {} (.-env js/process) (clj->js (programs/environment []))))

(defn exec!
  "A promise of `{:ok? :timeout? :stdout :stderr}` of `program` with
   `arguments`: `:ok?` when it ended with 0, `:timeout?` when `timeout`
   (`timeout-ms`) ran out; a program that did not start ends with its
   reason in `:stderr`."
  [program arguments {:keys [cwd timeout]}]
  (js/Promise.
   (fn [resolve]
     (child/execFile program (clj->js arguments)
                     #js {:cwd cwd :timeout (or timeout timeout-ms) :maxBuffer (* 16 1024 1024) :env (environment)}
                     (fn [error stdout stderr]
                       (resolve {:ok? (nil? error)
                                 :timeout? (boolean (some-> error .-killed))
                                 :stdout (str stdout)
                                 :stderr (if (and error (not (number? (.-code error))))
                                           (str stderr "\n" (.-message error))
                                           (str stderr))}))))))

(defn- ^:async check!
  "The result of `run` (a promise of `exec!`), a throw naming `step`
   with the last line of stderr when it failed."
  [run step]
  (let [{:keys [ok? timeout? stderr] :as result} (await run)]
    (cond
      timeout? (throw (js/Error. (str step " took too long.")))
      (not ok?) (throw (js/Error. (str step " failed: " (last-line stderr))))
      :else result)))

;;; Installing

(defn ^:async download-file!
  "`url` fetched into `file`, redirects followed; a throw with the
   status when the server refuses."
  [url file]
  (let [response (await (js/fetch url))]
    (when-not (.-ok response)
      (throw (js/Error. (str url " answered " (.-status response)))))
    (fs/mkdirSync (path/dirname file) #js {:recursive true})
    (await (stream-promises/pipeline (.fromWeb stream/Readable (.-body response)) (fs/createWriteStream file)))
    file))

(defn ^:async latest-tag
  "The tag of the latest release of the GitHub repository `repo`, read
   off the redirect of `releases/latest`: no API, no token, no rate
   limit of the API."
  [repo]
  (let [response (await (js/fetch (str "https://github.com/" repo "/releases/latest") #js {:redirect "manual"}))
        location (or (.get (.-headers response) "location") "")
        [_ tag] (re-find #"/releases/tag/([^/?#]+)$" location)]
    (or tag
        (throw (js/Error. (str "GitHub did not say the latest release of " repo " (" (.-status response) ")."))))))

(defn yt-dlp-asset
  "The standalone yt-dlp of this machine among the release's assets."
  []
  (let [platform (.-platform js/process)
        arch (.-arch js/process)]
    (case platform
      "linux" (if (= "arm64" arch) "yt-dlp_linux_aarch64" "yt-dlp_linux")
      "darwin" "yt-dlp_macos"
      (throw (js/Error. (str "yt-dlp has no build for " platform ": install one and name its folder in RRAGENT_YOUTUBE_PROGRAMS."))))))

(defn- npm
  "The npm beside this Node, or the PATH's."
  []
  (let [beside (path/join (path/dirname (.-execPath js/process)) "npm")]
    (if (file? beside) beside "npm")))

(defn ^:async install!
  "The programs installed into the folder of `paths`: yt-dlp downloaded
   whole and swapped in, the provider's plugin and the source of its
   server of the same release, the server unpacked with tar and built
   with npm and its own TypeScript; `versions.json` says what came
   when. A step that fails throws, and the next call starts over."
  [{:keys [dir yt-dlp plugins server]}]
  (say "installing yt-dlp and the PO token provider into" dir)
  (fs/mkdirSync plugins #js {:recursive true})
  (let [fresh (str yt-dlp ".new")]
    (await (download-file! (str "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" (yt-dlp-asset)) fresh))
    (fs/chmodSync fresh 0x1ed)
    (fs/renameSync fresh yt-dlp))
  (let [tag (await (latest-tag plugin-repo))
        tarball (path/join dir "server.tar.gz")
        unpacked (path/join dir (str "bgutil-ytdlp-pot-provider-" tag))]
    (await (download-file! (str "https://github.com/" plugin-repo "/releases/download/" tag "/" plugin-zip) (path/join plugins plugin-zip)))
    (await (download-file! (str "https://github.com/" plugin-repo "/archive/refs/tags/" tag ".tar.gz") tarball))
    (fs/rmSync server #js {:recursive true :force true})
    (fs/rmSync unpacked #js {:recursive true :force true})
    (await (check! (exec! "tar" ["-xzf" tarball "-C" dir (str "bgutil-ytdlp-pot-provider-" tag "/server")] {:timeout install-timeout-ms})
                   "Unpacking the PO token provider"))
    (fs/renameSync (path/join unpacked "server") server)
    (fs/rmSync unpacked #js {:recursive true :force true})
    (fs/rmSync tarball #js {:force true})
    (await (check! (exec! (npm) ["ci" "--no-audit" "--no-fund" "--loglevel=error"] {:cwd server :timeout install-timeout-ms})
                   "Installing the PO token provider's packages"))
    (await (check! (exec! (.-execPath js/process) [(path/join server "node_modules" "typescript" "bin" "tsc")] {:cwd server :timeout install-timeout-ms})
                   "Building the PO token provider"))
    (fs/writeFileSync (path/join dir "versions.json")
                      (js/JSON.stringify #js {:bgutil tag :at (.toISOString (js/Date.))} nil 1))
    (say "installed yt-dlp and bgutil" tag)))

(defn- stale?
  "Whether the stamp is older than `update-every-ms`, or missing."
  [stamp]
  (let [at (try (.getTime (.-mtime (fs/statSync stamp))) (catch :default _ 0))]
    (< update-every-ms (- (js/Date.now) at))))

(defn ^:async update!
  "yt-dlp brought to its latest release once a day (`yt-dlp -U`, which
   swaps the binary itself); the stamp is set before, so that a failure
   is said once on stderr and not tried again before the next day."
  [{:keys [yt-dlp stamp]}]
  (when (stale? stamp)
    (fs/writeFileSync stamp "")
    (let [{:keys [ok? stdout stderr]} (await (exec! yt-dlp ["-U"] {:timeout 120000}))]
      (if ok?
        (say (last-line stdout))
        (say "yt-dlp -U failed:" (last-line stderr))))))

(defonce ^:private installing
  (atom nil))

(defn ^:async ready!
  "The paths of the programs, there: installed when the folder is
   rragent's own and something is missing (one install at a time, a
   second call waits for the first), yt-dlp updated once a day; a
   folder of someone else's as it is, a throw when no yt-dlp is in it."
  []
  (let [p (paths (programs-dir))]
    (if (own?)
      (do (when-not (installed? p)
            (await (or @installing
                       (reset! installing (.finally (install! p) #(reset! installing nil))))))
          (await (update! p))
          p)
      (do (when-not (file? (:yt-dlp p))
            (throw (js/Error. (str "RRAGENT_YOUTUBE_PROGRAMS names " (:dir p) ", which holds no yt-dlp."))))
          p))))

;;; The command line

(defn arguments
  "The command line of yt-dlp for `request`: `:url`, `:what` (`kinds`),
   `:height` (the most lines of a video, 1080 by default), `:section`
   (`10:00-12:30`, cut at the exact times), `:languages` (subtitles:
   as `--sub-langs` takes them, `en` or `.*-orig`, the automatic
   captions in the video's own language) with `:written?` and
   `:automatic?` (which of the two kinds; a written one wins over an
   automatic one of the same language), `:out` (the output template),
   `:proxy`; with the programs of `paths` and the Node `node`. One
   video, never a playlist; a video as an mp4 of h264 and aac when
   YouTube has them at that size, else the best there is; the audio as
   an m4a; the subtitles as json3 files (vtt when YouTube has no json3
   of them), no download; and the `fields` printed at the end as JSON,
   a line each."
  [{:keys [plugins server]} node {:keys [url what height section languages written? automatic? out proxy]}]
  (let [stage (if (= "subtitles" what) "video" "after_move")]
    (-> ["--no-playlist" "--no-progress" "--no-simulate" "--color" "never"
         "--no-js-runtimes" "--js-runtimes" (str "node:" node)
         "--plugin-dirs" plugins
         "--extractor-args" (str "youtubepot-bgutilscript:server_home=" server)
         "--socket-timeout" "30" "--retries" "5"
         "-o" out]
        (into (when-not (seq section) ["--max-filesize" max-filesize]))
        (into (when proxy ["--proxy" proxy]))
        (into (case what
                "audio" ["-f" "ba[ext=m4a]/ba" "-x" "--audio-format" "m4a"]
                "subtitles" (-> ["--skip-download" "--sub-format" "json3/vtt" "--sub-langs" languages]
                                (into (when written? ["--write-subs"]))
                                (into (when automatic? ["--write-auto-subs"])))
                ["-S" (str "res:" (or height 1080) ",vcodec:h264,acodec:mp4a") "--merge-output-format" "mp4"]))
        (into (when (seq section) ["--download-sections" (str "*" section) "--force-keyframes-at-cuts"]))
        (into (mapcat (fn [field] ["--print" (str stage ":%(" field ")j")]) fields))
        (conj url))))

(defn printed
  "The `fields` of the lines of `stdout`, `{:id :title :channel
   :duration :height :language :filepath}` (nil for what YouTube did
   not say, which yt-dlp prints as NA), or nil when yt-dlp printed
   them not: it wrote no file."
  [stdout]
  (let [lines (remove str/blank? (str/split-lines (str stdout)))]
    (when (= (count fields) (count lines))
      (zipmap [:id :title :channel :duration :height :language :filepath]
              (map #(try (js/JSON.parse %) (catch :default _ nil)) lines)))))

(defn redact
  "`text` without the proxy's URL, its login or its password."
  [text proxy]
  (if (str/blank? (str proxy))
    (str text)
    (let [parsed (try (js/URL. proxy) (catch :default _ nil))
          password (some-> parsed .-password not-empty)
          login (when password (str (.-username parsed) ":" password))
          decoded (when password (try (js/decodeURIComponent password) (catch :default _ nil)))]
      (cond-> (str/replace (str text) proxy "«RRAGENT_YOUTUBE_PROXY»")
        login (str/replace login "«RRAGENT_YOUTUBE_PROXY»")
        (seq decoded) (str/replace decoded "«RRAGENT_YOUTUBE_PROXY»")))))

(def failures
  "What yt-dlp's stderr says, in the order checked, and the sentence
   for the model."
  [[#"(?i)sign in to confirm you.re not a bot"
    "YouTube took the request for a bot's and asks for a login: the IP the machine leaves through is flagged. The operator changes it; try again later, not at once."]
   [#"(?i)sign in to confirm your age|age.restricted|inappropriate for some users"
    "The video is age-restricted: YouTube shows it to accounts only, and the machine has none."]
   [#"(?i)not (?:made this video )?available in your country|not available in your (?:country|location)|geo.?restrict"
    "The uploader locked the video for the country the machine leaves from (the USA, through the proxy), so it cannot be fetched from here."]
   [#"(?i)private video"
    "The video is private: its owner would have to make it unlisted or public."]
   [#"(?i)video unavailable|has been removed|no longer available|does not exist"
    "The video is unavailable: removed, or the id in the URL is wrong."]
   [#"(?i)is a live|live event|premieres? in|this live stream"
    "The video is a live stream or a premiere that has not ended: fetch it once it has."]
   [#"(?i)unable to connect to proxy|proxy(?:error| connection| authentication)|tunnel connection failed|407 proxy"
    "The proxy the machine leaves through does not answer or refuses the login: the operator checks RRAGENT_YOUTUBE_PROXY."]
   [#"(?i)unsupported url|is not a valid url"
    "That is not the URL of a YouTube video."]
   [#"(?i)requested format is not available"
    "YouTube offers no format that fits: try a lower `height`, or the audio."]
   [#"(?i)ffmpeg (?:not found|is not installed|could not be found)|needs? ffmpeg|ffmpeg is required"
    "ffmpeg is needed for this (a section, the audio, the merge of video and audio) and the machine has none."]])

(defn failure
  "The sentence for the model about `stderr` of a run that failed, the
   `proxy` never in it: a known failure's, else yt-dlp's last error
   line without its prefix."
  [stderr proxy]
  (let [text (redact stderr proxy)]
    (or (some (fn [[pattern sentence]] (when (re-find pattern text) sentence)) failures)
        (let [lines (str/split-lines text)
              line (or (last (filter #(str/starts-with? % "ERROR:") lines)) (last-line text))]
          (str "yt-dlp failed: "
               (str/replace (str/trim (str line)) #"^ERROR:\s*(?:\[[^\]]+\]\s*)?(?:[A-Za-z0-9_-]{11}:\s*)?" ""))))))

(defn ^:async download!
  "yt-dlp run for `request` (`arguments`) with the programs ready
   (`ready!`): `{:fields :stderr}`, the `fields` printed (nil when it
   wrote no file) and what it said, the proxy redacted; a throw with
   `failure`'s sentence when it failed."
  [request]
  (let [p (await (ready!))
        proxy (proxy)
        {:keys [ok? timeout? stdout stderr]} (await (exec! (:yt-dlp p)
                                                           (arguments p (.-execPath js/process) (assoc request :proxy proxy))
                                                           {:timeout timeout-ms}))]
    (cond
      timeout? (throw (js/Error. (str "yt-dlp did not finish in " (quot timeout-ms 60000) " minutes.")))
      (not ok?) (throw (js/Error. (failure stderr proxy)))
      :else {:fields (printed stdout) :stderr (redact stderr proxy)})))

;;; Subtitles

(def word-seconds
  "The most a word of a caption is taken to last: the captions say
   when a word starts, not when it ends, so a word ends when the next
   starts, or this much later, and the pause before the next is what
   is left (`rragent.tools.transcribe/paragraphs` cuts at the pauses)."
  0.6)

(defn caption-words
  "The words of a json3 subtitle file (YouTube's, `events` with their
   `segs`), `[{:word :start :end}]` in order: the automatic captions
   carry a word per segment with its offset, written subtitles a cue
   per event, which is one \"word\" then, its lines joined, and ends
   with its event."
  [json3]
  (let [words (vec (for [event (:events json3)
                         :when (seq (:segs event))
                         :let [start-ms (or (:tStartMs event) 0)
                               end (/ (+ start-ms (or (:dDurationMs event) 0)) 1000)
                               cue? (= 1 (count (:segs event)))]
                         seg (:segs event)
                         :let [text (str/replace (str/trim (str (:utf8 seg))) #"\s+" " ")]
                         :when (seq text)]
                     {:word text
                      :start (/ (+ start-ms (or (:tOffsetMs seg) 0)) 1000)
                      :event-end end
                      :cue? cue?}))]
    (vec (map-indexed (fn [i {:keys [word start event-end cue?]}]
                        (let [next-start (:start (get words (inc i)))
                              end (if cue?
                                    (max start (min event-end (or next-start event-end)))
                                    (max start (min (or next-start (+ start word-seconds)) (+ start word-seconds))))]
                          {:word word :start start :end end}))
                      words))))

(defn- vtt-seconds
  "The seconds of a cue's time, `hh:mm:ss.mmm` or `mm:ss.mmm`."
  [text]
  (reduce (fn [sum part] (+ (* 60 sum) part)) 0 (map js/parseFloat (str/split text #":"))))

(defn caption-cues
  "The cues of a WebVTT subtitle file, `[{:word :start :end}]` in
   order: a cue's lines as one \"word\", its tags stripped."
  [vtt]
  (vec (for [block (str/split (str/replace (str vtt) #"\r\n?" "\n") #"\n\n+")
             :let [lines (remove str/blank? (str/split-lines block))
                   timing (first (filter #(str/includes? % "-->") lines))
                   [_ start end] (when timing (re-find #"((?:\d+:)?\d\d:\d\d\.\d+)\s+-->\s+((?:\d+:)?\d\d:\d\d\.\d+)" timing))
                   text (when timing
                          (->> lines
                               (drop-while #(not= % timing))
                               rest
                               (map #(str/replace % #"<[^>]*>" ""))
                               (str/join " ")
                               str/trim))]
             :when (and start (seq text))]
         {:word text :start (vtt-seconds start) :end (vtt-seconds end)})))

(defn captions
  "The words or the cues of the subtitle `file`, by its extension:
   `caption-words` of a json3, `caption-cues` of a vtt."
  [file]
  (let [text (fs/readFileSync file "utf8")]
    (if (str/ends-with? (str file) ".json3")
      (caption-words (js->clj (js/JSON.parse text) :keywordize-keys true))
      (caption-cues text))))
