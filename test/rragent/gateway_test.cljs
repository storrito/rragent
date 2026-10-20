(ns rragent.gateway-test
  "The list at start: which failures are a gateway that is not up
   yet, and the tries that follow them; a port that refuses costs one."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.gateway :as gateway]
            [rragent.sync-fetch :as sync-fetch]))

(deftest retry-test
  (testing "a gateway that is not up yet: tried again"
    (is (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list failed: The operation was aborted due to timeout"}))
    (is (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list failed: The operation was aborted"}))
    (is (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list failed: fetch failed (UND_ERR_SOCKET)"}))
    (is (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list failed: fetch failed (ECONNRESET)"}))
    (is (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list got no answer in time; the request worker was restarted."}))
    (is (gateway/retry? {:error "HTTP 503 from http://sprite.internal/v1/gateway/list"}))
    (is (gateway/retry? {:error "HTTP 502 from http://sprite.internal/v1/gateway/list"})))
  (testing "no gateway on this machine: one try"
    (is (not (gateway/retry? {:error "GET http://127.0.0.1:1/v1/gateway/list failed: fetch failed (ECONNREFUSED)"})))
    (is (not (gateway/retry? {:error "GET http://127.0.0.1:1/v1/gateway/list failed: fetch failed (bad port)"})))
    (is (not (gateway/retry? {:error "GET http://sprite.internal/v1/gateway/list failed: fetch failed (ENOTFOUND)"})))
    (is (not (gateway/retry? {:error "HTTP 404 from http://sprite.internal/v1/gateway/list"})))
    (is (not (gateway/retry? {:error "HTTP 401 from http://sprite.internal/v1/gateway/list"})))
    (is (not (gateway/retry? {:error "no list from http://sprite.internal/v1/gateway/list"})))
    (is (not (gateway/retry? {:connections []})))))

(defn- ^:async with-fake
  "`f` called with the URL of a fake started with `plan`, the fake and
   the request worker stopped after."
  [plan f]
  (let [{:keys [url stop]} (await (fake-gateway/start! nil {:plan plan}))]
    (try
      (await (f url))
      (finally
        (stop)
        (sync-fetch/stop!)))))

(deftest discover-at-start-test
  (async done
    (-> ((^:async fn []
           (testing "the list from the third request: two connections closed under it, then the answer"
             (await (with-fake ["reset" "reset"]
                      (^:async fn [url]
                        (let [{:keys [tries error connections]} (await (gateway/discover-at-start url {:delays-ms [20 20 20 20]}))]
                          (is (= 3 tries))
                          (is (nil? error))
                          (is (= 5 (count connections))))))))
           (testing "a request that times out is tried again"
             (await (with-fake ["hang"]
                      (^:async fn [url]
                        (let [started (js/Date.now)
                              {:keys [tries error connections]} (await (gateway/discover-at-start url {:delays-ms [20 20] :timeout-ms 200}))]
                          (is (= 2 tries))
                          (is (nil? error))
                          (is (= 5 (count connections)))
                          (is (<= 200 (- (js/Date.now) started))))))))
           (testing "a 503 of what stands in front of the gateway is tried again, until the waits are used up"
             (await (with-fake [503 503 503 503]
                      (^:async fn [url]
                        (let [{:keys [tries error connections]} (await (gateway/discover-at-start url {:delays-ms [10 10]}))]
                          (is (= 3 tries))
                          (is (str/starts-with? (str error) "HTTP 503 from"))
                          (is (= [] connections)))))))
           (testing "a port that refuses at once is no gateway: one try, no wait"
             (let [port (await (fake-gateway/free-port))
                   started (js/Date.now)
                   {:keys [tries error]} (await (gateway/discover-at-start (str "http://127.0.0.1:" port) {:delays-ms [1000 1000]}))]
               (is (= 1 tries))
               (is (str/includes? (str error) "ECONNREFUSED"))
               (is (> 500 (- (js/Date.now) started)))
               (sync-fetch/stop!)))
           (testing "the waits of production, as data"
             (is (= [1000 2000 4000 8000] gateway/retry-delays-ms)))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
