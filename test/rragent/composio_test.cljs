(ns rragent.composio-test
  "The pure parts of the Composio functions: how the connection is
   told from the other custom APIs, the meta tools' arguments, what an
   answer becomes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.composio :as composio]))

(def connection
  {:provider "custom_api" :id "conn_c" :notes []
   :provider_info {:auth_method "custom_header" :auth_custom_header_name "x-api-key" :base_api_url "https://backend.composio.dev"}})

(deftest connection-test
  (is (true? (composio/connection? connection)))
  (is (false? (composio/connection? {:provider "composio" :id "conn_x"})) "Sprites' own Composio connector has no sessions")
  (is (false? (composio/connection? {:provider "custom_api" :id "conn_s" :provider_info {:base_api_url "https://acme.storrito.com"}})))
  (is (= connection (composio/connection {:connections [{:provider "echo" :id "e"} connection]})))
  (is (nil? (composio/connection {:connections []})))
  (is (= "/api/v3/tool_router/session/trs_1/execute_meta" (composio/meta-path "trs_1"))))

(deftest arguments-test
  (is (= {:queries [{:use_case "send an email"}] :session {:generate_id true}} (composio/search-arguments "send an email" nil)))
  (is (= {:queries [{:use_case "send an email"}] :session {:generate_id true} :toolkits ["gmail"]} (composio/search-arguments "send an email" ["gmail"])))
  (is (= {:tool_slugs ["A" "B"]} (composio/schemas-arguments ["A" "B"] nil)))
  (is (= {:tool_slugs ["A"] :session_id "s1"} (composio/schemas-arguments ["A"] "s1")))
  (is (= {:tools [{:tool_slug "A" :arguments {}}]} (composio/execute-arguments "A" nil nil)))
  (is (= {:tools [{:tool_slug "A" :arguments {:to "x"}}] :session_id "s1"} (composio/execute-arguments "A" {:to "x"} "s1")))
  (is (= {:toolkits ["gmail"] :session_id "s1"} (composio/connections-arguments ["gmail"] "s1")))
  (is (= "search_1" (composio/search-session {:session {:id "search_1"}})))
  (is (nil? (composio/search-session {:results []}))))

(deftest result-test
  (testing "a completed run is its data, a failed one a throw with the data, anything else the answer itself"
    (is (= {:results [1]} (composio/result "X" {:status 200 :body {:result_type "completed" :data {:results [1]}}})))
    (let [e (try (composio/result "X" {:status 200 :body {:result_type "failed" :error "gmail is not connected" :data {:results [{:index 0}]}}}) nil (catch :default e e))]
      (is (str/includes? (.-message e) "X failed: gmail is not connected"))
      (is (str/includes? (.-message e) "\"index\":0"))
      (is (= {:data {:results [{:index 0}]}} (ex-data e))))
    (is (= {:result_type "input_required" :questions ["which account?"]}
           (composio/result "X" {:status 200 :body {:result_type "input_required" :questions ["which account?"]}}))))
  (testing "an error status"
    (let [e (try (composio/result "X" {:status 404 :body {:error {:message "Session not found"}}}) nil (catch :default e e))]
      (is (= "Composio answered HTTP 404 to X: Session not found" (.-message e)))
      (is (= 404 (:status (ex-data e)))))))
