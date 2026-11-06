(ns rragent.state-machine-test
  "The machine as data: which tool a call names, the answer into the
   state, when it is done or stuck, what the model reads."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.state-machine :as machine]))

(defn- failure
  [thunk]
  (try (thunk) nil (catch :default e (.-message e))))

(deftest tool-call-test
  (testing "a storrito/ procedure, any name of the catalog, one map"
    (is (= {:tool "storrito" :args {:procedure "create-note" :args {:text "a"}}} (machine/tool-call [:storrito/create-note {:text "a"}] nil)))
    (is (= {:tool "storrito" :args {:procedure "generate-uuid" :args {}}} (machine/tool-call [:storrito/generate-uuid] nil)))
    (is (str/includes? (failure #(machine/tool-call [:storrito/create-note "a"] nil)) "one map of arguments")))
  (testing "a request: a GET is a read the host may make again, anything else is not"
    (is (= {:tool "gateway-read" :args {:which "echo" :path "/x" :opts {}}} (machine/tool-call [:call "echo" "/x"] nil)))
    (is (= {:tool "gateway-read" :args {:which "echo" :path "/x" :opts {:query {:a 1}}}} (machine/tool-call [:call "echo" "/x" {:query {:a 1}}] nil)))
    (is (= {:tool "gateway" :args {:which "echo" :path "/x" :opts {:method :post :body {:a 1}}}} (machine/tool-call [:call "echo" "/x" {:method :post :body {:a 1}}] nil)))
    (is (str/includes? (failure #(machine/tool-call [:call "echo"] nil)) "it takes which, path at least"))
    (is (str/includes? (failure #(machine/tool-call [:call "echo" "/x" {} 4] nil)) "it takes which, path, opts and no more")))
  (testing "composio, the search session carried into the calls after a search"
    (is (= {:tool "composio-read" :safe true :args {:action "search" :use-case "send an email" :toolkits ["gmail"]}}
           (machine/tool-call [:composio/search "send an email" ["gmail"]] nil)))
    (is (= {:tool "composio" :safe nil :args {:action "execute" :slug "GMAIL_SEND_EMAIL" :args {:to "a"} :session "search_1"}}
           (machine/tool-call [:composio/execute "GMAIL_SEND_EMAIL" {:to "a"}] "search_1")))
    (is (= {:tool "composio" :safe nil :args {:action "connections" :toolkits ["gmail"]}} (machine/tool-call [:composio/connections ["gmail"]] nil)))
    (is (= {:tool "composio-read" :safe true :args {:action "accounts"}} (machine/tool-call [:composio/accounts] nil)))
    (is (= {:tool "composio" :safe nil :args {:action "add-trigger" :slug "T" :account "ca_1" :config {}}} (machine/tool-call [:composio/add-trigger "T" "ca_1" {}] nil))))
  (testing "mcp: a tool, and the changes of the servers"
    (is (= {:tool "mcp" :args {:server "github" :tool "search_issues" :args {:q "x"}}} (machine/tool-call [:mcp/call "github" "search_issues" {:q "x"}] nil)))
    (is (= {:tool "mcp-admin" :args {:admin "add" :name "gh" :opts {:url "u"}}} (machine/tool-call [:mcp/add! "gh" {:url "u"}] nil)))
    (is (= {:tool "mcp-admin" :args {:admin "test" :name "gh"}} (machine/tool-call [:mcp/test "gh"] nil)))
    (is (= {:tool "mcp-admin" :args {:admin "enable" :name "gh" :on? false}} (machine/tool-call [:mcp/enable! "gh" false] nil)))
    (is (= {:tool "mcp-admin" :args {:admin "remove" :name "gh"}} (machine/tool-call [:mcp/remove! "gh"] nil))))
  (testing "the bot's own: events, files, the connectors"
    (is (= {:tool "remember" :args {:event "posted" :data {:id 1}}} (machine/tool-call [:remember "posted" {:id 1}] nil)))
    (is (= {:tool "events" :args {:event "posted"}} (machine/tool-call [:recall {:event "posted"}] nil)))
    (is (= {:tool "events" :args {}} (machine/tool-call [:recall] nil)))
    (is (= {:tool "spit" :args {:path "data/x.edn" :content "{:a 1}"}} (machine/tool-call [:spit "data/x.edn" "{:a 1}"] nil)))
    (is (= {:tool "slurp" :args {:path "data/x.edn"}} (machine/tool-call [:slurp "data/x.edn"] nil)))
    (is (= {:tool "connectors" :args {} :safe true} (machine/tool-call [:connectors] nil))))
  (testing "what is no call"
    (is (str/includes? (failure #(machine/tool-call {:a 1} nil)) ":next names no call"))
    (is (str/includes? (failure #(machine/tool-call [:foo 1] nil)) "no call the shell runs"))))

(deftest next-test
  (is (nil? (machine/next-of {:a 1})))
  (is (= {:call [:call "echo" "/x"] :as :r} (machine/next-of {:next {:call [:call "echo" "/x"] :as :r}})))
  (is (= {:call [:call "echo" "/x"] :as :result} (machine/next-of {:next {:call [:call "echo" "/x"]}})) ":result without :as")
  (is (str/includes? (failure #(machine/next-of {:next [:call "echo" "/x"]})) ":next is a map"))
  (is (str/includes? (failure #(machine/next-of {:next {:as :r}})) ":next has no :call"))
  (is (str/includes? (failure #(machine/next-of {:next {:call [:call "echo" "/x"] :as "r"}})) ":as names the key")))

(deftest answered-test
  (let [next {:call [:call "echo" "/x"] :as :r}]
    (is (= {:a 1 :r {:status 200}} (machine/answered {:a 1 :next next} next {:ok true :value {:status 200}})))
    (is (= {:a 1 :r {:error "down"}} (machine/answered {:a 1 :next next} next {:ok false :error "down"})))
    (is (= {:r {:error "HTTP 400" :status 400 :body {:e 1}}} (machine/answered {:next next} next {:ok false :error "HTTP 400" :status 400 :body {:e 1}})))))

(deftest session-test
  (is (= "search_1" (machine/session-of [:composio/search "x"] {:session {:id "search_1"} :tools []})))
  (is (nil? (machine/session-of [:composio/execute "X" {}] {:session {:id "search_1"}})))
  (is (nil? (machine/session-of [:composio/search "x"] {}))))

(deftest done-test
  (is (true? (machine/done? {:a 1})))
  (is (true? (machine/done? {:done 1 :next {:call [:call "e" "/x"]}})) ":done ends it whatever :next says")
  (is (false? (machine/done? {:next {:call [:call "e" "/x"]}})))
  (testing "stuck: the last answer failed and the state does nothing about it"
    (is (true? (machine/stuck? {:r {:error "down"}} :r)))
    (is (false? (machine/stuck? {:r {:error "down"} :next {:call [:call "e" "/x"]}} :r)) "it asks again")
    (is (false? (machine/stuck? {:r {:error "down"} :done :gave-up} :r)) "it is done with it")
    (is (false? (machine/stuck? {:r {:status 200}} :r)))
    (is (false? (machine/stuck? {:r {:error "down"}} nil)) "no call was answered yet")))

(deftest text-test
  (is (= "State: {:accounts [1 2]}" (machine/text [""] {:accounts [1 2]} 0)))
  (is (= "hi\nAfter 2 calls.\nDone: 3\nState: {:posted 3}" (machine/text ["hi\n" ""] {:posted 3 :done 3} 2)))
  (is (= "one\ntwo\nAfter 1 call.\nState: {}" (machine/text ["one" "\ntwo"] {} 1)))
  (is (str/ends-with? (machine/text [] {:big (apply str (repeat 20000 "a"))} 0) "(cut at 10000 characters)")))

(deftest describe-test
  (is (= "storrito/create-note {:text \"a\"}" (machine/describe [:storrito/create-note {:text "a"}])))
  (is (= "connectors" (machine/describe [:connectors]))))
