(ns rragent.events
  "The bot's memory beyond its transcript, as events: one EDN map per
   line in `events.edn` of the bot's folder (`rragent.home`), appended
   with `append!` and never changed, each with an `:event` name, a
   time `:at` and whatever the bot noted. The file is the bot's, not a
   conversation's, so the runs of the bot (`rragent.runs`),
   conversations of their own, read and append the same events: a run
   that posted a review notes it, the next run sees it. There is no
   state to keep in step: a read model is a projection the bot writes
   as Clojure over the events, oldest first, evaluated here by SCI
   (`project`). The file is never held whole: `reducible` is what a
   projection reduces, transduces or seqs, one chunk of the file in
   memory at a time, and a `reduced` value stops the reading; `read`
   is the vector for the few places that want it all. Compaction
   summarizes what the model sees and leaves the file alone, so an
   event outlives every summary. The programs a bot writes append and
   read the same file in the same format (`starters/nbb/events.cljs`
   mirrors `line`, `reducible` and `read`): an import they kept, a
   posting they made, which `recall` then answers from.
   Before the file, the events were entries of the kind `rragent.fact`
   in the bot's conversation; `migrate!` writes those into the file
   once."
  (:require ["node:fs" :as fs]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [sci.core :as sci]))

(def legacy-kind
  "The kind of the entries that held the events before the file."
  "rragent.fact")

(def max-chars
  10000)

(defn line
  "One event as its line: `data`, a map of the bot's own keys, with
   the `event` name and the time."
  [event data now]
  (pr-str (assoc (or data {}) :event (str event) :at now)))

(defn append!
  "The event appended to `file`, made with the first event."
  [file event data now]
  (fs/appendFileSync file (str (line event data now) "\n"))
  nil)

(defn- parse
  [text]
  (try
    (reader/read-string text)
    (catch :default _
      nil)))

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

(defn- event-of
  "The event of a line of `file`, or nil for a blank line or one that
   does not read as a map, said on stderr."
  [file text]
  (let [text (str/trim text)]
    (when-not (str/blank? text)
      (let [value (parse text)]
        (if (map? value)
          value
          (js/console.error "[events]" (str "a line of " file " does not read: " (subs text 0 (min 80 (count text))))))))))

(defn- parsing
  "`f` over the events of the lines of `file`, the lines that are
   none skipped."
  [file f]
  (fn [acc text]
    (if-some [event (event-of file text)]
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
        (reduce-lines file (parsing file f) init)
        init))
    ISeqable
    (-seq [_]
      (when (fs/existsSync file)
        (seq (keep #(event-of file %) (lines-from file 0 (js/Buffer.alloc 0))))))))

(defn read
  "Every event of `file` in one vector, oldest first, for the few
   places that want them all; `reducible` otherwise."
  [file]
  (into [] (reducible file)))

(defn- since-ms
  [since]
  (when (seq (str since))
    (let [ms (.getTime (js/Date. since))]
      (when (js/isNaN ms)
        (throw (js/Error. (str "since is not a date: " since))))
      ms)))

(defn selection
  "A transducer: the events of `event` (any without) from `since` (a
   date or milliseconds; all without)."
  [{:keys [event since]}]
  (let [from (since-ms since)]
    (apply comp
           (cond-> []
             (seq (str event)) (conj (filter #(= event (:event %))))
             from (conj (filter #(<= from (:at %))))))))

(defn select
  "The events of `event` from `since` (`selection`) as a vector,
   oldest first."
  [events options]
  (into [] (selection options) events))

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn project
  "`code`, Clojure with `events` bound to the events (a vector, or
   anything to reduce and seq, such as an eduction over `reducible`),
   evaluated in a fresh context; the value printed. Without code the
   events themselves."
  [events code]
  (cut (if (str/blank? (str code))
         (pr-str (vec events))
         (pr-str (sci/eval-string code {:classes {'js js/globalThis :allow :all}
                                        :features #{:cljs}
                                        :namespaces {'user {'events events}}})))))

(defn- legacy
  "The events among a page of entries: the data of the `rragent.fact`
   ones."
  [items]
  (keep (fn [entry]
          (when (= legacy-kind (.-kind entry))
            (js->clj (.-data entry) :keywordize-keys true)))
        items))

(defn ^:async migrate!
  "The `rragent.fact` entries of the bot's `conversation` written into
   its events `file`, oldest first, when the file is not there yet.
   Answers how many; nil when the file was there, or there were none."
  [conversation file context]
  (when-not (fs/existsSync file)
    (let [found (loop [cursor js/undefined
                       found []]
                  (let [page (await (.entries conversation #js {} 200 cursor context))
                        items (array-seq (.-items page))
                        found (into found (legacy items))]
                    (if (and (.-next page) (seq items))
                      (recur (.-next page) found)
                      found)))]
      (when (seq found)
        ;; the pages come newest first
        (fs/writeFileSync file (str (str/join "\n" (map pr-str (reverse found))) "\n"))
        (count found)))))
