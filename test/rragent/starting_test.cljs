(ns rragent.starting-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.starting :as starting]))

(deftest response-test
  (testing "a browser asking for the page gets the page that waits, in its language"
    (let [{:keys [status headers body]} (starting/response {:request-method :get
                                                            :uri "/"
                                                            :query-string "token=secret"
                                                            :headers {"accept" "text/html,application/xhtml+xml"
                                                                      "accept-language" "de-DE,de;q=0.9"}})]
      (is (= 503 status))
      (is (= "1" (get headers "Retry-After")))
      (is (str/starts-with? (get headers "Content-Type") "text/html"))
      (is (str/includes? body "Der Agent startet"))
      (is (str/includes? body "location.reload()"))
      (is (not (str/includes? body "secret")) "nothing of the request goes into it")))
  (testing "?lang=en beats the browser"
    (is (str/includes? (:body (starting/response {:request-method :get
                                                  :query-string "lang=en"
                                                  :headers {"accept" "text/html" "accept-language" "de"}}))
                       "The agent is starting")))
  (testing "a command, the frame stream or an upload gets a plain 503"
    (doseq [request [{:request-method :post :uri "/command" :headers {"accept" "application/transit+json"}}
                     {:request-method :get :uri "/stream" :headers {"accept" "text/event-stream"}}
                     {:request-method :post :uri "/upload" :headers {}}]]
      (let [{:keys [status headers body]} (starting/response request)]
        (is (= 503 status))
        (is (= "1" (get headers "Retry-After")))
        (is (= "starting" body))))))
