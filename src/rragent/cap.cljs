(ns rragent.cap
  "The cap on what the machine spends a month (docs/one-loop.md §8):
   a session document `rragent.cap` with `monthly`, the dollars the
   owner allows (`set-monthly!`; no control of the page sets it since
   the settings lost their Budget section), and `noticed`, the month the loop
   was warned in; and a ledger of the month's charges in
   `<home>/costs/<year-month>.edn`, one line per charge, which the
   extension appends from every answer's usage (after
   `rragent.costs` put OpenRouter's charge there) and from every tool
   result that paid at OpenRouter (the media tools), the loop's and
   the tasks' alike. What is spent is the ledger's sum as the person
   pays it (`rragent.costs/priced`). At `warn-share` of the cap the
   loop gets one notice a month (`rragent.cap-notice`) and warns the
   person; at the cap the page refuses a message, the `task` tool
   refuses, a schedule's fire, a hook's call and a Composio event are
   dropped with a line in the log, until the cap is raised.
   The state the page and the checks read is kept in the atom of the
   page (`:cap` of `rragent.db`, `refresh!`): `{:monthly :spent
   :reached? :share}`."
  (:require ["@earendil-works/pi-durable" :refer [GenerationTask ToolTask defineDoc defineExtension hook]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rragent.costs :as costs]
            [rragent.db :as db]
            [rragent.loop :as loop]))

(def Cap
  (defineDoc #js {:kind "rragent.cap"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {})}))

(defonce home
  (atom nil))

(defonce harness
  (atom nil))

(def warn-share
  "The share of the cap at which the loop is told once a month."
  0.8)

(def notice-kind
  "The entry of the notice in the loop's chat."
  "rragent.cap-notice")

(defn month
  "`2026-11` for the time `now`, UTC."
  [now]
  (let [d (js/Date. now)]
    (str (.getUTCFullYear d) "-" (.padStart (str (inc (.getUTCMonth d))) 2 "0"))))

(defn ledger-file
  "The month's ledger of `home`."
  [home now]
  (path/join home "costs" (str (month now) ".edn")))

(defn charge!
  "A charge of `usd` at OpenRouter by the conversation `conversation-id`
   appended to the month's ledger; nothing for no charge."
  [home conversation-id usd now]
  (when (and (seq (str home)) (number? usd) (pos? usd))
    (let [file (ledger-file home now)]
      (fs/mkdirSync (path/dirname file) #js {:recursive true})
      (fs/appendFileSync file (str (pr-str {:at now :conversation conversation-id :usd usd}) "\n")))))

(defn spent
  "What the machine spent this month as the person pays it: the
   ledger's charges summed and priced; 0 without a ledger."
  [home now]
  (let [file (when (seq (str home)) (ledger-file home now))]
    (if (and file (fs/existsSync file))
      (costs/priced (reduce + 0 (keep (fn [line]
                                        (try
                                          (:usd (reader/read-string line))
                                          (catch :default _ nil)))
                                      (str/split-lines (fs/readFileSync file "utf8")))))
      0)))

(defn state
  "The state the page and the checks read: the cap, what is spent,
   whether the cap is reached, and the share spent."
  [{:keys [monthly]} spent]
  {:monthly monthly
   :spent spent
   :reached? (boolean (and monthly (pos? monthly) (>= spent monthly)))
   :share (when (and monthly (pos? monthly)) (/ spent monthly))})

(defn ^:async settings
  "The document: `{:monthly :noticed}`."
  [harness context]
  (let [doc (await (.snapshot harness Cap context))]
    {:monthly (some-> doc .-monthly)
     :noticed (some-> doc .-noticed)}))

(defn ^:async refresh!
  "The state read into the atom of the page, `:cap`; answers it."
  [harness context]
  (let [s (state (await (settings harness context)) (spent @home (js/Date.now)))]
    (swap! db/db assoc :cap s)
    s))

(defn current
  "The state as last read (`refresh!`)."
  []
  (:cap @db/db))

(defn reached?
  "Whether the month's budget is spent, as last read."
  []
  (boolean (:reached? (current))))

(defn ^:async set-monthly!
  "The cap set to `usd` a month, none for no number or zero."
  [harness usd context]
  (await (.commit harness
                  (^:async fn [tx]
                    (let [doc (await (.doc tx Cap))]
                      (if (and (number? usd) (pos? usd))
                        (aset doc "monthly" usd)
                        (js-delete doc "monthly")))
                    js/undefined)
                  context))
  (await (refresh! harness context)))

(defn notice-content
  "What the loop reads when the month's budget nears its cap."
  [spent monthly]
  (str "The machine has spent $" (.toFixed spent 2) " of its $" (.toFixed monthly 2) " for this month, "
       (js/Math.round (* 100 (/ spent monthly))) " percent. Tell the person once, with one card: what is running now, "
       "and that once the cap is reached nothing starts, no task, no schedule, no message, until the cap is raised. "
       "Ask nothing else."))

(defn ^:async warn!
  "The notice into the loop `loop-id`, once (its request id is the
   month's), and the month marked as noticed."
  [harness loop-id spent monthly month context]
  (when-let [conversation (await (.conversation harness loop-id context))]
    (await (.submit conversation
                    #js {:type "input"
                         :content (notice-content spent monthly)
                         :whenBusy "followUp"
                         :requestId (str "cap-notice:" month)
                         :entry #js {:kind notice-kind :data #js {:spent spent :monthly monthly}}}
                    context))
    (await (.commit harness
                    (^:async fn [tx]
                      (aset (await (.doc tx Cap)) "noticed" month)
                      js/undefined)
                    context))))

(defn ^:async charged!
  "A charge of `usd` by `conversation-id` recorded, the state refreshed,
   and the loop warned when the month crossed `warn-share` of the cap
   and was not warned yet."
  [usd conversation-id context]
  (when (and @harness (number? usd) (pos? usd))
    (let [now (js/Date.now)]
      (charge! @home conversation-id usd now)
      (let [{:keys [monthly spent share]} (await (refresh! @harness context))
            {:keys [noticed]} (await (settings @harness context))]
        (when (and monthly @loop/id (<= warn-share (or share 0)) (not= noticed (month now)))
          (await (warn! @harness (js/Number @loop/id) spent monthly (month now) context)))))))

(defn- ^:async charged-quietly!
  [usd conversation-id context]
  (try
    (await (charged! usd conversation-id context))
    (catch :default e
      (js/console.error "[cap]" (str e)))))

(defn extension
  "The extension that records every charge: an answer's usage (its
   cost's total, OpenRouter's charge once `rragent.costs` took it,
   else the catalog's estimate) and a tool result's (the media tools,
   `rragent.costs/tool-usage`). Installed after the costs extension."
  []
  (defineExtension #js {:name "rragent-cap"
                        :hooks #js [(hook GenerationTask #js {:afterResponse (fn [^js message ^js api context]
                                                                                (charged-quietly! (some-> message .-usage .-cost .-total) (.-conversationId api) context)
                                                                                js/undefined)})
                                    (hook ToolTask #js {:afterTool (fn [_call ^js result ^js api context]
                                                                     (charged-quietly! (some-> result .-usage .-cost .-total) (.-conversationId api) context)
                                                                     js/undefined)})]}))
