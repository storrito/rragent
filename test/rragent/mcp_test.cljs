(ns rragent.mcp-test
  "The MCP servers of the machine against the fake server
   (`rragent.fake-mcp`): the file and its variables, the catalog, a
   call's value for text, JSON, structured content, pictures and a
   failure, progress, cancellation and the timeout, a session the
   server forgot, a connection that closed, tools that changed, and
   a file that changed."
  (:require ["node:fs" :as fs]
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
