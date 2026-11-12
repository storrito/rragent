(ns rragent.branding-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.branding :as branding]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(def png
  "A 1x1 PNG."
  (js/Buffer.from "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=" "base64"))

(def svg
  (js/Buffer.from "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><circle cx=\"4\" cy=\"4\" r=\"4\"/></svg>" "utf8"))

(defn- make-home
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-branding-")))

(defn- logo-files
  [home]
  (try
    (vec (sort (array-seq (fs/readdirSync (branding/dir home)))))
    (catch :default _
      [])))

(deftest clean-test
  (is (= "Acme" (branding/clean "  Acme \n")))
  (is (= "Acme Social Studio" (branding/clean "Acme\tSocial\n Studio")) "one line")
  (is (nil? (branding/clean "   ")))
  (is (nil? (branding/clean nil)))
  (is (= branding/max-chars (count (branding/clean (apply str (repeat 100 "x"))))))
  (is (= "abc def" (branding/clean (str "abc" (apply str (repeat 60 " ")) "def"))) "one space for a run of them")
  (is (= (apply str (repeat 39 "x")) (branding/clean (str (apply str (repeat 39 "x")) " tail"))) "no space at the cut"))

(deftest extension-test
  (is (= "png" (branding/extension-for "image/png" nil)))
  (is (= "jpg" (branding/extension-for "image/jpeg" "logo.png")) "the type the browser named wins")
  (is (= "svg" (branding/extension-for "image/svg+xml" "logo")))
  (is (= "svg" (branding/extension-for "application/octet-stream" "Logo.SVG")) "the name, for a file the browser types poorly")
  (is (= "jpg" (branding/extension-for "" "photo.jpeg")))
  (is (nil? (branding/extension-for "application/pdf" "logo.pdf")))
  (is (nil? (branding/extension-for nil nil))))

(deftest saved-test
  (let [home (make-home)]
    (try
      (testing "nothing saved"
        (is (= {} (branding/saved home)))
        (is (= {} (branding/saved (path/join home "nowhere")))))
      (testing "a name"
        (is (= {:name "Acme"} (branding/save-name! home "  Acme  ")))
        (is (= {:name "Acme"} (branding/saved home)))
        (is (= {"name" "Acme"} (js->clj (js/JSON.parse (fs/readFileSync (branding/file home) "utf8"))))))
      (testing "a logo beside it, its file named by the hash of its bytes"
        (let [{:keys [name logo]} (branding/save-logo! home png "png")]
          (is (= "Acme" name))
          (is (re-matches #"[0-9a-f]{32}\.png" logo))
          (is (= [logo] (logo-files home)))
          (is (= 0 (.compare png (fs/readFileSync (path/join (branding/dir home) logo)))))
          (is (= (str "/branding/logo/" logo) (branding/logo-url logo)))
          (testing "the same picture again is the same file"
            (is (= logo (:logo (branding/save-logo! home png "png")))))
          (testing "another one takes its place, the file of before goes"
            (let [other (:logo (branding/save-logo! home svg "svg"))]
              (is (str/ends-with? other ".svg"))
              (is (not= logo other))
              (is (= [other] (logo-files home)))))))
      (testing "a blank name brings the default back, the logo stays"
        (let [saved (branding/save-name! home " ")]
          (is (nil? (:name saved)))
          (is (some? (:logo saved)))))
      (testing "the logo removed"
        (is (= {} (branding/remove-logo! home)))
        (is (= [] (logo-files home))))
      (testing "a file that says something else is nothing saved"
        (fs/writeFileSync (branding/file home) "not json")
        (is (= {} (branding/saved home)))
        (fs/writeFileSync (branding/file home) "[1]")
        (is (= {} (branding/saved home)))
        (fs/writeFileSync (branding/file home) "{\"name\": 7, \"logo\": \"../secrets.json\"}")
        (is (= {} (branding/saved home)) "no name that is no text, no logo outside its folder")
        (fs/writeFileSync (branding/file home) (str "{\"name\": \"Acme\", \"logo\": \"" (apply str (repeat 32 "a")) ".png\"}"))
        (is (= {:name "Acme"} (branding/saved home)) "a logo whose file is gone is none"))
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))

(deftest server-test
  (async done
    (-> ((^:async fn []
           (let [home (make-home)
                 routes [{:ring/route [:post branding/logo-path]
                          :ring/handler (fn [w] (branding/upload-handler home w))}
                         {:ring/route [:get (str branding/logo-path "/*")]
                          :ring/handler (fn [w] (branding/logo-handler home w))}]
                 server (await (http/listen (http/ring-handler (constantly routes)) 0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 post (^:async fn [type body & [query]]
                        (let [response (await (js/fetch (str base branding/logo-path (when query (str "?" query)))
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" type}
                                                             :body body}))]
                          {:status (.-status response)
                           :body (transit/read-str (await (.text response)))}))]
             (try
               (testing "a picture becomes the logo"
                 (let [{:keys [status body]} (await (post "image/png" png))
                       logo (:logo body)]
                   (is (= 200 status))
                   (is (re-matches #"[0-9a-f]{32}\.png" logo))
                   (is (= {:logo logo} (branding/saved home)))
                   (testing "and is served, for the browser to keep, with no script allowed"
                     (let [got (await (js/fetch (str base (branding/logo-url logo))))]
                       (is (= 200 (.-status got)))
                       (is (= "image/png" (.get (.-headers got) "content-type")))
                       (is (str/includes? (.get (.-headers got) "cache-control") "immutable"))
                       (is (str/includes? (.get (.-headers got) "content-security-policy") "sandbox"))
                       (is (= 0 (.compare png (js/Buffer.from (await (.arrayBuffer got))))))))
                   (testing "an SVG named by its file takes its place; the address of before is gone"
                     (let [{:keys [status body]} (await (post "application/octet-stream" svg "name=Acme%20Logo.svg"))]
                       (is (= 200 status))
                       (is (str/ends-with? (:logo body) ".svg"))
                       (is (= "image/svg+xml" (.get (.-headers (await (js/fetch (str base (branding/logo-url (:logo body)))))) "content-type")))
                       (is (= 404 (.-status (await (js/fetch (str base (branding/logo-url logo)))))))))))
               (testing "no other file of the machine's folder is served"
                 (fs/writeFileSync (path/join home "secrets.json") "{}")
                 (fs/writeFileSync (path/join (branding/dir home) "note.txt") "x")
                 (is (= 404 (.-status (await (js/fetch (str base branding/logo-path "/note.txt"))))))
                 (is (= 404 (.-status (await (js/fetch (str base branding/logo-path "/..%2Fsecrets.json")))))))
               (testing "what is no picture, too large or empty is not taken; the logo stays"
                 (let [before (branding/saved home)]
                   (is (= {:status 415 :body {:error :not-taken}} (await (post "application/pdf" png "name=logo.pdf"))))
                   (is (= {:status 413 :body {:error :too-large}}
                          (await (post "image/png" (js/Uint8Array. (inc branding/logo-max-bytes))))))
                   (is (= {:status 400 :body {:error :empty}} (await (post "image/png" (js/Uint8Array. 0)))))
                   (is (= before (branding/saved home)))))
               (finally
                 (.closeAllConnections server)
                 (await (js/Promise. (fn [resolve] (.close server resolve))))
                 (fs/rmSync home #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
