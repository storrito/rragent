(ns rragent.composio-setup-test
  "The session and the subscription ensured against a fake of the
   gateway's answers: what an earlier run left is kept, what is
   missing is made, what points elsewhere is set here."
  (:require [clojure.test :refer [async deftest is testing]]
            [rragent.composio :as composio]
            [rragent.composio-setup :as setup]))

(defn- fake-fetch
  "A `fetch` whose answers are `answers`, `[method path] -> {:status
   :body}`; the calls made are kept in `calls`."
  [answers calls]
  (fn [_which path {:keys [method body]}]
    (swap! calls conj [method path body])
    (js/Promise.resolve (or (get answers [method path])
                            {:status 404 :body {:error {:message (str "no answer for " method " " path)}}}))))

(defn- ^:async failure-of
  "The message the promise of `thunk` rejects with; nil when it
   resolves."
  [thunk]
  (try
    (await (thunk))
    nil
    (catch :default e
      (.-message e))))

(deftest ensure-session-test
  (async done
    (-> ((^:async fn []
           (let [env (.-env js/process)
                 before (aget env "COMPOSIO_SESSION_ID")]
             (try
               (testing "the environment's session wins, no call made"
                 (aset env "COMPOSIO_SESSION_ID" "trs_env")
                 (let [calls (atom [])]
                   (is (= {:session "trs_env" :source :environment} (await (setup/ensure-session! (fake-fetch {} calls) "conn_c" "agent-1" "trs_stored"))))
                   (is (= [] @calls))))
               (js-delete env "COMPOSIO_SESSION_ID")
               (testing "then the stored one, no call made"
                 (let [calls (atom [])]
                   (is (= {:session "trs_stored" :source :stored} (await (setup/ensure-session! (fake-fetch {} calls) "conn_c" "agent-1" "trs_stored"))))
                   (is (= [] @calls))))
               (testing "without either one is made for the user id, with Storrito's config"
                 (let [calls (atom [])
                       answers {["POST" composio/session-path] {:status 201 :body {:session_id "trs_new"}}}]
                   (is (= {:session "trs_new" :source :made} (await (setup/ensure-session! (fake-fetch answers calls) "conn_c" "agent-1" nil))))
                   (is (= [["POST" composio/session-path (composio/session-body "agent-1")]] @calls))))
               (testing "Composio refusing is said"
                 (let [answers {["POST" composio/session-path] {:status 403 :body {:error {:message "Project API key is not allowed for this endpoint"}}}}]
                   (is (= "Composio answered HTTP 403 to the session: Project API key is not allowed for this endpoint"
                          (await (failure-of #(setup/ensure-session! (fake-fetch answers (atom [])) "conn_c" "agent-1" nil)))))))
               (finally
                 (if (nil? before) (js-delete env "COMPOSIO_SESSION_ID") (aset env "COMPOSIO_SESSION_ID" before)))))))
        (.finally done))))

(deftest ensure-subscription-test
  (async done
    (-> ((^:async fn []
           (let [url "https://a-1-b365p.sprites.app/composio/event"
                 listed (fn [items] {:status 200 :body {:items items}})]
             (testing "the one subscription of the project is kept when it points here with the events wanted"
               (let [calls (atom [])
                     existing {:id "ws_1" :webhook_url url :enabled_events setup/event-types :secret "s1"}]
                 (is (= {:id "ws_1" :url url :secret "s1" :source :kept}
                        (await (setup/ensure-subscription! (fake-fetch {["GET" setup/subscriptions-path] (listed [existing])} calls) "conn_c" url))))
                 (is (= [["GET" setup/subscriptions-path nil]] @calls))))
             (testing "one that points elsewhere is set to this page; its secret stays"
               (let [calls (atom [])
                     existing {:id "ws_1" :webhook_url "https://storrito.com/composio/webhook" :enabled_events ["composio.trigger.message"] :secret "s1"}
                     answers {["GET" setup/subscriptions-path] (listed [existing])
                              ["PATCH" (str setup/subscriptions-path "/ws_1")] {:status 200 :body {:id "ws_1" :webhook_url url :enabled_events setup/event-types}}}]
                 (is (= {:id "ws_1" :url url :secret "s1" :source :updated}
                        (await (setup/ensure-subscription! (fake-fetch answers calls) "conn_c" url))))
                 (is (= ["PATCH" (str setup/subscriptions-path "/ws_1") {:webhook_url url :enabled_events setup/event-types}] (second @calls)))))
             (testing "none yet: one is made, V3, with the secret Composio answers"
               (let [calls (atom [])
                     answers {["GET" setup/subscriptions-path] (listed [])
                              ["POST" setup/subscriptions-path] {:status 201 :body {:id "ws_2" :webhook_url url :enabled_events setup/event-types :secret "s2"}}}]
                 (is (= {:id "ws_2" :url url :secret "s2" :source :made}
                        (await (setup/ensure-subscription! (fake-fetch answers calls) "conn_c" url))))
                 (is (= ["POST" setup/subscriptions-path {:webhook_url url :enabled_events setup/event-types :version "V3"}] (second @calls)))))
             (testing "Composio refusing is said"
               (is (= "Composio answered HTTP 401 to the subscriptions: Invalid x-api-key"
                      (await (failure-of #(setup/ensure-subscription! (fake-fetch {["GET" setup/subscriptions-path] {:status 401 :body {:error {:message "Invalid x-api-key"}}}} (atom [])) "conn_c" url)))))))))
        (.finally done))))

(deftest event-url-test
  (is (= "https://a-1-b365p.sprites.app/composio/event" (setup/event-url "https://a-1-b365p.sprites.app/")))
  (is (= "https://a-1-b365p.sprites.app/composio/event" (setup/event-url "https://a-1-b365p.sprites.app")))
  (is (= "composio: session trs_1 (made), events to https://x/composio/event (made)"
         (setup/report {:session {:session "trs_1" :source :made} :subscription {:url "https://x/composio/event" :source :made}})))
  (is (= "composio: session trs_1 (stored), no events (RRAGENT_URL is not set)"
         (setup/report {:session {:session "trs_1" :source :stored}})))
  (is (= "composio: nothing" (setup/report {:skipped "nothing"}))))
