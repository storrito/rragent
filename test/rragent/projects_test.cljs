(ns rragent.projects-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:stream" :refer [Readable]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.projects :as projects]
            [rragent.rframes.transit :as transit]
            [rragent.templates :as templates]
            [rragent.test-templates :as test-templates]))

(defn- temp-dir
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-projects-")))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(def fake-server
  "A project that answers its port, its environment and the request."
  (str "import http from 'node:http';\n"
       "const server = http.createServer((req, res) => {\n"
       "  let body = '';\n"
       "  req.on('data', (c) => { body += c; });\n"
       "  req.on('end', () => {\n"
       "    res.setHeader('Content-Type', 'text/plain');\n"
       "    res.end(`${req.method} ${req.url} base=${process.env.RRAGENT_BASE} pid=${process.env.RRAGENT_PID} ${body}`);\n"
       "  });\n"
       "});\n"
       "server.listen(Number(process.env.PORT), '127.0.0.1');\n"))

(defn- project!
  [dir name]
  (let [project-dir (path/join dir name)]
    (fs/mkdirSync project-dir #js {:recursive true})
    (fs/writeFileSync (path/join project-dir "package.json") "{\"type\": \"module\", \"main\": \"server.mjs\"}")
    (fs/writeFileSync (path/join project-dir "server.mjs") fake-server)
    project-dir))

(deftest names-test
  (let [dir (temp-dir)]
    (try
      (project! dir "b")
      (project! dir "a")
      (fs/mkdirSync (path/join dir "plain"))
      (project! dir ".hidden")
      (is (= ["a" "b"] (projects/names dir)))
      (is (= "server.mjs" (projects/main-file (path/join dir "a"))))
      (fs/writeFileSync (path/join dir "a" "package.json") "{\"main\": \"app.js\"}")
      (is (= "app.js" (projects/main-file (path/join dir "a"))))
      (is (= "server.mjs" (projects/main-file (path/join dir "plain"))) "no package.json, the default")
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest command-test
  (let [dir (temp-dir)
        {templates-dir :dir restore! :restore!} (test-templates/install! [test-templates/fake test-templates/static])]
    (try
      (project! dir "plain")
      (templates/copy! "fake" (path/join dir "card"))
      (templates/copy! "static" (path/join dir "page"))
      (testing "a project without a template runs with Node on its main file"
        (is (= {:cmd (.-execPath js/process) :args ["server.mjs"] :needs []} (projects/command (path/join dir "plain")))))
      (testing "a project of a template runs with the template's entry on its folder"
        (is (= {:cmd (path/join templates-dir "fake" "serve") :args [(path/join dir "card")] :needs ["chrome" "ffmpeg"]}
               (projects/command (path/join dir "card"))))
        (is (nil? (projects/static-template (path/join dir "card")))))
      (testing "a project of a static template has no process"
        (is (= "static" (:name (projects/static-template (path/join dir "page"))))))
      (testing "a template that is gone"
        (fs/writeFileSync (path/join dir "card" "package.json") "{\"template\": \"gone\"}")
        (is (thrown-with-msg? js/Error #"not installed" (projects/command (path/join dir "card")))))
      (finally
        (restore!)
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest template-project-test
  (async done
    (-> ((^:async fn []
           (let [dir (temp-dir)
                 {:keys [restore!]} (test-templates/install! [test-templates/fake test-templates/static])
                 db (atom {:artifacts/current "card"})
                 handle (projects/handle {:db db :dir dir :procs (atom {})})
                 w (fn [path]
                     {:ring/route-params {:* path}
                      :ring/request {:request-method :get :uri (str "/artifacts/" path) :headers {}}})]
             (templates/copy! "fake" (path/join dir "card"))
             (templates/copy! "static" (path/join dir "page"))
             (try
               (testing "the project of the template is started with the template's entry, the static one is ready as it is"
                 (await (projects/sync! handle))
                 (loop [n 0]
                   (when (and (< n 50) (nil? (get-in @db [:projects "card" :ready-at])))
                     (await (sleep 100))
                     (await (projects/sync! handle))
                     (recur (inc n))))
                 (is (number? (get-in @db [:projects "card" :ready-at])) (str (fs/readFileSync (path/join dir "card" ".log") "utf8")))
                 (is (true? (get-in @db [:projects "page" :static?])))
                 (is (= "index.html" (get-in @db [:projects "page" :entry])))
                 (is (number? (get-in @db [:projects "page" :ready-at])))
                 (is (nil? (get @(:procs handle) (path/join dir "page"))) "no process for a page")
                 (is (some? (get @(:procs handle) (path/join dir "card"))) "the table is keyed by the project's folder"))
               (testing "the bots of a machine share the table: another bot's sync leaves this bot's project alone"
                 (let [other (projects/handle {:db (atom {}) :dir (temp-dir) :procs (:procs handle)})
                       before (get @(:procs handle) (path/join dir "card"))]
                   (await (projects/sync! other))
                   (is (identical? (:process before) (:process (get @(:procs handle) (path/join dir "card")))) "the same process, not stopped")
                   (is (nil? (get-in @db [:projects "card" :exit])))
                   (projects/stop-all! other)
                   (is (some? (get @(:procs handle) (path/join dir "card"))) "stop-all! of the other bot stops only its own")))
               (testing "the page of the process, through the proxy"
                 (let [{:keys [status body]} (:ring/response (await (projects/handler handle (w "card/"))))]
                   (is (= 200 status))
                   (is (= (str "page of " (path/join dir "card") " GET /") (await (transit/read-body body))))))
               (testing "the static page, served as it is; its other files are left to the files route"
                 (let [{:keys [status body headers]} (:ring/response (await (projects/handler handle (w "page/"))))]
                   (is (= 200 status))
                   (is (str/starts-with? (get headers "Content-Type") "text/html"))
                   (is (= "<h1>static</h1>" (await (transit/read-body body)))))
                 (is (= 302 (:status (:ring/response (await (projects/handler handle (w "page")))))))
                 (is (nil? (:ring/response (await (projects/handler handle (w "page/style.css")))))))
               (testing "a project whose template is gone is noted as failed, its .log saying why"
                 (projects/stop! handle "card")
                 (fs/writeFileSync (path/join dir "card" "package.json") "{\"template\": \"gone\"}")
                 (await (projects/sync! handle))
                 (is (= 1 (get-in @db [:projects "card" :exit])))
                 (is (str/includes? (fs/readFileSync (path/join dir "card" ".log") "utf8") "The template gone is not installed")))
               (testing "a static project that goes leaves the db"
                 (fs/rmSync (path/join dir "page") #js {:recursive true :force true})
                 (await (projects/sync! handle))
                 (is (nil? (get-in @db [:projects "page"]))))
               (finally
                 (projects/stop-all! handle)
                 (restore!)
                 (fs/rmSync dir #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest handler-passes-unknown-test
  (async done
    (-> ((^:async fn []
           (let [handle (projects/handle {:db (atom {}) :dir "/tmp/none" :procs (atom {})})
                 w {:ring/route-params {:* "hello.html"} :ring/request {:uri "/artifacts/hello.html" :headers {}}}]
             (testing "a path that is no running project is left to the caller"
               (is (= w (await (projects/handler handle w)))))
             (testing "no projects, nothing to sync"
               (await (projects/sync! handle))
               (is (= {} @(:procs handle)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest lifecycle-test
  (async done
    (-> ((^:async fn []
           (let [dir (temp-dir)
                 db (atom {:artifacts/current "short"})
                 shown? (atom true)
                 handle (projects/handle {:db db :dir dir :shown? (fn [] @shown?) :procs (atom {})})
                 w (fn [path & [method body]]
                     {:ring/route-params {:* path}
                      :ring/request {:request-method (or method :get)
                                     :uri (str "/artifacts/" path)
                                     :headers {}
                                     :body body}})]
             (project! dir "short")
             (fs/writeFileSync (path/join dir "short" "highlight.mp4") "video")
             (try
               (testing "the project on screen is started and becomes ready"
                 (await (projects/sync! handle))
                 (is (number? (get-in @db [:projects "short" :port])))
                 (is (nil? (get-in @db [:projects "short" :ready-at])))
                 (loop [n 0]
                   (when (and (< n 50) (nil? (get-in @db [:projects "short" :ready-at])))
                     (await (sleep 100))
                     (await (projects/sync! handle))
                     (recur (inc n))))
                 (is (number? (get-in @db [:projects "short" :ready-at])))
                 (is (fs/existsSync (path/join dir "short" ".log")) "its output goes to .log"))
               (testing "its page goes to the process, with the environment rragent gave it"
                 (let [{:keys [status body]} (:ring/response (await (projects/handler handle (w "short/"))))]
                   (is (= 200 status))
                   (is (= (str "GET / base=/artifacts/short pid=" (.-pid js/process) " ") (await (transit/read-body body)))))
                 (let [{:keys [status body]} (:ring/response (await (projects/handler handle (w "short/variables" :post (.from Readable #js [(js/Buffer.from "x=1" "utf8")])))))]
                   (is (= 200 status))
                   (is (= (str "POST /variables base=/artifacts/short pid=" (.-pid js/process) " x=1") (await (transit/read-body body))))))
               (testing "a command posted from its page goes to it, any other stays with the caller"
                 (let [command (fn [referer]
                                 {:ring/route-params {:* ""}
                                  :ring/request {:request-method :post
                                                 :uri "/command"
                                                 :headers (cond-> {} referer (assoc "referer" referer))
                                                 :body (.from Readable #js [(js/Buffer.from "cmd" "utf8")])}})
                       {:keys [status body]} (:ring/response (await (projects/command-handler handle (command "http://localhost:8080/artifacts/short/"))))]
                   (is (= 200 status))
                   (is (= (str "POST /command base=/artifacts/short pid=" (.-pid js/process) " cmd") (await (transit/read-body body))))
                   (is (nil? (:ring/response (await (projects/command-handler handle (command "http://localhost:8080/"))))))
                   (is (nil? (:ring/response (await (projects/command-handler handle (command nil))))))
                   (is (nil? (:ring/response (await (projects/command-handler handle (command "http://localhost:8080/artifacts/other/"))))))))
               (testing "without the slash the page is sent to its folder"
                 (let [{:keys [status headers]} (:ring/response (await (projects/handler handle (w "short"))))]
                   (is (= 302 status))
                   (is (= "/artifacts/short/" (get headers "Location")))))
               (testing "a clip in the project's folder is left to the files route, not proxied"
                 (is (= (w "short/highlight.mp4") (await (projects/handler handle (w "short/highlight.mp4"))))))
               (testing "stopped, the project is gone from the db and the table"
                 (projects/stop! handle "short")
                 (is (nil? (get-in @db [:projects "short"])))
                 (is (= {} @(:procs handle))))
               (testing "a project no one wants is not started"
                 (reset! shown? false)
                 (fs/utimesSync (path/join dir "short" "server.mjs") 1000 1000)
                 (fs/utimesSync (path/join dir "short" "package.json") 1000 1000)
                 (fs/utimesSync (path/join dir "short" "highlight.mp4") 1000 1000)
                 (await (projects/sync! handle))
                 (is (= {} @(:procs handle))))
               (finally
                 (projects/stop-all! handle)
                 (fs/rmSync dir #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
