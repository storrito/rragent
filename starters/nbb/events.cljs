(ns events
  "The bot's log, one EDN map per line, the file and the format the
   `remember` and `recall` tools use (`rragent.events` of the harness):
   `:event` the kind, `:at` milliseconds, the rest the program's own
   keys. Appended, never changed, read oldest first; a program's
   events sit among the bot's own, so `recall` answers from both. A
   path in an event is relative to the folder of the log.

   The log is never held whole: `reducible` is what a program
   `reduce`s, `transduce`s or `into`s, one chunk of the file in
   memory at a time, and a `reduced` value stops the reading. It is
   seqable too, chunk by chunk, for `filter` and friends."
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn append!
  "The event appended to `file`, made with the first event."
  [file event data]
  (fs/appendFileSync file (str (pr-str (assoc (or data {}) :event (str event) :at (js/Date.now))) "\n"))
  nil)

(def chunk-size
  "How much of the file is in memory at a time."
  65536)

(defn- chunk-at
  "The bytes of `file` from byte `position` on, at most `chunk-size`
   of them; nil at the end. The file is opened for each chunk and
   closed again, so nothing holds it between two."
  [file position]
  (let [fd (fs/openSync file "r")]
    (try
      (let [buffer (js/Buffer.alloc chunk-size)
            n (fs/readSync fd buffer 0 chunk-size position)]
        (when (pos? n)
          (.subarray buffer 0 n)))
      (finally
        (fs/closeSync fd)))))

(defn- with-carry
  "The bytes of an unfinished line before `chunk`, then the chunk."
  [carry chunk]
  (if (pos? (.-length carry))
    (js/Buffer.concat #js [carry chunk])
    chunk))

(defn- rest-from
  "The bytes of `bytes` from `from` on, a copy of its own."
  [bytes from]
  (js/Buffer.from (.subarray bytes from)))

(defn- reduce-lines
  "`f` over the lines of `file`, oldest first, from `init`; a
   `reduced` value stops the reading. A line is cut at the newline
   byte before it is decoded, so a character that spans two chunks
   stays whole; the last line needs no newline."
  [file f init]
  (loop [position 0
         carry (js/Buffer.alloc 0)
         acc init]
    (if-let [chunk (chunk-at file position)]
      (let [bytes (with-carry carry chunk)
            [acc from] (loop [from 0
                              acc acc]
                         (let [at (.indexOf bytes 10 from)]
                           (if (or (neg? at) (reduced? acc))
                             [acc from]
                             (recur (inc at) (f acc (.toString bytes "utf8" from at))))))]
        (if (reduced? acc)
          @acc
          (recur (+ position (.-length chunk)) (rest-from bytes from) acc)))
      (let [acc (if (pos? (.-length carry))
                  (f acc (.toString carry "utf8"))
                  acc)]
        (if (reduced? acc) @acc acc)))))

(defn- lines-from
  "The lines of `file` from byte `position` on as a lazy seq, a chunk
   at a time; `carry` the bytes of an unfinished line before it."
  [file position carry]
  (lazy-seq
   (if-let [chunk (chunk-at file position)]
     (let [bytes (with-carry carry chunk)
           [lines from] (loop [from 0
                               lines []]
                          (let [at (.indexOf bytes 10 from)]
                            (if (neg? at)
                              [lines from]
                              (recur (inc at) (conj lines (.toString bytes "utf8" from at))))))]
       (concat lines (lines-from file (+ position (.-length chunk)) (rest-from bytes from))))
     (when (pos? (.-length carry))
       (list (.toString carry "utf8"))))))

(defn- parse
  "The event of a line, or nil for a blank line or one that does not
   read as a map."
  [text]
  (let [text (str/trim text)]
    (when-not (str/blank? text)
      (let [value (try (edn/read-string text) (catch :default _ nil))]
        (when (map? value) value)))))

(defn- parsing
  "`f` over the events of the lines, the lines that are none skipped."
  [f]
  (fn [acc text]
    (if-some [event (parse text)]
      (f acc event)
      acc)))

(defn reducible
  "The events of `file`, oldest first: something to `reduce`,
   `transduce` or `into` with one chunk of the file in memory at a
   time (a `reduced` value stops the reading), and to `seq`, lazily.
   No file is no events."
  [file]
  (reify
    IReduce
    (-reduce [this f]
      (if-let [events (seq this)]
        (reduce f (first events) (rest events))
        (f)))
    (-reduce [_ f init]
      (if (fs/existsSync file)
        (reduce-lines file (parsing f) init)
        init))
    ISeqable
    (-seq [_]
      (when (fs/existsSync file)
        (seq (keep parse (lines-from file 0 (js/Buffer.alloc 0))))))))

(defn read
  "Every event of `file` in one vector, for the few places that want
   them all; `reducible` otherwise."
  [file]
  (into [] (reducible file)))

(defn kind
  "A transducer: the events of `event` alone."
  [event]
  (filter #(= event (:event %))))
