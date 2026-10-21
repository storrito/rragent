(ns rragent.starting-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.starting :as starting]))

(def never
  "An agent that is still opening."
  (js/Promise. (fn [_])))

(deftest response-test
  (testing "a browser asking for the page gets the page that waits, in its language"
    (let [{:keys [status headers body]} (starting/response never {:request-method :get
                                                            :uri "/"
                                                            :query-string "token=secret"
                                                            :headers {"accept" "text/html,application/xhtml+xml"
                                                                      "accept-language" "de-DE,de;q=0.9"}})]
      (is (= 503 status))
      (is (= "1" (get headers "Retry-After")))
      (is (str/starts-with? (get headers "Content-Type") "text/html"))
      (is (str/includes? body "Der Agent startet"))
      (is (str/includes? body "location.reload()"))
      (is (str/includes? body "rragent.theme") "the theme the person chose, before it paints")
      (is (not (str/includes? body "secret")) "nothing of the request goes into it")))
  (testing "?lang=en beats the browser"
    (is (str/includes? (:body (starting/response never {:request-method :get
                                                  :query-string "lang=en"
                                                  :headers {"accept" "text/html" "accept-language" "de"}}))
                       "The agent is starting")))
  (testing "a command, the frame stream, an upload or a hook waits for the agent and goes to the page's handler"
    (async done
      (let [request {:request-method :post :uri "/hook/github/secret" :headers {}}]
        (-> (starting/response (js/Promise.resolve (fn [r] {:status 202 :body (:uri r)})) request)
            (.then (fn [answer]
                     (is (= {:status 202 :body "/hook/github/secret"} answer))
                     (done)))))))
  (testing "the page itself never waits"
    (is (= 503 (:status (starting/response never {:request-method :get :headers {"accept" "text/html"}}))))))
