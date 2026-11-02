(ns rragent.file-kinds-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.file-kinds :as file-kinds]
            [rragent.uploads :as uploads]))

(deftest kind-test
  (testing "a text or data file the agent reads as it is, a document it opens with a command"
    (is (= :text (file-kinds/kind "notes.md")))
    (is (= :text (file-kinds/kind "sales.CSV")) "the case of the extension does not matter")
    (is (= :text (file-kinds/kind "main.clj")))
    (is (= :text (file-kinds/kind "captions.srt")))
    (is (= :file (file-kinds/kind "Q3 report.pdf")))
    (is (= :file (file-kinds/kind "deck.pptx")))
    (is (= :file (file-kinds/kind "brand.woff2")))
    (is (= :file (file-kinds/kind "footage.zip"))))
  (testing "what the agent is not handed"
    (is (nil? (file-kinds/kind "setup.exe")))
    (is (nil? (file-kinds/kind "disk.dmg")))
    (is (nil? (file-kinds/kind "notes")))
    (is (nil? (file-kinds/kind "")))))

(deftest extension-test
  (testing "the name says, else the type"
    (is (= "pdf" (file-kinds/extension "Q3 report.pdf" "application/octet-stream")))
    (is (= "clj" (file-kinds/extension "main.clj" "")))
    (is (= "zip" (file-kinds/extension nil "application/zip")))
    (is (= "docx" (file-kinds/extension "" "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
    (is (nil? (file-kinds/extension "setup.exe" "application/x-msdownload")))
    (is (nil? (file-kinds/extension nil nil)))))

(deftest lists-test
  (testing "no extension is a text and a document both; every one has a kind the server knows"
    (is (empty? (set/intersection file-kinds/texts file-kinds/documents)))
    (is (every? #(= :text (uploads/kind (str "x." %))) file-kinds/texts))
    (is (every? #(= :file (uploads/kind (str "x." %))) file-kinds/documents))
    (is (every? #(or (file-kinds/texts %) (file-kinds/documents %)) (vals file-kinds/types)) "a type leads to a listed extension"))
  (testing "the picker's accept names every extension with a dot"
    (is (str/includes? file-kinds/accept ".pdf"))
    (is (str/includes? file-kinds/accept ".csv"))
    (is (= (count (into file-kinds/texts file-kinds/documents)) (count (str/split file-kinds/accept #","))))
    (is (every? #(str/starts-with? % ".") (str/split file-kinds/accept #",")))))

(deftest icon-test
  (is (= "🎬" (file-kinds/icon :video)))
  (is (= "📄" (file-kinds/icon :text)))
  (is (= "📎" (file-kinds/icon :file)))
  (is (= "📎" (file-kinds/icon nil))))
