(ns rragent.costs
  "What the model answers cost, as OpenRouter charges them. OpenRouter
   puts the charge into every answer, `usage.cost` in dollars, on the
   chunk of the stream that carries the usage; pi-ai reads the token
   counts from that chunk and prices them itself, from its catalog,
   which is what the page showed, and what ran well above the bill.
   So the charge is caught on its way: the fetch wrapper
   (`rragent.model-requests`) splits the stream of every chat
   completion (`tee`), one branch read here for the chunk with the
   charge, remembered by the generation's id (`charges`), the other
   handed on as the response. pi-ai keeps the id on the message as
   `responseId`, and pi-durable's `afterResponse` hook sees the
   message before its usage is added to the conversation's ledger
   (`pi.usage`): the hook (`extension`) puts the charge into the
   message's usage in place of the estimate (`adopt!`). An answer
   whose charge was not seen (another provider on a laptop, a stream
   without the chunk) keeps pi-ai's estimate. A tool that pays
   OpenRouter itself (a picture, a voice, a piece of music,
   `rragent.tools.media`) answers its charge as the usage of its
   result (`tool-usage`), which the ledger adds to its tools.

   The page shows the charge times `factor`, `RRAGENT_COST_FACTOR`,
   what a dollar at OpenRouter costs the person (Storrito's markup,
   set at the provisioning), 1 without one."
  (:require ["@earendil-works/pi-durable" :refer [GenerationTask defineExtension hook]]
            [clojure.string :as str]))

(defn factor
  "What a dollar at OpenRouter costs the person: `RRAGENT_COST_FACTOR`,
   1 without one or with one that is no positive number."
  []
  (let [n (js/parseFloat (str (aget (.-env js/process) "RRAGENT_COST_FACTOR")))]
    (if (and (not (js/isNaN n)) (pos? n)) n 1)))

(defn priced
  "`usd` at OpenRouter as the person pays it; nil for no number."
  [usd]
  (when (number? usd)
    (* usd (factor))))

(defonce charges
  ;; {generation id {:cost :at}}
  (atom {}))

(def kept-ms
  "How long a charge waits for its message: an answer's hook runs
   within seconds; ten minutes covers a long tool round."
  600000)

(defn remember!
  "The charge `cost` of the generation `id` kept, the ones older than
   `kept-ms` forgotten."
  [id cost now]
  (swap! charges (fn [m]
                   (-> (into {} (remove (fn [[_ v]] (< (+ (:at v) kept-ms) now))) m)
                       (assoc id {:cost cost :at now})))))

(defn take!
  "The charge of the generation `id`, forgotten with the taking; nil
   for none."
  [id]
  (when (seq (str id))
    (when-let [found (get @charges (str id))]
      (swap! charges dissoc (str id))
      (:cost found))))

(defn charge-of-chunk
  "The charge in one line of a stream, `{:id :cost}`: a `data:` line
   whose JSON carries an `id` and a numeric `usage.cost`; nil for any
   other line, `[DONE]` and comments among them."
  [line]
  (let [line (str/trim (str line))]
    (when (and (str/starts-with? line "data:") (not= "data: [DONE]" line))
      (try
        (let [chunk (js/JSON.parse (str/trim (subs line 5)))
              cost (some-> chunk .-usage .-cost)
              id (.-id chunk)]
          (when (and (number? cost) (string? id) (seq id))
            {:id id :cost cost}))
        (catch :default _
          nil)))))

(defn scan!
  "Every complete line of `buffer` scanned for a charge
   (`charge-of-chunk`), which is remembered; answers the rest, the
   last line when it is not complete yet."
  [buffer now]
  (let [lines (str/split buffer #"\n" -1)]
    (doseq [line (butlast lines)]
      (when-let [{:keys [id cost]} (charge-of-chunk line)]
        (remember! id cost now)))
    (last lines)))

(defn- ^:async drain!
  "`stream` read to its end, its lines scanned (`scan!`); nothing
   thrown, a stream that fails was the caller's to fail too."
  [^js stream]
  (let [reader (.getReader stream)
        decoder (js/TextDecoder.)]
    (try
      (loop [buffer ""]
        (let [result (await (.read reader))]
          (if (.-done result)
            (scan! (str buffer "\n") (js/Date.now))
            (recur (scan! (str buffer (.decode decoder (.-value result) #js {:stream true})) (js/Date.now))))))
      (catch :default _
        nil))))

(defn tee
  "`response` with its body split in two: one branch read here for
   the charge (`drain!`), the other in a response like it, which the
   caller reads as it would have. A response without a body as it
   is."
  [^js response]
  (if-let [body (.-body response)]
    (let [branches (.tee body)]
      (drain! (aget branches 1))
      (js/Response. (aget branches 0) #js {:status (.-status response)
                                           :statusText (.-statusText response)
                                           :headers (.-headers response)}))
    response))

(defn adopt!
  "The charge of `message` (pi-ai's assistant message, `responseId`
   the generation's id) into its usage in place of pi-ai's estimate:
   the parts scaled to the charge, the total the charge; without an
   estimate the whole charge stands as input. Answers `{:estimate
   :charged}`, nil when no charge was seen for it, the usage then as
   it was."
  [^js message]
  (when-let [usage (some-> message .-usage)]
    (when-let [charged (take! (.-responseId message))]
      (let [cost (or (.-cost usage) (let [c #js {}] (aset usage "cost" c) c))
            estimate (or (.-total cost) 0)
            scale (when (pos? estimate) (/ charged estimate))]
        (doseq [part ["input" "output" "cacheRead" "cacheWrite"]]
          (aset cost part (if scale (* scale (or (aget cost part) 0)) 0)))
        (when-not scale
          (aset cost "input" charged))
        (aset cost "total" charged)
        {:estimate estimate :charged charged}))))

(defn tool-usage
  "The usage of a tool's result that paid `usd` at OpenRouter itself:
   no tokens, the charge as its cost."
  [usd]
  #js {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :totalTokens 0
       :cost #js {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total usd}})

(defn extension
  "The extension that puts OpenRouter's charge into every answer's
   usage before the ledger takes it (`adopt!`), with a line on stdout
   that says what the catalog had estimated, for the record."
  []
  (defineExtension #js {:name "rragent-costs"
                        :hooks #js [(hook GenerationTask #js {:afterResponse (fn [^js message _api _context]
                                                                                (when-let [{:keys [estimate charged]} (adopt! message)]
                                                                                  (js/console.log (str "[costs] " (.-responseId message) ": OpenRouter charged $" (.toFixed charged 4)
                                                                                                       ", the catalog had estimated $" (.toFixed estimate 4))))
                                                                                js/undefined)})]}))
