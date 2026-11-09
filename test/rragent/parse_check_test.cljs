(ns rragent.parse-check-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.parse-check :as parse-check]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(def program
  "A namespace as a bot writes one for nbb: a shebang, aliases, an
   auto-resolved keyword, `#js`, a regex of the JVM's syntax, a reader
   conditional, an anonymous function, syntax quote."
  (str "#!/usr/bin/env nbb\n"
       "(ns planner\n"
       "  (:require [clojure.string :as str] [\"node:fs\" :as fs] [promesa.core :as p]))\n"
       "(def opts #js {:timeZone \"America/Indiana/Indianapolis\"})\n"
       "(def hours [13 14 15 16])\n"
       "(defn slot [s] {::p/hour (:hour s) ::id (:id s) :re #\"a++(?<name>\\p{L})\"})\n"
       "(defn pad [n] #?(:clj (format \"%02d\" n) :cljs (str (when (< n 10) \"0\") n)))\n"
       "(defmacro m [x] `(inc ~x))\n"
       "(def xs (map #(* 2 %) hours))\n"
       "#_(comment (slot {}))\n"
       "(def c \\a)\n"))

(deftest problem-test
  (testing "a program that reads"
    (is (nil? (parse-check/problem program)))
    (is (nil? (parse-check/problem "")) "an empty file")
    (is (nil? (parse-check/problem "{:a 1 :b #inst \"2026-11-09T00:00:00Z\" :c #uuid \"00000000-0000-0000-0000-000000000000\"}")) "EDN with tags"))
  (testing "a form that does not close"
    (let [p (parse-check/problem "(ns a)\n\n(defn f [x]\n  (let [y (inc x)]\n    y)\n")]
      (is (str/includes? (:message p) "EOF while reading"))
      (is (str/includes? (:message p) "[3,1]") "nbb's message names where the open form starts")
      (is (number? (:line p)))))
  (testing "a paren too many"
    (let [p (parse-check/problem "(ns a)\n(def x (inc 1)))\n")]
      (is (str/includes? (:message p) "Unmatched delimiter: )"))
      (is (= 2 (:line p)))
      (is (= 16 (:column p)))))
  (testing "a string left open"
    (is (some? (parse-check/problem "(def s \"open)\n")))))

(deftest clojure-file-test
  (is (parse-check/clojure-file? "programs/clyde/main.cljs"))
  (is (parse-check/clojure-file? "a/b.CLJC"))
  (is (parse-check/clojure-file? "data/plan.edn"))
  (is (parse-check/clojure-file? "bb/task.bb"))
  (is (not (parse-check/clojure-file? "README.md")))
  (is (not (parse-check/clojure-file? "main.cljs.bak"))))

(deftest resolve-path-test
  (is (= "/w/programs/a.cljs" (parse-check/resolve-path "/w" "programs/a.cljs")))
  (is (= "/w/programs/a.cljs" (parse-check/resolve-path "/w" "@programs/a.cljs")) "pi-durable drops a leading @")
  (is (= "/x/a.cljs" (parse-check/resolve-path "/w" "/x/a.cljs")))
  (is (= "/x/a.cljs" (parse-check/resolve-path "/w" "../x/a.cljs"))))

(defn- ok-result [text] #js {:output #js [#js {:type "text" :text text}] :details #js {:x 1}})

(deftest checked-test
  (async done
    (-> ((^:async fn []
           (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "parse-check-"))
                 cwd-of (fn [] (js/Promise.resolve dir))]
             (try
               (testing "write: the content of the call"
                 (is (nil? (await (parse-check/checked #js {:name "write" :arguments #js {:path "a.cljs" :content program}}
                                                       (ok-result "Successfully wrote to a.cljs") cwd-of)))
                     "a file that reads gets nothing")
                 (let [r (await (parse-check/checked #js {:name "write" :arguments #js {:path "programs/x/main.cljs" :content "(ns main)\n(defn f [] (inc 1)\n"}}
                                                     (ok-result "Successfully wrote to programs/x/main.cljs") cwd-of))
                       texts (mapv #(.-text %) (array-seq (.-output r)))]
                   (is (= "Successfully wrote to programs/x/main.cljs" (first texts)) "the tool's own line stays first")
                   (is (str/starts-with? (second texts) "programs/x/main.cljs does not read as Clojure: EOF while reading"))
                   (is (str/includes? (second texts) "fix this form first"))
                   (is (= 1 (.. r -details -x)) "the rest of the result stays")))
               (testing "edit: the file as the edit left it, in the conversation's directory"
                 (fs/writeFileSync (path/join dir "b.cljs") "(ns b)\n(def x 1))\n")
                 (let [r (await (parse-check/checked #js {:name "edit" :arguments #js {:path "b.cljs" :edits #js []}}
                                                     (ok-result "Successfully replaced 1 block(s) in b.cljs.") cwd-of))]
                   (is (str/includes? (.-text (aget (.-output r) 1)) "Unmatched delimiter: ) (line 2, column 10)")))
                 (fs/writeFileSync (path/join dir "c.cljs") "(ns c)\n")
                 (is (nil? (await (parse-check/checked #js {:name "edit" :arguments #js {:path "c.cljs"}} (ok-result "ok") cwd-of)))))
               (testing "no check"
                 (is (nil? (await (parse-check/checked #js {:name "write" :arguments #js {:path "notes.md" :content "(("}} (ok-result "ok") cwd-of)))
                     "a file that is no Clojure")
                 (is (nil? (await (parse-check/checked #js {:name "write" :arguments #js {:path "a.cljs" :content "(("}} #js {:isError true :output #js []} cwd-of)))
                     "a call that failed")
                 (is (nil? (await (parse-check/checked #js {:name "read" :arguments #js {:path "b.cljs"}} (ok-result "(ns b)") cwd-of)))
                     "a read")
                 (is (nil? (await (parse-check/checked #js {:name "edit" :arguments #js {:path "gone.cljs"}} (ok-result "ok") cwd-of)))
                     "a file that is not there"))
               (finally
                 (fs/rmSync dir #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest write-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 sent (atom nil)]
             (.setResponses faux #js [(tools-test/tool-call "write" {:path "programs/plan/main.cljs" :content "(ns main)\n\n(defn plan [x]\n  (inc x)\n"} "w1")
                                      (fn [context _options _state _model]
                                        (reset! sent (js/JSON.stringify (.-messages context)))
                                        (tools-test/answer "Written."))])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))]
               (try
                 (is (= "Written." (await (harness/ask agent "Write the planner."))))
                 (is (fs/existsSync (path/join (home/work-dir (home/bot-dir home 1)) "programs/plan/main.cljs")) "the file is written as it came")
                 (is (str/includes? @sent "programs/plan/main.cljs does not read as Clojure: EOF while reading") "the model is told at once")
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
