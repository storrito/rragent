(ns rragent.events-test
  "The bot's events: the file of one EDN map per line, appended and
   read back, narrowed and projected; the entries of before moved into
   the file once."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.events :as events]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def sample
  [{:event "review-posted" :at 1000 :id 1 :rid 4711 :stars 5}
   {:event "price-checked" :at 2000 :id 2 :eur 180}
   {:event "review-posted" :at 3000 :id 3 :rid 4712 :stars 4}])

(deftest select-test
  (is (= [1 2 3] (mapv :id (events/select sample {}))) "everything, as it came")
  (is (= [1 3] (mapv :id (events/select sample {:event "review-posted"}))))
  (is (= [2 3] (mapv :id (events/select sample {:since "1970-01-01T00:00:02Z"}))) "from a time on")
  (is (thrown? js/Error (events/select sample {:since "yesterday"}))))

(deftest project-test
  (testing "a projection the agent writes over the events"
    (is (= "#{4711 4712}" (events/project (events/select sample {:event "review-posted"}) "(set (map :rid events))")))
    (is (= "2" (events/project sample "(count (filter #(= \"review-posted\" (:event %)) events))")))
    (is (= "{4711 5, 4712 4}" (events/project sample "(->> events (filter :rid) (map (juxt :rid :stars)) (into (sorted-map)))"))))
  (testing "without code the events themselves; an error is the program's; the answer is cut"
    (is (str/starts-with? (events/project sample nil) "[{:event \"review-posted\""))
    (is (thrown? js/Error (events/project sample "(nothing-here events)")))
    (is (str/ends-with? (events/project sample "(apply str (repeat 20000 \"x\"))") "(cut at 10000 characters)"))))

(deftest file-test
  (let [home (test-home/make)
        file (home/events-file home)]
    (try
      (testing "no file is no events"
        (is (= [] (events/read file))))
      (testing "appended one per line, read back oldest first, the agent's keys kept"
        (events/append! file "review-posted" {:id 4711 :stars 5 :text "Great\nstay"} 1000)
        (events/append! file "price-checked" nil 2000)
        (is (= 2 (count (str/split-lines (fs/readFileSync file "utf8")))))
        (is (= [{:event "review-posted" :at 1000 :id 4711 :stars 5 :text "Great\nstay"}
                {:event "price-checked" :at 2000}]
               (events/read file))))
      (testing "a line that does not read is skipped, the others stay"
        (fs/appendFileSync file "{:event \"broken\n")
        (fs/appendFileSync file "42\n")
        (events/append! file "seen" {} 3000)
        (is (= ["review-posted" "price-checked" "seen"] (mapv :event (events/read file)))))
      (finally
        (test-home/remove! home)))))

(deftest migrate-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [models]} (tools-test/faux-models)
                 agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                 root (:root agent)
                 file (home/events-file (home/bot-dir home (.-id root)))]
             (try
               (testing "the fact entries of before, written as the events file, oldest first, once"
                 (is (nil? (await (events/migrate! root file harness/context))) "nothing to move: no file is made")
                 (is (not (fs/existsSync file)))
                 (await (.submit root #js {:type "write" :entry #js {:kind "rragent.fact" :data #js {:event "seen" :at 1000 :n 1}}} harness/context))
                 (await (.submit root #js {:type "write" :entry #js {:kind "rragent.fact" :data #js {:event "seen" :at 2000 :n 2}}} harness/context))
                 (await (.submit root #js {:type "write" :entry #js {:kind "app.note" :data #js {:x 1}}} harness/context))
                 (is (= 2 (await (events/migrate! root file harness/context))))
                 (is (= [{:event "seen" :at 1000 :n 1} {:event "seen" :at 2000 :n 2}] (events/read file)))
                 (is (nil? (await (events/migrate! root file harness/context))) "the file is there: nothing moved again")
                 (is (= 2 (count (events/read file)))))
               (finally
                 (await (harness/close agent))
                 (test-home/remove! home))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest reducible-test
  (let [home (test-home/make)
        file (home/events-file home)
        n 3000]
    (try
      ;; a few chunks' worth, with characters of more than one byte on
      ;; every line, so that some chunk ends inside one of them
      (dotimes [i n]
        (events/append! file (if (odd? i) "odd" "even") {:n i :text "grüß ✓ naïve"} (* 1000 i)))
      (is (< events/chunk-size (.-size (fs/statSync file))) "the log spans chunks")
      (testing "every event, oldest first, whole"
        (let [all (into [] (events/reducible file))]
          (is (= n (count all)))
          (is (= (range n) (map :n all)))
          (is (every? #(= "grüß ✓ naïve" (:text %)) all))
          (is (= all (events/read file)))))
      (testing "a transduce, a reduced value that stops the reading, a seq"
        (is (= 1500 (transduce (events/selection {:event "odd"}) (completing (fn [acc _] (inc acc))) 0 (events/reducible file))))
        (is (= 7 (reduce (fn [acc {:keys [n]}] (if (= 7 n) (reduced n) acc)) nil (events/reducible file))))
        (is (= [0 1 2] (into [] (comp (take 3) (map :n)) (events/reducible file))))
        (is (= 5 (:n (first (filter #(= 5 (:n %)) (events/reducible file)))))))
      (testing "recall's shape: an eduction of the selection, the bot's idioms over it"
        (let [chosen (eduction (events/selection {:event "odd" :since "1970-01-01T00:00:10Z"}) (events/reducible file))]
          (is (= "1495" (events/project chosen "(count events)")))
          (is (= "#{11 13}" (events/project chosen "(->> events (map :n) (take 2) set)")))
          (is (= (str (reduce + (filter odd? (range 10 n)))) (events/project chosen "(reduce (fn [acc e] (+ acc (:n e))) 0 events)")))
          (is (str/starts-with? (events/project chosen nil) "[{:n 11,") "without code the events themselves, a vector")))
      (testing "a Windows line end, a blank line, a line that is no map, and no newline at the end"
        (fs/writeFileSync file "{:event \"a\" :at 1}\r\n\n{:event \"b\" :at 2}\n[:a :vector]\n{:event \"c\" :at 3}")
        (is (= ["a" "b" "c"] (map :event (events/reducible file)))))
      (testing "no file is no events"
        (fs/rmSync file)
        (is (= [] (into [] (events/reducible file))))
        (is (nil? (seq (events/reducible file)))))
      (finally
        (fs/rmSync home #js {:recursive true :force true})))))
