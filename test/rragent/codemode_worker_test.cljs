(ns rragent.codemode-worker-test
  "The REPL's worker: a def that stays across programs, the events
   that go with a form, a call answered by the host from the worker,
   Stop and the timeout ending a program with the worker within a
   second, and the next program in a fresh context that says so; the
   tools of an MCP server called from the worker, answered by the
   host, the vars of a form given back to a fresh worker."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.fake-mcp :as fake-mcp]
            [rragent.mcp :as mcp]
            [rragent.test-home :as test-home]
            [rragent.test-host :as test-host]
            [rragent.sync-fetch :as sync-fetch]))

(defn- ^:async run
  "`code` run in `worker` through the test host, `more` its options:
   `{:text}` or `{:error}`, with `:note` after a stop."
  [worker code & [more]]
  (let [answer (await (test-host/run-in-worker worker code more))]
    (cond-> (select-keys answer [:text :error])
      (seq (:images answer)) (assoc :images (:images answer))
      (:note answer) (assoc :note (:note answer)))))

(defn- ^:async stopped-within
  "The form of `code` run in `worker` and aborted after `after-ms`:
   the answer, and how long the whole took."
  [worker code after-ms]
  (let [controller (js/AbortController.)
        started (js/Date.now)
        answer (codemode-worker/attempt! worker {:code code :index 0 :answers [] :events [] :signal (.-signal controller)})]
    (js/setTimeout #(.abort controller) after-ms)
    {:answer (await answer) :took (- (js/Date.now) started)}))

(deftest programs-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 worker (codemode-worker/handle (codemode/handle url))
                 events (atom [{:event "old" :at 1}])]
             (try
               (testing "a def stays for the next program"
                 (is (= {:text "=> #'user/x"} (await (run worker "(def x 21)"))))
                 (is (= {:text "=> 42"} (await (run worker "(* x 2)")))))
               (testing "the events go in with the form, what it remembers the host notes"
                 (is (= {:text "=> 1"} (await (run worker "(remember \"seen\" {:n (count (recall))}) (:n (first (recall {:event \"seen\"})))" {:events events}))))
                 (is (= [{:event "old"} {:event "seen" :n 1}] (mapv #(dissoc % :at) @events))))
               (testing "a call is a need the host answers, the storrito/ functions are there"
                 (is (= {:text "=> \"/x\""} (await (run worker "(get-in (call \"echo\" \"/x\") [:body :path])"))))
                 (is (= {:text "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}"} (await (run worker "(storrito/generate-uuid)")))))
               (testing "the environment the bindings read comes with the form"
                 (is (= "=> \"trs_9\"" (:text (await (run worker "(get-in (composio/search \"x\") [:echo :session])" {:env {"COMPOSIO_SESSION_ID" "trs_9"}})))))
                 (is (str/includes? (:error (await (run worker "(composio/search \"x\")" {:env {"COMPOSIO_SESSION_ID" nil}}))) "No Composio session")))
               (testing "an error is the form's, with what it printed before"
                 (let [{:keys [error]} (await (run worker "(println \"so far\") (undefined-fn)"))]
                   (is (str/includes? error "Unable to resolve symbol: undefined-fn"))
                   (is (not (str/includes? error "so far")) "an earlier form's print is its own"))
                 (let [{:keys [error]} (await (run worker "(do (println \"so far\") (undefined-fn))"))]
                   (is (str/includes? error "Printed before the error:\nso far"))))
               (testing "the vars of a form done come back, a fresh worker takes them"
                 (let [fresh (codemode-worker/handle (codemode/handle url))]
                   (try
                     (is (= {:done true} (await (codemode-worker/restore! fresh {'y "[1 2 3]"} nil))))
                     (is (= {:text "=> 6"} (await (run fresh "(reduce + y)"))))
                     (finally
                       (codemode-worker/stop! fresh "over")))))
               (finally
                 (codemode-worker/stop! worker "over")
                 (stop)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest stop-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 worker (codemode-worker/handle (codemode/handle url))]
             (try
               (is (= {:text "=> #'user/x"} (await (run worker "(def x 1)"))))
               (testing "a form that never ends is stopped within a second of the abort"
                 (let [{:keys [answer took]} (await (stopped-within worker "(loop [] (recur))" 300))]
                   (is (= {:error "The program was stopped."} answer))
                   (is (< took 1300) (str "took " took " ms"))))
               (testing "the next program runs in a fresh context and says so first"
                 (let [{:keys [error note]} (await (run worker "x"))]
                   (is (= codemode-worker/restart-note note))
                   (is (str/includes? error "Unable to resolve symbol: x")))
                 (is (= {:text "=> #'user/x"} (await (run worker "(def x 2)"))) "the note is said once"))
               (testing "a signal aborted before the form starts"
                 (let [controller (js/AbortController.)]
                   (.abort controller)
                   (is (= {:error "The program was stopped."} (await (codemode-worker/attempt! worker {:code "1" :index 0 :answers [] :events [] :signal (.-signal controller)}))))))
               (finally
                 (codemode-worker/stop! worker "over")
                 (stop)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest timeout-test
  (async done
    (-> ((^:async fn []
           (let [worker (codemode-worker/handle (codemode/handle "http://127.0.0.1:1"))
                 env (.-env js/process)
                 before (aget env "RRAGENT_PROGRAM_TIMEOUT_MIN")]
             (try
               (is (= 10 (codemode-worker/timeout-minutes)) "ten minutes without the variable")
               (aset env "RRAGENT_PROGRAM_TIMEOUT_MIN" "0.01")
               (is (= 0.01 (codemode-worker/timeout-minutes)))
               (is (= {:text "=> #'user/x"} (await (run worker "(def x 1)"))))
               (testing "the eval tool stops a program longer than the timeout, the next one starts over"
                 (let [started (js/Date.now)
                       minutes (codemode-worker/timeout-minutes)
                       timer (js/setTimeout #(codemode-worker/stop! worker (codemode-worker/timeout-message minutes)) (* 60000 minutes))
                       answer (await (codemode-worker/attempt! worker {:code "(loop [] (recur))" :index 0 :answers [] :events []}))]
                   (js/clearTimeout timer)
                   (is (= {:error "The program ran longer than 0.01 minutes and was stopped."} answer))
                   (is (< 500 (- (js/Date.now) started) 1600)))
                 (let [{:keys [text error note]} (await (run worker "x"))]
                   (is (nil? text))
                   (is (= codemode-worker/restart-note note))
                   (is (str/includes? error "Unable to resolve symbol: x"))))
               (finally
                 (if (nil? before) (js-delete env "RRAGENT_PROGRAM_TIMEOUT_MIN") (aset env "RRAGENT_PROGRAM_TIMEOUT_MIN" before))
                 (codemode-worker/stop! worker "over")
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- mcp-worker
  "A worker over a REPL whose MCP servers are the fake's one server
   `fake`, and the registry."
  [fake]
  (let [home (test-home/make)
        _ (fs/writeFileSync (mcp/file home) (js/JSON.stringify (clj->js {:mcpServers {:fake {:url "https://fake.example/mcp"}}})))
        registry (mcp/registry {:file (mcp/file home) :home home :transport (:transport-fn fake)})]
    {:registry registry
     :worker (codemode-worker/handle (assoc (codemode/handle "http://127.0.0.1:1") :mcp registry))}))

(deftest mcp-test
  (async done
    (-> ((^:async fn []
           (let [fake (fake-mcp/fake)
                 {:keys [registry worker]} (mcp-worker fake)
                 run (fn [code] (run worker code {:registry registry}))]
             (try
               (testing "a tool called from the worker, answered by the host"
                 (is (= {:text "=> \"echo: hi\""} (await (run "(mcp.fake/echo {:text \"hi\"})"))))
                 (is (= {:text "=> [\"echo: a\" \"echo: b\"]"}
                        (await (run "(mapv #(mcp.fake/echo {:text %}) [\"a\" \"b\"])")))
                     "calls in a loop"))
               (testing "a failure is the program's to catch"
                 (is (= {:text "=> \"mcp.fake/fail failed: it broke\""}
                        (await (run "(try (mcp.fake/fail {}) (catch :default e (ex-message e)))")))))
               (testing "pictures come with the answer"
                 (is (= [{:data fake-mcp/png :mime-type "image/png"}] (:images (await (run "(mcp.fake/picture)")))))
                 (is (nil? (:images (await (run "1")))) "the next program has none"))
               (testing "mcp/add! from a program: the host writes the file and connects, the next program has the tools"
                 (is (= {:text "=> [true 9]"}
                        (await (run "(let [{:keys [connected tools]} (mcp/add! \"second\" {:url \"https://second.example/mcp\"})] [connected (count tools)])"))))
                 (is (contains? (mcp/read-servers (:file registry)) "second"))
                 (is (= {:text "=> \"echo: two\""} (await (run "(mcp.second/echo {:text \"two\"})"))))
                 (is (= {:text "=> \"needs the secret NOPE_TOKEN\""}
                        (await (run "(let [{:keys [needs]} (mcp/add! \"third\" {:url \"https://third.example/mcp\" :token \"NOPE_TOKEN\"})] (str \"needs the secret \" (first needs)))"))))
                 (is (= {:text "=> true"} (await (run "(mcp/remove! \"third\")"))))
                 (is (str/includes? (:text (await (run "(try (mcp/remove! \"third\") (catch :default e (ex-message e)))")))
                                    "There is no MCP server third")))
               (finally
                 (codemode-worker/stop! worker "over")
                 (await (mcp/close! registry)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
