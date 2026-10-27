(ns rragent.rframes.http-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(deftest match-test
  (is (= {} (http/match [:get "/"] {:request-method :get
                                    :uri "/"})))
  (is (= {:* "assets/a.mp4"}
         (http/match [:get "/project/*"] {:request-method :get
                                          :uri "/project/assets/a.mp4"})))
  (is (nil? (http/match [:get "/"] {:request-method :post
                                    :uri "/"})))
  (is (nil? (http/match [:get "/project/*"] {:request-method :get
                                             :uri "/projects"}))))

(deftest parse-range-test
  (is (= [0 999] (http/parse-range "bytes=0-" 1000)))
  (is (= [100 199] (http/parse-range "bytes=100-199" 1000)))
  (is (= [900 999] (http/parse-range "bytes=900-5000" 1000)))
  (is (= [500 999] (http/parse-range "bytes=-500" 1000)))
  (is (nil? (http/parse-range "bytes=1000-" 1000)))
  (is (nil? (http/parse-range "items=0-1" 1000)))
  (is (nil? (http/parse-range nil 1000))))

(deftest files-test
  (async done
    (-> ((^:async fn []
           (let [root (fs/mkdtempSync (path/join (os/tmpdir) "project-"))
                 file (path/join root "assets" "a b.txt")]
             (try
               (fs/mkdirSync (path/dirname file) #js {:recursive true})
               (fs/writeFileSync file "0123456789")
               (testing "the path is URL-decoded and stays below the root"
                 (is (= (fs/realpathSync file) (http/resolve-file root "assets/a%20b.txt")))
                 (is (nil? (http/resolve-file root "../etc/passwd")))
                 (is (nil? (http/resolve-file root "assets")))
                 (is (nil? (http/resolve-file root "missing.txt"))))
               (testing "a range request gets the bytes it asked for"
                 (let [{:keys [status headers body]} (http/file-response {:headers {"range" "bytes=2-5"}} file)]
                   (is (= 206 status))
                   (is (= "bytes 2-5/10" (get headers "Content-Range")))
                   (is (= "2345" (await (transit/read-body body))))))
               (testing "without a range the whole file"
                 (let [{:keys [status body]} (http/file-response {:headers {}} file)]
                   (is (= 200 status))
                   (is (= "0123456789" (await (transit/read-body body))))))
               (finally
                 (fs/rmSync root #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(def fixtures
  (path/resolve "test/rragent/rframes/fixtures"))

(deftest files-entry-test
  (async done
    (-> ((^:async fn []
           (let [handler (http/ring-handler (constantly [(http/files "/hello.txt" (path/join fixtures "hello.txt"))
                                                          (http/files "/files/*" fixtures)]))
                 fetch (fn [uri]
                         (handler {:request-method :get
                                   :uri uri
                                   :headers {}}))]
             (testing "one file on a plain path"
               (let [{:keys [status headers body]} (await (fetch "/hello.txt"))]
                 (is (= 200 status))
                 (is (= "text/plain; charset=utf-8" (get headers "Content-Type")))
                 (is (= "hello\n" (await (transit/read-body body))))))
             (testing "the files below a root on a /* path"
               (is (= "hello\n" (await (transit/read-body (:body (await (fetch "/files/hello.txt")))))))
               (is (= 404 (:status (await (fetch "/files/missing.txt")))))
               (is (= 404 (:status (await (fetch "/files/")))))
               (is (= 404 (:status (await (fetch "/files/../fixtures/hello.txt")))))))
           (testing "several roots: the first that has the file serves it, a missing root is no harm"
             (let [handler (http/ring-handler (constantly [(http/files "/either/*" [(path/join fixtures "missing") fixtures])
                                                            (http/files "/one.txt" [(path/join fixtures "missing.txt") (path/join fixtures "hello.txt")])]))
                   fetch (fn [uri] (handler {:request-method :get :uri uri :headers {}}))]
               (is (= "hello\n" (await (transit/read-body (:body (await (fetch "/either/hello.txt")))))))
               (is (= "hello\n" (await (transit/read-body (:body (await (fetch "/one.txt")))))))
               (is (= 404 (:status (await (fetch "/either/missing.txt")))))))))

        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest server-test
  (async done
    (-> ((^:async fn []
           (let [handler (http/ring-handler (constantly [{:ring/route [:get "/echo/*"]
                                                           :ring/handler (fn [w]
                                                                           (assoc w :ring/response {:status 200
                                                                                                    :headers {"Content-Type" "text/plain"}
                                                                                                    :body (str (get-in w [:ring/route-params :*])
                                                                                                               " " (get-in w [:ring/request :query-string]))}))}
                                                          {:ring/route [:get "/boom"]
                                                           :ring/handler (fn [_] (throw (js/Error. "boom")))}]))
                 server (await (http/listen handler 0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))
                 quiet (.-error js/console)]
             (try
               (testing "the ring request from the Node request, the response written back"
                 (let [response (await (js/fetch (str base "/echo/a?x=1")))]
                   (is (= 200 (.-status response)))
                   (is (= "text/plain" (.get (.-headers response) "content-type")))
                   (is (= "a x=1" (await (.text response))))))
               (testing "no route: 404"
                 (is (= 404 (.-status (await (js/fetch (str base "/missing")))))))
               (testing "a handler that throws: 500"
                 (set! (.-error js/console) (fn [& _]))
                 (is (= 500 (.-status (await (js/fetch (str base "/boom")))))))
               (finally
                 (set! (.-error js/console) quiet)
                 (.close server))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
