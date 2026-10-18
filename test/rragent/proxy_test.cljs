(ns rragent.proxy-test
  (:require ["node:http" :as http]
            ["node:stream" :refer [Readable]]
            [clojure.test :refer [async deftest is testing]]
            [rragent.proxy :as proxy]
            [rragent.rframes.transit :as transit]))

(deftest headers-test
  (is (= {"accept" "*/*" "x-a" "1"}
         (proxy/request-headers {"host" "x" "content-length" "3" "Connection" "keep-alive" "accept" "*/*" "x-a" "1"})))
  (is (= {"content-type" "text/plain" "x-list" "a, b"}
         (proxy/response-headers #js {"content-type" "text/plain" "transfer-encoding" "chunked" "x-list" #js ["a" "b"]}))))

(deftest request-test
  (async done
    (let [server (http/createServer (fn [^js req ^js res]
                                      (let [chunks #js []]
                                        (.on req "data" (fn [c] (.push chunks c)))
                                        (.on req "end" (fn []
                                                         (.setHeader res "Content-Type" "text/plain")
                                                         (.setHeader res "X-Seen" (or (aget (.-headers req) "x-token") "-"))
                                                         (.end res (str (.-method req) " " (.-url req) " " (.toString (js/Buffer.concat chunks) "utf8"))))))))]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (let [port (.-port (.address server))]
               (testing "a GET with its query string and headers, the answer streamed"
                 (let [{:keys [status headers body]} (await (proxy/request port "page/index.html" {:request-method :get
                                                                                               :query-string "rev=3"
                                                                                               :headers {"x-token" "t" "host" "nope"}}))]
                   (is (= 200 status))
                   (is (= "text/plain" (get headers "content-type")))
                   (is (= "t" (get headers "x-seen")))
                   (is (= "GET /page/index.html?rev=3 " (await (transit/read-body body))))))
               (testing "a POST with its body piped on"
                 (let [{:keys [status body]} (await (proxy/request port "variables" {:request-method :post
                                                                                   :headers {"content-type" "application/json"}
                                                                                   :body (.from Readable #js [(js/Buffer.from "{\"a\":1}" "utf8")])}))]
                   (is (= 200 status))
                   (is (= "POST /variables {\"a\":1}" (await (transit/read-body body))))))
               (testing "nothing on the port: 502 with a word"
                 (.close server)
                 (await (js/Promise. (fn [resolve] (.on server "close" resolve))))
                 (let [{:keys [status body]} (await (proxy/request port "" {:request-method :get :headers {}}))]
                   (is (= 502 status))
                   (is (re-find #"did not answer" body)))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))
