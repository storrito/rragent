(ns rragent.watcher-test
  "The job of milestone 1, end to end on a laptop against the faux
   model: a schedule the agent sets, which wakes it every second; at
   each run it fetches a page from a local server and notes what it saw
   in its state. The model's moves are scripted; the plumbing is real:
   the task that sleeps and fires, the input it submits, the tools it
   calls, the document it writes."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.schedule-test :as schedule-test]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.tools.state :as state-tool]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest the-watcher-test
  (async done
    (let [hits (atom 0)
          server (http/createServer (fn [_req res]
                                      (swap! hits inc)
                                      (.setHeader res "Content-Type" "text/html")
                                      (.end res (str "<title>" (if (< @hits 3) "Hello" "Changed") "</title>"))))]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
             (let [url (str "http://127.0.0.1:" (.-port (.address server)) "/")
                   home (test-home/make)
                   {:keys [faux models]} (tools-test/faux-models)
                   run (fn [n] [(tools-test/tool-call "fetch" {:url url} (str "f" n))
                                (tools-test/tool-call "state" {:patch {:last (str "run " n)}} (str "s" n))
                                (tools-test/answer (str "Checked, run " n "."))])]
               (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "watch" :cron "* * * * * *" :text (str "Check " url " and note what you saw.")} "w1")
                                                     (tools-test/answer "Watching.")]
                                                    (mapcat run (range 1 12)))))
               (binding [sprite/*sprite-env* "no-such-command-anywhere"]
                 (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                   (try
                     (is (= "Watching." (await (harness/ask agent (str "Watch " url " every second.")))))
                     (await (sleep 3500))
                     (testing "the schedule fired more than once, each run fetched the page and wrote the state"
                       (let [k (await (schedule-test/kinds agent))
                             state (js->clj (await (.snapshot (:harness agent) state-tool/State (.-id (:root agent)) harness/context)))]
                         (is (<= 2 (count (filter #(= "rragent.schedule" %) k))) (pr-str k))
                         (is (<= 2 @hits) "the page was fetched at each run")
                         (is (re-matches #"run \d+" (str (get state "last"))) (pr-str state))))
                     (testing "the fetch tool saw the page"
                       (let [page (await (.entries (:root agent) #js {} 100 js/undefined harness/context))
                             results (->> (array-seq (.-items page))
                                          (filter #(= "pi.tool-result" (.-kind %)))
                                          (map #(js/JSON.stringify (.-model %))))]
                         (is (some #(str/includes? % "HTTP 200") results))
                         (is (some #(str/includes? % "<title>") results))))
                     (finally
                       (await (harness/close agent))
                       (test-home/remove! home))))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))
