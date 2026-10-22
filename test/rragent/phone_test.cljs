(ns rragent.phone-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.phone :as phone]
            [rragent.recordings :as recordings]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(deftest codes-test
  (let [code (phone/new-code! "7")]
    (is (re-matches #"[0-9a-f]{12}" code))
    (is (= "7" (phone/take-code! code)) "the code names its bot")
    (is (nil? (phone/take-code! code)) "once")
    (is (nil? (phone/take-code! "nope")))))

(deftest status-test
  (is (nil? (phone/status nil)))
  (is (nil? (phone/status {:device "Pixel 8" :seen (- (js/Date.now) 60000)})) "an old hello is no phone")
  (is (= {:device "Pixel 8" :facing "environment"}
         (phone/status {:device "Pixel 8" :facing "environment" :seen (js/Date.now)}))))

(deftest origin-test
  (is (= "http://localhost:8080" (phone/origin {:headers {"host" "localhost:8080"}})))
  (is (= "https://a-b365p.sprites.app" (phone/origin {:headers {"host" "127.0.0.1:8080" "x-forwarded-host" "a-b365p.sprites.app" "x-forwarded-proto" "https"}}))))

(defn- read-event
  "The first event of an SSE body, `[event data]`."
  [^js response]
  (let [reader (.getReader (.-body response))
        decoder (js/TextDecoder.)]
    ((^:async fn []
       (loop [text ""]
         (let [chunk (await (.read reader))
               text (str text (.decode decoder (.-value chunk)))]
           (if-let [[_ event data] (re-find #"event: (\w+)\ndata: ([^\n]+)\n\n" text)]
             (do (.cancel reader)
                 [event (js->clj (js/JSON.parse data))])
             (if (.-done chunk)
               nil
               (recur text)))))))))

(deftest server-test
  (async done
    (-> ((^:async fn []
           (let [dir (path/join (fs/mkdtempSync (path/join (os/tmpdir) "rragent-phone-")) "artifacts")
                 db (atom {})
                 ctx {:db db :path [] :bot "7"}
                 register [{:ring/route [:post "/bot/7/phone/*"]
                            :ring/handler (fn [w] (phone/post! ctx (get-in w [:ring/route-params :*]) w))}
                           {:ring/route [:get "/bot/7/phone/events"]
                            :ring/handler (fn [w] (phone/events-handler "7" w))}
                           {:ring/route [:get "/pair/*"]
                            :ring/handler (fn [w] (phone/pair-handler "secret" w))}
                           {:ring/route [:post "/bot/7/record/*"]
                            :ring/handler (fn [w] (recordings/handler dir {:phone-done? #(phone/done? db [] %)} w))}
                           {:ring/route [:get "/bot/7/record/*"]
                            :ring/handler (fn [w] (recordings/get-handler dir w))}]
                 server (await (http/listen (http/ring-handler (constantly register)) 0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 post (^:async fn [subpath value & [type]]
                        (let [response (await (js/fetch (str base subpath)
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" (or type "application/transit+json")}
                                                             :body (if (string? value) value (transit/write-str value))}))]
                          [(.-status response) (transit/read-str (await (.text response)))]))
                 stamp "2026-10-21T20-00-00"]
             (try
               (testing "a pairing code and its link, taken by the phone once, which is let in and sent to its page"
                 (let [[status {:keys [code url]}] (await (post "/bot/7/phone/code" {}))]
                   (is (= 200 status))
                   (is (= (str base "/pair/" code) url) "the link is where the laptop reached the machine")
                   (let [response (await (js/fetch url #js {:redirect "manual"}))]
                     (is (= 302 (.-status response)))
                     (is (= "/bot/7/phone" (.get (.-headers response) "location")))
                     (is (str/starts-with? (str (.get (.-headers response) "set-cookie")) "rragent=") "the token's cookie"))
                   (is (= 404 (.-status (await (js/fetch url #js {:redirect "manual"})))) "the code is gone")))
               (testing "the phone's hello is in the bot's map, the panel sees it while fresh"
                 (is (= [200 {:ok true}] (await (post "/bot/7/phone/hello" {:device "Pixel 8" :facing "environment"}))))
                 (is (= {:device "Pixel 8" :facing "environment"} (phone/status (:phone @db)))))
               (testing "start and stop reach a phone listening, with the stamp"
                 (is (= [200 {:phones 0}] (await (post "/bot/7/phone/start" {:stamp stamp}))) "nobody listens yet")
                 (let [response (await (js/fetch (str base "/bot/7/phone/events")))
                       event (read-event response)]
                   (is (= 200 (.-status response)))
                   (is (= "text/event-stream" (.get (.-headers response) "content-type")))
                   ;; the listener is registered once the head is out
                   (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                   (is (= [200 {:phones 1}] (await (post "/bot/7/phone/start" {:stamp stamp}))))
                   (is (= ["start" {"stamp" stamp}] (await event)))
                   (is (= [400 {:error :no-stamp}] (await (post "/bot/7/phone/start" {}))))))
               (testing "the end waits for the phone's file: 409 until the phone says it is up"
                 (do
                   (is (= [200 {:received 5}] (await (post (str "/bot/7/record/" stamp "/screen?offset=0") "12345" "video/webm"))))
                   (is (= [200 {:received 5}] (await (post (str "/bot/7/record/" stamp "/phone?offset=0&ext=mp4") "12345" "video/mp4"))))
                   (is (= [400 {:error :bad-piece}] (await (post (str "/bot/7/record/" stamp "/phone?offset=5&ext=mkv") "x" "video/mp4"))) "an extension that is not ours")
                   (is (= [409 {:error :phone-uploading}]
                          (await (post (str "/bot/7/record/" stamp "/finish") {:started {} :files {"screen" "webm" "phone" "mp4"}}))))
                   (is (= [200 {:ok true}] (await (post "/bot/7/phone/done" {:stamp stamp}))))
                   (let [[status value] (await (post (str "/bot/7/record/" stamp "/finish") {:started {} :files {"screen" "webm" "phone" "mp4"}}))]
                     (is (= 200 status))
                     (is (= {:folder (str "recordings/" stamp) :files ["phone.mp4" "screen.webm"]} value))
                     (is (= ["phone.mp4" "recording.json" "screen.webm"] (sort (array-seq (fs/readdirSync (recordings/folder dir stamp)))))))
                   (is (= [400 {:error :bad-files}] (await (post (str "/bot/7/record/" stamp "/finish") {:started {} :files {}}))) "no files is no recording")
                   (is (= [400 {:error :bad-files}] (await (post (str "/bot/7/record/" stamp "/finish") {:started {} :files {"tablet" "mp4"}}))) "a name that is not ours")))
               (testing "a test clip of the phone: asked for, sent to the hidden test folder, finished by the phone, named for the laptop, served"
                 (let [response (await (js/fetch (str base "/bot/7/phone/events")))
                       event (read-event response)]
                   (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                   (is (= [200 {:phones 1}] (await (post "/bot/7/phone/test" {:seconds 5}))))
                   (is (= ["test" {"stamp" "test" "seconds" 5}] (await event))))
                 (is (= [200 {:received 3}] (await (post "/bot/7/record/test/phone?offset=0&ext=webm" "abc" "video/webm"))))
                 (is (= [200 {:received 3}] (await (post "/bot/7/record/test/phone?offset=0&ext=webm" "xyz" "video/webm"))) "the first piece starts the file over")
                 (is (= [200 {:folder "recordings/test" :files ["phone.webm"]}]
                        (await (post "/bot/7/record/test/finish" {:files {"phone" "webm"} :test true}))) "the phone ends its own test, nothing to wait for")
                 (is (= [200 {:ok true}] (await (post "/bot/7/phone/done" {:stamp "test" :file "phone.webm"}))))
                 (is (= "phone.webm" (:file (phone/test-of db []))))
                 (is (= ".test" (path/basename (recordings/folder dir "test"))) "hidden from the pane")
                 (let [served (await (js/fetch (str base "/bot/7/record/test/phone.webm")))]
                   (is (= 200 (.-status served)))
                   (is (= "xyz" (await (.text served)))))
                 (is (= 404 (.-status (await (js/fetch (str base "/bot/7/record/test/recording.json"))))) "only the files of a recording are served")
                 (is (= 404 (.-status (await (js/fetch (str base "/bot/7/record/..%2F..%2Fx/phone.webm")))))))
               (finally
                 (.close server))))))
        (.catch (fn [error]
                  (is (nil? error) (str error))))
        (.finally done))))
