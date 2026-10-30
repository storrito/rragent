(ns rragent.csv-test
  "A CSV file read for the pane: quotes, separators, line ends, a byte
   order mark, a quote left open, the cap of the rows."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.csv :as csv]))

(deftest parse-test
  (testing "plain rows, a trailing line break making no row, CRLF, a byte order mark dropped"
    (is (= [["a" "b"] ["1" "2"]] (:rows (csv/parse "a,b\n1,2\n"))))
    (is (= [["a" "b"] ["1" "2"]] (:rows (csv/parse "a,b\r\n1,2\r\n"))))
    (is (= [["a" "b"] ["1" "2"]] (:rows (csv/parse "﻿a,b\n1,2\n"))) "Excel's mark"))
  (testing "quoted fields hold the separator, line breaks and doubled quotes"
    (is (= [["name" "text"] ["Anna" "Great, really.\nWould come again, \"soon\"."]]
           (:rows (csv/parse "name,text\nAnna,\"Great, really.\nWould come again, \"\"soon\"\".\"\n")))))
  (testing "empty fields and a ragged row stay as they are"
    (is (= [["a" "b" "c"] ["" "2" ""] ["only"]] (:rows (csv/parse "a,b,c\n,2,\nonly\n")))))
  (testing "the separator is detected: semicolon, tab, comma; one column is no error"
    (is (= ";" (:delimiter (csv/parse "a;b;c\n1;2;3\n"))))
    (is (= "\t" (:delimiter (csv/parse "a\tb\n1\t2\n"))))
    (is (= [["a" "b"] ["1" "2"]] (:rows (csv/parse "a;b\n1;2\n"))))
    (is (= {:rows [["single"] ["x"]] :errors []} (select-keys (csv/parse "single\nx\n") [:rows :errors]))))
  (testing "a quote left open: the rows that follow are swallowed into the field, and the error names the row"
    (let [{:keys [rows errors]} (csv/parse "a,b\n1,\"open\n3,4\n")]
      (is (= [["a" "b"] ["1" "open\n3,4\n"]] rows))
      (is (= [{:code "MissingQuotes" :row 1}] errors))))
  (testing "nothing in the file"
    (is (= {:rows [] :errors []} (select-keys (csv/parse "") [:rows :errors])))))

(deftest table-test
  (let [text (str "id,name\n" (apply str (map #(str % ",row" % "\n") (range 1 11))))]
    (testing "the header, the rows padded to it, the count"
      (is (= {:header ["id" "name"] :rows [["1" "row1"] ["2" "row2"]] :total 10 :cut? true :errors 0}
             (csv/table text {:limit 2})))
      (is (= 10 (count (:rows (csv/table text)))))
      (is (false? (:cut? (csv/table text)))))
    (testing "a ragged row is padded, a wide one cut to the header"
      (is (= [["1" ""] ["2" "x"]] (:rows (csv/table "a,b\n1\n2,x,y\n")))))
    (testing "the start of a longer file: the last row is cut through and dropped"
      (is (= {:header ["id" "name"] :rows [["1" "row1"]] :total 1 :cut? false :errors 0}
             (csv/table "id,name\n1,row1\n2,ro" {:incomplete? true}))))
    (testing "a row that did not read is counted"
      (is (= 1 (:errors (csv/table "a,b\n1,\"open\n3,4\n")))))
    (testing "nothing in the file"
      (is (= {:header [] :rows [] :total 0 :cut? false :errors 0} (csv/table ""))))))
