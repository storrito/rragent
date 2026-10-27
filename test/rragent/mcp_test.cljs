(ns rragent.mcp-test
  "The MCP servers of the machine against the fake server
   (`rragent.fake-mcp`): the file and its variables, the catalog, a
   call's value for text, JSON, structured content, pictures and a
   failure, progress, cancellation and the timeout, a session the
   server forgot, a connection that closed, tools that changed, and
   a file that changed; and pi-mcp's own transports: a program over
   stdio, a server over HTTP with a token from the environment."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.fake-mcp :as fake-mcp]
            [rragent.mcp :as mcp]
            [rragent.test-home :as test-home]))

(defn- write-servers!
  [home servers]
  (fs/writeFileSync (mcp/file home) (js/JSON.stringify (clj->js {:mcpServers servers}))))

(defn- registry
  "A registry over `servers` in a fresh folder, its transports the
   fake's."
  [fake servers]
  (let [home (test-home/make)]
    (write-servers! home servers)
    (mcp/registry {:file (mcp/file home) :home home :transport (:transport-fn fake)})))

(defn- run
  "`f` as an async test, the registry it returns closed after."
  [done f]
  (-> ((^:async fn []
         (let [registry (await (f))]
           (when registry
             (await (mcp/close! registry))))))
      (.catch (fn [e] (is (nil? e) (str e))))
      (.finally done)))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest names-test
  (is (= "search_issues" (mcp/symbol-name "search_issues")))
  (is (= "files-read" (mcp/symbol-name "files.read")))
  (is (= "a-b-c" (mcp/symbol-name "a/b c")))
  (is (= "t3d" (mcp/symbol-name "3d")))
  (is (= "mcp.storrito-agent" (mcp/ns-name-of "storrito-agent"))))

(deftest transport-spec-test
  (let [env (.-env js/process)]
    (aset env "RRAGENT_TEST_TOKEN" "t0k")
    (try
      (testing "a URL with headers, ${NAME} filled in from the environment"
        (is (= {:kind :http :url "https://a.b/mcp" :headers {"Authorization" "Bearer t0k"}}
               (mcp/transport-spec {"url" "https://a.b/mcp" "headers" {"Authorization" "Bearer ${RRAGENT_TEST_TOKEN}"}} "/home"))))
      (testing "a program, its cwd the machine's folder or one relative to it"
        (is (= {:kind :stdio :command "npx" :args ["-y" "server" "t0k"] :env {"KEY" "t0k"} :cwd "/home"}
               (mcp/transport-spec {"command" "npx" "args" ["-y" "server" "${RRAGENT_TEST_TOKEN}"] "env" {"KEY" "${RRAGENT_TEST_TOKEN}"}} "/home")))
        (is (= "/home/srv" (:cwd (mcp/transport-spec {"command" "x" "cwd" "srv"} "/home")))))
      (testing "what cannot be reached says why"
        (is (= {:error "the environment variable RRAGENT_TEST_MISSING is not set"}
               (mcp/transport-spec {"url" "https://a.b/mcp" "headers" {"X" "${RRAGENT_TEST_MISSING}"}} "/home")))
        (is (= {:error "the entry has neither a url nor a command"} (mcp/transport-spec {} "/home")))
        (is (= {:error "the url must start with https:// or http://"} (mcp/transport-spec {"url" "ftp://x"} "/home")))
        (is (str/includes? (:error (mcp/transport-spec {"url" "https://a.b/sse" "type" "sse"} "/home")) "legacy")))
      (finally
        (js-delete env "RRAGENT_TEST_TOKEN")))))

(deftest read-servers-test
  (let [home (test-home/make)]
    (try
      (is (= {} (mcp/read-servers (mcp/file home))) "no file, no servers")
      (fs/writeFileSync (mcp/file home) "{nope")
      (is (str/starts-with? (get-in (mcp/read-servers (mcp/file home)) ["mcp.json" :error]) "the file is not JSON"))
      (fs/writeFileSync (mcp/file home) "{\"servers\": {}}")
      (is (= {"mcp.json" {:error "the file has no \"mcpServers\" object"}} (mcp/read-servers (mcp/file home))))
      (finally
        (test-home/remove! home)))))

(deftest catalog-test
  (async done
    (run done
         (^:async fn []
           (let [fake (fake-mcp/fake)
                 registry (registry fake {:fake {:url "https://fake.example/mcp"}
                                          :quiet {:url "https://quiet.example/mcp" :enabled false}
                                          :broken {:args ["x"]}
                                          "bad name" {:url "https://x.example/mcp"}})
                 {:keys [version servers]} (await (mcp/catalog registry))
                 by-name (into {} (map (juxt :name identity)) servers)
                 fake-server (by-name "fake")]
             (testing "a server's tools, every page of them, its namespace and instructions"
               (is (= (mapv :name fake-mcp/tools) (mapv :name (:tools fake-server))))
               (is (= "mcp.fake" (:ns fake-server)))
               (is (= "Use echo to test." (:instructions fake-server)))
               (is (:connected fake-server))
               (is (= {"type" "object" "properties" {"text" {"type" "string" "description" "The text to echo"}} "required" ["text"]}
                      (:input-schema (first (:tools fake-server))))))
             (testing "an entry that is off or cannot be reached is listed with why, nothing connected for it"
               (is (:off (by-name "quiet")))
               (is (= "the entry has neither a url nor a command" (:error (by-name "broken"))))
               (is (= "the name may have letters, digits, - and _ only" (:error (by-name "bad name"))))
               (is (= ["fake"] (mapv first @(:specs fake)))))
             (testing "a second catalog connects nothing again and keeps the version"
               (is (= version (:version (await (mcp/catalog registry)))))
               (is (= 1 (count @(:specs fake)))))
             registry)))))

(deftest call-test
  (async done
    (run done
         (^:async fn []
           (let [fake (fake-mcp/fake)
                 registry (registry fake {:fake {:url "https://fake.example/mcp"}})]
             (testing "a call connects the server when no program did"
               (is (= {:value "echo: hi" :images []} (await (mcp/call! registry "fake" "echo" {:text "hi"}))))
               (is (= [{:name "echo" :arguments {:text "hi"}}] @(:calls fake))))
             (testing "a text that is JSON is data, structured content wins over the text"
               (is (= {:items [{:id 1} {:id 2}] :next nil} (:value (await (mcp/call! registry "fake" "json" {})))))
               (is (= {:n 2 :names ["a" "b"]} (:value (await (mcp/call! registry "fake" "structured" nil))))))
             (testing "a picture goes with the answer, the value names it"
               (let [{:keys [value images]} (await (mcp/call! registry "fake" "picture" {}))]
                 (is (= ["a dot" "[picture, image/png, shown to you after the program's answer]"] value))
                 (is (= [{:data fake-mcp/png :mime-type "image/png"}] images))))
             (testing "no content is nil"
               (is (= {:value nil :images []} (await (mcp/call! registry "fake" "nothing" {})))))
             (testing "a tool's failure is in its result, the protocol's is an error too"
               (is (= {:error "mcp.fake/fail failed: it broke"} (await (mcp/call! registry "fake" "fail" {}))))
               (is (= {:error "mcp.fake/nope: Unknown tool nope"} (await (mcp/call! registry "fake" "nope" {}))))
               (is (str/starts-with? (:error (await (mcp/call! registry "ghost" "echo" {}))) "There is no MCP server ghost in ")))
             (testing "progress reaches on-progress, a line each"
               (let [lines (atom [])]
                 (is (= "done" (:value (await (mcp/call! registry "fake" "slow" {:ms 60} {:on-progress #(swap! lines conj %)})))))
                 (is (= ["mcp.fake/slow: 1/2 halfway" "mcp.fake/slow: 2/2"] @lines))))
             registry)))))

(deftest cancel-and-timeout-test
  (async done
    (run done
         (^:async fn []
           (let [fake (fake-mcp/fake)
                 registry (registry fake {:fake {:url "https://fake.example/mcp" :timeoutMs 300}})]
             (testing "an aborted call is answered at once and the server told"
               (let [controller (js/AbortController.)
                     answer (mcp/call! registry "fake" "hang" {} {:signal (.-signal controller)})]
                 (await (sleep 50))
                 (.abort controller "Stop")
                 (is (= {:error "mcp.fake/hang: the call was stopped"} (await answer)))
                 (await (sleep 10))
                 (is (= 1 (count @(:cancelled fake))))))
             (testing "a call without an answer times out after timeoutMs, the server told"
               (let [started (js/Date.now)
                     {:keys [error]} (await (mcp/call! registry "fake" "hang" {}))]
                 (is (str/includes? error "no answer within 300 ms"))
                 (is (< (- (js/Date.now) started) 1000))
                 (await (sleep 10))
                 (is (= 2 (count @(:cancelled fake))))))
             (testing "progress renews the timeout"
               (is (= "done" (:value (await (mcp/call! registry "fake" "slow" {:ms 500} {:on-progress identity}))))))
             registry)))))

(deftest reconnect-test
  (async done
    (run done
         (^:async fn []
           (let [fake (fake-mcp/fake {:expire-once true})
                 registry (registry fake {:fake {:url "https://fake.example/mcp"}})]
             (testing "a session the server forgot is opened again and the call made once more"
               (is (= "echo: again" (:value (await (mcp/call! registry "fake" "echo" {:text "again"})))))
               (is (= 2 (count (filter #{"initialize"} @(:requests fake)))))
               (is (= 1 (count @(:calls fake))) "the server took the call once"))
             (testing "a connection that closed is opened again at the next call"
               (fake-mcp/drop-connections! fake)
               (await (sleep 10))
               (is (= "echo: back" (:value (await (mcp/call! registry "fake" "echo" {:text "back"})))))
               (is (= 3 (count (filter #{"initialize"} @(:requests fake))))))
             registry)))))

(deftest changes-test
  (async done
    (run done
         (^:async fn []
           (let [fake (fake-mcp/fake)
                 home (test-home/make)
                 _ (write-servers! home {:fake {:url "https://fake.example/mcp"}})
                 registry (mcp/registry {:file (mcp/file home) :home home :transport (:transport-fn fake)})
                 before (await (mcp/catalog registry))]
             (testing "tools the server says changed are listed again"
               (fake-mcp/change-tools! fake [{:name "only" :inputSchema {:type "object"}}])
               (await (sleep 20))
               (let [after (mcp/snapshot registry)]
                 (is (< (:version before) (:version after)))
                 (is (= ["only"] (mapv :name (:tools (first (:servers after))))))))
             (testing "a changed file: a removed server is closed, a new one connected"
               (write-servers! home {:other {:url "https://other.example/mcp"}})
               ;; a file written within the same millisecond keeps its mtime
               (fs/utimesSync (mcp/file home) (js/Date.) (js/Date. (+ 5000 (js/Date.now))))
               (let [{:keys [servers]} (await (mcp/catalog registry))]
                 (is (= ["other"] (mapv :name servers)))
                 (is (= ["fake" "other"] (mapv first @(:specs fake))))))
             (testing "report names the servers"
               (is (= (str "MCP servers in " (mcp/file home) ": other") (mcp/report registry))))
             registry)))))

(deftest no-file-test
  (async done
    (run done
         (^:async fn []
           (let [home (test-home/make)
                 registry (mcp/registry {:file (path/join home "mcp.json") :home home})]
             (is (= {:version 1 :servers []} (await (mcp/catalog registry))))
             (is (nil? (mcp/report registry)))
             registry)))))

(def stdio-server
  "An MCP server as a program, JSON lines on stdin and stdout: one
   tool `whoami` that answers its pid, the variable FAKE_TOKEN and its
   working directory as JSON."
  "import readline from 'node:readline';
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const m = JSON.parse(line);
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'stdio-fake', version: '1' } } });
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools: [{ name: 'whoami', inputSchema: { type: 'object' } }] } });
  else if (m.method === 'tools/call') send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: JSON.stringify({ pid: process.pid, token: process.env.FAKE_TOKEN, cwd: process.cwd() }) }] } });
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} });
});
")

(defn- alive?
  [pid]
  (try (js/process.kill pid 0) true (catch :default _ false)))

(deftest stdio-test
  (async done
    (run done
         (^:async fn []
           (let [home (test-home/make)
                 _ (fs/writeFileSync (path/join home "server.mjs") stdio-server)
                 _ (aset (.-env js/process) "RRAGENT_TEST_STDIO" "s3cret")
                 _ (write-servers! home {:local {:command "node" :args ["server.mjs"] :env {:FAKE_TOKEN "${RRAGENT_TEST_STDIO}"}}})
                 registry (mcp/registry {:file (mcp/file home) :home home})]
             (try
               (is (= ["whoami"] (mapv :name (:tools (first (:servers (await (mcp/catalog registry))))))))
               (let [{:keys [pid token cwd]} (:value (await (mcp/call! registry "local" "whoami" {})))]
                 (is (= "s3cret" token) "${NAME} filled in from the environment")
                 (is (= (fs/realpathSync home) (fs/realpathSync cwd)) "the program runs in the machine's folder")
                 (is (alive? pid))
                 (await (mcp/close! registry))
                 (await (sleep 100))
                 (is (not (alive? pid)) "closed, the program is gone"))
               (finally
                 (js-delete (.-env js/process) "RRAGENT_TEST_STDIO")
                 (test-home/remove! home)))
             nil)))))

(defn- ^:async http-server
  "An MCP server over Streamable HTTP that takes only `Bearer <token>`:
   `{:url :seen :close}`, `seen` an atom of the Authorization headers."
  [token]
  (let [seen (atom [])
        server (http/createServer
                (fn [req res]
                  (swap! seen conj (.. req -headers -authorization))
                  (let [chunks #js []]
                    (.on req "data" #(.push chunks %))
                    (.on req "end"
                         (fn []
                           (cond
                             (not= (str "Bearer " token) (.. req -headers -authorization))
                             (do (.writeHead res 401) (.end res "no"))
                             (not= "POST" (.-method req))
                             (do (.writeHead res 405) (.end res))
                             :else
                             (let [m (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks))) :keywordize-keys true)
                                   result (case (:method m)
                                            "initialize" {:protocolVersion (get-in m [:params :protocolVersion])
                                                          :capabilities {:tools {}}
                                                          :serverInfo {:name "http-fake" :version "1"}}
                                            "tools/list" {:tools [{:name "add" :inputSchema {:type "object"}}]}
                                            "tools/call" {:content [{:type "text" :text (str (+ (get-in m [:params :arguments :a])
                                                                                                 (get-in m [:params :arguments :b])))}]}
                                            {})]
                               (if (nil? (:id m))
                                 (do (.writeHead res 202) (.end res))
                                 (do (.writeHead res 200 #js {"Content-Type" "application/json" "Mcp-Session-Id" "s1"})
                                     (.end res (js/JSON.stringify (clj->js {:jsonrpc "2.0" :id (:id m) :result result}))))))))))))]
    (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
    {:url (str "http://127.0.0.1:" (.-port (.address server)) "/mcp")
     :seen seen
     :close (fn [] (.closeAllConnections server) (.close server))}))

(deftest http-test
  (async done
    (run done
         (^:async fn []
           (let [{:keys [url seen close]} (await (http-server "t0k"))
                 home (test-home/make)
                 _ (aset (.-env js/process) "RRAGENT_TEST_HTTP" "t0k")
                 _ (write-servers! home {:remote {:url url :headers {:Authorization "Bearer ${RRAGENT_TEST_HTTP}"}}
                                         :wrong {:url url :headers {:Authorization "Bearer nope"}}})
                 registry (mcp/registry {:file (mcp/file home) :home home})]
             (try
               (let [{:keys [servers]} (await (mcp/catalog registry))
                     by-name (into {} (map (juxt :name identity)) servers)]
                 (is (= ["add"] (mapv :name (:tools (by-name "remote")))))
                 (is (str/includes? (:error (by-name "wrong")) "requires authentication")))
               (is (= {:value "5" :images []} (await (mcp/call! registry "remote" "add" {:a 2 :b 3}))) "a text that is no JSON object or array stays text")
               (is (some #{"Bearer t0k"} @seen))
               (finally
                 (await (mcp/close! registry))
                 (close)
                 (js-delete (.-env js/process) "RRAGENT_TEST_HTTP")
                 (test-home/remove! home)))
             nil)))))
