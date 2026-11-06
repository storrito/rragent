(ns rragent.youtube-test
  "The youtube tool: the id a URL names, yt-dlp's command line, what
   its lines and its errors become, the words of a caption file, and a
   video, its audio and its subtitles fetched with a fake yt-dlp in a
   folder RRAGENT_YOUTUBE_PROGRAMS names, so nothing is installed."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.tools.youtube :as tool]
            [rragent.youtube :as youtube]))

(deftest video-id-test
  (testing "the forms of a video's URL"
    (is (= "dQw4w9WgXcQ" (tool/video-id "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")))
    (is (= "dQw4w9WgXcQ" (tool/video-id "https://youtu.be/dQw4w9WgXcQ?si=abc")))
    (is (= "dQw4w9WgXcQ" (tool/video-id "https://m.youtube.com/shorts/dQw4w9WgXcQ")))
    (is (= "dQw4w9WgXcQ" (tool/video-id "https://music.youtube.com/watch?v=dQw4w9WgXcQ")))
    (is (= "dQw4w9WgXcQ" (tool/video-id "https://www.youtube.com/live/dQw4w9WgXcQ"))))
  (testing "a playlist, a channel, another site, no URL"
    (is (thrown-with-msg? js/Error #"one video" (tool/video-id "https://www.youtube.com/playlist?list=PL123")))
    (is (thrown-with-msg? js/Error #"one video" (tool/video-id "https://www.youtube.com/@blender")))
    (is (thrown-with-msg? js/Error #"one video" (tool/video-id "https://vimeo.com/12345")))
    (is (thrown-with-msg? js/Error #"one video" (tool/video-id "dQw4w9WgXcQ")))))

(def paths
  (youtube/paths "/p"))

(defn- after
  "The value after `flag` in `args`."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(deftest arguments-test
  (testing "a video: one video, this Node as the runtime, the plugin and its script, the sort, the fields printed after the move"
    (let [args (youtube/arguments paths "/usr/bin/node" {:url "https://youtu.be/x" :what "video" :out "/w/%(id)s.%(ext)s"})]
      (is (= "https://youtu.be/x" (last args)))
      (is (some #{"--no-playlist"} args))
      (is (= "node:/usr/bin/node" (after args "--js-runtimes")))
      (is (= "/p/plugins" (after args "--plugin-dirs")))
      (is (= "youtubepot-bgutilscript:server_home=/p/server" (after args "--extractor-args")))
      (is (= "res:1080,vcodec:h264,acodec:mp4a" (after args "-S")))
      (is (= "mp4" (after args "--merge-output-format")))
      (is (= "/w/%(id)s.%(ext)s" (after args "-o")))
      (is (= "after_move:%(id)j" (after args "--print")))
      (is (some #{"after_move:%(filepath)j"} args))
      (is (not (some #{"--proxy"} args)) "no proxy unless given")
      (is (= youtube/max-filesize (after args "--max-filesize")))
      (is (not (some #{"--download-sections"} args)))))
  (testing "the height, the proxy, a section"
    (let [args (youtube/arguments paths "/n" {:url "u" :what "video" :out "o" :height 720 :proxy "http://u:p@h:1" :section "10:00-12:30"})]
      (is (= "res:720,vcodec:h264,acodec:mp4a" (after args "-S")))
      (is (= "http://u:p@h:1" (after args "--proxy")))
      (is (= "*10:00-12:30" (after args "--download-sections")))
      (is (some #{"--force-keyframes-at-cuts"} args))
      (is (not (some #{"--max-filesize"} args)) "a section of a video of any size")))
  (testing "the audio: the best m4a, extracted"
    (let [args (youtube/arguments paths "/n" {:url "u" :what "audio" :out "o"})]
      (is (= "ba[ext=m4a]/ba" (after args "-f")))
      (is (some #{"-x"} args))
      (is (= "m4a" (after args "--audio-format")))
      (is (not (some #{"-S"} args)))))
  (testing "the subtitles: no download, json3 or vtt, the languages and the kinds asked, the fields printed before"
    (let [args (youtube/arguments paths "/n" {:url "u" :what "subtitles" :out "o" :languages ".*-orig" :automatic? true})]
      (is (some #{"--skip-download"} args))
      (is (= "json3/vtt" (after args "--sub-format")))
      (is (= ".*-orig" (after args "--sub-langs")))
      (is (some #{"--write-auto-subs"} args))
      (is (not (some #{"--write-subs"} args)))
      (is (= "video:%(id)j" (after args "--print"))))
    (let [args (youtube/arguments paths "/n" {:url "u" :what "subtitles" :out "o" :languages "de" :written? true :automatic? true})]
      (is (= "de" (after args "--sub-langs")))
      (is (some #{"--write-subs"} args))
      (is (some #{"--write-auto-subs"} args)))))

(deftest printed-test
  (testing "the seven lines, a missing field as NA"
    (is (= {:id "abc" :title "A title" :channel "Who" :duration 212 :height nil :language "en" :filepath "/w/abc.m4a"}
           (youtube/printed "\"abc\"\n\"A title\"\n\"Who\"\n212\nNA\n\"en\"\n\"/w/abc.m4a\"\n"))))
  (testing "fewer lines: no file"
    (is (nil? (youtube/printed "\"abc\"\n\"A title\"\n")))
    (is (nil? (youtube/printed "")))))

(deftest failure-test
  (testing "the known ones"
    (is (str/includes? (youtube/failure "ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies" nil) "took the request for a bot's"))
    (is (str/includes? (youtube/failure "ERROR: [youtube] abc: The uploader has not made this video available in your country" nil) "locked the video for the country"))
    (is (str/includes? (youtube/failure "ERROR: [youtube] abc: Private video. Sign in if you've been granted access" nil) "private"))
    (is (str/includes? (youtube/failure "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users." nil) "age-restricted"))
    (is (str/includes? (youtube/failure "ERROR: Unable to download webpage: Unable to connect to proxy" nil) "proxy")))
  (testing "another: yt-dlp's last error line without its prefix"
    (is (= "yt-dlp failed: Something odd happened"
           (youtube/failure "WARNING: first\nERROR: [youtube] dQw4w9WgXcQ: Something odd happened\n" nil)))
    (is (= "yt-dlp failed: Something odd" (youtube/failure "ERROR: Something odd" nil)))
    (is (= "yt-dlp failed: just a line" (youtube/failure "just a line\n" nil))))
  (testing "the proxy never in it"
    (let [proxy "http://user:s3cret@proxy.example:10000"
          text (youtube/failure (str "ERROR: Unable to download webpage " proxy " (user:s3cret)") proxy)]
      (is (not (str/includes? text "s3cret")))
      (is (str/includes? text "«RRAGENT_YOUTUBE_PROXY»")))))

(deftest redact-test
  (is (= "a «RRAGENT_YOUTUBE_PROXY» b «RRAGENT_YOUTUBE_PROXY» c «RRAGENT_YOUTUBE_PROXY»"
         (youtube/redact "a http://u:p%40ss@h:1 b u:p%40ss c p@ss" "http://u:p%40ss@h:1")))
  (is (= "as it is" (youtube/redact "as it is" nil))))

(def json3
  "A caption file: an automatic event of two words, then a cue an
   event, one with a line break."
  {:events [{:tStartMs 0 :dDurationMs 20000 :segs [{:utf8 "Never"} {:utf8 " gonna" :tOffsetMs 10000}]}
            {:tStartMs 15000 :dDurationMs 100 :aAppend 1 :segs [{:utf8 "\n"}]}
            {:tStartMs 20000 :dDurationMs 1000 :segs [{:utf8 "give\n "}]}
            {:tStartMs 30000 :dDurationMs 1000 :segs [{:utf8 "you"}]}
            {:tStartMs 40000 :dDurationMs 1000 :segs [{:utf8 "up"}]}]})

(deftest caption-words-test
  (testing "a word of an automatic caption ends when the next starts, at most 0.6 s later; a cue ends with its event, trimmed; a line break is no word"
    (is (= [{:word "Never" :start 0 :end 0.6}
            {:word "gonna" :start 10 :end 10.6}
            {:word "give" :start 20 :end 21}
            {:word "you" :start 30 :end 31}
            {:word "up" :start 40 :end 41}]
           (youtube/caption-words json3)))))

(deftest caption-cues-test
  (testing "the cues of a vtt, tags stripped, lines joined; headers and notes are none"
    (is (= [{:word "Hear that?" :start 19.56 :end 24.84}
            {:word "A second line. And its end." :start 3624.84 :end 3630}]
           (youtube/caption-cues (str "WEBVTT\nKind: captions\nLanguage: en\n\nNOTE a note\n\n"
                                      "00:19.560 --> 00:24.840 align:start position:0%\n<c.colorE5E5E5>Hear</c> that?\n\n"
                                      "01:00:24.840 --> 01:00:30.000\nA second line.\nAnd its end.\n"))))))

(deftest caption-file-test
  (let [files ["x.en-orig.json3" "x.en.vtt"]]
    (testing "a language asked: its file"
      (is (= "x.en.vtt" (tool/caption-file "x" files "en" "en")))
      (is (nil? (tool/caption-file "x" files "fr" "en"))))
    (testing "none asked: the written one in the video's own language, else the automatic one"
      (is (= "x.en.vtt" (tool/caption-file "x" files nil "en")))
      (is (= "x.en-orig.json3" (tool/caption-file "x" files nil "de")))
      (is (= "x.en-orig.json3" (tool/caption-file "x" ["x.en-orig.json3"] nil nil)))
      (is (nil? (tool/caption-file "x" [] nil nil))))))

(def vtt
  "Written subtitles: a cue of thirty seconds, a pause, one more."
  "WEBVTT\n\n00:00.000 --> 00:30.000\nNever gonna give you\n\n00:40.000 --> 00:41.000\nup\n")

(def fake
  "A yt-dlp that writes the file of its output template and prints the
   fields; its arguments go to args.txt beside it. A URL with `botwall`
   fails as YouTube's bot check does, one with `proxyfail` fails
   naming its arguments, the proxy among them. Subtitles: the automatic
   ones as en-orig.json3 when asked for, the written ones as en.vtt
   when `en` is asked for (none with `autoonly` in the URL)."
  (str/join "\n"
            ["#!/bin/bash"
             "out=\"\"; skip=0; audio=0; prev=\"\"; url=\"\"; langs=\"\"; written=0; automatic=0"
             "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/args.txt\""
             "for a in \"$@\"; do"
             "  [ \"$prev\" = \"-o\" ] && out=\"$a\""
             "  [ \"$prev\" = \"--sub-langs\" ] && langs=\"$a\""
             "  [ \"$a\" = \"--skip-download\" ] && skip=1"
             "  [ \"$a\" = \"--write-subs\" ] && written=1"
             "  [ \"$a\" = \"--write-auto-subs\" ] && automatic=1"
             "  [ \"$a\" = \"-x\" ] && audio=1"
             "  prev=\"$a\"; url=\"$a\""
             "done"
             "case \"$url\" in *v=*) id=\"${url##*v=}\";; *) id=\"${url##*/}\";; esac; id=\"${id%%&*}\""
             "case \"$url\" in"
             "  *botwall*) echo \"ERROR: [youtube] $id: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.\" >&2; exit 1;;"
             "  *proxyfail*) echo \"ERROR: [youtube] $id: Unable to download webpage: $*\" >&2; exit 1;;"
             "esac"
             "if [ \"$skip\" = 1 ]; then"
             "  dir=\"$(dirname \"$out\")\""
             (str "  [ \"$automatic\" = 1 ] && printf '%s' '" (js/JSON.stringify (clj->js json3)) "' > \"$dir/$id.en-orig.json3\"")
             (str "  case \"$url\" in *autoonly*) ;; *) [ \"$written\" = 1 ] && [ \"$langs\" = en ] && printf '%s' '" vtt "' > \"$dir/$id.en.vtt\";; esac")
             "  file=NA"
             "else"
             "  ext=mp4; [ \"$audio\" = 1 ] && ext=m4a"
             "  file=\"${out//'%(id)s'/$id}\"; file=\"${file//'%(ext)s'/$ext}\""
             "  printf 'bytes' > \"$file\""
             "fi"
             "printf '\"%s\"\\n\"Never Gonna Give You Up\"\\n\"Rick Astley\"\\n212\\n' \"$id\""
             "[ \"$audio\" = 1 ] && echo NA || echo 1080"
             "echo '\"en\"'"
             "[ \"$file\" = NA ] && echo NA || printf '\"%s\"\\n' \"$file\""
             ""]))

(defn- programs!
  "A folder with the fake yt-dlp, named in the environment."
  []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-youtube-programs-"))]
    (fs/writeFileSync (path/join dir "yt-dlp") fake)
    (fs/chmodSync (path/join dir "yt-dlp") 0x1ed)
    (aset (.-env js/process) "RRAGENT_YOUTUBE_PROGRAMS" dir)
    dir))

(defn- args-of
  [dir]
  (str/split-lines (fs/readFileSync (path/join dir "args.txt") "utf8")))

(deftest fetch-test
  (async done
    (let [programs (programs!)
          work (fs/mkdtempSync (path/join (os/tmpdir) "rragent-youtube-work-"))
          dir (path/join work "artifacts")
          url "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
          fetch! (fn [options] (tool/fetch! (merge {:work work :dir dir :url url} options)))]
      (-> ((^:async fn []
             (testing "a video into downloads/, the line with the title, the channel, the length and the size"
               (let [answer (await (fetch! {}))]
                 (is (= "artifacts/downloads/dQw4w9WgXcQ.mp4: Never Gonna Give You Up, Rick Astley, 3:32, 1080p, 0.0 MB." answer))
                 (is (fs/existsSync (path/join dir "downloads" "dQw4w9WgXcQ.mp4")))
                 (is (= "res:1080,vcodec:h264,acodec:mp4a" (after (args-of programs) "-S")))))
             (testing "the audio, a section, where `out` says"
               (let [answer (await (fetch! {:what "audio" :section "1:00-1:30" :out "clips/chorus.m4a"}))]
                 (is (= "artifacts/clips/chorus.m4a: Never Gonna Give You Up, Rick Astley, 3:32 (the section 1:00-1:30), 0.0 MB." answer))
                 (is (fs/existsSync (path/join dir "clips" "chorus.m4a")))
                 (is (= "*1:00-1:30" (after (args-of programs) "--download-sections")))))
             (testing "the subtitles as a transcript: the written ones in the video's language after the automatic ones, in paragraphs cut at the pauses, with the times"
               (let [answer (await (fetch! {:what "subtitles" :timestamps? true}))
                     file (path/join dir "transcripts" "dQw4w9WgXcQ.md")]
                 (is (= "artifacts/transcripts/dQw4w9WgXcQ.md: Never Gonna Give You Up, Rick Astley, 3:32, 5 words, subtitles en.\n\n[0:00] Never gonna give you\n\n[0:40] up" answer))
                 (is (= "# Never Gonna Give You Up\n\nSubtitles of https://www.youtube.com/watch?v=dQw4w9WgXcQ, 3:32 (en).\n\n[0:00] Never gonna give you\n\n[0:40] up\n"
                        (fs/readFileSync file "utf8")))
                 (is (= "en" (after (args-of programs) "--sub-langs")) "the second run, the written ones in the video's language")
                 (is (some #{"--write-subs"} (args-of programs)))
                 (is (not (some #{"--write-auto-subs"} (args-of programs))))))
             (testing "the automatic captions when there are no written ones, the words in paragraphs"
               (let [answer (await (fetch! {:url "https://youtu.be/autoonly001" :what "subtitles"}))]
                 (is (= "artifacts/transcripts/autoonly001.md: Never Gonna Give You Up, Rick Astley, 3:32, 5 words, subtitles automatic, en.\n\nNever gonna give you\n\nup" answer))))
             (testing "a language asked for, in one go, and one the video has not"
               (is (string? (await (fetch! {:what "subtitles" :language "en"}))))
               (is (= "en" (after (args-of programs) "--sub-langs")))
               (is (some #{"--write-auto-subs"} (args-of programs)))
               (is (thrown-with-msg? js/Error #"no subtitles in de" (await (fetch! {:what "subtitles" :language "de"})))))
             (testing "what is asked for is checked first"
               (is (thrown-with-msg? js/Error #"video, audio or subtitles" (await (fetch! {:what "gif"}))))
               (is (thrown-with-msg? js/Error #"start-end" (await (fetch! {:section "10-20"}))))
               (is (thrown-with-msg? js/Error #"under artifacts/" (await (fetch! {:out "../x.mp4"})))))
             (testing "YouTube's bot check becomes the sentence for the model"
               (is (thrown-with-msg? js/Error #"took the request for a bot's" (await (fetch! {:url "https://youtu.be/botwall0001"})))))
             (testing "the proxy of the environment goes to yt-dlp and never into the answer"
               (aset (.-env js/process) "RRAGENT_YOUTUBE_PROXY" "http://user:s3cret@proxy.example:10000")
               (try
                 (let [error (await (-> (fetch! {:url "https://youtu.be/proxyfail01"}) (.then (fn [_] nil)) (.catch identity)))]
                   (is (some? error))
                   (is (str/includes? (.-message error) "yt-dlp failed"))
                   (is (not (str/includes? (.-message error) "s3cret")))
                   (is (= "http://user:s3cret@proxy.example:10000" (after (args-of programs) "--proxy"))))
                 (finally
                   (js-delete (.-env js/process) "RRAGENT_YOUTUBE_PROXY"))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (js-delete (.-env js/process) "RRAGENT_YOUTUBE_PROGRAMS")
                      (fs/rmSync programs #js {:recursive true :force true})
                      (fs/rmSync work #js {:recursive true :force true})
                      (done)))))))
