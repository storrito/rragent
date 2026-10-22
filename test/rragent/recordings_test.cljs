(ns rragent.recordings-test
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.recordings :as recordings]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(defn- bytes-of
  [n]
  (js/Uint8Array.from (clj->js (map #(mod % 251) (range n)))))

(defn- files-of
  [dir]
  (sort (array-seq (fs/readdirSync dir))))

(defn- opus-webm
  "A second of a tone in WebM with Opus, as the browser records the
   microphone; false without ffmpeg."
  [file]
  (try
    (zero? (.-status (child/spawnSync "ffmpeg" #js ["-y" "-v" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=1" "-c:a" "libopus" file] #js {:stdio "ignore"})))
    (catch :default _
      false)))

(deftest audio-test
  (async done
    (-> ((^:async fn []
           (let [root (fs/mkdtempSync (path/join (os/tmpdir) "rragent-recordings-"))
                 dir (path/join root "artifacts")
                 server (await (http/listen (http/ring-handler (constantly [{:ring/route [:post "/record/*"]
                                                                              :ring/handler (fn [w] (recordings/handler dir {} w))}
                                                                             {:ring/route [:get "/record/*"]
                                                                              :ring/handler (fn [w] (recordings/get-handler dir w))}]))
                                            0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 post (^:async fn [subpath body & [type]]
                        (let [response (await (js/fetch (str base "/record/" subpath)
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" (or type "video/webm")}
                                                             :body body}))]
                          [(.-status response) (transit/read-str (await (.text response)))]))
                 stamp "2026-10-22T12-00-00"
                 source (path/join root "tone.webm")
                 real? (opus-webm source)
                 content (if real? (fs/readFileSync source) (bytes-of 30))]
             (try
               (testing "the microphone alone: its file, an MP3 of it when ffmpeg is there"
                 (is (= [200 {:received (.-length content)}] (await (post (str stamp "/audio?offset=0") content))))
                 (let [[status value] (await (post (str stamp "/finish")
                                                   (transit/write-str {:started {"audio" 1} :devices {:mic "Shure MV7"} :mime "audio/webm" :files {"audio" "webm"}})
                                                   "application/transit+json"))
                       made (if real? "audio.mp3" "audio.webm")]
                   (is (= 200 status))
                   (is (= {:folder (str "recordings/" stamp) :files [made]} value))
                   (is (= [made "recording.json"] (files-of (recordings/folder dir stamp))) "no part file, no WebM beside the MP3")
                   (is (= [made] (get (js->clj (js/JSON.parse (fs/readFileSync (path/join (recordings/folder dir stamp) "recording.json") "utf8"))) "files")))
                   (testing ", served"
                     (is (= 200 (.-status (await (js/fetch (str base "/record/" stamp "/" made)))))))))
               (finally
                 (.close server)
                 (fs/rmSync root #js {:recursive true :force true}))))))
        (.catch (fn [error]
                  (is (nil? error))))
        (.finally done))))

(deftest server-test
  (async done
    (-> ((^:async fn []
           (let [dir (path/join (fs/mkdtempSync (path/join (os/tmpdir) "rragent-recordings-")) "artifacts")
                 server (await (http/listen (http/ring-handler (constantly [{:ring/route [:post "/record/*"]
                                                                              :ring/handler (fn [w] (recordings/handler dir {} w))}]))
                                            0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 post (^:async fn [subpath body & [type]]
                        (let [response (await (js/fetch (str base "/record/" subpath)
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" (or type "video/webm")}
                                                             :body body}))]
                          [(.-status response) (transit/read-str (await (.text response)))]))
                 stamp "2026-10-21T11-56-03"
                 content (bytes-of 30)]
             (try
               (testing "pieces of the two files, in order, into hidden part files"
                 (is (= [200 {:received 10}] (await (post (str stamp "/screen?offset=0") (.slice content 0 10)))))
                 (is (= [200 {:received 30}] (await (post (str stamp "/screen?offset=10") (.slice content 10 30)))))
                 (is (= [200 {:received 30}] (await (post (str stamp "/webcam?offset=0") content))))
                 (is (= [".screen.webm.part" ".webcam.webm.part"] (files-of (recordings/folder dir stamp)))))
               (testing "a piece that does not start where the file ends is answered with how much is there"
                 (is (= [409 {:error :out-of-order :received 30}] (await (post (str stamp "/screen?offset=20") (.slice content 20 30)))))
                 (is (= [400 {:error :bad-piece}] (await (post (str stamp "/screen") content))))
                 (is (= [404 {:error :no-such-file}] (await (post (str stamp "/other?offset=0") content))))
                 (is (= [400 {:error :bad-recording}] (await (post "nope/screen?offset=0" content))))
                 (is (= [400 {:error :bad-recording}] (await (post "..%2F2026-10-21T11-56-03/screen?offset=0" content))) "no path above the folder"))
               (testing "the end: the files, the facts beside them, the folder as the agent names it"
                 (is (= [409 {:error :not-recorded :missing ["screen" "webcam"]}]
                        (await (post "2026-10-21T11-57-00/finish" (transit/write-str {:started {}}) "application/transit+json")))
                     "a recording without both files is not finished")
                 (is (= [400 {:error :bad-facts}] (await (post (str stamp "/finish") "not transit" "application/transit+json"))))
                 (let [[status value] (await (post (str stamp "/finish")
                                                   (transit/write-str {:started {"screen" 1 "webcam" 2} :devices {:mic "Shure MV7"} :mime "video/webm"})
                                                   "application/transit+json"))]
                   (is (= 200 status))
                   (is (= {:folder (str "recordings/" stamp) :files ["screen.webm" "webcam.webm"]} value))
                   (is (= ["recording.json" "screen.webm" "webcam.webm"] (files-of (recordings/folder dir stamp))) "no part files are left")
                   (let [facts (js->clj (js/JSON.parse (fs/readFileSync (path/join (recordings/folder dir stamp) "recording.json") "utf8")))]
                     (is (= "Shure MV7" (get-in facts ["devices" "mic"])))
                     (is (= ["screen.webm" "webcam.webm"] (get facts "files"))))
                   ;; what the test sent is no video: ffmpeg, when there is one, refuses it and the bytes stay as recorded
                   (is (= (vec content) (vec (fs/readFileSync (path/join (recordings/folder dir stamp) "webcam.webm")))))))
               (testing "the phone's screen over USB, a file of the laptop like the screen"
                 (let [stamp "2026-10-22T15-00-00"]
                   (is (= [200 {:received 30}] (await (post (str stamp "/screen?offset=0") content))))
                   (is (= [200 {:received 30}] (await (post (str stamp "/phone-screen?offset=0") content))))
                   (is (= [200 {:folder (str "recordings/" stamp) :files ["phone-screen.webm" "screen.webm"]}]
                          (await (post (str stamp "/finish")
                                       (transit/write-str {:started {} :files {"screen" "webm" "phone-screen" "webm"}})
                                       "application/transit+json"))))
                   (is (= [200 {:folder (str "recordings/" stamp) :files ["phone-screen.webm" "screen.webm"]}]
                          (await (post (str stamp "/finish")
                                       (transit/write-str {:started {} :files {"screen" "webm" "phone-screen" "webm"}})
                                       "application/transit+json")))
                       "asked again once done, the end answers as it did")
                   (is (re-matches recordings/file-pattern "phone-screen.webm") "served for the panel's test")))
               (finally
                 (.close server))))))
        (.catch (fn [error]
                  (is (nil? error))))
        (.finally done))))
