(ns rragent.plan-test
  "The plan as data: what counts as one, what is wrong with one, the
   placeholders, the run that stops at the first failure, the lines."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.plan :as plan]))

(def known
  {:procedures {"generate-uuid" {:kind :query}
                "status-instagram-story" {:kind :query}
                "create-note" {:kind :command}
                "flaky-once" {:kind :command}}})

(defn- failure
  [thunk]
  (try (thunk) nil (catch :default e (.-message e))))

(deftest of-test
  (testing "a vector of actions the host knows, or one action"
    (is (= [[:storrito/create-note {:text "a"}] [:remember "noted" {:id 1}]]
           (plan/of known [[:storrito/create-note {:text "a"}] [:remember "noted" {:id 1}]])))
    (is (= [[:storrito/create-note {:text "a"}]] (plan/of known [:storrito/create-note {:text "a"}])))
    (is (= [[:remember "x" nil]] (plan/of known (list [:remember "x" nil]))) "a seq as well"))
  (testing "data is data"
    (is (nil? (plan/of known 42)))
    (is (nil? (plan/of known "text")))
    (is (nil? (plan/of known [])))
    (is (nil? (plan/of known {:a 1})))
    (is (nil? (plan/of known [[:a 1] [:b 2]])) "a map's pairs are no plan")
    (is (nil? (plan/of known [:a 1])))
    (is (nil? (plan/of known [[:storrito/x {}] 3])) "not every element an action: data"))
  (testing "a plan with a stranger in it is a mistake"
    (is (str/includes? (failure #(plan/of known [[:storrito/create-note {}] [:foo 1]]))
                       "effect 2, foo 1, is none the host runs")))
  (testing "the other effects the host runs"
    (is (= [[:call "echo" "/x" {:method :post}] [:composio/execute "GMAIL_SEND_EMAIL" {:to "a"}] [:composio/connections ["gmail"]]
            [:composio/add-trigger "T" "ca_1" {}] [:composio/delete-trigger "ti_1"] [:mcp/call "github" "create_issue" {:title "x"}]]
           (plan/of known [[:call "echo" "/x" {:method :post}] [:composio/execute "GMAIL_SEND_EMAIL" {:to "a"}] [:composio/connections ["gmail"]]
                           [:composio/add-trigger "T" "ca_1" {}] [:composio/delete-trigger "ti_1"] [:mcp/call "github" "create_issue" {:title "x"}]])))))

(deftest check-test
  (is (= [] (plan/check known [[:storrito/create-note {:text "a"}]
                               [:storrito/flaky-once]
                               [:remember "noted" {:id [:plan/result 0 :id]}]
                               [:remember "bare"]])))
  (is (= ["effect 1, storrito/nope {}: storrito/nope is no procedure of the catalog; (storrito/procedures) lists them"]
         (plan/check known [[:storrito/nope {}]])))
  (is (= ["effect 1, storrito/generate-uuid: storrito/generate-uuid is a query: call it in the program, (storrito/generate-uuid {...}) answers at once"]
         (plan/check known [[:storrito/generate-uuid]])))
  (is (= ["effect 1, storrito/create-note \"a\": its arguments are no map"]
         (plan/check known [[:storrito/create-note "a"]])))
  (is (= ["effect 1, storrito/create-note {} {}: a storrito/ effect is the keyword and one map"]
         (plan/check known [[:storrito/create-note {} {}]])))
  (is (= ["effect 1, remember \"\": remember takes an event name"
          "effect 2, remember \"x\" 3: remember takes a map of data"]
         (plan/check known [[:remember ""] [:remember "x" 3]])))
  (testing "a placeholder points back, never forward or at itself"
    (is (= ["effect 1, storrito/create-note {:text [:plan/result 0 :id]}: the placeholder [:plan/result 0 :id] refers to effect 1, which is not before this one (the first effect is 0)"]
           (plan/check known [[:storrito/create-note {:text [:plan/result 0 :id]}]])))
    (is (= ["effect 2, storrito/create-note {:text [:plan/result 5 :id]}: the placeholder [:plan/result 5 :id] refers to effect 6, which is not before this one (the first effect is 0)"]
           (plan/check known [[:storrito/flaky-once] [:storrito/create-note {:text [:plan/result 5 :id]}]])))
    (is (= ["effect 2, storrito/create-note {:text [:plan/result \"0\"]}: the placeholder [:plan/result \"0\"] names no index"]
           (plan/check known [[:storrito/flaky-once] [:storrito/create-note {:text [:plan/result "0"]}]])))))

(deftest interpolate-test
  (let [outcomes [{:action [:storrito/create-note {:text "a"}] :status :done :value {:id "n_a" :tags ["x" "y"]}}]]
    (is (= [:remember "noted" {:id "n_a" :second "y" :all {:id "n_a" :tags ["x" "y"]}}]
           (plan/interpolate [:remember "noted" {:id [:plan/result 0 :id] :second [:plan/result 0 :tags 1] :all [:plan/result 0]}] outcomes)))
    (is (= [:remember "noted" {:id nil}] (plan/interpolate [:remember "noted" {:id [:plan/result 0 :missing]}] outcomes)) "a part that is not there is nil")))

(deftest check-kinds-test
  (is (= [] (plan/check known [[:call "echo" "/x"] [:call "echo" "/x" {:method :post}] [:composio/execute "S"] [:composio/execute "S" {:a 1}]
                               [:composio/connections ["gmail"]] [:composio/add-trigger "T" "ca_1"] [:composio/add-trigger "T" "ca_1" {:x 1}]
                               [:composio/delete-trigger "ti_1"] [:mcp/call "s" "t"] [:mcp/call "s" "t" {:q 1}]])))
  (is (= ["effect 1, call \"echo\": it takes which, path at least"] (plan/check known [[:call "echo"]])))
  (is (= ["effect 1, call \"echo\" \"/x\" {} 4: it takes which, path, opts and no more"] (plan/check known [[:call "echo" "/x" {} 4]])))
  (is (= ["effect 1, call \"echo\" \"/x\" 3: the opts of call are a map"] (plan/check known [[:call "echo" "/x" 3]])))
  (is (= ["effect 1, composio/execute \"S\" 1: the arguments of composio/execute are a map"] (plan/check known [[:composio/execute "S" 1]])))
  (is (= ["effect 1, composio/connections \"gmail\": composio/connections takes a vector of toolkit slugs"] (plan/check known [[:composio/connections "gmail"]])))
  (is (= ["effect 1, composio/add-trigger \"T\": it takes slug, account at least"] (plan/check known [[:composio/add-trigger "T"]])))
  (is (= ["effect 1, mcp/call \"s\" \"t\" 1: the arguments of mcp/call are a map"] (plan/check known [[:mcp/call "s" "t" 1]]))))

(deftest effect-test
  (is (= {:tool "gateway" :args {:which "echo" :path "/x" :opts {:method :post}}} (plan/effect [:call "echo" "/x" {:method :post}])))
  (is (= {:tool "gateway" :args {:which "echo" :path "/x" :opts {}}} (plan/effect [:call "echo" "/x"])))
  (is (= {:tool "composio-execute" :args {:slug "S" :args {:a 1} :session "search_1"}} (plan/effect [:composio/execute "S" {:a 1}] "search_1")))
  (is (= {:tool "composio-execute" :args {:slug "S" :args {}}} (plan/effect [:composio/execute "S"])))
  (is (= {:tool "composio-connections" :args {:toolkits ["gmail"] :session "search_1"}} (plan/effect [:composio/connections ["gmail"]] "search_1")))
  (is (= {:tool "composio-add-trigger" :args {:slug "T" :account "ca_1" :config {}}} (plan/effect [:composio/add-trigger "T" "ca_1"])))
  (is (= {:tool "composio-delete-trigger" :args {:id "ti_1"}} (plan/effect [:composio/delete-trigger "ti_1"])))
  (is (= {:tool "mcp" :args {:server "s" :tool "t" :args {:q 1}}} (plan/effect [:mcp/call "s" "t" {:q 1}])))
  (is (= {:tool "storrito" :args {:procedure "create-note" :args {:text "a"}}} (plan/effect [:storrito/create-note {:text "a"}])))
  (is (= {:tool "storrito" :args {:procedure "flaky-once" :args {}}} (plan/effect [:storrito/flaky-once])))
  (is (= {:tool "remember" :args {:event "noted" :data {:id 1}}} (plan/effect [:remember "noted" {:id 1}])))
  (is (= {:tool "remember" :args {:event "bare" :data {}}} (plan/effect [:remember "bare"]))))

(defn- execute-with
  "An `execute!` that answers from `answers` by key and logs each call."
  [log answers]
  (fn [key tool args]
    (swap! log conj [key tool args])
    (js/Promise.resolve (get answers key {:ok true :value nil}))))

(deftest run-test
  (async done
    (-> ((^:async fn []
           (testing "one after the other, each seeing the results before it"
             (let [log (atom [])
                   outcomes (await (plan/perform! [[:storrito/create-note {:text "a"}]
                                               [:remember "noted" {:id [:plan/result 0 :id]}]]
                                              {:execute! (execute-with log {"e0" {:ok true :value {:id "n_a"}}
                                                                            "e1" {:ok true :value "Noted noted"}})}))]
               (is (= [["e0" "storrito" {:procedure "create-note" :args {:text "a"}}]
                       ["e1" "remember" {:event "noted" :data {:id "n_a"}}]]
                      @log))
               (is (= [:done :done] (mapv :status outcomes)))
               (is (= "Plan of 2 effects: 2 done.\n1. storrito/create-note {:text \"a\"}: done {:id \"n_a\"}\n2. remember \"noted\" {:id [:plan/result 0 :id]}: done \"Noted noted\""
                      (plan/report outcomes)))
               (is (false? (plan/failed? outcomes)))))
           (testing "the first failure ends the plan"
             (let [log (atom [])
                   outcomes (await (plan/perform! [[:storrito/flaky-once] [:storrito/create-note {:text "b"}] [:remember "after"]]
                                              {:execute! (execute-with log {"e0" {:ok true :value {:ok true}}
                                                                            "e1" {:ok false :error "Storrito answered HTTP 400 to create-note: no"}})}))]
               (is (= ["e0" "e1"] (mapv first @log)) "the third is never called")
               (is (= [:done :failed :skipped] (mapv :status outcomes)))
               (is (true? (plan/failed? outcomes)))
               (is (= "Plan of 3 effects: 1 done, failed at effect 2, the rest not run.\n1. storrito/flaky-once: done {:ok true}\n2. storrito/create-note {:text \"b\"}: failed: Storrito answered HTTP 400 to create-note: no\n3. remember \"after\": not run"
                      (plan/report outcomes)))))
           (testing "a stop ends it too"
             (let [log (atom [])
                   stopped (atom false)
                   outcomes (await (plan/perform! [[:storrito/flaky-once] [:storrito/flaky-once]]
                                              {:execute! (fn [key tool args]
                                                           (reset! stopped true)
                                                           ((execute-with log {}) key tool args))
                                               :aborted? #(deref stopped)}))]
               (is (= [:done :aborted] (mapv :status outcomes)))
               (is (= "Plan of 2 effects: 1 done, stopped at effect 2." (plan/summary outcomes)))))
           (testing "a plan of one"
             (is (= "Plan of 1 effect: 1 done.\n1. remember \"x\": done"
                    (plan/report (await (plan/perform! [[:remember "x"]] {:execute! (execute-with (atom []) {})}))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest describe-test
  (is (= "storrito/create-note {:text \"a\"}" (plan/describe [:storrito/create-note {:text "a"}])))
  (is (= "remember \"x\" {:a 1}" (plan/describe [:remember "x" {:a 1}])))
  (is (str/ends-with? (plan/describe [:remember "x" {:text (apply str (repeat 300 "a"))}]) " …") "cut"))
