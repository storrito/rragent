(ns events
  "The bot's log, one EDN map per line, the file and the format the
   `remember` and `recall` tools use (`rragent.events` of the harness):
   `:event` the kind, `:at` milliseconds, the rest the program's own
   keys. Appended, never changed, read oldest first; a program's
   events sit among the bot's own, so `recall` answers from both. A
   path in an event is relative to the folder of the log."
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn append!
  "The event appended to `file`, made with the first event."
  [file event data]
  (fs/appendFileSync file (str (pr-str (assoc (or data {}) :event (str event) :at (js/Date.now))) "\n"))
  nil)

(defn read
  "Every event of `file`, oldest first; no file is no events. A line
   that does not read as a map is skipped."
  [file]
  (if (fs/existsSync file)
    (->> (str/split-lines (fs/readFileSync file "utf8"))
         (remove str/blank?)
         (keep (fn [text]
                 (let [value (try (edn/read-string text) (catch :default _ nil))]
                   (when (map? value) value))))
         (vec))
    []))

(defn of-kind
  "The events of `event`, oldest first."
  [events event]
  (filterv #(= event (:event %)) events))
