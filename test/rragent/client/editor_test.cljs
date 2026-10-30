(ns rragent.client.editor-test
  "The editor of the pane: the syntax by extension."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.client.editor :as editor]))

(deftest language-test
  (testing "a syntax for the kinds of files a project has, Clojure's from clojure-mode, none for a plain text"
    (is (some? (editor/language "html")))
    (is (some? (editor/language "htm")))
    (is (some? (editor/language "css")))
    (is (some? (editor/language "js")))
    (is (some? (editor/language "mjs")))
    (is (some? (editor/language "ts")))
    (is (some? (editor/language "json")))
    (is (some? (editor/language "md")))
    (is (some? (editor/language "yml")))
    (is (some? (editor/language "xml")))
    (is (some? (editor/language "cljs")))
    (is (some? (editor/language "edn")))
    (is (nil? (editor/language "txt")))
    (is (nil? (editor/language "log")))
    (is (nil? (editor/language nil))))
  (testing "the key of a file's editor"
    (is (= "1/card/main.js" (editor/editor-key "1" "card/main.js")))))
