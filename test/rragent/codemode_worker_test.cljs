(ns rragent.codemode-worker-test
  "The REPL's worker: a def that stays across programs, the facts and
   the environment that go with a program, a call through the gateway
   from the worker, Stop and the timeout ending a program with the
   worker within a second, and the next program in a fresh context
   that says so."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.fake-gateway :as fake-gateway]
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
                 (is (str/includes? (:error (await (run worker "(composio/search \"x\")" {:env {"COMPOSIO_SESSION_ID" nil}}))) "COMPOSIO_SESSION_ID is not set")))
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
