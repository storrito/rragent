(ns rragent.watcher-test
  "The job of milestone 1, end to end on a laptop against the faux
   model: a schedule the agent sets, which wakes it every second; at
   each run it fetches a page from a local server and notes what it saw
   as an event. The model's moves are scripted; the plumbing is real:
   the task that sleeps and fires, the input it submits, the tools it
   calls, the document it writes."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.events :as events]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.runs :as runs]
            [rragent.schedule-test :as schedule-test]
            [rragent.sprite :as sprite]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

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
                                (tools-test/tool-call "remember" {:event "page-seen" :data {:run n :title "Hello"}} (str "s" n))
                                (tools-test/answer (str "Checked, run " n "."))])]
               (.setResponses faux (clj->js (concat [(tools-test/tool-call "schedule" {:action "set" :name "watch" :cron "* * * * * *" :text (str "Check " url " and note what you saw.")} "w1")
                                                     (tools-test/answer "Watching.")]
                                                    (mapcat run (range 1 12)))))
               (binding [sprite/*sprite-env* "no-such-command-anywhere"]
                 (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
                   (try
                     (is (= "Watching." (await (harness/ask agent (str "Watch " url " every second.")))))
                     (await (sleep 3500))
                     (testing "the schedule fired more than once, each run of the bot fetched the page and noted an event in the bot's file"
                       (let [runs (await (schedule-test/runs-of agent))
                             done (filter #(= :done (:state %)) runs)
                             seen (events/select (events/read (home/events-file (home/bot-dir home (.-id (:root agent))))) {:event "page-seen"})]
                         (is (<= 2 (count done)) (pr-str runs))
                         (is (every? #(str/starts-with? (:line %) "Checked, run") done))
                         (is (<= 2 @hits) "the page was fetched at each run")
                         (is (<= 2 (count seen)) (pr-str seen))
                         (is (= "#{\"Hello\"}" (events/project seen "(set (map :title events))")) "a projection over them")
                         (is (= [] (filter #(= "rragent.schedule" %) (await (schedule-test/kinds agent)))) "nothing of it in the bot's chat")
                         (testing "the fetch tool saw the page, in the run's conversation"
                           (let [child (await (.conversation (:harness agent) (:conversation (first done)) harness/context))
                                 page (await (.entries child #js {} 100 js/undefined harness/context))
                                 results (->> (array-seq (.-items page))
                                              (filter #(= "pi.tool-result" (.-kind %)))
                                              (map #(js/JSON.stringify (.-model %))))]
                             (is (some #(str/includes? % "HTTP 200") results))
                             (is (some #(str/includes? % "<title>") results))))))
                     (finally
                       (await (harness/close agent))
                       (test-home/remove! home))))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn [] (.close server) (done)))))))
