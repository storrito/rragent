(ns rragent.codemode-test
  "The REPL against the fake gateway: discovery, calls with JSON in
   and out, the closed context, defs that stay, the output cap, the
   Composio and Storrito functions, the events a program notes. A
   program with calls runs through the test host, which answers each
   need as the eval tool would (`rragent.test-host`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.fake-sprite-env :as fake-sprite-env]
            [rragent.need :as need]
            [rragent.sprite :as sprite]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-host :as test-host]))

(defn- failure
  "The message of the throw `thunk` ends in; nil when it does not throw."
  [thunk]
  (try (thunk) nil (catch :default e (.-message e))))

(defn- ^:async fails
  "The message of the rejection the promise of `thunk` ends in; nil
   when it resolves."
  [thunk]
  (try (await (thunk)) nil (catch :default e (.-message e))))

(defn- with-env
  "`thunk` run with the environment variable `k` as `v` (nil unset),
   the variable restored after; a promise of the thunk's when it
   answers one."
  [k v thunk]
  (let [env (.-env js/process)
        before (aget env k)
        restore (fn [] (if (nil? before) (js-delete env k) (aset env k before)))]
    (if (nil? v) (js-delete env k) (aset env k v))
    (let [out (try (thunk) (catch :default e (restore) (throw e)))]
      (if (instance? js/Promise out)
        (.finally out restore)
        (do (restore) out)))))

(defn- with-fake
  "`f` called with the fake's URL and a handle over it, awaited when
   it answers a promise; the fake and the request worker stopped
   after; `list` as `fake-gateway/start!` takes it."
  [list f]
  (-> ((^:async fn []
         (let [{:keys [url stop]} (await (fake-gateway/start! list))]
           (try
             (await (f url (codemode/handle url)))
             (finally
               (stop)
               (sync-fetch/stop!))))))
      (.catch (fn [e] (is (nil? e) (str e))))))

(deftest discovery-test
  (async done
    (-> (with-fake nil
          (^:async fn [url handle]
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
            (testing "(connectors) reads the list again, through the host, as data"
              (is (= "=> (\"conn_echo\" \"conn_openrouter\" \"conn_composio\" \"conn_storrito\" \"conn_other\")"
                     (await (test-host/run handle "(map :id (:connections (connectors)))"))))
              (is (= "=> \"github\"" (await (test-host/run handle "(:provider (first (:available (connectors))))")))))))
        (.finally done))))

(deftest no-gateway-test
  (async done
    (-> ((^:async fn []
           (testing "no sprite around: an empty list that says why, and help says so"
             (let [handle (codemode/handle "http://127.0.0.1:1")
                   {:keys [connections available error]} (codemode/discovery handle)]
               (is (= [] connections))
               (is (= [] available))
               (is (str/includes? (str error) "failed"))
               (is (str/starts-with? (codemode/report handle) "no gateway at http://127.0.0.1:1 ("))
               (is (str/includes? (codemode/evaluate handle "(help)") "did not answer"))
               (is (str/includes? (await (fails #(test-host/run handle "(call \"github\" \"/user\")"))) "Nothing is connected. The gateway did not answer"))
               (is (str/includes? (await (fails #(test-host/run handle "(storrito/procedures)"))) "No Storrito among the connections"))
               (sync-fetch/stop!)))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest call-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
            (testing "JSON in and out, the query and the headers"
              (is (= "=> {:method \"POST\", :path \"/x\", :query {:a \"1\"}, :headers {:content-type \"application/json\", :x-extra \"y\"}, :body {:a [1 2]}}"
                     (await (test-host/run handle "(-> (call \"echo\" \"x\" {:method :post :body {:a [1 2]} :headers {:x-extra \"y\"} :query {:a 1}}) :body (dissoc :id))")))))
            (testing "a string body goes as it is, a text answer comes as text, the status is the program's to look at"
              (is (= "=> [200 \"raw\" \"text/plain\"]"
                     (await (test-host/run handle "(let [r (call \"echo\" \"/x\" {:method \"POST\" :body \"raw\" :headers {\"Content-Type\" \"text/plain\"}})] [(:status r) (get-in r [:body :body]) (get-in r [:body :headers :content-type])])"))))
              (is (= "=> [200 \"plain /y\" \"text/plain\"]"
                     (await (test-host/run handle "(let [r (call \"echo\" \"/y\" {:query {:text 1}})] [(:status r) (:body r) (get-in r [:headers :content-type])])"))))
              (is (= "=> 404" (await (test-host/run handle "(:status (call \"echo\" \"/z\" {:query {:status 404}}))")))))
            (testing "no such connection: refused before the host is asked"
              (let [message (await (fails #(test-host/run handle "(call \"github\" \"/user\")")))]
                (is (str/includes? message "No connection \"github\". Connected: echo conn_echo, openrouter conn_openrouter"))
                (is (str/includes? message "GitHub is available, the person can set it up"))))
            (testing "a program loops over calls, each a need in its turn"
              (is (= "=> [\"/0\" \"/1\" \"/2\"]"
                     (await (test-host/run handle "(vec (for [i (range 3)] (get-in (call \"conn_other\" (str \"/\" i)) [:body :path])))")))))
            (testing "a GET is a read the host may make again, a POST is not"
              (let [asked (atom [])
                    perform (test-host/perform handle (atom []))
                    noting (fn [need] (swap! asked conj (:tool need)) (perform need))]
                (await (test-host/run handle "(call \"echo\" \"/a\") (call \"echo\" \"/b\" {:method :post})" {:perform noting}))
                (is (= ["gateway-read" "gateway"] @asked))))))
        (.finally done))))

(deftest need-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
            (testing "a call outside a form, with no host to answer it"
              (is (str/includes? (failure #(codemode/evaluate handle "(call \"echo\" \"/x\")")) "No host to answer gateway-read here.")))
            (testing "a program that catches the need goes on with nothing: the attempt is thrown away and the form runs again with the answer"
              (is (= "=> [200 \"caught nothing\"]"
                     (await (test-host/run handle "(def seen (atom [])) (let [r (try (call \"echo\" \"/x\") (catch :default e (swap! seen conj (ex-message e)) :caught))] [(:status r) \"caught nothing\"])")))))
            (testing "what an attempt defined before its need is gone with it"
              (is (= "=> [1 1]"
                     (await (test-host/run handle "(def n (atom 0)) (swap! n inc) (call \"echo\" \"/once\") [(deref n) 1]")))))
            (testing "a form that asks something else the second time is an error: an atom of an earlier form survives the attempt that is thrown away"
              (let [calls (atom 0)
                    perform (test-host/perform handle (atom []))
                    changing (fn [need] (swap! calls inc) (perform need))]
                (is (str/includes? (await (fails #(test-host/run handle "(def n (atom 0)) (call \"echo\" (str \"/\" (swap! n inc)))" {:perform changing})))
                                   "The program asked something else than before at call 1 of this form"))
                (is (= 1 @calls) "the host answered once, the rerun asked another path")))
            (testing "a recorded failure is thrown where the call is, which the program may catch"
              (is (= "=> \"No connection \\\"nope\\\"\""
                     (await (test-host/run handle "(try (call \"nope\" \"/x\") (catch :default e (subs (ex-message e) 0 20)))"))))
              (let [perform (fn [need] (js/Promise.resolve {:ok false :error "the gateway is down" :request (dissoc need :index)}))]
                (is (= "=> \"the gateway is down\""
                       (await (test-host/run handle "(try (call \"echo\" \"/x\") (catch :default e (ex-message e)))" {:perform perform}))))))
            (testing "the need describes the call"
              (is (= "gateway-read conn_echo /x" (need/describe {:tool "gateway-read" :args {:which "conn_echo" :path "/x"}}))))))
        (.finally done))))

(deftest chance-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
           (let [asked (atom [])
                 perform (test-host/perform handle (atom []))
                 noting (fn [need] (swap! asked conj (:tool need)) (perform need))
                 run (fn [code] (test-host/run handle code {:perform noting}))
                 program "(let [k (rand-int 1000)] [k (rand) (rand-nth [:a :b :c]) (shuffle [1 2 3 4 5]) (str (random-uuid)) (get-in (call \"echo\" (str \"/\" k)) [:body :path])])"]
             (testing "random numbers come from a generator the host seeded for the form, so a form draws the same before and after its calls are answered"
               (let [first-run (await (run program))
                     second-run (await (run program))]
                 (is (= first-run second-run) "the same seed, the same draws")
                 (is (= ["seed" "gateway-read"] (take 2 @asked)) "the seed is the form's first need, the call comes after it, on the rerun")
                 (is (re-find #"^=> \[\d+ 0\.\d+ :[abc] \[\d \d \d \d \d\] \"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\" \"/\d+\"\]$" first-run) first-run)))
             (testing "every form that draws asks for a seed of its own, a form that draws nothing asks none"
               (reset! asked [])
               (await (run "(rand) (+ 1 2) (rand)"))
               (is (= ["seed" "seed"] @asked)))
             (testing "the clock is a need, the same on every attempt of the form"
               (reset! asked [])
               (is (= "=> [1700000000000 \"/x\"]" (await (run "[(system-time) (get-in (call \"echo\" \"/x\") [:body :path])]"))))
               (is (= ["clock" "gateway-read"] @asked)))
             (testing "a draw outside a form, with no host"
               (is (str/includes? (failure #(codemode/evaluate handle "(rand)")) "No host to answer the seed of rand here."))
               (is (str/includes? (failure #(codemode/evaluate handle "(system-time)")) "No host to answer clock here."))))))
        (.finally done))))

(deftest forms-test
  (async done
    (-> ((^:async fn []
           (let [handle (codemode/handle "http://127.0.0.1:1")]
             (testing "the forms of a program, and a reading error"
               (is (= 3 (count (codemode/forms handle "(def x 1) (+ x 1) ; a note\n x"))))
               (is (= 0 (count (codemode/forms handle ""))))
               (is (str/includes? (failure #(codemode/forms handle "(def x")) "EOF")))
             (testing "a form done answers what it printed, its value, and what it defined as EDN"
               (is (= {:done {:printed "hi\n" :value "nil" :vars {}}}
                      (codemode/evaluate-form handle {:code "(println \"hi\") (def x {:a 1 :b [1 2 3]})" :index 0 :answers [] :events []})))
               (is (= {:done {:printed "" :value "#'user/x" :vars {'x "{:a 1, :b [1 2 3]}"}}}
                      (codemode/evaluate-form handle {:code "(println \"hi\") (def x {:a 1 :b [1 2 3]})" :index 1 :answers [] :events []})))
               (is (= {:done {:printed "" :value "3" :vars {}}}
                      (codemode/evaluate-form handle {:code "(+ 1 2)" :index 0 :answers [] :events []}))))
             (testing "a var that cannot be read back has the form run again on a restart"
               (is (= {:done {:printed "" :value "#'user/f" :redo true}}
                      (codemode/evaluate-form handle {:code "(defn f [n] (* 2 n))" :index 0 :answers [] :events []})))
               (is (= {:done {:printed "" :value "#'user/r" :redo true}}
                      (codemode/evaluate-form handle {:code "(def r #\"a+\")" :index 0 :answers [] :events []}))))
             (testing "a form that fails"
               (let [{:keys [error]} (codemode/evaluate-form handle {:code "(do (print \"before\") (nope))" :index 0 :answers [] :events []})]
                 (is (str/includes? error "Unable to resolve symbol: nope (line 1"))
                 (is (str/includes? error "Printed before the error:\nbefore")))
               (is (str/includes? (:error (codemode/evaluate-form handle {:code "(def x" :index 0 :answers [] :events []})) "EOF")))
             (testing "a var a form defined is read back into the program's context of a fresh handle"
               (let [fresh (codemode/handle "http://127.0.0.1:1")]
                 (codemode/begin! fresh)
                 (codemode/restore! fresh {'x "{:a 1, :b [1 2 3]}" 'y "#{:k}"})
                 (is (= {:done {:printed "" :value "[[1 2 3] #{:k}]" :vars {}}}
                        (codemode/evaluate-form fresh {:code "[(:b x) y]" :index 0 :answers [] :events []})))
                 (codemode/begin! fresh)
                 (is (str/includes? (:error (codemode/evaluate-form fresh {:code "x" :index 0 :answers [] :events []}))
                                    "Unable to resolve symbol: x")
                     "the next program starts fresh")))
             (sync-fetch/stop!))))
        (.catch (fn [e] (is (nil? e) (str e))))
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
    (testing "a program is one of its own: a def is there for its later forms, not for the next program"
      (is (= "=> 42" (codemode/evaluate handle "(def x 21) (* x 2)")))
      (is (str/includes? (failure #(codemode/evaluate handle "(* x 2)")) "Unable to resolve symbol: x"))
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

(deftest files-test
  (async done
    (-> ((^:async fn []
           (let [handle (codemode/handle "http://127.0.0.1:1")
                 files (atom {})
                 run (fn [code] (test-host/run handle code {:files files}))]
             (testing "data goes into a file as EDN and comes back in a later program, where a def does not"
               (is (= "=> \"data/accounts.edn\"" (await (run "(def accounts [{:name \"a\" :n 1} {:name \"b\" :n 2}]) (spit \"data/accounts.edn\" accounts)"))))
               (is (= {"data/accounts.edn" "[{:name \"a\", :n 1} {:name \"b\", :n 2}]"} @files))
               (is (= "=> [\"a\" \"b\"]" (await (run "(mapv :name (edn/read-string (slurp \"data/accounts.edn\")))"))))
               (is (str/includes? (await (fails #(run "accounts"))) "Unable to resolve symbol: accounts")))
             (testing "a string is written as it is"
               (is (= "=> \"notes.txt\"" (await (run "(spit \"notes.txt\" \"hello\")"))))
               (is (= "hello" (get @files "notes.txt")))
               (is (= "=> \"hello\"" (await (run "(slurp \"notes.txt\")")))))
             (testing "what would not read back is refused before the host is asked"
               (is (str/includes? (await (fails #(run "(spit \"data/f.edn\" (fn [x] x))"))) "spit writes data that reads back as EDN"))
               (is (str/includes? (await (fails #(run "(spit \"data/r.edn\" #\"a+\")"))) "spit writes data that reads back as EDN"))
               (is (= 2 (count @files))))
             (testing "a path outside the work folder is refused"
               (is (str/includes? (await (fails #(run "(spit \"/etc/passwd\" {})"))) "inside your work folder"))
               (is (str/includes? (await (fails #(run "(slurp \"../secrets.json\")"))) "inside your work folder"))
               (is (str/includes? (await (fails #(run "(slurp \"\")"))) "inside your work folder")))
             (testing "a file that is not there throws where the call is, which the program may catch"
               (is (= "=> \"No file data/none.edn in your work folder.\""
                      (await (run "(try (slurp \"data/none.edn\") (catch :default e (ex-message e)))")))))
             (sync-fetch/stop!))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest events-test
  (async done
    (-> ((^:async fn []
           (let [handle (codemode/handle "http://127.0.0.1:1")
                 at 1700000000000
                 events (atom [{:event "review-posted" :at at :id 4711}])
                 run (fn [code] (test-host/run handle code {:events events}))]
             (testing "what a program recalls, and what it remembers the host notes, recalled by the form run again"
               (is (= "=> [4711]" (await (run "(mapv :id (recall {:event \"review-posted\"}))"))))
               (is (= "=> [\"Noted review-posted\" 2]" (await (run "[(remember \"review-posted\" {:id 4712 :stars 4}) (count (recall))]"))))
               (is (= "=> []" (await (run "(recall {:since \"2099-01-01\"})"))))
               (is (= 2 (count @events)))
               (is (= {:event "review-posted" :id 4712 :stars 4} (dissoc (second @events) :at)))
               (is (number? (:at (second @events)))))
             (testing "the data is a map, the event a name"
               (is (str/includes? (await (fails #(run "(remember \"x\" 1)"))) "remember takes a map of data"))
               (is (str/includes? (await (fails #(run "(remember \"\" {})"))) "remember takes an event name")))
             (testing "a program starts with the events as they are"
               (reset! events [])
               (is (= "=> 0" (await (run "(count (recall))")))))
             (sync-fetch/stop!))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest composio-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
            (await
             (with-env "COMPOSIO_SESSION_ID" "trs_1"
               (^:async fn []
                 (testing "the four meta tools, each one execute_meta of the session through the Composio connection, the search session kept"
                   (is (= "=> {:session \"trs_1\", :slug \"COMPOSIO_SEARCH_TOOLS\", :arguments {:queries [{:use_case \"send an email\"}], :session {:generate_id true}, :toolkits [\"gmail\"]}}"
                          (await (test-host/run handle "(:echo (composio/search \"send an email\" [\"gmail\"]))"))))
                   (is (= "=> {:session \"trs_1\", :slug \"COMPOSIO_GET_TOOL_SCHEMAS\", :arguments {:tool_slugs [\"GMAIL_SEND_EMAIL\"], :session_id \"search_1\"}}"
                          (await (test-host/run handle "(:echo (composio/schemas [\"GMAIL_SEND_EMAIL\"]))"))))
                   (is (= "=> [{:index 0, :tool_slug \"GMAIL_SEND_EMAIL\", :response {:sent true}}]"
                          (await (test-host/run handle "(:results (composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\" :subject \"Hi\"}))"))))
                   (is (= "=> {:tools [{:tool_slug \"GMAIL_SEND_EMAIL\", :arguments {:to \"a@example.com\", :subject \"Hi\"}}], :session_id \"search_1\"}"
                          (await (test-host/run handle "(get-in (composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\" :subject \"Hi\"}) [:echo :arguments])"))))
                   (is (= "=> \"https://connect.example/gmail\""
                          (await (test-host/run handle "(get-in (composio/connections [\"gmail\"]) [:results :gmail :redirect_url])")))))
                 (testing "a failed run throws with Composio's error and the data"
                   (let [message (await (fails #(test-host/run handle "(composio/execute \"SLACK_SEND_MESSAGE\" {:channel \"#x\"})")))]
                     (is (str/includes? message "COMPOSIO_MULTI_EXECUTE_TOOL failed: SLACK_SEND_MESSAGE: slack is not connected"))
                     (is (str/includes? message "\"tool_slug\":\"SLACK_SEND_MESSAGE\""))))
                 (testing "a program catches it"
                   (is (= "=> \"no slack\"" (await (test-host/run handle "(try (composio/execute \"SLACK_SEND_MESSAGE\" {}) (catch :default e (if (ex-data e) \"no slack\" \"?\")))"))))))))
            (testing "a session Composio does not know"
              (await
               (with-env "COMPOSIO_SESSION_ID" "trs_dead"
                 (^:async fn []
                   (is (str/includes? (await (fails #(test-host/run handle "(composio/search \"x\")"))) "Composio answered HTTP 404 to COMPOSIO_SEARCH_TOOLS: Session not found"))))))
            (testing "without the session"
              (await
               (with-env "COMPOSIO_SESSION_ID" nil
                 (^:async fn []
                   (is (str/includes? (await (fails #(test-host/run handle "(composio/search \"x\")"))) "No Composio session"))
                   (is (str/includes? (codemode/evaluate handle "(help)") "Composio: no session yet"))))))))
        (.finally done))))

(deftest no-composio-test
  (async done
    (-> (with-fake {:connections (remove #(= "conn_composio" (:id %)) fake-gateway/connections) :available []}
          (^:async fn [_url handle]
            (await
             (with-env "COMPOSIO_SESSION_ID" "trs_1"
               (^:async fn []
                 (is (str/includes? (await (fails #(test-host/run handle "(composio/search \"x\")"))) "No connection to Composio: no custom_api connection of the gateway goes to backend.composio.dev"))
                 (is (str/includes? (codemode/evaluate handle "(help)") "Composio: no connection")))))))
        (.finally done))))

(deftest storrito-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
            (testing "the catalog's procedures are functions with docstrings"
              (is (= "=> [\"generate-uuid\" \"status-instagram-story\" \"flaky-once\" \"create-note\"]" (codemode/evaluate handle "(mapv :name (storrito/procedures))")))
              (let [doc (codemode/evaluate handle "(doc storrito/status-instagram-story)")]
                (is (str/includes? doc "storrito/status-instagram-story\n([] [args])\n  The status of a story post.\n\nSecond paragraph.\n\nParameters, a map:\n  storyPostUuid (string), required: The UUID of the story post\n  verbose (boolean)\n  limit (integer)\n  destination\n"))))
            (testing "a call is the procedure's POST through the connection, the answer as data"
              (is (= "=> {:uuid \"0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d\"}" (await (test-host/run handle "(storrito/generate-uuid)"))))
              (is (= "=> {:status \"scheduled\", :params {:storyPostUuid \"u1\", :limit 2}}" (await (test-host/run handle "(storrito/status-instagram-story {:storyPostUuid \"u1\" :limit 2})"))))
              (is (= "=> {:id \"n_a\", :text \"a\"}" (await (test-host/run handle "(storrito/create-note {:text \"a\"})")))))
            (testing "a query is a read the host may make again, a command is not"
              (let [asked (atom [])
                    perform (test-host/perform handle (atom []))
                    noting (fn [need] (swap! asked conj (:tool need)) (perform need))]
                (await (test-host/run handle "(storrito/generate-uuid) (storrito/create-note {:text \"b\"})" {:perform noting}))
                (is (= ["gateway-read" "gateway"] @asked))))
            (testing "what the API said about a bad call reaches the program"
              (let [message (await (fails #(test-host/run handle "(storrito/status-instagram-story {})")))]
                (is (str/includes? message "Storrito answered HTTP 400 to status-instagram-story"))
                (is (str/includes? message "missing required key"))))
            (testing "a 503 is tried again"
              (is (= "=> {:ok true}" (await (test-host/run handle "(storrito/flaky-once)")))))))
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
                (is (str/includes? help "(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\" {})"))))
            (js/Promise.resolve nil)))
        (.finally done))))

(deftest no-storrito-test
  (async done
    (-> (with-fake {:connections (remove #(= "conn_storrito" (:id %)) fake-gateway/connections) :available []}
          (^:async fn [_url handle]
            (is (str/includes? (failure #(codemode/evaluate handle "(storrito/procedures)")) "No Storrito among the connections"))
            (is (str/includes? (failure #(codemode/evaluate handle "(storrito/generate-uuid)")) "Unable to resolve symbol: storrito/generate-uuid"))
            (is (str/includes? (codemode/evaluate handle "(help)") "Storrito: no connection with a catalog"))))
        (.finally done))))

(deftest composio-triggers-test
  (async done
    (-> (with-fake nil
          (^:async fn [_url handle]
            (await
             (with-env "COMPOSIO_SESSION_ID" "trs_1"
               (^:async fn []
                 (await
                  (with-env "COMPOSIO_USER_ID" "agent-1"
                    (^:async fn []
                      (let [events (atom [])
                            run (fn [code] (test-host/run handle code {:events events}))]
                        (testing "the accounts and the trigger types of the agent's user id, through the Composio connection"
                          (is (= "=> [{:id \"ca_1\", :toolkit \"googledrive\", :status \"ACTIVE\"}]"
                                 (await (run "(composio/accounts)"))))
                          (is (= "=> [\"GOOGLEDRIVE_FILE_CREATED_TRIGGER\"]"
                                 (await (run "(mapv :slug (composio/trigger-types \"googledrive\"))")))))
                        (testing "a trigger added is noted as a fact, with the user id named for Composio, and listed; deleted it is noted too"
                          (is (= "=> \"ti_1\"" (await (run "(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\")"))))
                          (is (= [{:event "composio-trigger" :id "ti_1" :slug "GOOGLEDRIVE_FILE_CREATED_TRIGGER" :account "ca_1"}]
                                 (mapv #(dissoc % :at) @events)))
                          (is (= "=> [{:id \"ti_1\", :slug \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\", :connected-account \"ca_1\", :state \"active\"}]"
                                 (await (run "(composio/triggers)"))))
                          (is (= "=> \"Deleted ti_1\"" (await (run "(composio/delete-trigger \"ti_1\")"))))
                          (is (= ["composio-trigger" "composio-trigger-deleted"] (mapv :event @events)))
                          (is (= "=> []" (await (run "(composio/triggers)")))))
                        (testing "help names the session and the user id"
                          (is (str/includes? (codemode/evaluate handle "(help)") "session trs_1, your accounts and triggers under agent-1")))))))
                 (testing "without a user id a trigger cannot be added: Composio wants the account's owner"
                   (await
                    (with-env "COMPOSIO_USER_ID" nil
                      (^:async fn []
                        (is (str/includes? (await (fails #(test-host/run handle "(composio/add-trigger \"X\" \"ca_1\")"))) "No Composio user id")))))))))))
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

(defn- mcp-perform
  "An answerer of the MCP needs as the main thread's tools would, which
   notes each in `calls`: `{:tool :args}` of the need."
  [calls]
  (fn [{:keys [tool args] :as need}]
    (swap! calls conj args)
    (let [{:keys [admin name opts on?]} args
          {tool-name :tool :keys [server]} args
          outcome (case tool
                    "mcp-admin" (case admin
                                  :add (if (:token opts)
                                         {:ok true :value {:value {:name name :connected false :tools [] :needs [(:token opts)]}}}
                                         {:ok false :error "mcp/add! takes either :url (a Streamable HTTP server) or :command (a program over stdio)"})
                                  :test {:ok true :value {:value {:name name :connected true :tools ["echo"]}}}
                                  :enable {:ok true :value {:value {:name name :off (not on?)}}}
                                  :remove {:ok true :value {:value true}})
                    "mcp" (case tool-name
                            "echo" {:ok true :value {:value (str "echo: " (:text (:args args)))} :images []}
                            "files.read" {:ok true :value {:value {:read true}} :images []}
                            "picture" {:ok true :value {:value ["a dot" "[picture, image/png, shown to you after the program's answer]"]}
                                       :images [{:data "iVBO" :mime-type "image/png"}]}
                            {:ok false :error (str "mcp." server "/" tool-name " failed: it broke")}))]
      (js/Promise.resolve (assoc outcome :request (dissoc need :index))))))

(defn- mcp-handle
  "A REPL with the catalog above."
  []
  (let [handle (codemode/handle "http://127.0.0.1:1")]
    (codemode/install-mcp! handle mcp-catalog)
    handle))

(deftest mcp-test
  (async done
    (-> ((^:async fn []
           (let [calls (atom [])
                 handle (mcp-handle)
                 perform (mcp-perform calls)
                 run (fn [code] (test-host/run handle code {:perform perform}))
                 run-all (fn [code] (test-host/run-with #(js/Promise.resolve (codemode/evaluate-form handle %))
                                                        (count (codemode/forms handle code)) code perform (atom [])))]
             (testing "a tool is a function of its server's namespace, a map of arguments in, its value out"
               (is (= "=> \"echo: hi\"" (await (run "(mcp.fake/echo {:text \"hi\"})"))))
               (is (= {:server "fake" :tool "echo" :args {:text "hi"}} (last @calls))))
             (testing "a name that is no symbol: its symbol, or mcp/call by the name itself"
               (is (= "=> {:read true}" (await (run "(mcp.fake/files-read)"))))
               (is (= {:server "fake" :tool "files.read" :args {}} (last @calls)))
               (is (= "=> {:read true}" (await (run "(mcp/call \"fake\" \"files.read\" {})"))))
               (is (= {:server "fake" :tool "files.read" :args {}} (last @calls))))
             (testing "a failure throws with what the server said, which a program can catch"
               (is (str/includes? (await (fails #(run "(mcp.fake/fail {})"))) "mcp.fake/fail failed: it broke"))
               (is (= "=> \"mcp.fake/fail failed: it broke\"" (await (run "(try (mcp.fake/fail {}) (catch :default e (ex-message e)))")))))
             (testing "arguments that are no map are refused before the host is asked"
               (let [before (count @calls)]
                 (is (str/includes? (await (fails #(run "(mcp.fake/echo \"hi\")"))) "takes a map of arguments"))
                 (is (= before (count @calls)))))
             (testing "the pictures of a program's calls come with the answers, for the host"
               (is (= [{:data "iVBO" :mime-type "image/png"}] (:images (await (run-all "(mcp.fake/picture)")))))
               (is (= [] (:images (await (run-all "1"))))))
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
             (testing "mcp/add!, mcp/test, mcp/enable! and mcp/remove! are needs of the host, its errors thrown"
               (is (= "=> {:name \"gh\", :connected false, :tools [], :needs [\"GH_TOKEN\"]}"
                      (await (run "(mcp/add! \"gh\" {:url \"https://gh.example/mcp\" :token \"GH_TOKEN\"})"))))
               (is (= {:admin :add :name "gh" :opts {:url "https://gh.example/mcp" :token "GH_TOKEN"}} (last @calls)))
               (is (str/includes? (await (fails #(run "(mcp/add! \"gh\" {})"))) "either :url"))
               (is (= "=> [\"echo\"]" (await (run "(:tools (mcp/test 'gh))"))))
               (is (= {:admin :test :name "gh"} (last @calls)))
               (is (= "=> {:name \"gh\", :off true}" (await (run "(mcp/enable! \"gh\" false)"))))
               (is (= {:admin :enable :name "gh" :on? false} (last @calls)))
               (is (= "=> true" (await (run "(mcp/remove! \"gh\")"))))
               (is (str/includes? (codemode/evaluate handle "(doc mcp/add!)") "[:secret {:name")))
             (testing "servers installed after the context was made are there for the next program"
               (codemode/install-mcp! handle {:version 2 :servers [{:name "late" :ns "mcp.late" :tools [{:name "echo" :input-schema {}}]}]})
               (is (= "=> \"echo: late\"" (await (run "(mcp.late/echo {:text \"late\"})"))))
               (is (= {:server "late" :tool "echo" :args {:text "late"}} (last @calls)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest no-mcp-test
  (async done
    (-> ((^:async fn []
           (let [handle (codemode/handle "http://127.0.0.1:1")]
             (is (= "=> []" (codemode/evaluate handle "(mcp/servers)")))
             (is (str/includes? (codemode/help-text handle) "MCP: no servers yet; (mcp/add! …) connects one."))
             (is (str/includes? (await (fails #(test-host/run handle "(mcp/call \"x\" \"y\" {})"))) "There are no MCP servers here.")))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
