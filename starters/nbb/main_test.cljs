(ns main-test
  "The tests, run with `nbb main_test.cljs`: exit code 1 when one
   failed. They work in a folder of their own laid out like a bot's,
   `events.edn` and `work/imports/`, through `--events` and
   `--imports`."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :as t :refer [async deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [events]
            [main]
            [nbb.core :refer [*file*]]
            [promesa.core :as p]))

(def here
  "This folder, whatever the working directory the tests run from."
  (path/dirname *file*))

(defn bot-folder
  "A fresh folder laid out like a bot's; the options `main/parse`
   would give for it."
  []
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "program-"))]
    {:events (path/join root "events.edn")
     :imports (path/join root "work" "imports")}))

(defn- write-json
  [dir name items]
  (let [file (path/join dir name)]
    (fs/writeFileSync file (js/JSON.stringify (clj->js items)))
    file))

(deftest parse-test
  (is (= (assoc main/defaults :command "import" :args ["items.json"])
         (main/parse ["import" "items.json"])))
  (is (= (assoc main/defaults :command "posted" :args ["r1" "instagram"] :events "x.edn" :source "google")
         (main/parse ["posted" "--events" "x.edn" "r1" "--source" "google" "instagram"])))
  (is (thrown? js/Error (main/parse ["new" "--events"]))))

(deftest source-name-test
  (is (= "example.com" (main/source-name "https://www.example.com/reviews?x=1")))
  (is (= "items" (main/source-name "artifacts/items.json"))))

(deftest key-of-test
  (is (= "7" (main/key-of {:id 7 :text "x"})))
  (is (= (main/key-of {:a 1 :b 2}) (main/key-of {:b 2 :a 1})) "the keys' order does not count")
  (is (not= (main/key-of {:a 1}) (main/key-of {:a 2}))))

(deftest import-and-replay-test
  (async done
    (-> (p/let [{:keys [events imports] :as options} (bot-folder)
                _ (main/import! options (path/join here "items.json"))
                _ (main/import! (assoc options :source "again") (path/join here "items.json"))]
          (testing "two imports, two files kept as they came, two events in the log's format"
            (is (= 1 (count (fs/readdirSync (path/join imports "items")))))
            (is (= 1 (count (fs/readdirSync (path/join imports "again")))))
            (let [lines (str/split-lines (fs/readFileSync events "utf8"))
                  first-event (edn/read-string (first lines))]
              (is (= 2 (count lines)))
              (is (= "imported" (:event first-event)))
              (is (number? (:at first-event)))
              (is (= 2 (:count first-event)))
              (is (str/starts-with? (:file first-event) "work/imports/items/") "the file relative to the log's folder")))
          (testing "the replay: the same items once, keyed by id, in the order first seen"
            (is (= ["1" "2"] (map :key (main/replay events))))
            (is (= ["1" "2"] (map :key (main/new-items events)))))
          (testing "an edited item in a later import: its newest version, still one item"
            (let [edited (write-json (path/join imports "items") "later.json"
                                     [{:id 1 :text "Great stay, we would come again. Loved the view." :stars 5}])]
              (events/append! events "imported" {:source edited :file (path/relative (path/dirname events) edited) :count 1})
              (is (= ["1" "2"] (map :key (main/replay events))))
              (is (str/ends-with? (:text (first (main/replay events))) "Loved the view."))))
          (testing "a posted item leaves the new ones; the others stay"
            (events/append! events "posted" {:key "1" :channel "instagram"})
            (is (= ["2"] (map :key (main/new-items events)))))
          (testing "an import file that is gone is an error, not a silent loss"
            (fs/rmSync (path/join imports "again") #js {:recursive true :force true})
            ;; `(is (thrown? …))` catches nothing inside a p/let body (README, Traps)
            (is (re-find #"is gone" (try (main/replay events) "no error" (catch :default error (.-message error)))))))
        (p/catch (fn [error] (is (nil? error) (str error))))
        (p/finally done))))

(deftest items-without-ids-test
  (async done
    (-> (p/let [{:keys [events imports] :as options} (bot-folder)
                rows (path/join (os/tmpdir) (str "rows-" (js/Date.now) ".json"))
                _ (write-json (path/dirname rows) (path/basename rows)
                              [{:date "2026-10-01" :amount -3.5 :text "Coffee"}
                               {:date "2026-10-02" :amount -120 :text "Rent"}])
                _ (main/import! (assoc options :source "bank") rows)
                _ (main/import! (assoc options :source "bank") rows)]
          (testing "overlapping exports without ids: a row once, by its content"
            (is (= 2 (count (fs/readdirSync (path/join imports "bank")))))
            (is (= 2 (count (main/replay events))))
            (is (= #{16} (set (map #(count (:key %)) (main/replay events))))))
          (fs/rmSync rows #js {:force true}))
        (p/catch (fn [error] (is (nil? error) (str error))))
        (p/finally done))))

(deftest items-of-test
  (is (= [{:id 1}] (main/items-of "[{\"id\": 1}]" "x")))
  (is (thrown? js/Error (main/items-of "{\"id\": 1}" "x")))
  (is (thrown? js/Error (main/items-of "[1, 2]" "x"))))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'main-test)
