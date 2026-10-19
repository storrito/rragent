(ns rragent.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.auth :as auth]))

(def page
  {:status 200 :headers {} :body "the page"})

(deftest wrap-test
  (let [handler (auth/wrap (constantly page) {:token "s3cret"})]
    (testing "without the cookie: access required"
      (is (= 403 (:status (handler {:request-method :get :uri "/" :headers {}})))))
    (testing "the token in the query: the cookie and a redirect to the path"
      (let [{:keys [status headers]} (handler {:request-method :get :uri "/x" :query-string "token=s3cret" :headers {}})
            cookie (get headers "Set-Cookie")]
        (is (= 302 status))
        (is (= "/x" (get headers "Location")))
        (is (str/starts-with? cookie (str "rragent=" (auth/cookie-value "s3cret") "; Path=/; HttpOnly")))
        (is (not (str/includes? cookie "s3cret")) "the cookie holds an HMAC, never the token")
        (testing "the cookie passes"
          (is (= page (handler {:request-method :get :uri "/" :headers {"cookie" (first (str/split cookie #";"))}}))))))
    (testing "a wrong token and a wrong cookie"
      (is (= 403 (:status (handler {:request-method :get :uri "/" :query-string "token=other" :headers {}}))))
      (is (= 403 (:status (handler {:request-method :get :uri "/" :headers {"cookie" "rragent=abc"}}))))))
  (testing "without a token everything passes"
    (is (= page ((auth/wrap (constantly page) {:token nil}) {:request-method :get :uri "/" :headers {}}))))
  (testing "the link and a generated token"
    (is (= "http://localhost:8080/?token=a%2Fb" (auth/link "http://localhost:8080" "a/b")))
    (is (re-matches #"[A-Za-z0-9_-]{20,}" (auth/generate-token)))))
