(ns events
  "The bot's log, one EDN map per line, the file and the format the
   `remember` and `recall` tools use (`rragent.events` of the harness):
   `:event` the kind, `:at` milliseconds, the rest the program's own
   keys. Appended, never changed, read oldest first; a program's
   events sit among the bot's own, so `recall` answers from both. A
   path in an event is relative to the folder of the log.

   `reducible` is what a program `reduce`s, `transduce`s or `into`s:
   the events are made one at a time as they are consumed, never all
   at once, and a `reduced` value stops the rest. It is seqable too,
   for `filter` and friends."
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn append!
  "The event appended to `file`, made with the first event."
  [file event data]
  (fs/appendFileSync file (str (pr-str (assoc (or data {}) :event (str event) :at (js/Date.now))) "\n"))
  nil)

(defn- parse
  "The event of a line, or nil for a blank line or one that does not
   read as a map."
  [text]
  (let [text (str/trim text)]
    (when-not (str/blank? text)
      (let [value (try (edn/read-string text) (catch :default _ nil))]
        (when (map? value) value)))))

(defn reducible
  "The events of `file`, oldest first, as an eduction: something to
   `reduce`, `transduce`, `into` or `seq`. The file's text is read
   once; each event is made as it is consumed, and a `reduced` value
   stops the rest. No file is no events."
  [file]
  (eduction (keep parse)
            (if (fs/existsSync file)
              (str/split-lines (fs/readFileSync file "utf8"))
              [])))

(defn read
  "Every event of `file` in one vector, for the few places that want
   them all; `reducible` otherwise."
  [file]
  (into [] (reducible file)))

(defn kind
  "A transducer: the events of `event` alone."
  [event]
  (filter #(= event (:event %))))
