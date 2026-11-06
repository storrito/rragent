(ns rragent.codemode-test
  "The core against the fake gateway: discovery, a program loaded for
   its state, a step on a state, the functions answering calls as
   data, the closed context, help and doc, the Storrito catalog and
   the MCP servers as calls."
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

(defn- state
  "The initial state `code` loads to in `handle`."
  [handle code]
  (:state (codemode/load handle code)))

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
              (is (str/includes? (codemode/report handle) "custom_api conn_storrito (127.0.0.1) = Storrito, 4 procedures"))
              (is (str/includes? (codemode/report handle) "available: github")))
            (testing "(connectors) is a call, (connector ...) answers from the reading at start"
              (is (= {:next {:call [:connectors] :as :c}} (state handle "{:next {:call (connectors) :as :c}}")))
              (is (= {:id "conn_echo"} (state handle "{:id (:id (connector \"echo\"))}"))))
            (testing "the main thread reads the list again itself"
              (is (= 5 (count (:connections (codemode/refresh! handle)))))
              (is (pos? (codemode/asked-at handle))))))
        (.finally done))))

(deftest no-gateway-test
  (testing "no sprite around: an empty list that says why, and help says so"
    (let [handle (codemode/handle "http://127.0.0.1:1")
          {:keys [connections available error]} (codemode/discovery handle)]
      (is (= [] connections))
      (is (= [] available))
      (is (str/includes? (str error) "failed"))
      (is (str/starts-with? (codemode/report handle) "no gateway at http://127.0.0.1:1 ("))
      (is (str/includes? (:help (state handle "(help)")) "did not answer"))
      (is (str/includes? (failure #(codemode/load handle "(call \"github\" \"/user\")")) "Nothing is connected. The gateway did not answer"))
      (is (str/includes? (failure #(codemode/load handle "(storrito/procedures)")) "No Storrito among the connections"))
      (sync-fetch/stop!))))

(deftest load-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (testing "a program's value is its state, a map that reads back"
      (is (= {:state {:a 1 :b [1 2]} :step? false :printed "hi\n"} (codemode/load handle "(println \"hi\") {:a 1 :b [1 2]}")))
      (is (= {:state {} :step? false :printed ""} (codemode/load handle "{}"))))
    (testing "step is seen"
      (is (= {:state {:n 0} :step? true :printed ""} (codemode/load handle "(defn step [s] (update s :n inc)) {:n 0}")))
      (is (= {:state {:n 1} :printed ""} (codemode/step handle {:n 0})))
      (is (= {:state {:n 6 :x 1} :printed ""} (codemode/step handle {:n 5 :x 1})) "a step is a function of the state it is given"))
    (testing "a step runs in the core's own context: read-string and its kin work there"
      (codemode/load handle "(defn step [s] (assoc s :v (read-string (:text s)) :e (edn/read-string (:text s)))) {}")
      (is (= {:state {:text "[1 2]" :v [1 2] :e [1 2]} :printed ""} (codemode/step handle {:text "[1 2]"}))))
    (testing "a value that is no map, no data, or a step that is no function"
      (is (str/includes? (failure #(codemode/load handle "(+ 1 2)")) "A program's value is a map, the state of the machine"))
      (is (str/includes? (failure #(codemode/load handle "nil")) "A program's value is a map"))
      (is (str/includes? (failure #(codemode/load handle "{:f (fn [x] x)}")) "must be data that reads back as EDN"))
      (is (str/includes? (failure #(codemode/load handle "{:r #\"a\"}")) "must be data"))
      (is (str/includes? (failure #(codemode/load handle "(def step 1) {}")) "step is a function"))
      (codemode/load handle "(defn step [s] (assoc s :f (fn [] 1))) {}")
      (is (str/includes? (failure #(codemode/step handle {})) "A step's value must be data")))
    (testing "a call written as a statement is refused at load: it would do nothing"
      (is (str/includes? (failure #(codemode/load handle "(remember \"seen\" {:n 1}) {:done true}"))
                         "(remember \"seen\" {:n 1}) is a call, data that does nothing by itself: put it under :next of the state, {:next {:call (remember \"seen\" {:n 1}) :as :remember}}"))
      (is (str/includes? (failure #(codemode/load handle "(spit \"data/x.edn\" {:a 1})\n{}")) "is a call, data that does nothing by itself"))
      (is (= {:c [:recall {}]} (state handle "(def c (recall)) {:c c}")) "a def of a call is fine, the value is a var")
      (is (= {:next {:call [:recall {}] :as :events}} (state handle "{:next {:call (recall) :as :events}}")) "under :next as it should be"))
    (testing "a program is one of its own: a def is there for its steps, not for the next program"
      (is (= {:n 21} (state handle "(def n 21) {:n n}")))
      (is (str/includes? (failure #(codemode/load handle "{:n n}")) "Unable to resolve symbol: n"))
      (codemode/load handle "(def k 3) (defn step [s] (assoc s :k k)) {}")
      (is (= {:k 3} (:state (codemode/step handle {})))))
    (testing "an error says where, and what was printed before it"
      (let [message (failure #(codemode/load handle "(println \"so far\")\n(undefined-fn 1)"))]
        (is (str/includes? message "Unable to resolve symbol: undefined-fn (line 2, column 1)"))
        (is (str/includes? message "Printed before the error:\nso far")))
      (codemode/load handle "(defn step [s] (println \"in step\") (throw (ex-info \"boom\" {:x 1}))) {}")
      (let [message (failure #(codemode/step handle {}))]
        (is (str/includes? message "boom"))
        (is (str/includes? message "Printed before the error:\nin step"))))
    (testing "what was printed is cut"
      (is (str/ends-with? (:printed (codemode/load handle "(print (apply str (repeat 20000 \"a\"))) {}")) "(cut at 10000 characters)")))
    (sync-fetch/stop!)))

(deftest closed-context-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (testing "nothing of the host: no js/, no require of a module, no environment, no interop on values"
      (is (str/includes? (failure #(codemode/load handle "(.-title js/process)")) "Unable to resolve symbol: js/process"))
      (is (str/includes? (failure #(codemode/load handle "(js/require \"fs\")")) "Unable to resolve symbol: js/require"))
      (is (str/includes? (failure #(codemode/load handle "(require '[\"node:fs\" :as fs])")) "Could not find namespace node:fs"))
      (is (str/includes? (failure #(codemode/load handle "(require 'rragent.harness)")) "Could not find namespace rragent.harness"))
      (is (str/includes? (failure #(codemode/load handle "(.-constructor (fn []))")) "not allowed"))
      (is (str/includes? (failure #(codemode/load handle "(aget (fn []) \"constructor\")")) "aget is not available in a machine"))
      (is (str/includes? (failure #(codemode/load handle "(js-obj)")) "js-obj is not available in a machine")))
    (testing "Clojure's own namespaces are there, EDN at hand"
      (is (= {:u #{1 2}} (state handle "(require '[clojure.set :as set]) {:u (set/union #{1} #{2})}")))
      (is (= {:s "a-b"} (state handle "{:s (clojure.string/join \"-\" [\"a\" \"b\"])}")))
      (is (= {:m {:a 1}} (state handle "{:m (edn/read-string \"{:a 1}\")}"))))
    (testing "parse-long and parse-double, which models expect"
      (is (= {:n 42 :m -7 :x nil :d 1.5 :e nil} (state handle "{:n (parse-long \"42\") :m (parse-long \"-7\") :x (parse-long \"4x\") :d (parse-double \"1.5\") :e (parse-double \"\")}"))))
    (testing "rand and the clock are free: nothing is replayed"
      (is (number? (:r (state handle "{:r (rand)}")))))
    (sync-fetch/stop!)))

(deftest calls-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "every function of a connector answers its call, data, which goes under :next"
              (is (= {:next {:call [:call "echo" "/x" {:method :post :body {:a 1}}] :as :r}}
                     (state handle "{:next {:call (call \"echo\" \"/x\" {:method :post :body {:a 1}}) :as :r}}")))
              (is (= [:call "echo" "/x" {}] (:c (state handle "{:c (call \"echo\" \"/x\")}"))))
              (is (= [:remember "seen" {:n 1}] (:c (state handle "{:c (remember \"seen\" {:n 1})}"))))
              (is (= [:recall {:event "seen"}] (:c (state handle "{:c (recall {:event \"seen\"})}"))))
              (is (= [:recall {}] (:c (state handle "{:c (recall)}"))))
              (is (= [:spit "data/x.edn" "{:a 1}"] (:c (state handle "{:c (spit \"data/x.edn\" {:a 1})}"))) "data as EDN")
              (is (= [:spit "notes.txt" "hello"] (:c (state handle "{:c (spit \"notes.txt\" \"hello\")}"))) "a string as it is")
              (is (= [:slurp "data/x.edn"] (:c (state handle "{:c (slurp \"data/x.edn\")}")))))
            (testing "what is refused before the host is asked"
              (is (str/includes? (failure #(codemode/load handle "(call \"github\" \"/user\")")) "No connection \"github\". Connected: echo conn_echo"))
              (is (str/includes? (failure #(codemode/load handle "(remember \"\" {})")) "remember takes an event name"))
              (is (str/includes? (failure #(codemode/load handle "(remember \"x\" 1)")) "remember takes a map of data"))
              (is (str/includes? (failure #(codemode/load handle "(spit \"/etc/passwd\" {})")) "inside your work folder"))
              (is (str/includes? (failure #(codemode/load handle "(slurp \"../secrets.json\")")) "inside your work folder"))
              (is (str/includes? (failure #(codemode/load handle "(spit \"data/f.edn\" (fn [x] x))")) "spit writes data that reads back as EDN")))
            (testing "the composio/ calls"
              (is (= [:composio/search "send an email" ["gmail"]] (:c (state handle "{:c (composio/search \"send an email\" [\"gmail\"])}"))))
              (is (= [:composio/search "x" []] (:c (state handle "{:c (composio/search \"x\")}"))))
              (is (= [:composio/schemas ["GMAIL_SEND_EMAIL"]] (:c (state handle "{:c (composio/schemas [\"GMAIL_SEND_EMAIL\"])}"))))
              (is (= [:composio/execute "GMAIL_SEND_EMAIL" {:to "a"}] (:c (state handle "{:c (composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a\"})}"))))
              (is (= [:composio/connections ["gmail"]] (:c (state handle "{:c (composio/connections [\"gmail\"])}"))))
              (is (= [:composio/accounts] (:c (state handle "{:c (composio/accounts)}"))))
              (is (= [:composio/trigger-types "googledrive"] (:c (state handle "{:c (composio/trigger-types \"googledrive\")}"))))
              (is (= [:composio/triggers] (:c (state handle "{:c (composio/triggers)}"))))
              (is (= [:composio/add-trigger "T" "ca_1" {}] (:c (state handle "{:c (composio/add-trigger \"T\" \"ca_1\")}"))))
              (is (= [:composio/delete-trigger "ti_1"] (:c (state handle "{:c (composio/delete-trigger \"ti_1\")}"))))
              (is (str/includes? (failure #(codemode/load handle "(composio/execute \"X\" 1)")) "takes a map of arguments"))
              (is (str/includes? (failure #(codemode/load handle "(composio/connections \"gmail\")")) "takes a vector of toolkit slugs")))))
        (.finally done))))

(deftest storrito-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "the catalog's procedures are functions with docstrings, each answering its call"
              (is (= {:p ["generate-uuid" "status-instagram-story" "flaky-once" "create-note"]} (state handle "{:p (mapv :name (storrito/procedures))}")))
              (let [doc (:doc (state handle "(doc storrito/status-instagram-story)"))]
                (is (str/starts-with? doc "storrito/status-instagram-story\n([] [args])\n  A call, for :next: answers [:storrito/status-instagram-story args].\n\nThe status of a story post."))
                (is (str/includes? doc "storyPostUuid (string), required: The UUID of the story post")))
              (is (= [:storrito/generate-uuid {}] (:c (state handle "{:c (storrito/generate-uuid)}"))))
              (is (= [:storrito/create-note {:text "a"}] (:c (state handle "{:c (storrito/create-note {:text \"a\"})}"))))
              (is (str/includes? (failure #(codemode/load handle "(storrito/create-note \"a\")")) "storrito/create-note takes a map of arguments")))))
        (.finally done))))

(deftest agent-uuid-test
  (async done
    (-> (with-fake nil
          (fn [_url handle]
            (testing "the agent's uuid is the machine's agent: label"
              (binding [sprite/*sprite-env* (:script (fake-sprite-env/make! {:discover (fake-sprite-env/discover-json ["token:test123" "agent:7b1b2c3d-0000-4000-8000-000000000042"])}))]
                (is (= {:u "7b1b2c3d-0000-4000-8000-000000000042"} (state handle "{:u (storrito/agent-uuid)}")))))
            (testing "a machine without the label"
              (binding [sprite/*sprite-env* (:script (fake-sprite-env/make! {:discover (fake-sprite-env/discover-json ["token:test123"])}))]
                (is (str/includes? (failure #(codemode/load handle "{:u (storrito/agent-uuid)}")) "No agent uuid"))))
            (testing "help names it and the trigger recipe"
              (let [help (:help (state handle "(help)"))]
                (is (str/includes? help "(storrito/agent-uuid)"))
                (is (str/includes? help "(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\" {})"))))))
        (.finally done))))

(deftest no-storrito-test
  (async done
    (-> (with-fake {:connections (remove #(= "conn_storrito" (:id %)) fake-gateway/connections) :available []}
          (fn [_url handle]
            (is (str/includes? (failure #(codemode/load handle "{:p (storrito/procedures)}")) "No Storrito among the connections"))
            (is (str/includes? (failure #(codemode/load handle "{:c (storrito/generate-uuid)}")) "Unable to resolve symbol: storrito/generate-uuid"))
            (is (str/includes? (:help (state handle "(help)")) "Storrito: no connection with a catalog"))))
        (.finally done))))

(deftest help-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (testing "help and doc are maps, a program of them is a machine that runs nothing"
      (let [help (:help (state handle "(help)"))]
        (is (str/starts-with? help "A machine: Clojure with the organization's connectors as calls"))
        (is (str/includes? help "{:next {:call (storrito/list-social-accounts {}) :as :accounts}}"))
        (is (str/includes? help "(defn step [state] ...)"))
        (is (str/includes? help "(composio/connections [\"googlesheets\"])\n    a call: the connections of services by toolkit slug"))
        (is (str/includes? help "good for ten minutes")))
      (is (= {:doc "agent/call\n((which path & [opts]))\n  A call, for :next: an HTTP request through the gateway to a\n   connection, `which` its id, or its provider name when one\n   connection has it (several custom_api connections: name the id).\n   `path` is appended to the connection's base, the gateway adds the\n   credential. `opts`: :method (GET), :body (data goes as JSON, a\n   string as it is), :headers (a map), :query (a map, appended to the\n   URL), :to-file (a path under your work folder: the body is written\n   there, the answer carries the file instead of the body; for\n   anything big or binary). The answer under :as is {:status :headers\n   :body}, the body as data when it is JSON; a failed request\n   {:error ...}. A connection that is not there is refused here."}
             (state handle "(doc call)")))
      (is (str/includes? (failure #(codemode/load handle "(doc nope)")) "No such function: nope")))
    (sync-fetch/stop!)))

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

(deftest mcp-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (codemode/install-mcp! handle mcp-catalog)
    (testing "a tool is a function of its server's namespace that answers its call"
      (is (= [:mcp/call "fake" "echo" {:text "hi"}] (:c (state handle "{:c (mcp.fake/echo {:text \"hi\"})}"))))
      (is (= [:mcp/call "fake" "files.read" {}] (:c (state handle "{:c (mcp.fake/files-read)}"))) "a name that is no symbol: its symbol")
      (is (= [:mcp/call "fake" "files.read" {}] (:c (state handle "{:c (mcp/call \"fake\" \"files.read\" {})}"))) "or mcp/call by the name itself")
      (is (str/includes? (failure #(codemode/load handle "{:c (mcp.fake/echo \"hi\")}")) "takes a map of arguments")))
    (testing "doc says the tool's arguments from its schema"
      (let [doc (:doc (state handle "(doc mcp.fake/echo)"))]
        (is (str/includes? doc "mcp.fake/echo\n([args])\n  Answers with the text it was given.\n\nA call, for :next: answers [:mcp/call \"fake\" \"echo\" args]. It takes a map:\n  :text (string, required) The text to echo\n  :mode (string, one of \"loud\" \"soft\")")))
      (is (str/includes? (:doc (state handle "(doc mcp.fake/picture)")) "A picture\nThe tool picture of fake.")))
    (testing "mcp/servers and mcp/tools say what there is, pure"
      (is (= [{:name "fake" :ns "mcp.fake" :tools 4 :connected true :instructions "Use echo to test."} {:name "down" :ns "mcp.down" :tools 0 :error "connection refused"}]
             (:s (state handle "{:s (mcp/servers)}"))))
      (is (= {:name "files.read" :fn 'mcp.fake/files-read :description "A dotted name."} (:t (state handle "{:t (second (mcp/tools \"fake\"))}"))))
      (is (str/includes? (failure #(codemode/load handle "{:t (mcp/tools \"ghost\")}")) "There is no MCP server ghost")))
    (testing "help names the servers"
      (is (str/includes? (codemode/help-text handle) "MCP: fake as mcp.fake/<tool>, 4 tools, down (failed: connection refused)."))
      (is (str/includes? (codemode/help-text handle) "(mcp/servers)")))
    (testing "the changes of the servers are calls"
      (is (= [:mcp/add! "gh" {:url "https://gh.example/mcp" :token "GH_TOKEN"}] (:c (state handle "{:c (mcp/add! \"gh\" {:url \"https://gh.example/mcp\" :token \"GH_TOKEN\"})}"))))
      (is (= [:mcp/test "gh"] (:c (state handle "{:c (mcp/test 'gh)}"))))
      (is (= [:mcp/enable! "gh" false] (:c (state handle "{:c (mcp/enable! \"gh\" false)}"))))
      (is (= [:mcp/remove! "gh"] (:c (state handle "{:c (mcp/remove! \"gh\")}"))))
      (is (str/includes? (:doc (state handle "(doc mcp/add!)")) "[:secret {:name")))
    (testing "servers installed after the context was made are there for the next program"
      (codemode/install-mcp! handle {:version 2 :servers [{:name "late" :ns "mcp.late" :tools [{:name "echo" :input-schema {}}]}]})
      (is (= [:mcp/call "late" "echo" {:text "late"}] (:c (state handle "{:c (mcp.late/echo {:text \"late\"})}")))))
    (sync-fetch/stop!)))

(deftest no-mcp-test
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (is (= {:s []} (state handle "{:s (mcp/servers)}")))
    (is (str/includes? (codemode/help-text handle) "MCP: no servers yet; (mcp/add! …) connects one."))
    (is (= [:mcp/call "x" "y" {}] (:c (state handle "{:c (mcp/call \"x\" \"y\" {})}"))) "a call, whatever the servers; the host says what there is")
    (sync-fetch/stop!)))
