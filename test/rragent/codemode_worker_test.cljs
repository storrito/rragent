(ns rragent.codemode-worker-test
  "The REPL's worker: a def that stays across programs, the facts and
   the environment that go with a program, a call through the gateway
   from the worker, Stop and the timeout ending a program with the
   worker within a second, and the next program in a fresh context
   that says so; the tools of an MCP server called from the worker,
   answered by the main thread, a call in flight cancelled by Stop."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.fake-mcp :as fake-mcp]
            [rragent.mcp :as mcp]
            [rragent.test-home :as test-home]
            [rragent.sync-fetch :as sync-fetch]))

(defn- ^:async run
  "`code` run in `worker`, with `more` of the program's map."
  [worker code & [more]]
  (await (codemode-worker/evaluate! worker (merge {:code code :facts [] :env {}} more))))

(defn- ^:async stopped-within
  "`code` run in `worker` and the run aborted after `after-ms`: the
   answer, and how long the whole took."
  [worker code after-ms]
  (let [controller (js/AbortController.)
        started (js/Date.now)
        answer (codemode-worker/evaluate! worker {:code code :facts [] :env {} :signal (.-signal controller)})]
    (js/setTimeout #(.abort controller) after-ms)
    {:answer (await answer) :took (- (js/Date.now) started)}))

(deftest programs-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 worker (codemode-worker/handle (codemode/handle url))]
             (try
               (testing "a def stays for the next program"
                 (is (= {:text "=> #'user/x" :pending []} (await (run worker "(def x 21)"))))
                 (is (= {:text "=> 42" :pending []} (await (run worker "(* x 2)")))))
               (testing "the facts go in with the program, what it remembered comes out"
                 (let [{:keys [text pending]} (await (run worker "(remember \"seen\" {:n (count (recall))}) (:n (first (recall {:event \"seen\"})))"
                                                          {:facts [{:event "old" :at 1 :fact/id "e1"}]}))]
                   (is (= "=> 1" text))
                   (is (= [{:event "seen" :n 1}] (mapv #(dissoc % :at) pending)))))
               (testing "a call goes through the gateway from the worker, the storrito/ functions are there"
                 (is (= {:text "=> \"/x\"" :pending []} (await (run worker "(get-in (call \"echo\" \"/x\") [:body :path])"))))
                 (is (= {:text "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" :pending []} (await (run worker "(storrito/generate-uuid)")))))
               (testing "the environment the bindings read comes with the program"
                 (is (= "=> \"trs_9\"" (:text (await (run worker "(get-in (composio/search \"x\") [:echo :session])" {:env {"COMPOSIO_SESSION_ID" "trs_9"}})))))
                 (is (str/includes? (:error (await (run worker "(composio/search \"x\")" {:env {"COMPOSIO_SESSION_ID" nil}}))) "No Composio session")))
               (testing "an error is the program's, with what it printed before"
                 (let [{:keys [error pending]} (await (run worker "(remember \"half\" {}) (println \"so far\") (undefined-fn)"))]
                   (is (str/includes? error "Unable to resolve symbol: undefined-fn"))
                   (is (str/includes? error "Printed before the error:\nso far"))
                   (is (= ["half"] (mapv :event pending)) "what it remembered before the error is pending all the same")))
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
               (is (= {:text "=> #'user/x" :pending []} (await (run worker "(def x 1)"))))
               (testing "a program that never ends is stopped within a second of the abort"
                 (let [{:keys [answer took]} (await (stopped-within worker "(loop [] (recur))" 300))]
                   (is (= {:error "The program was stopped." :pending []} answer))
                   (is (< took 1300) (str "took " took " ms"))))
               (testing "the next program runs in a fresh context and says so first"
                 (let [{:keys [error]} (await (run worker "x"))]
                   (is (str/starts-with? error "The REPL started over since the last program was stopped: what earlier programs defined is gone.\n"))
                   (is (str/includes? error "Unable to resolve symbol: x")))
                 (is (= {:text "=> #'user/x" :pending []} (await (run worker "(def x 2)"))) "the note is said once"))
               (testing "a program blocked in a call is stopped as well"
                 (let [{:keys [answer took]} (await (stopped-within worker "(call \"echo\" \"/slow\" {:query {:delay 20000}})" 300))]
                   (is (= {:error "The program was stopped." :pending []} answer))
                   (is (< took 1300) (str "took " took " ms")))
                 (is (str/starts-with? (:text (await (run worker "(def x 3)"))) "The REPL started over")))
               (testing "a signal aborted before the program starts"
                 (let [controller (js/AbortController.)]
                   (.abort controller)
                   (is (= {:error "The program was stopped." :pending []} (await (run worker "1" {:signal (.-signal controller)}))))))
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
               (is (= {:text "=> #'user/x" :pending []} (await (run worker "(def x 1)"))))
               (testing "a program longer than the timeout is stopped, the next one starts over"
                 (let [started (js/Date.now)
                       answer (await (run worker "(loop [] (recur))"))]
                   (is (= {:error "The program ran longer than 0.01 minutes and was stopped." :pending []} answer))
                   (is (< 500 (- (js/Date.now) started) 1600)))
                 (let [{:keys [text error]} (await (run worker "x"))]
                   (is (nil? text))
                   (is (str/starts-with? error "The REPL started over since the last program was stopped"))))
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
                 {:keys [registry worker]} (mcp-worker fake)]
             (try
               (testing "a tool called from the worker, answered by the main thread"
                 (is (= {:text "=> \"echo: hi\"" :pending []} (await (run worker "(mcp.fake/echo {:text \"hi\"})"))))
                 (is (= {:text "=> [\"echo: a\" \"echo: b\"]" :pending []}
                        (await (run worker "(mapv #(mcp.fake/echo {:text %}) [\"a\" \"b\"])")))
                     "calls in a loop"))
               (testing "a failure is the program's to catch"
                 (is (= {:text "=> \"mcp.fake/fail failed: it broke\"" :pending []}
                        (await (run worker "(try (mcp.fake/fail {}) (catch :default e (ex-message e)))")))))
               (testing "pictures come with the answer"
                 (is (= [{:data fake-mcp/png :mime-type "image/png"}] (:images (await (run worker "(mcp.fake/picture)")))))
                 (is (nil? (:images (await (run worker "1")))) "the next program has none"))
               (testing "progress goes to the program's on-progress"
                 (let [lines (atom [])]
                   (is (= "=> \"done\"" (:text (await (run worker "(mcp.fake/slow {:ms 60})" {:on-progress #(swap! lines conj %)})))))
                   (is (= ["mcp.fake/slow: 1/2 halfway" "mcp.fake/slow: 2/2"] @lines))))
               (testing "Stop ends a program blocked in a call within a second, the server told"
                 (let [{:keys [answer took]} (await (stopped-within worker "(mcp.fake/hang {})" 300))]
                   (is (= {:error "The program was stopped." :pending []} answer))
                   (is (< took 1300) (str "took " took " ms"))
                   (await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                   (is (= 1 (count @(:cancelled fake))))))
               (testing "the next worker has the tools again, over the same connection"
                 (is (= {:text (str codemode-worker/restart-note "\n=> \"echo: again\"") :pending []}
                        (await (run worker "(mcp.fake/echo {:text \"again\"})"))))
                 (is (= 1 (count (filter #{"initialize"} @(:requests fake))))))
               (finally
                 (codemode-worker/stop! worker "over")
                 (await (mcp/close! registry)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
