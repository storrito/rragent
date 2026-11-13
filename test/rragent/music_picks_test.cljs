(ns rragent.music-picks-test
  "The song of a story's music sticker, picked on Storrito's music pick
   page: the link the tool answers, what is read of Storrito's callback,
   and a callback that works once."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.music-picks :as music-picks]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def storrito-body
  "What storrito-server's `app.music-pick.confirm/payload` posts."
  (js/JSON.stringify
    (clj->js {:event "music-pick.confirmed"
              :trackId "249810515420048"
              :title "Beautiful Day"
              :artist "U2"
              :searchTerm "Beautiful Day U2"
              :startMs 42000
              :durationMs 15000
              :songDurationMs 246093
              :coverUrl "https://scontent.cdninstagram.com/thumb.jpg"
              :insta "<insta-music search-term=\"ignored\"></insta-music>"})))

(deftest choice-test
  (testing "the fields of Storrito's body"
    (is (= {:track-id "249810515420048" :title "Beautiful Day" :artist "U2" :search-term "Beautiful Day U2"
            :start-ms 42000 :duration-ms 15000 :song-ms 246093}
           (music-picks/choice storrito-body))))
  (testing "no track id of digits or no start: no song"
    (is (nil? (music-picks/choice "{\"trackId\":\"abc\",\"startMs\":0}")))
    (is (nil? (music-picks/choice "{\"trackId\":\"1\"}")))
    (is (nil? (music-picks/choice "{\"trackId\":\"1\",\"startMs\":-5}")))
    (is (nil? (music-picks/choice "not json")))
    (is (nil? (music-picks/choice nil))))
  (testing "text is cut and loses its control characters"
    (let [{:keys [title search-term]} (music-picks/choice (js/JSON.stringify #js {:trackId "1" :startMs 0 :title (str "A\nB" (apply str (repeat 300 "x")))}))]
      (is (str/starts-with? title "A B"))
      (is (= 200 (count title)))
      (is (= title search-term) "the title and the artist without a search term"))))

(deftest message-test
  (let [choice (music-picks/choice storrito-body)
        message (music-picks/message choice)]
    (is (str/includes? message "\"Beautiful Day\" – U2 · 0:42–0:57"))
    (is (str/includes? message "<insta-music search-term=\"Beautiful Day U2\" track-id=\"249810515420048\" start-ms=\"42000\"></insta-music>")
        "the element is written here from the fields")
    (is (not (str/includes? message "ignored")) "Storrito's own element does not reach the model"))
  (testing "quotes in a title cannot leave the attribute"
    (is (= "<insta-music search-term=\"Say &quot;Hi&quot; &amp; &lt;b\" track-id=\"1\" start-ms=\"0\"></insta-music>"
           (music-picks/insta-music {:track-id "1" :search-term "Say \"Hi\" & <b" :start-ms 0})))))

(def audio-url
  "https://scontent-fra5-1.cdninstagram.com/o1/v/t2/f2/m69/song.mp4?oe=6AEFB6A0&_nc_sid=1")

(deftest preview-audio-test
  (testing "the song on Instagram's CDN is kept for the preview"
    (is (= audio-url (:audio-url (music-picks/choice (js/JSON.stringify #js {:trackId "1" :startMs 0 :audioUrl audio-url})))))
    (is (some? (music-picks/cdn-url "https://video.xx.fbcdn.net/v/song.mp4"))))
  (testing "any other URL is left out: the bot downloads it"
    (is (nil? (music-picks/cdn-url "https://evil.example/song.mp4")))
    (is (nil? (music-picks/cdn-url "https://cdninstagram.com.evil.example/x")))
    (is (nil? (music-picks/cdn-url "http://scontent.cdninstagram.com/x")))
    (is (nil? (music-picks/cdn-url "https://scontent.cdninstagram.com/x'; rm -rf ~; '")))
    (is (nil? (:audio-url (music-picks/choice storrito-body)))))
  (testing "the message says how the preview plays the picked part"
    (let [message (music-picks/message (assoc (music-picks/choice storrito-body) :audio-url audio-url))]
      (is (str/includes? message (str "curl -fsSL '" audio-url "' -o composition/music-preview.m4a")))
      (is (str/includes? message "<audio id=\"music-preview\" src=\"music-preview.m4a\" data-start=\"0\" data-duration=\"15\" data-media-start=\"42\" data-preview-only></audio>")))
    (is (= "<audio id=\"music-preview\" src=\"music-preview.m4a\" data-start=\"0\" data-duration=\"15\" data-media-start=\"100.5\" data-preview-only></audio>"
           (music-picks/preview-audio {:start-ms 100500 :duration-ms 15000})))
    (is (not (str/includes? (music-picks/message (music-picks/choice storrito-body)) "<audio"))
        "no audio URL, no preview audio")))

(deftest page-url-test
  (is (= "https://acme.storrito.com/ui/music-pick?callback=https%3A%2F%2Fbot.example%2Fmusic-pick%2Fab%2Fcd&section-ms=15000"
         (music-picks/page-url "https://acme.storrito.com" "https://bot.example/music-pick/ab/cd" 15000)))
  (is (= 15000 (music-picks/section-ms nil)))
  (is (= 60000 (music-picks/section-ms 90000)))
  (is (= 1000 (music-picks/section-ms 10)))
  (testing "the host from the REPL's Storrito connection"
    (is (= "https://acme.storrito.com"
           (music-picks/storrito-host {:storrito (atom {:connection {:provider "custom_api"
                                                                     :provider_info {:base_api_url "https://acme.storrito.com/api/v1"}}})})))))

(defn- env!
  [k v]
  (let [before (aget (.-env js/process) k)]
    (if (some? v)
      (aset (.-env js/process) k v)
      (js-delete (.-env js/process) k))
    before))

(deftest a-pick-works-once-test
  (async done
    (let [url-before (env! "RRAGENT_URL" "https://bot.example/")
          storrito-before (env! "RRAGENT_STORRITO_URL" "https://acme.storrito.com")]
      (-> ((^:async fn []
             (let [home (test-home/make)
                   {:keys [faux models]} (tools-test/faux-models)]
               (.setResponses faux #js [(tools-test/tool-call "music_pick" {:sectionMs 10000} "m1")
                                        (tools-test/answer "Here is the link.")
                                        (tools-test/answer "Got the song.")])
               (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                 (try
                   (is (= "Here is the link." (await (harness/ask agent "Put music on my story."))))
                   (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                         result (->> (array-seq (.-items page))
                                     (filter #(= "pi.tool-result" (.-kind %)))
                                     (map #(tools-test/result-text (aget (.-model %) 0)))
                                     first)
                         [_ id secret] (re-find #"music-pick%2F([0-9a-f]+)%2F([0-9a-f]+)&section-ms=10000" (str result))]
                     (is (str/includes? result "https://acme.storrito.com/ui/music-pick?callback=https%3A%2F%2Fbot.example%2Fmusic-pick%2F") result)
                     (is (and id secret) "the link carries the pick's callback")
                     (is (= {:ok false} (await (music-picks/receive! agent id "wrong" storrito-body harness/context))))
                     (is (= {:ok false} (await (music-picks/receive! agent id secret "{}" harness/context))) "a body without a song")
                     (let [{:keys [ok submission]} (await (music-picks/receive! agent id secret storrito-body harness/context))]
                       (is (true? ok))
                       (await (.wait (await (.submission (:harness agent) submission harness/context)) harness/context))
                       (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                             entry (->> (array-seq (.-items page))
                                        (filter #(= music-picks/kind (.-kind %)))
                                        first)]
                         (is (some? entry) "the song is an entry of the chat")
                         (is (= "Beautiful Day" (.. entry -data -title)))))
                     (is (= {:ok false} (await (music-picks/receive! agent id secret storrito-body harness/context)))
                         "a pick is used once"))
                   (finally
                     (await (harness/close agent))
                     (test-home/remove! home)))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (env! "RRAGENT_URL" url-before)
                      (env! "RRAGENT_STORRITO_URL" storrito-before)
                      (done)))))))
