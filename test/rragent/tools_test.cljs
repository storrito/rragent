(ns rragent.tools-test
  (:require ["node:http" :as http]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/faux" :refer [fauxAssistantMessage fauxProvider fauxText fauxToolCall]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.test-home :as test-home]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.state :as state-tool]))

(defn faux-models
  "The faux provider playing the model, its answers set with `set-responses!`;
   `options` as `fauxProvider` takes them, `:models` for models beside
   `faux-1`."
  ([] (faux-models nil))
  ([options]
   (let [faux (fauxProvider (if options (clj->js options) js/undefined))
         models (doto (createModels) (.setProvider (.-provider faux)))]
     {:faux faux :models models})))

(defn tool-call
  [name args id]
  (fauxAssistantMessage #js [(fauxToolCall name (clj->js args) #js {:id id})] #js {:stopReason "toolUse"}))

(defn answer
  [text]
  (fauxAssistantMessage #js [(fauxText text)]))

(deftest state-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (faux-models)]
             (.setResponses faux #js [(tool-call "state" {:patch {:handled [4711] :last "2026-10-18"}} "s1")
                                      (tool-call "state" {:patch {:last nil :note "x"}} "s2")
                                      (tool-call "state" {} "s3")
                                      (answer "Noted.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Noted." (await (harness/ask agent "Remember review 4711."))))
                 (let [state (js->clj (await (.snapshot (:harness agent) state-tool/State (.-id (:root agent)) harness/context)))]
                   (is (= {"handled" [4711] "note" "x"} state) "merged, a null key removed, read back as the last call"))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest fetch-test
  (async done
    (let [server (http/createServer (fn [req res]
                                      (.setHeader res "Content-Type" "text/plain")
                                      (.end res (str "hello from " (.-url req)))))]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (let [port (.-port (.address server))
                   {:keys [status headers text truncated]} (await (fetch-tool/request {:url (str "http://127.0.0.1:" port "/page")}))]
               (is (= 200 status))
               (is (= "text/plain" (get headers "content-type")))
               (is (= "hello from /page" text))
               (is (false? truncated)))
             (testing "only http and https"
               (let [e (try (await (fetch-tool/request {:url "file:///etc/passwd"})) nil (catch :default e e))]
                 (is (some? e))
                 (is (str/includes? (.-message e) "http or https"))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))

(deftest eval-test
  (is (= "3" (eval-tool/evaluate "(+ 1 2)")))
  (is (= "#'user/x" (eval-tool/evaluate "(def x 21)")))
  (is (= "42" (eval-tool/evaluate "(* x 2)")) "a def stays for the next call")
  (is (= "\"node\"" (eval-tool/evaluate "(.-title js/process)")) "the host is reachable as js/...")
  (is (thrown? js/Error (eval-tool/evaluate "(undefined-fn 1)")))
  (is (str/ends-with? (eval-tool/evaluate "(apply str (repeat 20000 \"a\"))") "(cut at 10000 characters)")))
