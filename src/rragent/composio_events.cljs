(ns rragent.composio-events
  "The events of the agent's Composio triggers, posted by Composio to
   the page at `/composio/event` (`rragent.composio-setup` subscribes
   it), outside the token. Each delivery is signed the Standard
   Webhooks way, `webhook-id`, `webhook-timestamp` and
   `webhook-signature` (`v1,<base64>`), an HMAC-SHA256 over
   `id.timestamp.body` with the subscription's secret, which the setup
   keeps in the machine's storage; one that does not verify, or is
   older than five minutes, is a 401. Composio delivers an event
   several times, so the ids taken are kept and a repeat is answered
   with 200 and nothing else. An event goes to the bot that owns its
   trigger, the one whose newest fact about the trigger is a
   `composio-trigger` (`rragent.codemode/add-trigger` remembers it),
   else to the bot the page opens by default, as an input of its
   conversation, the way a hook's call is submitted (`rragent.hooks`)."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [rragent.composio :as composio]
            [rragent.composio-setup :as setup]
            [rragent.facts :as facts]
            [rragent.hooks :as hooks]))

(def tolerance-ms
  "How far from now a delivery's timestamp may be."
  300000)

(def seen-max
  "How many event ids are kept."
  500)

(def max-body-chars
  20000)

(def entry-kind
  "rragent.composio-event")

(defn signed-content
  [id timestamp body]
  (str id "." timestamp "." body))

(defn signature
  "The signature of a delivery, as Composio computes it: HMAC-SHA256
   with the secret as it is, base64."
  [secret id timestamp body]
  (-> (crypto/createHmac "sha256" (str secret))
      (.update (signed-content id timestamp body) "utf8")
      (.digest "base64")))

(defn signatures-of
  "The signatures a header carries: space-separated, each
   `v1,<signature>`; one without a version as it is."
  [header]
  (->> (str/split (str header) #"\s+")
       (remove str/blank?)
       (mapv (fn [token]
               (let [[_ signature] (str/split token #"," 2)]
                 (or signature token))))))

(defn- timing-safe=
  [a b]
  (let [a (js/Buffer.from (str a) "utf8")
        b (js/Buffer.from (str b) "utf8")]
    (and (= (.-length a) (.-length b))
         (crypto/timingSafeEqual a b))))

(defn fresh?
  "Whether `timestamp`, seconds or milliseconds since the epoch or an
   ISO date, is within `tolerance-ms` of `now-ms`."
  [timestamp now-ms]
  (let [n (js/Number (str timestamp))
        ms (cond
             (js/isNaN n) (.getTime (js/Date. (str timestamp)))
             (< n 1e11) (* n 1000)
             :else n)]
    (and (not (js/isNaN ms))
         (<= (js/Math.abs (- now-ms ms)) tolerance-ms))))

(defn verified?
  "Whether the delivery is Composio's: the three headers there, the
   timestamp fresh, one of the signatures the one of `body` with
   `secret`."
  [secret headers body now-ms]
  (let [id (get headers "webhook-id")
        timestamp (get headers "webhook-timestamp")
        header (get headers "webhook-signature")]
    (boolean (and (seq (str secret)) (seq (str id)) (seq (str timestamp)) (seq (str header))
                  (fresh? timestamp now-ms)
                  (let [expected (signature secret id timestamp body)]
                    (some #(timing-safe= expected %) (signatures-of header)))))))

(defn parse
  "The event as data; nil for a body that is no JSON."
  [body]
  (try
    (js->clj (js/JSON.parse body) :keywordize-keys true)
    (catch :default _
      nil)))

(defn event-id
  "What a delivery is told again by: the event's own id, else the
   delivery's."
  [headers event]
  (or (not-empty (str (:id event)))
      (get headers "webhook-id")))

(defn trigger-id
  [event]
  (not-empty (str (get-in event [:metadata :trigger_id]))))

(defn owner
  "The bot that owns the trigger: of `facts-by-bot` (`{bot-id
   [facts]}`, each bot's facts oldest first) the one whose newest fact
   about the trigger id is a `composio-trigger`; nil when none has
   one, or the newest says it was deleted."
  [facts-by-bot trigger-id]
  (some (fn [[bot facts]]
          (let [about (last (filter #(= trigger-id (:id %)) facts))]
            (when (and about (= composio/trigger-fact (:event about)))
              bot)))
        facts-by-bot))

(defn- cut
  [body]
  (let [body (str body)]
    (if (< max-body-chars (count body))
      (str (subs body 0 max-body-chars) "\n(cut at " max-body-chars " characters)")
      body)))

(defn message
  "What the bot reads: the event's line when it is a trigger's
   (`rragent.hooks/event-line`), its type, its body."
  [event body]
  (str "Composio event"
       (when-let [line (hooks/event-line body)] (str ": " line))
       "\nType: " (:type event)
       (when-let [slug (get-in event [:metadata :trigger_slug])] (str "\nTrigger: " slug " (" (trigger-id event) ")"))
       "\nBody:\n" (cut body)))

(defn- ^:async facts-of
  "Every bot's facts, `{bot-id [facts]}`."
  [bots context]
  (loop [[[id {:keys [conversation]}] & more] (seq bots)
         found {}]
    (if (nil? id)
      found
      (recur more (if conversation
                    (assoc found id (await (facts/facts (facts/from-root conversation context))))
                    found)))))

(defn- ^:async take!
  "The event id noted among the seen, `seen-max` of them kept; true
   when it was new."
  [harness context id]
  (let [new? (atom true)]
    (await (setup/write! harness context
                         (fn [doc]
                           (let [seen (array-seq (or (aget doc "seen") #js []))]
                             (if (some #{id} seen)
                               (reset! new? false)
                               (aset doc "seen" (clj->js (vec (take-last seen-max (conj (vec seen) id))))))))))
    @new?))

(defn ^:async receive!
  "One delivery: `{:status :body}` for the response. 401 for one that
   does not verify, 200 for a repeat (`:duplicate`), 200 with the bot
   it went to otherwise. `bots` are the open bots by id
   (`rragent.machine/bots`), `default-id` answers the page's default
   bot, `now` the clock."
  [{:keys [harness context bots default-id now]} headers body]
  (let [stored (await (setup/snapshot harness context))
        secret (get-in stored [:subscription :secret])]
    (if-not (verified? secret headers body (now))
      {:status 401 :body {:ok false :error "the signature does not verify"}}
      (let [event (parse body)
            id (event-id headers event)]
        (if-not (await (take! harness context id))
          {:status 200 :body {:ok true :duplicate true}}
          (let [trigger (trigger-id event)
                bot (or (when trigger (owner (await (facts-of bots context)) trigger))
                        (default-id))
                conversation (:conversation (get bots bot))]
            (when conversation
              (await (.submit conversation
                              #js {:type "input"
                                   :content (message event body)
                                   :whenBusy "followUp"
                                   :entry #js {:kind entry-kind
                                               :data #js {:id id :trigger trigger :type (:type event)}}}
                              context)))
            {:status 200 :body (cond-> {:ok true} bot (assoc :bot bot))}))))))
