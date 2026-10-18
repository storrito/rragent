(ns rragent.rframes.command-test
  (:require ["node:stream" :refer [Readable]]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.command :as command]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(def db
  (atom 0))

(defn- add
  [w]
  (swap! db + (:n (command/command-data w)))
  (command/accepted w))

(defn- reject
  [w]
  (command/rejected w :no))

(defn- fail
  [_w]
  (throw (ex-info "boom" {})))

(def register
  (concat command/register
          [{:command/kind :test/add
            :command/fn add}
           {:command/kind :test/reject
            :command/fn reject}
           {:command/kind :test/fail
            :command/fn fail}]))

(defn- body-stream
  [s]
  (.from Readable #js [(js/Buffer.from s "utf8")]))

(defn- ^:async post
  [command]
  (let [handler (http/ring-handler (constantly register))
        {:keys [status headers body]} (await (handler {:request-method :post
                                                       :uri "/command"
                                                       :headers {}
                                                       :body (body-stream (transit/write-str command))}))]
    {:status status
     :type (get headers "Content-Type")
     :answer (transit/read-str body)}))

(deftest command-test
  (async done
    (-> ((^:async fn []
           (reset! db 0)
           (testing "an accepted command"
             (is (= {:status 200
                     :type "application/transit+json"
                     :answer {:success? true}}
                    (await (post {:command/kind :test/add
                                  :command/data {:n 2}}))))
             (is (= 2 @db)))
           (testing "a rejected command carries its error"
             (is (= {:success? false
                     :result {:error :no}}
                    (:answer (await (post {:command/kind :test/reject}))))))
           (testing "an unknown kind and a body that is no command"
             (is (= {:error :unknown-command-kind} (:answer (await (post {:command/kind :test/unknown})))))
             (is (= {:error :unknown-command-kind} (:answer (await (post "not a command"))))))
           (testing "a command function that throws"
             (let [quiet (.-error js/console)]
               (set! (.-error js/console) (fn [& _]))
               (try
                 (is (= {:error :command-fn-failed}
                        (:answer (await (post {:command/kind :test/fail})))))
                 (finally
                   (set! (.-error js/console) quiet)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
