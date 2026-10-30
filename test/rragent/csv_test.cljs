(ns rragent.csv-test
  "A CSV file read for the pane: quotes, separators, line ends, the
   cap of the rows."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.csv :as csv]))

(deftest parse-test
  (testing "plain rows, a trailing line break making no row"
    (is (= [["a" "b"] ["1" "2"]] (csv/parse "a,b\n1,2\n")))
    (is (= [["a" "b"] ["1" "2"]] (csv/parse "a,b\r\n1,2\r\n")) "CRLF"))
  (testing "quoted fields hold the separator, line breaks and doubled quotes"
    (is (= [["name" "text"] ["Anna" "Great, really.\nWould come again, \"soon\"."]]
           (csv/parse "name,text\nAnna,\"Great, really.\nWould come again, \"\"soon\"\".\"\n"))))
  (testing "empty fields and a ragged row stay as they are"
    (is (= [["a" "b" "c"] ["" "2" ""] ["only"]] (csv/parse "a,b,c\n,2,\nonly\n"))))
  (testing "the separator is sniffed from the header: semicolon, tab, comma by default"
    (is (= \; (csv/separator "a;b;c\n1;2;3")))
    (is (= \tab (csv/separator "a\tb\n1\t2")))
    (is (= \, (csv/separator "a,b;c\n")) "more commas than semicolons")
    (is (= \, (csv/separator "single\n")))
    (is (= \, (csv/separator "\"x;y\",b\n")) "a semicolon inside quotes does not count")
    (is (= [["a" "b"] ["1" "2"]] (csv/parse "a;b\n1;2\n")))))

(deftest table-test
  (let [text (str "id,name\n" (apply str (map #(str % ",row" % "\n") (range 1 11))))]
    (testing "the header, the rows padded to it, the count"
      (is (= {:header ["id" "name"] :rows [["1" "row1"] ["2" "row2"]] :total 10 :cut? true}
             (csv/table text {:limit 2})))
      (is (= 10 (count (:rows (csv/table text)))))
      (is (false? (:cut? (csv/table text)))))
    (testing "a ragged row is padded, a wide one cut to the header"
      (is (= [["1" ""] ["2" "x"]] (:rows (csv/table "a,b\n1\n2,x,y\n")))))
    (testing "the start of a longer file: the last row is cut through and dropped"
      (is (= {:header ["id" "name"] :rows [["1" "row1"]] :total 1 :cut? false}
             (csv/table "id,name\n1,row1\n2,ro" {:incomplete? true}))))
    (testing "nothing in the file"
      (is (= {:header [] :rows [] :total 0 :cut? false} (csv/table ""))))))
