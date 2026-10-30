(ns main-test
  "The tests, run with `nbb main_test.cljs`: exit code 1 when one
   failed. A test that waits for a promise is `async` around a
   promesa chain that ends in `done`."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :as t :refer [async deftest is testing]]
            [main]
            [nbb.core :refer [*file*]]
            [promesa.core :as p]
            [store]))

(def here
  "This folder, whatever the working directory the tests run from."
  (path/dirname *file*))

(deftest parse-test
  (is (= {:command "import" :source "items.json" :db main/state-file}
         (main/parse ["import" "items.json"])))
  (is (= {:command "seen" :source nil :db "x.sqlite"}
         (main/parse ["seen" "--db" "x.sqlite"])))
  (is (thrown? js/Error (main/parse ["seen" "--db"]))))

(deftest store-test
  (let [db (store/open ":memory:")
        item {:id 1 :text "Great stay"}]
    (testing "never seen"
      (is (= :new (store/status db item))))
    (testing "seen as it is"
      (store/remember! db item)
      (is (= :known (store/status db item)))
      (is (= :known (store/status db {:text "Great stay" :id 1})) "the keys' order does not count"))
    (testing "seen with other content, then remembered so"
      (let [edited (assoc item :text "Great stay!")]
        (is (= :changed (store/status db edited)))
        (store/remember! db edited)
        (is (= :known (store/status db edited)))
        (is (= :changed (store/status db item)))))
    (testing "the ids, the oldest first"
      (store/remember! db {:id "b"})
      (is (= ["1" "b"] (store/ids db))))))

(deftest import-test
  (async done
    (-> (p/let [db (store/open ":memory:")
                items (main/items-of (path/join here "items.json"))]
          (testing "the first run: every item is new"
            (is (= ["new" "new"] (map :status (main/import! db items)))))
          (testing "the second run: nothing to tell"
            (is (= [] (main/import! db items))))
          (testing "an edited item is told once, as changed"
            (let [edited (update-in items [0 :text] str " Loved the view.")]
              (is (= [(assoc (first edited) :status "changed")] (main/import! db edited)))
              (is (= [] (main/import! db edited))))))
        (p/catch (fn [error] (is (nil? error) (str error))))
        (p/finally done))))

(deftest items-of-test
  (async done
    (-> (p/let [dir (fs/mkdtempSync (path/join (os/tmpdir) "program-"))
                file (path/join dir "no-ids.json")
                _ (fs/writeFileSync file "[{\"text\": \"no id here\"}]")
                outcome (-> (main/items-of file)
                            (p/then (fn [_] "answered"))
                            (p/catch (fn [error] (.-message error))))]
          (is (re-find #"an id each" outcome) "a file without ids is refused")
          (fs/rmSync dir #js {:recursive true :force true}))
        (p/catch (fn [error] (is (nil? error) (str error))))
        (p/finally done))))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'main-test)
