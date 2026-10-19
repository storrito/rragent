(ns rragent.media-test
  "The image, voice and music tools against a fake OpenRouter: the
   routes answer the shapes the real one answers, the files land under
   artifacts/."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.render-test :as render-test]
            [rragent.tools.media :as media]))

(def png-b64
  (.toString (js/Buffer.from "a png") "base64"))

(defn- answer!
  "The response of the fake for one request."
  [^js req ^js res]
  (cond
    (not= "Bearer k3y" (aget (.-headers req) "authorization"))
    (do (.writeHead res 401 #js {"Content-Type" "application/json"})
        (.end res (js/JSON.stringify #js {:error #js {:message "No key"}})))

    (= "/v1/images" (.-url req))
    (do (.writeHead res 200 #js {"Content-Type" "application/json"})
        (.end res (js/JSON.stringify #js {:data #js [#js {:b64_json png-b64 :media_type "image/png"}]
                                          :usage #js {:cost 0.07}})))

    (= "/v1/audio/speech" (.-url req))
    (do (.writeHead res 200 #js {"Content-Type" "audio/mpeg"})
        (.end res (js/Buffer.from "mp3 bytes")))

    (= "/v1/chat/completions" (.-url req))
    (do (.writeHead res 200 #js {"Content-Type" "text/event-stream"})
        (.end res (str "data: " (js/JSON.stringify #js {:choices #js [#js {:delta #js {:audio #js {:data (.toString (js/Buffer.from "mu") "base64")}}}]}) "\n\n"
                       "data: " (js/JSON.stringify #js {:choices #js [#js {:delta #js {:audio #js {:data (.toString (js/Buffer.from "sic") "base64")}}}] :usage #js {:cost 0.04}}) "\n\n"
                       "data: [DONE]\n\n")))

    :else
    (do (.writeHead res 404)
        (.end res ""))))

(defn- fake-openrouter
  "Answers `/images` with one picture, `/audio/speech` with bytes,
   `/chat/completions` with a stream of audio chunks; 401 without the
   key. Every request is noted in `seen`."
  [seen]
  (http/createServer
   (fn [^js req ^js res]
     (let [chunks #js []]
       (.on req "data" (fn [c] (.push chunks c)))
       (.on req "end" (fn []
                        (let [body (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8"))]
                          (swap! seen conj {:url (.-url req)
                                            :auth (aget (.-headers req) "authorization")
                                            :body (js->clj body :keywordize-keys true)})
                          (answer! req res))))))))

(deftest media-test
  (async done
    (let [seen (atom [])
          server (fake-openrouter seen)
          env (.-env js/process)
          before {:url (aget env "OPENROUTER_BASE_URL") :key (aget env "OPENROUTER_API_KEY")}
          dir (render-test/temp-dir)]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (aset env "OPENROUTER_BASE_URL" (str "http://127.0.0.1:" (.-port (.address server)) "/v1/"))
             (aset env "OPENROUTER_API_KEY" "k3y")
             (fs/mkdirSync (path/join dir "card"))
             (testing "a picture, with the ratio, lands under artifacts/"
               (let [line (await (media/image! {:dir dir :work (path/dirname dir) :prompt "a pool" :file "card/pool" :ratio "9:16"}))]
                 (is (str/starts-with? line "artifacts/card/pool.png: 5 bytes, $0.070, model "))
                 (is (= "a png" (fs/readFileSync (path/join dir "card" "pool.png") "utf8")))
                 (is (= "9:16" (get-in (last @seen) [:body :aspect_ratio])))))
             (testing "a picture from another, sent along as a data URL"
               (await (media/image! {:dir dir :work (path/dirname dir) :prompt "as a drawing" :file "drawing.png" :from "card/pool.png"}))
               (is (str/starts-with? (get-in (last @seen) [:body :input_references 0 :image_url :url]) "data:image/png;base64,"))
               (is (fs/existsSync (path/join dir "drawing.png"))))
             (testing "a voice into the project's folder"
               (let [line (await (media/voice! {:dir dir :project "card" :text "Hello there" :voice "george"}))]
                 (is (str/starts-with? line "artifacts/card/voice.mp3: 9 bytes"))
                 (is (str/includes? line "voice george"))
                 (is (= "george" (get-in (last @seen) [:body :voice])))
                 (is (= "mp3 bytes" (fs/readFileSync (path/join dir "card" "voice.mp3") "utf8")))))
             (testing "music from the audio chunks of the stream"
               (let [line (await (media/music! {:dir dir :project "card" :prompt "calm piano" :file "calm.mp3"}))]
                 (is (str/starts-with? line "artifacts/card/calm.mp3: 5 bytes"))
                 (is (str/includes? line "$0.040"))
                 (is (= "music" (fs/readFileSync (path/join dir "card" "calm.mp3") "utf8")))
                 (is (= ["text" "audio"] (get-in (last @seen) [:body :modalities])))))
             (testing "a project that does not exist, a path above artifacts/, a bad key"
               (is (str/includes? (.-message (await (.catch (media/voice! {:dir dir :project "nope" :text "x"}) identity))) "does not exist"))
               (is (str/includes? (.-message (await (.catch (media/image! {:dir dir :work dir :prompt "x" :file "../x.png"}) identity))) "path under artifacts/"))
               (aset env "OPENROUTER_API_KEY" "wrong")
               (is (str/includes? (.-message (await (.catch (media/music! {:dir dir :project "card" :prompt "x"}) identity))) "answered 401: No key")))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (if (:url before) (aset env "OPENROUTER_BASE_URL" (:url before)) (js-delete env "OPENROUTER_BASE_URL"))
                      (if (:key before) (aset env "OPENROUTER_API_KEY" (:key before)) (js-delete env "OPENROUTER_API_KEY"))
                      (fs/rmSync dir #js {:recursive true :force true})
                      (.close server)
                      (done)))))))
