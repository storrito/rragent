(ns rragent.media-test
  "The image, voice and music tools against a fake OpenRouter, both
   ways: at OPENROUTER_BASE_URL with OPENROUTER_API_KEY when no gateway
   is around, and through the fake gateway's `openrouter` connection
   with no key of its own (the fake refuses a request that carries an
   Authorization header). The routes answer the shapes the real one
   answers, the files land under artifacts/."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.render-test :as render-test]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.tools.media :as media]))

(def none
  "No gateway: a port that refuses at once."
  "http://127.0.0.1:1")

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

(defn- set-var!
  [k v]
  (if (nil? v) (js-delete (.-env js/process) k) (aset (.-env js/process) k v)))

(defn- message-of
  "The message of the throw of `promise`."
  [promise]
  (.catch promise (fn [e] (.-message e))))

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
             ;; no gateway: the key of the environment
             (let [codemode (codemode/handle none)]
               (testing "a picture, with the ratio, lands under artifacts/"
                 (let [line (await (media/image! {:codemode codemode :dir dir :work (path/dirname dir) :prompt "a pool" :file "card/pool" :ratio "9:16"}))]
                   (is (str/starts-with? line "artifacts/card/pool.png: 5 bytes, $0.070, model "))
                   (is (= "a png" (fs/readFileSync (path/join dir "card" "pool.png") "utf8")))
                   (is (= "Bearer k3y" (:auth (last @seen))))
                   (is (= "9:16" (get-in (last @seen) [:body :aspect_ratio])))))
               (testing "a picture from another, sent along as a data URL"
                 (await (media/image! {:codemode codemode :dir dir :work (path/dirname dir) :prompt "as a drawing" :file "drawing.png" :from "card/pool.png"}))
                 (is (str/starts-with? (get-in (last @seen) [:body :input_references 0 :image_url :url]) "data:image/png;base64,"))
                 (is (fs/existsSync (path/join dir "drawing.png"))))
               (testing "a voice into the project's folder"
                 (let [line (await (media/voice! {:codemode codemode :dir dir :project "card" :text "Hello there" :voice "george"}))]
                   (is (str/starts-with? line "artifacts/card/voice.mp3: 9 bytes"))
                   (is (str/includes? line "voice george"))
                   (is (= "george" (get-in (last @seen) [:body :voice])))
                   (is (= "mp3 bytes" (fs/readFileSync (path/join dir "card" "voice.mp3") "utf8")))))
               (testing "music from the audio chunks of the stream"
                 (let [line (await (media/music! {:codemode codemode :dir dir :project "card" :prompt "calm piano" :file "calm.mp3"}))]
                   (is (str/starts-with? line "artifacts/card/calm.mp3: 5 bytes"))
                   (is (str/includes? line "$0.040"))
                   (is (= "music" (fs/readFileSync (path/join dir "card" "calm.mp3") "utf8")))
                   (is (= ["text" "audio"] (get-in (last @seen) [:body :modalities])))))
               (testing "a project that does not exist, a path above artifacts/, a bad key"
                 (is (str/includes? (await (message-of (media/voice! {:codemode codemode :dir dir :project "nope" :text "x"}))) "does not exist"))
                 (is (str/includes? (await (message-of (media/image! {:codemode codemode :dir dir :work dir :prompt "x" :file "../x.png"}))) "path under artifacts/"))
                 (aset env "OPENROUTER_API_KEY" "wrong")
                 (is (str/includes? (await (message-of (media/music! {:codemode codemode :dir dir :project "card" :prompt "x"}))) "answered 401: No key")))
               (testing "neither a gateway nor a key: the throw says so, with what the tool wanted"
                 (js-delete env "OPENROUTER_API_KEY")
                 (is (= "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no music."
                        (await (message-of (media/music! {:codemode codemode :dir dir :project "card" :prompt "x"})))))
                 (is (= "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no picture."
                        (await (message-of (media/image! {:codemode codemode :dir dir :work dir :prompt "x" :file "x.png"})))))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (set-var! "OPENROUTER_BASE_URL" (:url before))
                      (set-var! "OPENROUTER_API_KEY" (:key before))
                      (fs/rmSync dir #js {:recursive true :force true})
                      (.close server)
                      (sync-fetch/stop!)
                      (done)))))))

(deftest media-through-gateway-test
  (async done
    (let [env (.-env js/process)
          before {:url (aget env "OPENROUTER_BASE_URL") :key (aget env "OPENROUTER_API_KEY")}
          dir (render-test/temp-dir)
          gateway (atom nil)]
      (-> ((^:async fn []
             (reset! gateway (await (fake-gateway/start!)))
             ;; a key in the environment besides: the gateway's connection wins,
             ;; and the fake refuses a request that carries an Authorization header
             (aset env "OPENROUTER_API_KEY" "k3y")
             (js-delete env "OPENROUTER_BASE_URL")
             (fs/mkdirSync (path/join dir "card"))
             (let [codemode (codemode/handle (:url @gateway))]
               (testing "a picture through the gateway's openrouter connection"
                 (is (str/starts-with? (await (media/image! {:codemode codemode :dir dir :work (path/dirname dir) :prompt "a pool" :file "card/pool.png"}))
                                       "artifacts/card/pool.png: 5 bytes, $0.070, model "))
                 (is (= "a png" (fs/readFileSync (path/join dir "card" "pool.png") "utf8"))))
               (testing "a voice"
                 (is (str/starts-with? (await (media/voice! {:codemode codemode :dir dir :project "card" :text "Hello there"}))
                                       "artifacts/card/voice.mp3: 9 bytes"))
                 (is (= "mp3 bytes" (fs/readFileSync (path/join dir "card" "voice.mp3") "utf8"))))
               (testing "music"
                 (let [line (await (media/music! {:codemode codemode :dir dir :project "card" :prompt "calm piano"}))]
                   (is (str/starts-with? line "artifacts/card/music.mp3: 5 bytes"))
                   (is (str/includes? line "$0.040"))
                   (is (= "music" (fs/readFileSync (path/join dir "card" "music.mp3") "utf8"))))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (set-var! "OPENROUTER_BASE_URL" (:url before))
                      (set-var! "OPENROUTER_API_KEY" (:key before))
                      (fs/rmSync dir #js {:recursive true :force true})
                      (when-let [{:keys [stop]} @gateway] (stop))
                      (sync-fetch/stop!)
                      (done)))))))
