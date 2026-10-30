(ns events-test
  "The log read a chunk at a time: every event, an early stop, the
   seq, the lines that are none; run by main_test.cljs."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is testing]]
            [events]))

(defn- temp-log
  []
  (path/join (fs/mkdtempSync (path/join (os/tmpdir) "events-")) "events.edn"))

(deftest reducible-test
  (let [file (temp-log)
        n 3000]
    ;; a few chunks' worth, with characters of more than one byte on
    ;; every line, so that some chunk ends inside one of them
    (dotimes [i n]
      (events/append! file (if (odd? i) "odd" "even") {:n i :text "grüß ✓ naïve"}))
    (is (< events/chunk-size (.-size (fs/statSync file))) "the log spans chunks")
    (testing "every event, oldest first, whole"
      (let [all (into [] (events/reducible file))]
        (is (= n (count all)))
        (is (= (range n) (map :n all)))
        (is (every? #(= "grüß ✓ naïve" (:text %)) all))
        (is (every? number? (map :at all)))))
    (testing "transduce by kind, a reduced value stops the reading"
      (is (= 1500 (transduce (events/kind "odd") (completing (fn [acc _] (inc acc))) 0 (events/reducible file))))
      (is (= 7 (reduce (fn [acc {:keys [n]}] (if (= 7 n) (reduced n) acc)) nil (events/reducible file))))
      (is (= [0 1 2] (into [] (comp (take 3) (map :n)) (events/reducible file)))))
    (testing "the seq, for filter and friends"
      (is (= 5 (:n (first (filter #(= 5 (:n %)) (events/reducible file))))))
      (is (= n (count (seq (events/reducible file))))))
    (testing "read is all of it in a vector"
      (is (= n (count (events/read file)))))))

(deftest lines-test
  (let [file (temp-log)]
    (fs/writeFileSync file "{:event \"a\" :at 1}\r\n\n{:event \"b\" :at 2}\nnot an event\n[:a :vector]\n{:event \"c\" :at 3}")
    (testing "a Windows line end, a blank line, a line that is no map, and no newline at the end"
      (is (= ["a" "b" "c"] (map :event (events/reducible file))))
      (is (= ["a" "b" "c"] (into [] (map :event) (events/reducible file)))))))

(deftest no-file-test
  (let [file (path/join (os/tmpdir) "events-none" "events.edn")]
    (is (= [] (into [] (events/reducible file))))
    (is (nil? (seq (events/reducible file))))
    (is (= 0 (reduce + 0 (map :n (events/reducible file)))))))
