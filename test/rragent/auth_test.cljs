(ns rragent.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.auth :as auth]
            [rragent.fake-sprite-env :as fake]
            [rragent.sprite :as sprite]))

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

(defn- with-environment-token
  "`f` run with RRAGENT_TOKEN set to `value` (unset with nil), the
   variable as it was afterwards."
  [value f]
  (let [env (.-env js/process)
        before (aget env "RRAGENT_TOKEN")]
    (try
      (if value
        (aset env "RRAGENT_TOKEN" value)
        (js-delete env "RRAGENT_TOKEN"))
      (f)
      (finally
        (if before
          (aset env "RRAGENT_TOKEN" before)
          (js-delete env "RRAGENT_TOKEN"))))))

(deftest token-test
  (let [{:keys [script]} (fake/make! {:discover (fake/discover-json ["org-1" "token:0123456789abcdef0123456789abcdef"])})
        unlabeled (:script (fake/make! {:discover (fake/discover-json ["org-1"])}))]
    (testing "the sprite's label over the environment"
      (binding [sprite/*sprite-env* script]
        (is (= {:token "0123456789abcdef0123456789abcdef" :source :label}
               (with-environment-token "from-the-environment" auth/token)))
        (is (= {:token "0123456789abcdef0123456789abcdef" :source :label}
               (with-environment-token nil auth/token)))))
    (testing "the environment when no sprite answers"
      (binding [sprite/*sprite-env* "no-such-command-anywhere"]
        (is (= {:token "from-the-environment" :source :environment}
               (with-environment-token "from-the-environment" auth/token)))))
    (testing "the environment when the sprite has no token label"
      (binding [sprite/*sprite-env* unlabeled]
        (is (= {:token "from-the-environment" :source :environment}
               (with-environment-token "from-the-environment" auth/token)))))
    (testing "generated without either, a different one each time"
      (binding [sprite/*sprite-env* "no-such-command-anywhere"]
        (let [a (with-environment-token nil auth/token)
              b (with-environment-token nil auth/token)]
          (is (= :generated (:source a)))
          (is (re-matches #"[A-Za-z0-9_-]{20,}" (:token a)))
          (is (not= (:token a) (:token b))))))))
