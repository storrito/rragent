(ns rragent.csv
  "A CSV file as the rows of a table for the pane (`rragent.view`):
   RFC 4180 as people write it. A quoted field may hold the separator,
   line breaks and `\"\"` for a quote; the lines end in LF or CRLF; the
   separator is the one of comma, semicolon and tab that the header
   line has most of, a comma when none. The rows are capped for the
   frame, which carries the table."
  (:require [clojure.string :as str]))

(def max-rows
  "How many rows of a file the pane shows."
  500)

(def max-columns
  "How many columns of a row the pane shows."
  50)

(defn separator
  "The separator of `text`, from its first line: comma, semicolon or
   tab, whichever it has most of outside quotes; a comma when none."
  [text]
  (let [line (first (str/split-lines (str text)))
        outside (str/replace (str line) #"\"[^\"]*\"" "")
        counts (map (fn [c] [c (count (filter #{c} outside))]) [\tab \; \,])
        [c n] (apply max-key second counts)]
    (if (pos? n) c \,)))

(defn- clean
  "A row without a trailing carriage return in its last field."
  [row]
  (let [last-field (peek row)]
    (if (and last-field (str/ends-with? last-field "\r"))
      (conj (pop row) (subs last-field 0 (dec (count last-field))))
      row)))

(defn parse
  "The rows of `text`, vectors of strings, every row; a trailing line
   break makes no empty row."
  [text & [sep]]
  (let [sep (or sep (separator text))
        text (str text)
        n (count text)]
    (loop [i 0
           field (js/Array.)
           row []
           rows (transient [])
           quoted? false]
      (if (>= i n)
        (let [row (clean (conj row (.join field "")))]
          (persistent! (if (= [""] row) rows (conj! rows row))))
        (let [c (.charAt text i)]
          (cond
            quoted?
            (cond
              (and (= c "\"") (< (inc i) n) (= "\"" (.charAt text (inc i))))
              (do (.push field "\"") (recur (+ i 2) field row rows true))

              (= c "\"")
              (recur (inc i) field row rows false)

              :else
              (do (.push field c) (recur (inc i) field row rows true)))

            (= c "\"")
            (recur (inc i) field row rows true)

            (= c (str sep))
            (recur (inc i) (js/Array.) (conj row (.join field "")) rows false)

            (= c "\n")
            (recur (inc i) (js/Array.) [] (conj! rows (clean (conj row (.join field "")))) false)

            :else
            (do (.push field c) (recur (inc i) field row rows false))))))))

(defn table
  "`text` as `{:header :rows :total :cut?}`: the first row as the
   header, the next `limit` rows (`max-rows` without one) as the rows,
   each padded or cut to the header's width and to `max-columns`,
   `total` how many rows the text holds, `cut?` whether rows were left
   out. With `:incomplete?`, the text being the start of a longer file
   (`rragent.artifacts/text-of`), the last row is dropped as cut
   through."
  [text & [{:keys [limit incomplete?]}]]
  (let [rows (parse text)
        rows (if (and incomplete? (seq rows)) (pop rows) rows)
        header (vec (take max-columns (map str (first rows))))
        width (count header)
        fit (fn [row] (vec (take width (concat row (repeat "")))))
        data (rest rows)
        limit (or limit max-rows)]
    {:header header
     :rows (mapv fit (take limit data))
     :total (count data)
     :cut? (< limit (count data))}))
