(ns rragent.shares-test
  "A folder behind a public link: the folder's name, what may be
   shared, the lookup by token with its moment, the files the link
   serves and the ones it keeps back, the handler's answers."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.shares :as shares]))

(deftest folder-name-test
  (is (= "gallery" (shares/folder-name "artifacts/gallery/")))
  (is (= "gallery" (shares/folder-name " gallery ")))
  (is (= "a/b" (shares/folder-name "/artifacts/a/b//")))
  (is (= "" (shares/folder-name nil))))

(defn- home!
  "A machine folder with the bot 7's artifacts: a gallery with an
   index, a picture and a notes folder, a hidden file, the notes of
   the bot, and a file beside the artifacts a link must not reach."
  []
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "shares-"))
        artifacts (path/join home "bots" "7" "work" "artifacts")]
    (fs/mkdirSync (path/join artifacts "gallery" "notes") #js {:recursive true})
    (fs/mkdirSync (path/join artifacts "gallery" "sub") #js {:recursive true})
    (fs/mkdirSync (path/join artifacts "notes") #js {:recursive true})
    (fs/mkdirSync (path/join artifacts ".hidden") #js {:recursive true})
    (fs/writeFileSync (path/join artifacts "gallery" "index.html") "<h1>Gallery</h1>")
    (fs/writeFileSync (path/join artifacts "gallery" "clip.bin") "0123456789")
    (fs/writeFileSync (path/join artifacts "gallery" "sub" "index.html") "<h1>Sub</h1>")
    (fs/writeFileSync (path/join artifacts "gallery" ".secret") "s")
    (fs/writeFileSync (path/join artifacts "gallery" "notes" "draft.md") "d")
    (fs/writeFileSync (path/join artifacts "notes" "plan.md") "p")
    (fs/writeFileSync (path/join artifacts "loose.txt") "l")
    (fs/writeFileSync (path/join home "bots" "7" "AGENT.md") "# bot")
    (fs/symlinkSync (path/join home "bots" "7" "AGENT.md") (path/join artifacts "gallery" "escape.md"))
    {:home home :artifacts artifacts}))

(deftest shareable-test
  (let [{:keys [home artifacts]} (home!)]
    (try
      (is (true? (shares/shareable? artifacts "gallery")))
      (is (true? (shares/shareable? artifacts "gallery/sub")))
      (is (false? (shares/shareable? artifacts "")) "never the whole of the artifacts")
      (is (false? (shares/shareable? artifacts "notes")) "the bot's notes")
      (is (false? (shares/shareable? artifacts "notes/x")))
      (is (false? (shares/shareable? artifacts ".hidden")))
      (is (false? (shares/shareable? artifacts "loose.txt")) "a file is no folder")
      (is (false? (shares/shareable? artifacts "missing")))
      (is (false? (shares/shareable? artifacts "../../AGENT.md")))
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))

(def doc
  #js {"tok" #js {:bot 7 :folder "gallery" :createdAt 1000}
       "old" #js {:bot 7 :folder "old" :createdAt 2000 :until 5000}
       "other" #js {:bot 8 :folder "gallery" :createdAt 3000}})

(deftest lookup-test
  (testing "a token's share, none for a stranger, none past its moment"
    (is (= {:token "tok" :bot 7 :folder "gallery" :created 1000} (shares/lookup doc "tok" 9000)))
    (is (= {:token "old" :bot 7 :folder "old" :created 2000 :until 5000} (shares/lookup doc "old" 4999)))
    (is (nil? (shares/lookup doc "old" 5000)))
    (is (nil? (shares/lookup doc "nope" 0)))
    (is (nil? (shares/lookup doc "" 0)))
    (is (nil? (shares/lookup nil "tok" 0))))
  (testing "a bot's share of a folder, and its list, newest first"
    (is (= "tok" (:token (shares/of doc 7 "gallery"))))
    (is (= "other" (:token (shares/of doc "8" "gallery"))))
    (is (nil? (shares/of doc 7 "missing")))
    (is (= ["old" "tok"] (map :token (shares/listed doc 7))))))

(deftest url-test
  (let [before (aget (.-env js/process) "RRAGENT_URL")]
    (try
      (aset (.-env js/process) "RRAGENT_URL" "https://m.example.org/")
      (is (= "https://m.example.org/s/tok/" (shares/url "tok")))
      (is (= "artifacts/gallery/ at https://m.example.org/s/tok/" (shares/line {:token "tok" :folder "gallery"})))
      (is (str/ends-with? (shares/line {:token "tok" :folder "gallery" :until 5000}) "(until 1970-01-01T00:00:05.000Z)"))
      (js-delete (.-env js/process) "RRAGENT_URL")
      (is (= "/s/tok/" (shares/url "tok")) "the path alone without the page's address")
      (finally
        (if before
          (aset (.-env js/process) "RRAGENT_URL" before)
          (js-delete (.-env js/process) "RRAGENT_URL"))))))

(deftest file-test
  (let [{:keys [home artifacts]} (home!)
        share {:bot 7 :folder "gallery"}
        file (fn [subpath] (some-> (shares/file home share subpath) (str/replace (fs/realpathSync artifacts) "")))]
    (try
      (is (= "/gallery/index.html" (file "")) "the root is the index")
      (is (= "/gallery/index.html" (file "index.html")))
      (is (= "/gallery/sub/index.html" (file "sub/")) "a folder's slash is its index")
      (is (= "/gallery/clip.bin" (file "clip.bin")))
      (is (nil? (file "sub")) "a folder itself is no file")
      (is (nil? (file "missing.png")))
      (is (nil? (file ".secret")) "hidden")
      (is (nil? (file "%2Esecret")) "hidden, encoded")
      (is (nil? (file "notes/draft.md")) "a note")
      (is (nil? (file "../loose.txt")) "out of the folder")
      (is (nil? (file "..%2F..%2F..%2FAGENT.md")))
      (is (nil? (file "escape.md")) "a link out of the folder")
      (is (nil? (file "%ZZ")) "no path at all")
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))

(defn- ^:async answer
  "The handler's response for a request of `method` to `/s/<rest>`."
  [home shares method rest & [headers]]
  (:ring/response (await (shares/handler {:home home :shares (fn [] (js/Promise.resolve shares))}
                                         {:ring/request {:request-method method :uri (str "/s/" rest) :headers (or headers {})}
                                          :ring/route-params {:* rest}}))))

(defn- ^:async text
  [stream]
  (let [chunks (atom [])]
    (await (js/Promise. (fn [resolve reject]
                          (.on stream "data" #(swap! chunks conj (str %)))
                          (.on stream "end" resolve)
                          (.on stream "error" reject))))
    (str/join @chunks)))

(deftest handler-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [home]} (home!)]
             (try
               (testing "the index for the bare link, with the public headers; the bare token sent to its slash"
                 (let [{:keys [status headers body]} (await (answer home doc :get "tok/"))]
                   (is (= 200 status))
                   (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
                   (is (= "noindex, nofollow" (get headers "X-Robots-Tag")))
                   (is (= "no-referrer" (get headers "Referrer-Policy")))
                   (is (= "nosniff" (get headers "X-Content-Type-Options")))
                   (is (= "<h1>Gallery</h1>" (await (text body)))))
                 (let [{:keys [status headers]} (await (answer home doc :get "tok"))]
                   (is (= 302 status))
                   (is (= "/s/tok/" (get headers "Location")))))
               (testing "a HEAD carries the headers and no body; a range its bytes; bytes the file has not a 416"
                 (let [{:keys [status headers body]} (await (answer home doc :head "tok/clip.bin"))]
                   (is (= 200 status))
                   (is (= 10 (get headers "Content-Length")))
                   (is (nil? body)))
                 (let [{:keys [status headers body]} (await (answer home doc :get "tok/clip.bin" {"range" "bytes=2-5"}))]
                   (is (= 206 status))
                   (is (= "bytes 2-5/10" (get headers "Content-Range")))
                   (is (= "2345" (await (text body)))))
                 (let [{:keys [status headers]} (await (answer home doc :get "tok/clip.bin" {"range" "bytes=10-"}))]
                   (is (= 416 status))
                   (is (= "bytes */10" (get headers "Content-Range")))))
               (testing "nothing for a stranger's token, a share past its moment, a note, a hidden file, a path out of the folder"
                 (doseq [rest ["nope/index.html" "old/index.html" "tok/notes/draft.md" "tok/.secret" "tok/..%2Floose.txt" "tok/missing.png" "tok/sub"]]
                   (let [{:keys [status headers]} (await (answer home doc :get rest))]
                     (is (= 404 status) rest)
                     (is (= "noindex, nofollow" (get headers "X-Robots-Tag")) rest))))
               (finally
                 (fs/rmSync home #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
