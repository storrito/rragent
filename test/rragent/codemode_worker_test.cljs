(ns rragent.codemode-worker-test
  "The machines' worker: a program loaded for its state, a step on a
   state, Stop and the timeout ending a program with the worker within
   a second, the MCP servers' tools as calls, a program of its own
   each time."
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

(defn- ^:async load
  [worker code & [more]]
  (await (codemode-worker/load! worker (merge {:code code} more))))

(defn- ^:async step
  [worker state]
  (await (codemode-worker/step! worker {:state state})))

(defn- ^:async stopped-within
  "`code` loaded in `worker` and the load aborted after `after-ms`:
   the answer, and how long the whole took."
  [worker code after-ms]
  (let [controller (js/AbortController.)
        started (js/Date.now)
        answer (codemode-worker/load! worker {:code code :signal (.-signal controller)})]
    (js/setTimeout #(.abort controller) after-ms)
    {:answer (await answer) :took (- (js/Date.now) started)}))

(deftest programs-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 worker (codemode-worker/handle (codemode/handle url))]
             (try
               (testing "a program loaded for its state, its steps on states"
                 (is (= {:state {:n 0} :step? true :printed "loaded\n"} (await (load worker "(println \"loaded\") (defn step [s] (update s :n inc)) {:n 0}"))))
                 (is (= {:state {:n 1} :printed ""} (await (step worker {:n 0}))))
                 (is (= {:state {:n 8} :printed ""} (await (step worker {:n 7})))))
               (testing "a program is one of its own"
                 (is (= {:state {:x 21} :step? false :printed ""} (await (load worker "(def x 21) {:x x}"))))
                 (is (str/includes? (:error (await (load worker "{:x x}"))) "Unable to resolve symbol: x")))
               (testing "the functions answer calls, the storrito/ functions are there"
                 (is (= {:state {:next {:call [:call "echo" "/x" {}] :as :r}} :step? false :printed ""} (await (load worker "{:next {:call (call \"echo\" \"/x\") :as :r}}"))))
                 (is (= {:state {:c [:storrito/generate-uuid {}]} :step? false :printed ""} (await (load worker "{:c (storrito/generate-uuid)}")))))
               (testing "an error is the program's, with what it printed before"
                 (let [{:keys [error]} (await (load worker "(println \"so far\") (undefined-fn)"))]
                   (is (str/includes? error "Unable to resolve symbol: undefined-fn"))
                   (is (str/includes? error "Printed before the error:\nso far")))
                 (is (str/includes? (:error (await (load worker "42"))) "A program's value is a map"))
                 (is (str/includes? (:error (await (step worker {}))) "The program defines no step")))
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
               (is (= {:state {:x 1} :step? false :printed ""} (await (load worker "{:x 1}"))))
               (testing "a program that never ends is stopped within a second of the abort"
                 (let [{:keys [answer took]} (await (stopped-within worker "(loop [] (recur))" 300))]
                   (is (= {:error "The program was stopped."} answer))
                   (is (< took 1300) (str "took " took " ms"))))
               (testing "the next program runs in a new worker"
                 (is (= {:state {:x 2} :step? false :printed ""} (await (load worker "{:x 2}")))))
               (testing "a signal aborted before the program starts"
                 (let [controller (js/AbortController.)]
                   (.abort controller)
                   (is (= {:error "The program was stopped."} (await (load worker "{}" {:signal (.-signal controller)}))))))
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
               (is (= {:state {:x 1} :step? false :printed ""} (await (load worker "{:x 1}"))))
               (testing "the machine tool stops a program longer than the timeout, the next one starts over"
                 (let [started (js/Date.now)
                       minutes (codemode-worker/timeout-minutes)
                       timer (js/setTimeout #(codemode-worker/stop! worker (codemode-worker/timeout-message minutes)) (* 60000 minutes))
                       answer (await (load worker "(loop [] (recur))"))]
                   (js/clearTimeout timer)
                   (is (= {:error "The machine ran longer than 0.01 minutes and was stopped."} answer))
                   (is (< 500 (- (js/Date.now) started) 1600)))
                 (is (= {:state {:x 3} :step? false :printed ""} (await (load worker "{:x 3}"))) "the next program runs in a new worker"))
               (finally
                 (if (nil? before) (js-delete env "RRAGENT_PROGRAM_TIMEOUT_MIN") (aset env "RRAGENT_PROGRAM_TIMEOUT_MIN" before))
                 (codemode-worker/stop! worker "over")
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest mcp-test
  (async done
    (-> ((^:async fn []
           (let [fake (fake-mcp/fake)
                 home (test-home/make)
                 _ (fs/writeFileSync (mcp/file home) (js/JSON.stringify (clj->js {:mcpServers {:fake {:url "https://fake.example/mcp"}}})))
                 registry (mcp/registry {:file (mcp/file home) :home home :transport (:transport-fn fake)})
                 worker (codemode-worker/handle (assoc (codemode/handle "http://127.0.0.1:1") :mcp registry))]
             (try
               (testing "the servers' tools go with the program and answer calls"
                 (let [catalog (await (mcp/catalog registry))]
                   (is (= {:state {:c [:mcp/call "fake" "echo" {:text "hi"}]} :step? false :printed ""}
                          (await (load worker "{:c (mcp.fake/echo {:text \"hi\"})}" {:mcp catalog}))))
                   (is (= {:state {:s 1} :step? false :printed ""} (await (load worker "{:s (count (mcp/servers))}" {:mcp catalog}))))))
               (finally
                 (codemode-worker/stop! worker "over")
                 (await (mcp/close! registry))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
