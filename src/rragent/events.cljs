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
   (`project`). `reducible` is what a projection reduces, transduces
   or seqs: the file's text read once, each event made as the
   projection consumes it, a `reduced` value stopping the rest, so the
   events are never all in memory at once; `read` is the vector for
   the few places that want them all. Compaction
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

(defn reducible
  "The events of `file`, oldest first, as an eduction: something to
   `reduce`, `transduce`, `into` or `seq`. The file's text is read
   once; each event is made as it is consumed, and a `reduced` value
   stops the rest. No file is no events."
  [file]
  (eduction (keep #(event-of file %))
            (if (fs/existsSync file)
              (str/split-lines (fs/readFileSync file "utf8"))
              [])))

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
