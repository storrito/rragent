(ns rragent.projects-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:stream" :refer [Readable]]
            [clojure.test :refer [async deftest is testing]]
            [rragent.projects :as projects]
            [rragent.rframes.transit :as transit]))

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

(deftest handler-passes-unknown-test
  (async done
    (-> ((^:async fn []
           (let [handle (projects/handle {:db (atom {}) :dir "/tmp/none"})
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
                 handle (projects/handle {:db db :dir dir :shown? (fn [] @shown?)})
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
