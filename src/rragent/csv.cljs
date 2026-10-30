(ns rragent.csv
  "A CSV file as the rows of a table for the pane (`rragent.view`),
   read by PapaParse (`papaparse`): a byte order mark dropped, quoted
   fields with the separator, line breaks and `\"\"` in them, LF or
   CRLF, the separator detected (comma, semicolon, tab, pipe), a row
   whose quote was left open noted as an error. The rows are capped
   for the frame, which carries the table."
  (:require ["papaparse" :default Papa]))

(def max-rows
  "How many rows of a file the pane shows."
  500)

(def max-columns
  "How many columns of a row the pane shows."
  50)

(defn parse
  "`text` read: `{:rows :delimiter :errors}`, the rows as vectors of
   strings, every row (an empty line is none), the delimiter PapaParse
   found, and the errors of rows that did not read as CSV, `{:code
   :row}` each (a quote left open); that the delimiter could not be
   detected (one column, or nothing) is no error."
  [text]
  (let [result (.parse Papa (str text) #js {:delimiter "" :newline "" :skipEmptyLines true})]
    {:rows (mapv (fn [row] (mapv str (array-seq row))) (array-seq (.-data result)))
     :delimiter (.. result -meta -delimiter)
     :errors (->> (array-seq (.-errors result))
                  (remove #(= "UndetectableDelimiter" (.-code %)))
                  (mapv (fn [e] {:code (.-code e) :row (.-row e)})))}))

(defn table
  "`text` as `{:header :rows :total :cut? :errors}`: the first row as
   the header, the next `limit` rows (`max-rows` without one) as the
   rows, each padded or cut to the header's width and to
   `max-columns`, `total` how many rows the text holds, `cut?` whether
   rows were left out, `errors` how many rows did not read as CSV.
   With `:incomplete?`, the text being the start of a longer file
   (`rragent.artifacts/text-of`), the last row is dropped as cut
   through."
  [text & [{:keys [limit incomplete?]}]]
  (let [{:keys [rows errors]} (parse text)
        rows (if (and incomplete? (seq rows)) (pop rows) rows)
        header (vec (take max-columns (first rows)))
        width (count header)
        fit (fn [row] (vec (take width (concat row (repeat "")))))
        data (rest rows)
        limit (or limit max-rows)]
    {:header header
     :rows (mapv fit (take limit data))
     :total (count data)
     :cut? (< limit (count data))
     :errors (count errors)}))
