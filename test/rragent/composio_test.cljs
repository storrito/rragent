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

(deftest session-test
  (testing "the session is made for the agent's user id with Storrito's config: nothing that charges or reaches past the tools"
    (is (= {:instant false :workbench {:enable false} :proxy_execute {:enable false} :manage_connections {:enable true} :user_id "agent-1"}
           (composio/session-body "agent-1")))
    (is (= "/api/v3.1/tool_router/session" composio/session-path))))

(deftest rest-test
  (testing "the paths and bodies of the triggers and the accounts; a trigger names the account's owner"
    (is (= "/api/v3/trigger_instances/GOOGLEDRIVE_FILE_CREATED_TRIGGER/upsert" (composio/upsert-trigger-path "GOOGLEDRIVE_FILE_CREATED_TRIGGER")))
    (is (= {:connected_account_id "ca_1" :user_id "agent-1" :trigger_config {}} (composio/upsert-trigger-body "ca_1" "agent-1" nil)))
    (is (= {:connected_account_id "ca_1" :user_id "agent-1" :trigger_config {:folder "x"}} (composio/upsert-trigger-body "ca_1" "agent-1" {:folder "x"})))
    (is (= "/api/v3/trigger_instances/manage/ti_1" (composio/delete-trigger-path "ti_1")))
    (is (= {:user_ids "agent-1" :limit 50} (composio/accounts-query "agent-1")))
    (is (= {:toolkit_slugs "googledrive" :limit 50} (composio/trigger-types-query "googledrive"))))
  (testing "what the answers become"
    (is (= [{:id "ti_1" :slug "GOOGLEDRIVE_FILE_CREATED_TRIGGER" :connected-account "ca_1" :state "active"}]
           (composio/trigger-list {:items [{:id "ti_1" :trigger_name "GOOGLEDRIVE_FILE_CREATED_TRIGGER" :connected_account_id "ca_1" :state "active" :disabled_at nil}]})))
    (is (= [{:id "ti_2" :slug "X" :connected-account "ca_1" :state nil :disabled-at "2026-10-26"}]
           (composio/trigger-list {:items [{:id "ti_2" :slug "X" :connected_account_id "ca_1" :disabled_at "2026-10-26"}]})))
    (is (= [{:id "ca_1" :toolkit "googledrive" :status "ACTIVE"}]
           (composio/account-list {:items [{:id "ca_1" :toolkit {:slug "googledrive"} :status "ACTIVE" :user_id "agent-1"}]})))
    (is (= [{:slug "A" :name "A" :type "poll" :config {:required []}}]
           (composio/trigger-type-list {:items [{:slug "A" :name "A" :type "poll" :config {:required []} :toolkit {:slug "x"}}]}))))
  (testing "a call answers the body of a 2xx and throws with what Composio said otherwise"
    (let [discovery {:connections [connection]}
          ok (fn [_id path opts] {:status 200 :body {:path path :opts opts}})
          refused (fn [_id _path _opts] {:status 400 :body {:error {:message "2FA is enabled for this project: user_id is required to create or update a trigger." :code 1221}}})]
      (is (= {:path "/api/v3/trigger_instances/active" :opts {:method "GET"}}
             (composio/rest-call ok discovery "GET" composio/triggers-path {})))
      (is (= {:path "/x" :opts {:method "POST" :body {:a 1} :query {:q 2}}}
             (composio/rest-call ok discovery "POST" "/x" {:body {:a 1} :query {:q 2}})))
      (is (thrown-with-msg? js/Error #"Composio answered HTTP 400 to POST /api/v3/trigger_instances/X/upsert: 2FA is enabled"
                            (composio/rest-call refused discovery "POST" (composio/upsert-trigger-path "X") {:body {}}))))))
