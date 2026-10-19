(ns rragent.uploads-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]
            [rragent.uploads :as uploads]))

(defn- bytes-of
  "`n` bytes with a pattern."
  [n]
  (js/Uint8Array.from (clj->js (map #(mod % 251) (range n)))))

(defn- visible-files
  [dir]
  (sort (remove #(.startsWith % ".") (array-seq (fs/readdirSync dir)))))

(defn- part-files
  [dir]
  (filter #(.endsWith % ".part") (array-seq (fs/readdirSync dir))))

(def png
  "A 1x1 PNG."
  (js/Buffer.from "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=" "base64"))

(deftest kind-test
  (is (= :video (uploads/kind "ab12.mp4")))
  (is (= :video (uploads/kind "ab12.MOV")))
  (is (= :audio (uploads/kind "ff00.wav")))
  (is (= :image (uploads/kind "ff00.png")))
  (is (nil? (uploads/kind "notes.txt")))
  (is (nil? (uploads/kind "notes"))))

(deftest lines-test
  (let [text "Make a short of this\nvideo: uploads/ab12cd.mp4\naudio: uploads/ff00.wav\nimage: uploads/0a0b.png"]
    (testing "the lines that name files"
      (is (= [{:kind :video :name "ab12cd.mp4"}
              {:kind :audio :name "ff00.wav"}
              {:kind :image :name "0a0b.png"}]
             (uploads/attachments text)))
      (is (= "Make a short of this" (uploads/without-lines text))))
    (testing "a message without files is left as it is"
      (is (= [] (uploads/attachments "video: not a path")))
      (is (= "video: not a path" (uploads/without-lines "video: not a path"))))
    (testing "the line of a file"
      (is (= "video: uploads/ab12cd.mp4" (uploads/line :video "ab12cd.mp4"))))))

(deftest content-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-uploads-"))]
    (try
      (fs/writeFileSync (path/join dir "0a0b.png") png)
      (fs/writeFileSync (path/join dir "ab12.mp4") (bytes-of 10))
      (testing "only the files that are there, by the names the composer sent"
        (is (= [{:kind :image :name "0a0b.png"} {:kind :video :name "ab12.mp4"}]
               (uploads/named dir ["0a0b.png" "ab12.mp4" "missing.mp4" "../etc/passwd" "notes.txt"]))))
      (let [attachments (uploads/named dir ["ab12.mp4" "0a0b.png"])]
        (testing "a clip is a line, a picture a line and an image block when the model takes images"
          (is (= "Cut this\nvideo: uploads/ab12.mp4" (uploads/content dir "Cut this" [{:kind :video :name "ab12.mp4"}] true)))
          (let [[text image] (uploads/content dir "Cut this " attachments true)]
            (is (= {:type "text" :text "Cut this\nvideo: uploads/ab12.mp4\nimage: uploads/0a0b.png"} text))
            (is (= "image" (:type image)))
            (is (= "image/png" (:mimeType image)))
            (is (= (.toString png "base64") (:data image)))))
        (testing "a picture for a model without image input: the line and a note, no block"
          (is (= (str "video: uploads/ab12.mp4\nimage: uploads/0a0b.png\n" uploads/no-vision-note)
                 (uploads/content dir "" attachments false)))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest server-test
  (async done
    (-> ((^:async fn []
           (let [dir (path/join (fs/mkdtempSync (path/join (os/tmpdir) "rragent-uploads-")) "uploads")
                 server (await (http/listen (http/ring-handler (constantly (uploads/routes dir))) 0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 post (^:async fn [type body & [query]]
                        (let [response (await (js/fetch (str base "/upload" (when query (str "?" query)))
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" type}
                                                             :body body}))]
                          [(.-status response) (transit/read-str (await (.text response)))]))
                 id "00112233445566778899aabbccddeeff"
                 content (bytes-of 25)]
             (try
               (testing "pieces in order make the clip, the last answer names it"
                 (is (= [200 {:received 10}] (await (post "video/quicktime" (.slice content 0 10) (str "id=" id "&offset=0&total=25")))))
                 (is (= [409 {:error :out-of-order :received 10}] (await (post "video/quicktime" (.slice content 5 15) (str "id=" id "&offset=5&total=25")))) "a piece must start where the part ends")
                 (is (= [200 {:received 20}] (await (post "video/quicktime" (.slice content 10 20) (str "id=" id "&offset=10&total=25")))))
                 (is (= [409 {:error :out-of-order :received 20}] (await (post "video/quicktime" (.slice content 10 20) (str "id=" id "&offset=10&total=25")))))
                 (let [[status {:keys [name url kind size]}] (await (post "video/quicktime" (.slice content 20 25) (str "id=" id "&offset=20&total=25")))]
                   (is (= 200 status))
                   (is (= (str id ".mov") name))
                   (is (= (str "/uploads/" id ".mov") url))
                   (is (= :video kind))
                   (is (= 25 size))
                   (is (= (vec content) (vec (fs/readFileSync (path/join dir name)))))
                   (is (= [name] (visible-files dir)))
                   (is (empty? (part-files dir)))
                   (testing ", and answers the last piece sent again, whose answer was lost, with the clip"
                     (is (= [200 {:name name :url url :kind :video :size 25}]
                            (await (post "video/quicktime" (.slice content 20 25) (str "id=" id "&offset=20&total=25")))))
                     (is (empty? (part-files dir))))))
               (testing "a piece longer than the total, nothing left"
                 (is (= [400 {:error :longer-than-total}] (await (post "video/mp4" (bytes-of 30) "id=ffeeddccbbaa99887766554433221100&offset=0&total=25"))))
                 (is (empty? (part-files dir))))
               (testing "a bad id, a total past the cap"
                 (is (= 400 (first (await (post "video/mp4" (bytes-of 3) "id=../../x&offset=0&total=3")))))
                 (is (= [413 {:error :too-large}] (await (post "video/mp4" (bytes-of 3) (str "id=" id "&offset=0&total=" (inc uploads/max-bytes)))))))
               (testing "a clip in one request is named by its hash; the same bytes again are the same file"
                 (let [[status {:keys [name kind size]}] (await (post "audio/mpeg; charset=binary" (bytes-of 3000)))]
                   (is (= 200 status))
                   (is (re-matches #"[0-9a-f]{32}\.mp3" name))
                   (is (= :audio kind))
                   (is (= 3000 size))
                   (is (= name (:name (second (await (post "audio/mpeg" (bytes-of 3000)))))))
                   (is (= 2 (count (visible-files dir))))))
               (testing "a picture is content-addressed"
                 (let [[status {:keys [name url kind]}] (await (post "image/png" png))]
                   (is (= 200 status))
                   (is (re-matches #"[0-9a-f]{32}\.png" name))
                   (is (= (str "/uploads/" name) url))
                   (is (= :image kind))
                   (is (= name (:name (second (await (post "image/png" png))))))
                   (is (= 3 (count (visible-files dir))))
                   (testing "and served, with ranges for the players"
                     (let [whole (await (js/fetch (str base "/uploads/" name)))
                           part (await (js/fetch (str base "/uploads/" name) #js {:headers #js {:range "bytes=1-3"}}))]
                       (is (= 200 (.-status whole)))
                       (is (= "image/png" (.get (.-headers whole) "content-type")))
                       (is (= (vec png) (vec (js/Buffer.from (await (.arrayBuffer whole))))))
                       (is (= 206 (.-status part)))
                       (is (= (str "bytes 1-3/" (.-length png)) (.get (.-headers part) "content-range")))
                       (is (= "PNG" (await (.text part))))))))
               (testing "what is not served: a missing file, a part file, a path above the folder"
                 (fs/writeFileSync (path/join dir ".abc.mp4.part") "x")
                 (is (= 404 (.-status (await (js/fetch (str base "/uploads/missing.png"))))))
                 (is (= 404 (.-status (await (js/fetch (str base "/uploads/.abc.mp4.part"))))))
                 (is (= 404 (.-status (await (js/fetch (str base "/uploads/../AGENT.md"))))))
                 (fs/rmSync (path/join dir ".abc.mp4.part")))
               (testing "what is refused: another type, an empty body"
                 (is (= [415 {:error :not-media}] (await (post "application/zip" (bytes-of 10)))))
                 (is (= [400 {:error :empty}] (await (post "video/mp4" (js/Uint8Array. 0)))))
                 (is (= [400 {:error :empty}] (await (post "image/png" (js/Uint8Array. 0)))))
                 (is (empty? (part-files dir))))
               (finally
                 (.close server)
                 (fs/rmSync (path/dirname dir) #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
