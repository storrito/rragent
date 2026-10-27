(ns rragent.codemode-test
  "The REPL against the fake gateway: discovery, calls with JSON in
   and out, the closed context, defs that stay, the output cap, the
   Composio and Storrito functions, the facts a program notes."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.fake-sprite-env :as fake-sprite-env]
            [rragent.sprite :as sprite]
            [rragent.sync-fetch :as sync-fetch]))

(defn- failure
  "The message of the throw `thunk` ends in; nil when it does not throw."
  [thunk]
  (try (thunk) nil (catch :default e (.-message e))))

(defn- with-env
  "`thunk` run with the environment variable `k` as `v` (nil unset),
   the variable restored after."
  [k v thunk]
  (let [env (.-env js/process)
        before (aget env k)]
    (try
      (if (nil? v) (js-delete env k) (aset env k v))
      (thunk)
      (finally
        (if (nil? before) (js-delete env k) (aset env k before))))))

(defn- with-fake
  "`f` called with the fake's URL and a handle over it, the fake and
   the request worker stopped after; `list` as `fake-gateway/start!`
   takes it."
  [list f]
  (-> ((^:async fn []
         (let [{:keys [url stop]} (await (fake-gateway/start! list))]
           (try
             (f url (codemode/handle url))
             (finally
               (stop)
               (sync-fetch/stop!))))))
      (.catch (fn [e] (is (nil? e) (str e))))))

(deftest discovery-test
  (async done
    (-> (with-fake nil
          (fn [url handle]
            (let [{:keys [gateway connections available error]} (codemode/discovery handle)]
              (is (= url gateway))
              (is (nil? error))
              (is (= ["conn_echo" "conn_openrouter" "conn_composio" "conn_storrito" "conn_other"] (mapv :id connections)))
              (is (= (str url "/v1/gateway/echo/conn_echo") (:gateway_base_url (first connections))) "the fake fills the base in")
              (is (= ["github"] (mapv :provider available))))
            (testing "a connection by provider name, by id, and several of one provider"
              (is (= "conn_echo" (:id (codemode/connector handle "echo"))))
              (is (= "conn_other" (:id (codemode/connector handle "conn_other"))))
              (is (nil? (codemode/connector handle "github")))
              (is (str/includes? (failure #(codemode/connector handle "custom_api")) "3 connections have the provider \"custom_api\", name one by its id: custom_api conn_composio (backend.composio.dev)")))
            (testing "what the console gets"
              (is (str/includes? (codemode/report handle) "custom_api conn_composio (backend.composio.dev) = Composio"))
              (is (str/includes? (codemode/report handle) "custom_api conn_storrito (127.0.0.1) = Storrito, 3 procedures"))
              (is (str/includes? (codemode/report handle) "available: github")))
            (testing "(connectors) reads the list again, as data"
              (is (= "=> (\"conn_echo\" \"conn_openrouter\" \"conn_composio\" \"conn_storrito\" \"conn_other\")"
                     (codemode/evaluate handle "(map :id (:connections (connectors)))")))
              (is (= "=> \"github\"" (codemode/evaluate handle "(:provider (first (:available (connectors))))"))))))
        (.finally done))))

(deftest no-gateway-test
  (testing "no sprite around: an empty list that says why, and help says so"
    (let [handle (codemode/handle "http://127.0.0.1:1")
          {:keys [connections available error]} (codemode/discovery handle)]
      (is (= [] connections))
      (is (= [] available))
      (is (str/includes? (str error) "failed"))
      (is (str/starts-with? (codemode/report handle) "no gateway at http://127.0.0.1:1 ("))
      (is (str/includes? (codemode/evaluate handle "(help)") "did not answer"))
      (is (str/includes? (failure #(codemode/evaluate handle "(call \"github\" \"/user\")")) "Nothing is connected. The gateway did not answer"))
      (is (str/includes? (failure #(codemode/evaluate handle "(storrito/procedures)")) "No Storrito among the connections"))
      (sync-fetch/stop!))))

(deftest call-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "JSON in and out, the query and the headers"
              (is (= "=> {:method \"POST\", :path \"/x\", :query {:a \"1\"}, :headers {:content-type \"application/json\", :x-extra \"y\"}, :body {:a [1 2]}}"
                     (codemode/evaluate handle "(-> (call \"echo\" \"x\" {:method :post :body {:a [1 2]} :headers {:x-extra \"y\"} :query {:a 1}}) :body (dissoc :id))"))))
            (testing "a string body goes as it is, a text answer comes as text, the status is the program's to look at"
              (is (= "=> [200 \"raw\" \"text/plain\"]"
                     (codemode/evaluate handle "(let [r (call \"echo\" \"/x\" {:method \"POST\" :body \"raw\" :headers {\"Content-Type\" \"text/plain\"}})] [(:status r) (get-in r [:body :body]) (get-in r [:body :headers :content-type])])")))
              (is (= "=> [200 \"plain /y\" \"text/plain\"]"
                     (codemode/evaluate handle "(let [r (call \"echo\" \"/y\" {:query {:text 1}})] [(:status r) (:body r) (get-in r [:headers \"content-type\"])])")))
              (is (= "=> 404" (codemode/evaluate handle "(:status (call \"echo\" \"/z\" {:query {:status 404}}))"))))
            (testing "no such connection"
              (let [message (failure #(codemode/evaluate handle "(call \"github\" \"/user\")"))]
                (is (str/includes? message "No connection \"github\". Connected: echo conn_echo, openrouter conn_openrouter"))
                (is (str/includes? message "GitHub is available, the person can set it up"))))
            (testing "a program loops over calls"
              (is (= "=> [\"/0\" \"/1\" \"/2\"]"
                     (codemode/evaluate handle "(vec (for [i (range 3)] (get-in (call \"conn_other\" (str \"/\" i)) [:body :path])))"))))))
        (.finally done))))

(deftest closed-context-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (testing "nothing of the host: no js/, no require of a module, no environment, no interop on values"
      (is (str/includes? (failure #(codemode/evaluate handle "(.-title js/process)")) "Unable to resolve symbol: js/process"))
      (is (str/includes? (failure #(codemode/evaluate handle "(.-env js/process)")) "Unable to resolve symbol: js/process"))
      (is (str/includes? (failure #(codemode/evaluate handle "(js/require \"fs\")")) "Unable to resolve symbol: js/require"))
      (is (str/includes? (failure #(codemode/evaluate handle "(require '[\"node:fs\" :as fs])")) "Could not find namespace node:fs"))
      (is (str/includes? (failure #(codemode/evaluate handle "(require 'rragent.harness)")) "Could not find namespace rragent.harness"))
      (is (str/includes? (failure #(codemode/evaluate handle "(.-constructor (fn []))")) "not allowed"))
      (is (str/includes? (failure #(codemode/evaluate handle "(aget (fn []) \"constructor\")")) "aget is not available in this REPL"))
      (is (str/includes? (failure #(codemode/evaluate handle "(js-obj)")) "js-obj is not available in this REPL")))
    (testing "Clojure's own namespaces are there"
      (is (= "=> #{1 2}" (codemode/evaluate handle "(require '[clojure.set :as set]) (set/union #{1} #{2})")))
      (is (= "=> \"a-b\"" (codemode/evaluate handle "(clojure.string/join \"-\" [\"a\" \"b\"])"))))
    (sync-fetch/stop!)))

(deftest evaluate-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (testing "a def stays for the next program"
      (is (= "=> #'user/x" (codemode/evaluate handle "(def x 21)")))
      (is (= "=> 42" (codemode/evaluate handle "(* x 2)")))
      (is (= "=> 4" (codemode/evaluate handle "(defn twice [n] (* 2 n)) (twice 2)"))))
    (testing "what was printed, then the value"
      (is (= "hi\n{:a 1}\n=> 42" (codemode/evaluate handle "(println \"hi\") (prn {:a 1}) 42")))
      (is (= "=> nil" (codemode/evaluate handle "nil"))))
    (testing "an error says where, and what was printed before it"
      (let [message (failure #(codemode/evaluate handle "(println \"so far\")\n(undefined-fn 1)"))]
        (is (str/includes? message "Unable to resolve symbol: undefined-fn (line 2, column 1)"))
        (is (str/includes? message "Printed before the error:\nso far")))
      (is (str/includes? (failure #(codemode/evaluate handle "(throw (ex-info \"boom\" {:x 1}))")) "boom")))
    (testing "the output is cut"
      (is (str/ends-with? (codemode/evaluate handle "(apply str (repeat 20000 \"a\"))") "(cut at 10000 characters)"))
      (is (= (+ codemode/max-chars (count " … (cut at 10000 characters)")) (count (codemode/evaluate handle "(apply str (repeat 20000 \"a\"))")))))
    (testing "help and doc"
      (let [help (codemode/evaluate handle "(help)")]
        (is (str/starts-with? help "Your REPL: Clojure"))
        (is (str/includes? help "(composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\"})\n    runs one tool with its arguments"))
        (is (str/includes? help "(composio/connections [\"googlesheets\"])\n    the connections of services by toolkit slug") "the slug of a spreadsheet, and that the link is the answer to give")
        (is (str/includes? help "good for ten minutes"))
        (is (str/ends-with? help "=> nil")))
      (let [doc (codemode/evaluate handle "(doc call)")]
        (is (str/includes? doc "agent/call\n((which path & [opts]))\n  An HTTP request through the gateway"))))
    (sync-fetch/stop!)))

(deftest facts-test
  (let [handle (codemode/handle "http://127.0.0.1:1")
        at 1700000000000]
    (codemode/begin! handle [{:event "review-posted" :at at :id 4711 :fact/id "e1"}])
    (testing "what a program recalls, and what it remembers is pending for the commit and recalled at once"
      (is (= "=> [4711]" (codemode/evaluate handle "(mapv :id (recall {:event \"review-posted\"}))")))
      (is (= "=> [\"Noted review-posted\" 2]" (codemode/evaluate handle "[(remember \"review-posted\" {:id 4712 :stars 4}) (count (recall))]")))
      (is (= "=> []" (codemode/evaluate handle "(recall {:since \"2099-01-01\"})")))
      (let [pending (codemode/pending handle)]
        (is (= 1 (count pending)))
        (is (= {:event "review-posted" :id 4712 :stars 4} (dissoc (first pending) :at)))
        (is (number? (:at (first pending))))))
    (testing "the data is a map, the event a name"
      (is (str/includes? (failure #(codemode/evaluate handle "(remember \"x\" 1)")) "remember takes a map of data"))
      (is (str/includes? (failure #(codemode/evaluate handle "(remember \"\" {})")) "remember takes an event name")))
    (testing "the next program starts with the facts given to it"
      (codemode/begin! handle [])
      (is (= "=> 0" (codemode/evaluate handle "(count (recall))")))
      (is (= [] (codemode/pending handle))))
    (sync-fetch/stop!)))

(deftest composio-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (with-env "COMPOSIO_SESSION_ID" "trs_1"
              (fn []
                (testing "the four meta tools, each one execute_meta of the session through the Composio connection, the search session kept"
                  (is (= "=> {:session \"trs_1\", :slug \"COMPOSIO_SEARCH_TOOLS\", :arguments {:queries [{:use_case \"send an email\"}], :session {:generate_id true}, :toolkits [\"gmail\"]}}"
                         (codemode/evaluate handle "(:echo (composio/search \"send an email\" [\"gmail\"]))")))
                  (is (= "=> {:session \"trs_1\", :slug \"COMPOSIO_GET_TOOL_SCHEMAS\", :arguments {:tool_slugs [\"GMAIL_SEND_EMAIL\"], :session_id \"search_1\"}}"
                         (codemode/evaluate handle "(:echo (composio/schemas [\"GMAIL_SEND_EMAIL\"]))")))
                  (is (= "=> [{:index 0, :tool_slug \"GMAIL_SEND_EMAIL\", :response {:sent true}}]"
                         (codemode/evaluate handle "(:results (composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\" :subject \"Hi\"}))")))
                  (is (= "=> {:tools [{:tool_slug \"GMAIL_SEND_EMAIL\", :arguments {:to \"a@example.com\", :subject \"Hi\"}}], :session_id \"search_1\"}"
                         (codemode/evaluate handle "(get-in (composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\" :subject \"Hi\"}) [:echo :arguments])")))
                  (is (= "=> \"https://connect.example/gmail\""
                         (codemode/evaluate handle "(get-in (composio/connections [\"gmail\"]) [:results :gmail :redirect_url])"))))
                (testing "a failed run throws with Composio's error and the data"
                  (let [message (failure #(codemode/evaluate handle "(composio/execute \"SLACK_SEND_MESSAGE\" {:channel \"#x\"})"))]
                    (is (str/includes? message "COMPOSIO_MULTI_EXECUTE_TOOL failed: SLACK_SEND_MESSAGE: slack is not connected"))
                    (is (str/includes? message "\"tool_slug\":\"SLACK_SEND_MESSAGE\""))))
                (testing "a program catches it"
                  (is (= "=> \"no slack\"" (codemode/evaluate handle "(try (composio/execute \"SLACK_SEND_MESSAGE\" {}) (catch :default e (if (ex-data e) \"no slack\" \"?\")))"))))))
            (testing "a session Composio does not know"
              (with-env "COMPOSIO_SESSION_ID" "trs_dead"
                (fn []
                  (is (str/includes? (failure #(codemode/evaluate handle "(composio/search \"x\")")) "Composio answered HTTP 404 to COMPOSIO_SEARCH_TOOLS: Session not found")))))
            (testing "without the session"
              (with-env "COMPOSIO_SESSION_ID" nil
                (fn []
                  (is (str/includes? (failure #(codemode/evaluate handle "(composio/search \"x\")")) "No Composio session"))
                  (is (str/includes? (codemode/evaluate handle "(help)") "Composio: no session yet")))))))
        (.finally done))))

(deftest no-composio-test
  (async done
    (-> (with-fake {:connections (remove #(= "conn_composio" (:id %)) fake-gateway/connections) :available []}
          (fn [_url handle]
            (with-env "COMPOSIO_SESSION_ID" "trs_1"
              (fn []
                (is (str/includes? (failure #(codemode/evaluate handle "(composio/search \"x\")")) "No connection to Composio: no custom_api connection of the gateway goes to backend.composio.dev"))
                (is (str/includes? (codemode/evaluate handle "(help)") "Composio: no connection"))))))
        (.finally done))))

(deftest storrito-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "the catalog's procedures are functions with docstrings"
              (is (= "=> [\"generate-uuid\" \"status-instagram-story\" \"flaky-once\"]" (codemode/evaluate handle "(mapv :name (storrito/procedures))")))
              (let [doc (codemode/evaluate handle "(doc storrito/status-instagram-story)")]
                (is (str/includes? doc "storrito/status-instagram-story\n([] [args])\n  The status of a story post.\n\nSecond paragraph.\n\nParameters, a map:\n  storyPostUuid (string), required: The UUID of the story post\n  verbose (boolean)\n  limit (integer)\n  destination\n"))))
            (testing "a call is the procedure's POST through the connection, the answer as data"
              (is (= "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" (codemode/evaluate handle "(storrito/generate-uuid)")))
              (is (= "=> {:status \"scheduled\", :params {:storyPostUuid \"u1\", :limit 2}}" (codemode/evaluate handle "(storrito/status-instagram-story {:storyPostUuid \"u1\" :limit 2})"))))
            (testing "what the API said about a bad call reaches the program"
              (let [message (failure #(codemode/evaluate handle "(storrito/status-instagram-story {})"))]
                (is (str/includes? message "Storrito answered HTTP 400 to status-instagram-story"))
                (is (str/includes? message "missing required key"))))
            (testing "a 503 is tried again"
              (is (= "=> {:ok true}" (codemode/evaluate handle "(storrito/flaky-once)"))))))
        (.finally done))))

(deftest agent-uuid-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "the agent's uuid is the machine's agent: label"
              (binding [sprite/*sprite-env* (:script (fake-sprite-env/make! {:discover (fake-sprite-env/discover-json ["token:test123" "agent:7b1b2c3d-0000-4000-8000-000000000042"])}))]
                (is (= "=> \"7b1b2c3d-0000-4000-8000-000000000042\"" (codemode/evaluate handle "(storrito/agent-uuid)")))))
            (testing "a machine without the label"
              (binding [sprite/*sprite-env* (:script (fake-sprite-env/make! {:discover (fake-sprite-env/discover-json ["token:test123"])}))]
                (is (str/includes? (failure #(codemode/evaluate handle "(storrito/agent-uuid)")) "No agent uuid"))))
            (testing "help names it and the trigger recipe"
              (let [help (codemode/evaluate handle "(help)")]
                (is (str/includes? help "(storrito/agent-uuid)"))
                (is (str/includes? help "(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\" {})"))))))
        (.finally done))))

(deftest no-storrito-test
  (async done
    (-> (with-fake {:connections (remove #(= "conn_storrito" (:id %)) fake-gateway/connections) :available []}
          (fn [_url handle]
            (is (str/includes? (failure #(codemode/evaluate handle "(storrito/procedures)")) "No Storrito among the connections"))
            (is (str/includes? (failure #(codemode/evaluate handle "(storrito/generate-uuid)")) "Unable to resolve symbol: storrito/generate-uuid"))
            (is (str/includes? (codemode/evaluate handle "(help)") "Storrito: no connection with a catalog"))))
        (.finally done))))

(deftest composio-triggers-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (with-env "COMPOSIO_SESSION_ID" "trs_1"
              (fn []
                (with-env "COMPOSIO_USER_ID" "agent-1"
                  (fn []
                    (testing "the accounts and the trigger types of the agent's user id, through the Composio connection"
                      (is (= "=> [{:id \"ca_1\", :toolkit \"googledrive\", :status \"ACTIVE\"}]"
                             (codemode/evaluate handle "(composio/accounts)")))
                      (is (= "=> [\"GOOGLEDRIVE_FILE_CREATED_TRIGGER\"]"
                             (codemode/evaluate handle "(mapv :slug (composio/trigger-types \"googledrive\"))"))))
                    (testing "a trigger added is noted as a fact, with the user id named for Composio, and listed; deleted it is noted too"
                      (codemode/begin! handle [])
                      (is (= "=> \"ti_1\"" (codemode/evaluate handle "(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\")")))
                      (is (= [{:event "composio-trigger" :id "ti_1" :slug "GOOGLEDRIVE_FILE_CREATED_TRIGGER" :account "ca_1"}]
                             (mapv #(dissoc % :at) (codemode/pending handle))))
                      (is (= "=> [{:id \"ti_1\", :slug \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\", :connected-account \"ca_1\", :state \"active\"}]"
                             (codemode/evaluate handle "(composio/triggers)")))
                      (is (= "=> \"Deleted ti_1\"" (codemode/evaluate handle "(composio/delete-trigger \"ti_1\")")))
                      (is (= ["composio-trigger" "composio-trigger-deleted"] (mapv :event (codemode/pending handle))))
                      (is (= "=> []" (codemode/evaluate handle "(composio/triggers)"))))
                    (testing "help names the session and the user id"
                      (is (str/includes? (codemode/evaluate handle "(help)") "session trs_1, your accounts and triggers under agent-1")))))
                (testing "without a user id a trigger cannot be added: Composio wants the account's owner"
                  (with-env "COMPOSIO_USER_ID" nil
                    (fn []
                      (is (str/includes? (failure #(codemode/evaluate handle "(composio/add-trigger \"X\" \"ca_1\")")) "No Composio user id")))))))))
        (.finally done))))

(def mcp-catalog
  "Two MCP servers as `rragent.mcp/snapshot` answers them: one with
   tools, one that failed."
  {:version 1
   :servers [{:name "fake" :ns "mcp.fake" :connected true :instructions "Use echo to test."
              :tools [{:name "echo" :description "Answers with the text it was given."
                       :input-schema {"type" "object"
                                      "properties" {"text" {"type" "string" "description" "The text to echo"}
                                                    "mode" {"type" "string" "enum" ["loud" "soft"]}}
                                      "required" ["text"]}}
                      {:name "files.read" :description "A dotted name." :input-schema {"type" "object"}}
                      {:name "picture" :title "A picture" :description "" :input-schema {"type" "object"}}
                      {:name "fail" :description "Fails." :input-schema {"type" "object"}}]}
             {:name "down" :ns "mcp.down" :tools [] :error "connection refused"}]})

(defn- mcp-handle
  "A REPL with the catalog above, its calls answered by a fake of the
   main thread's half, which notes them in `calls`."
  [calls]
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (reset! (:mcp-rpc handle)
            (fn [{:keys [tool args] :as request}]
              (swap! calls conj request)
              (case tool
                "echo" {:value (str "echo: " (:text args)) :images []}
                "files.read" {:value {:read true} :images []}
                "picture" {:value ["a dot" "[picture, image/png, shown to you after the program's answer]"]
                           :images [{:data "iVBO" :mime-type "image/png"}]}
                {:error (str "mcp.fake/" tool " failed: it broke")})))
    (codemode/install-mcp! handle mcp-catalog)
    (codemode/begin! handle [])
    handle))

(deftest mcp-test
  (let [calls (atom [])
        handle (mcp-handle calls)]
    (testing "a tool is a function of its server's namespace, a map of arguments in, its value out"
      (is (= "=> \"echo: hi\"" (codemode/evaluate handle "(mcp.fake/echo {:text \"hi\"})")))
      (is (= {:server "fake" :tool "echo" :args {:text "hi"}} (last @calls))))
    (testing "a name that is no symbol: its symbol, or mcp/call by the name itself"
      (is (= "=> {:read true}" (codemode/evaluate handle "(mcp.fake/files-read)")))
      (is (= {:server "fake" :tool "files.read" :args nil} (last @calls)))
      (is (= "=> {:read true}" (codemode/evaluate handle "(mcp/call \"fake\" \"files.read\" {})")))
      (is (= {:server "fake" :tool "files.read" :args {}} (last @calls))))
    (testing "a failure throws with what the server said, which a program can catch"
      (is (str/includes? (failure #(codemode/evaluate handle "(mcp.fake/fail {})")) "mcp.fake/fail failed: it broke"))
      (is (= "=> \"mcp.fake/fail failed: it broke\"" (codemode/evaluate handle "(try (mcp.fake/fail {}) (catch :default e (ex-message e)))"))))
    (testing "arguments that are no map are refused before the call"
      (let [before (count @calls)]
        (is (str/includes? (failure #(codemode/evaluate handle "(mcp.fake/echo \"hi\")")) "takes a map of arguments"))
        (is (= before (count @calls)))))
    (testing "the pictures of a program's calls are kept for its answer, begin! forgets them"
      (codemode/evaluate handle "(mcp.fake/picture)")
      (is (= [{:data "iVBO" :mime-type "image/png"}] (codemode/images handle)))
      (codemode/begin! handle [])
      (is (= [] (codemode/images handle))))
    (testing "doc says the tool's arguments from its schema"
      (let [doc (codemode/evaluate handle "(doc mcp.fake/echo)")]
        (is (str/includes? doc "mcp.fake/echo\n([args])\n  Answers with the text it was given.\n\nThe tool echo of the MCP server fake. It takes a map:\n  :text (string, required) The text to echo\n  :mode (string, one of \"loud\" \"soft\")")))
      (is (str/includes? (codemode/evaluate handle "(doc mcp.fake/picture)") "A picture\nThe tool picture of fake.")))
    (testing "mcp/servers and mcp/tools say what there is"
      (is (= "=> [{:name \"fake\", :ns \"mcp.fake\", :tools 4, :connected true, :instructions \"Use echo to test.\"} {:name \"down\", :ns \"mcp.down\", :tools 0, :error \"connection refused\"}]"
             (codemode/evaluate handle "(mcp/servers)")))
      (is (= "=> {:name \"files.read\", :fn mcp.fake/files-read, :description \"A dotted name.\"}"
             (codemode/evaluate handle "(second (mcp/tools \"fake\"))")))
      (is (str/includes? (failure #(codemode/evaluate handle "(mcp/tools \"ghost\")")) "There is no MCP server ghost")))
    (testing "help names the servers"
      (is (str/includes? (codemode/help-text handle) "MCP: fake as mcp.fake/<tool>, 4 tools, down (failed: connection refused)."))
      (is (str/includes? (codemode/help-text handle) "(mcp/servers)")))
    (testing "servers installed after the context was made are there for the next program"
      (codemode/install-mcp! handle {:version 2 :servers [{:name "late" :ns "mcp.late" :tools [{:name "echo" :input-schema {}}]}]})
      (is (= "=> \"echo: late\"" (codemode/evaluate handle "(mcp.late/echo {:text \"late\"})")))
      (is (= {:server "late" :tool "echo" :args {:text "late"}} (last @calls))))))

(deftest no-mcp-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (is (= "=> []" (codemode/evaluate handle "(mcp/servers)")))
    (is (str/includes? (codemode/help-text handle) "MCP: no servers (mcp.json of the machine's folder names them)."))
    (is (str/includes? (failure #(codemode/evaluate handle "(mcp/call \"x\" \"y\" {})")) "The MCP servers are not reachable from this REPL."))))
