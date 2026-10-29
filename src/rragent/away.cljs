(ns rragent.away
  "A bot's conversation compacted while the person is away. After a
   pause the provider's cache of the conversation is gone (OpenAI and
   Anthropic keep it some five to ten minutes), so the next message
   reads all of it again at full price and speed: a bot at 442,000
   tokens paid $2.21 to answer \"What is 1 + 1?\" the next morning. A
   conversation whose last answer read at least `min-tokens` and that
   has been quiet for `idle-ms` is compacted then (`tick!`), so the
   person comes back to a small one; one that is smaller is cheaper to
   read again than to summarize. Until that compaction is done the
   machine counts as busy (`pending?`), so it does not go to sleep
   before and summarize when the person is back. The answers are kept
   in the process (`note!`, from `rragent.harness/context-budget`); a
   machine that starts again knows none until the next.")

(def idle-ms
  "How long a conversation is quiet before it is compacted: when the
   providers' caches are surely gone, and the machine's sleep
   (`rragent.sleep`, ten minutes) waits for it."
  (* 10 60000))

(def min-tokens
  "The context from which a quiet conversation is compacted: below it
   reading it again once costs less than a summary."
  50000)

(defonce answers
  ;; the last answer of every conversation, by its id: {:at :tokens}
  (atom {}))

(defn note!
  "The answer of the conversation `id` at `now` that read `tokens` of
   context: the conversation's last."
  [id tokens now]
  (swap! answers assoc id {:at now :tokens tokens}))

(defn forget-all!
  "No answer known: the harness closed."
  []
  (reset! answers {}))

(defn due
  "The ids of `answers` whose conversations are due for a compaction at
   `now`: their last answer read at least `min-tokens` and is
   `idle-ms` old."
  [answers now {:keys [idle-ms min-tokens]}]
  (for [[id {:keys [at tokens]}] answers
        :when (and (<= min-tokens tokens) (<= idle-ms (- now at)))]
    id))

(defn pending?
  "Whether a conversation of `answers` will be compacted once it has
   been quiet long enough: one whose last answer read at least
   `min-tokens`, of those `known?` says are a bot's (a subagent's or a
   run's never is)."
  ([known?] (pending? @answers min-tokens known?))
  ([answers min-tokens known?]
   (boolean (some (fn [[id {:keys [tokens]}]] (and (<= min-tokens tokens) (known? id))) answers))))

(defn ^:async tick!
  "The due conversations (`due`) compacted: a conversation that is no
   bot's (a subagent's, a run's) forgotten, one with a run going left
   for the next tick, the others compacted with `compact!` and
   forgotten. `known?` says whether `id` is a bot's, `idle?` whether
   it has no run going; `now`, `idle-ms` and `min-tokens` are the
   defaults' unless given."
  [{:keys [known? idle? compact! now] :as options}]
  (let [options (merge {:idle-ms idle-ms :min-tokens min-tokens} options)
        now (or now (js/Date.now))]
    (doseq [id (due @answers now options)]
      (cond
        (not (known? id)) (swap! answers dissoc id)
        (idle? id) (do (swap! answers dissoc id)
                       (try
                         (await (compact! id))
                         (catch :default error
                           (js/console.error "[away]" (str "The compaction of conversation " id " failed:") error))))))))

(defn watch!
  "`tick!` with `options` every half minute; answers a function that
   ends the watch."
  [options]
  (let [timer (js/setInterval (fn [] (tick! options)) 30000)]
    (.unref timer)
    (fn [] (js/clearInterval timer))))
