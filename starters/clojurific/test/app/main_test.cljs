(ns app.main-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [app.main :as main]
            [app.store :as store]
            [clojure.test :refer [async deftest is testing]]))

(deftest parse-test
  (is (= {:command "import" :source "items.json" :db "state.sqlite"}
         (main/parse ["import" "items.json"])))
  (is (= {:command "seen" :source nil :db "x.sqlite"}
         (main/parse ["seen" "--db" "x.sqlite"])))
  (is (thrown? js/Error (main/parse ["seen" "--db"]))))

(deftest import-test
  (async done
    (-> ((^:async fn []
           (let [db (store/open ":memory:")
                 items (await (main/items-of (path/join "test" "items.json")))]
             (testing "the first run: every item is new"
               (is (= ["new" "new"] (map :status (main/import! db items)))))
             (testing "the second run: nothing to tell"
               (is (= [] (main/import! db items))))
             (testing "an edited item is told once, as changed"
               (let [edited (update-in items [0 :text] str " Loved the view.")]
                 (is (= [(assoc (first edited) :status "changed")] (main/import! db edited)))
                 (is (= [] (main/import! db edited))))))))
        (.catch (fn [error] (is (nil? error) (str error))))
        (.finally done))))

(deftest items-of-test
  (async done
    (-> ((^:async fn []
           (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "program-"))
                 file (path/join dir "no-ids.json")]
             (fs/writeFileSync file "[{\"text\": \"no id here\"}]")
             (try
               (await (main/items-of file))
               (is false "a file without ids is refused")
               (catch :default error
                 (is (re-find #"an id each" (.-message error)))))
             (fs/rmSync dir #js {:recursive true :force true}))))
        (.catch (fn [error] (is (nil? error) (str error))))
        (.finally done))))
