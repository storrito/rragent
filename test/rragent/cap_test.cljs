(ns rragent.cap-test
  "The cap on the month's spend (docs/one-loop.md §8, `rragent.cap`):
   the ledger and its sum as the person pays it, the state, the cap
   set and taken away in its document, the notice into the loop once a
   month at 80 percent, and the refusals at the cap: the `task` tool,
   a schedule's fire, a hook's call."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.bots :as bots]
            [rragent.cap :as cap]
            [rragent.costs :as costs]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.machine :as machine]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(deftest ledger-test
  (let [home (test-home/make)
        now (.getTime (js/Date. "2026-11-09T20:00:00Z"))]
    (try
      (is (= "2026-11" (cap/month now)))
      (is (= "2026-01" (cap/month (.getTime (js/Date. "2026-01-31T23:59:00Z")))))
      (is (= 0 (cap/spent home now)) "no ledger: nothing spent")
      (cap/charge! home 7 0.5 now)
      (cap/charge! home 9 0.25 now)
      (cap/charge! home 7 0 now)
      (cap/charge! home 7 nil now)
      (is (= (* 0.75 (costs/factor)) (cap/spent home now)) "the charges summed, priced as the person pays")
      (is (= 2 (count (str/split-lines (str/trim (fs/readFileSync (path/join home "costs" "2026-11.edn") "utf8"))))) "two lines, nothing for no charge")
      (is (= 0 (cap/spent home (.getTime (js/Date. "2026-12-01T00:00:00Z")))) "another month, another ledger")
      (testing "the state"
        (is (= {:monthly nil :spent 3 :reached? false :share nil} (cap/state {:monthly nil} 3)) "no cap: never reached")
        (is (= {:monthly 10 :spent 3 :reached? false :share 0.3} (cap/state {:monthly 10} 3)))
        (is (true? (:reached? (cap/state {:monthly 10} 10))))
        (is (true? (:reached? (cap/state {:monthly 10} 12)))))
      (finally
        (test-home/remove! home)))))

(defn- ^:async entries
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (reverse)
         (mapv (fn [e] {:kind (.-kind e) :data (js->clj (.-data e) :keywordize-keys true)})))))

(defn- ^:async results
  [conversation]
  (let [page (await (.entries conversation #js {} 200 js/undefined harness/context))]
    (->> (array-seq (.-items page))
         (filter #(= "pi.tool-result" (.-kind %)))
         (map #(aget (.-model %) 0))
         (into {} (map (fn [r] [(.-toolCallId r) (tools-test/result-text r)]))))))

(deftest the-cap-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (try
               (await (machine/open! {:home home :models models :provider "faux" :storage (harness/memory-storage)}))
               (let [loop (machine/loop-id)
                     _ (bots/set-metadata! home loop "model" "faux-1")
                     conversation (:conversation (machine/bot loop))
                     harness (:harness (machine/agent))]
                 (testing "no cap at the open; set, it is in the document and the page's state"
                   (is (= {:monthly nil :spent 0 :reached? false :share nil} (cap/current)))
                   (await (cap/set-monthly! harness 10 harness/context))
                   (is (= 10 (:monthly (cap/current))))
                   (is (= {:monthly 10 :noticed nil} (await (cap/settings harness harness/context)))))
                 (testing "a charge past 80 percent: one notice into the loop, which it answers; a second charge no second notice"
                   (.setResponses faux #js [(tools-test/answer "Nearly spent, Lydia: one card.")])
                   (await (cap/charged! (/ 8.5 (costs/factor)) (js/Number loop) harness/context))
                   (await (.waitForIdle conversation harness/context))
                   (let [es (await (entries conversation))
                         notices (filter #(= cap/notice-kind (:kind %)) es)]
                     (is (= 1 (count notices)))
                     (is (= 10 (:monthly (:data (first notices)))))
                     (is (< 8.4 (:spent (:data (first notices))) 8.6)))
                   (is (= (cap/month (js/Date.now)) (:noticed (await (cap/settings harness harness/context)))))
                   (await (cap/charged! (/ 0.5 (costs/factor)) (js/Number loop) harness/context))
                   (is (= 1 (count (filter #(= cap/notice-kind (:kind %)) (await (entries conversation))))) "once a month")
                   (is (false? (cap/reached?))))
                 (testing "at the cap: the task tool refuses, a hook's call is dropped"
                   (await (cap/charged! (/ 2 (costs/factor)) (js/Number loop) harness/context))
                   (is (true? (cap/reached?)))
                   (.setResponses faux #js [(tools-test/tool-call "task" {:text "Count the covers." :model "faux-1"} "t1")
                                            (tools-test/answer "Told the person.")])
                   (is (= "Told the person." (await (harness/ask-in conversation "Count them."))))
                   (is (str/includes? (get (await (results conversation)) "t1") "budget is spent"))
                   (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                            (tools-test/answer "Made.")])
                   (is (= "Made." (await (harness/ask-in conversation "Make the hook."))))
                   (let [doc (await (.snapshot harness hooks/Hooks (.-id conversation) harness/context))
                         secret (.. doc -reviews -secret)
                         before (count (await (entries conversation)))
                         result (await (hooks/receive! {:harness harness :conversation conversation} "reviews" secret {} "five stars" harness/context))]
                     (is (= {:ok true :dropped true} result))
                     (is (= before (count (await (entries conversation)))) "nothing entered the chat")))
                 (testing "the cap raised: the refusals end"
                   (await (cap/set-monthly! harness 100 harness/context))
                   (is (false? (cap/reached?)))
                   (await (cap/set-monthly! harness nil harness/context))
                   (is (= {:monthly nil :noticed (cap/month (js/Date.now))} (await (cap/settings harness harness/context))) "taken away, the month's notice remembered")))
               (finally
                 (await (machine/close-all!))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
