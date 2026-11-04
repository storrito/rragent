(ns rragent.status
  "Where a bot's work stands, as a model read it from the bot's chat:
   the document `rragent.status` of the bot's conversation. The
   manager's `bot status` (`rragent.tools.bot`) fills it: it reads a
   worker's chat since the entry the last status covers
   (`rragent.transcript`), hands the status as it was and the new
   part of the chat to a model in a conversation of the call's own,
   on the cheapest adequate model (`*model*`, decision 6 of
   `docs/manager.md`), and writes what the model answers. Not a
   projection in code: the bet of the manager's design (decision 4)
   is that the model gets better at understanding a chaotic project
   while a projection stays as naive as the day it was written. The
   document: `summary`, a few sentences on where the work stands;
   `needs`, short strings, what waits for the person, the questions
   the bot asked, the approvals it wants, empty when nothing;
   `version`, a name the bot gave its deliverable, or none;
   `asOfEntry`, the id of the last entry the summary covers; `at`,
   when it was written. A bot's chat is stale when it has entries
   past `asOfEntry` (`stale?`): those the manager reads at its next
   `bot status`. The page reads the status from the bot's map
   (`rragent.machine`, `:status`): the sidebar's \"Needs you\" counts
   the `needs`, a card says the summary \"as of\"."
  (:require ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.definition :as definition]
            [rragent.harness :as harness]))

(def Status
  "The status of a bot, a document of its conversation, the latest
   alone; a fork starts from the initial, its own chat being another."
  (defineDoc #js {:kind "rragent.status"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "initial"
                  :initial (fn [] #js {:summary "" :needs #js [] :version nil :asOfEntry 0 :at 0})}))

(def initial
  "The status of a bot nothing was read for yet, as data: the keys of
   the document, `asOfEntry` as `:as-of-entry`."
  {:summary "" :needs [] :version nil :as-of-entry 0 :at 0})

(defn- as-data
  "The document as data; `initial` for none."
  [^js doc]
  (if doc
    {:summary (str (or (.-summary doc) ""))
     :needs (mapv str (array-seq (or (.-needs doc) #js [])))
     :version (not-empty (str (or (.-version doc) "")))
     :as-of-entry (or (.-asOfEntry doc) 0)
     :at (or (.-at doc) 0)}
    initial))

(defn ^:async snapshot
  "The status of the bot `id` as data, `initial` for a bot none was
   written for yet."
  [harness id]
  (as-data (await (.snapshot harness Status (js/Number id) harness/context))))

(defn ^:async write!
  "The status of the bot `id` written in its own commit: `:summary`,
   `:needs`, `:version`, `:as-of-entry` and `:at`, the time now
   without one."
  [harness id {:keys [summary needs version as-of-entry at]}]
  (await (.commit harness
                  (^:async fn [tx]
                    (let [doc (await (.doc tx Status (js/Number id)))]
                      (aset doc "summary" (str summary))
                      (aset doc "needs" (clj->js (mapv str needs)))
                      (aset doc "version" (or (not-empty (str version)) nil))
                      (aset doc "asOfEntry" (or as-of-entry 0))
                      (aset doc "at" (or at (js/Date.now)))))
                  harness/context)))

(defn stale?
  "Whether the bot's chat has entries the status does not cover:
   `newest`, the id of its newest entry (nil for a chat without any),
   is past `:as-of-entry`."
  [{:keys [as-of-entry]} newest]
  (boolean (and newest (< (or as-of-entry 0) newest))))

(def ^:dynamic *model*
  "The model the status is read on: the cheapest adequate one, the
   model a definition without one runs on; `RRAGENT_STATUS_MODEL`
   names another. A test sets it to the faux provider's."
  (or (not-empty (aget (.-env js/process) "RRAGENT_STATUS_MODEL"))
      definition/default-model))

(def thinking
  "The thinking level of the reading: a summary needs little."
  "low")

(def instructions
  "What the reading conversation is told: it answers a JSON object and
   nothing else."
  (str "You read a bot's chat for the bot's manager and write the bot's status. "
       "You get the status as it was and the part of the chat since then; the chat shows the person's messages, the bot's answers and the tools it called. "
       "Answer ONLY a JSON object, nothing before or after it: {\"summary\": \"...\", \"needs\": [\"...\"], \"version\": \"...\" or null}. "
       "summary: two to four sentences in the language the person writes in the chat: what was made, what changed since the status before, what is open. "
       "It replaces the old summary, so keep of the old what still stands. "
       "needs: short strings, only what truly waits for the person: a question the bot asked them and got no answer to, an approval it wants, a file it needs from them; an empty list when nothing waits. "
       "version: the name the bot gave its deliverable (v3, final) when it named one, else null. "
       "No tool calls, no questions, no prose."))

(defn prompt
  "The message of one reading: the status as it was and the new part
   of the chat."
  [{:keys [summary needs version]} text]
  (str "The status as it was:\n"
       (if (str/blank? summary) "(none yet)" summary)
       (when (seq needs)
         (str "\nWaiting for the person: " (str/join "; " needs)))
       (when version
         (str "\nVersion: " version))
       "\n\nThe chat since then:\n"
       (if (str/blank? text) "(nothing)" text)))

(defn- unfenced
  "`s` without a code fence around it."
  [s]
  (-> (str/trim (str s))
      (str/replace #"^```[a-zA-Z]*\s*" "")
      (str/replace #"\s*```$" "")
      (str/trim)))

(defn- object-of
  "The first JSON object of `s`, parsed; nil for none."
  [s]
  (let [from (str/index-of s "{")
        to (str/last-index-of s "}")]
    (when (and from to (< from to))
      (try
        (js/JSON.parse (subs s from (inc to)))
        (catch :default _
          nil)))))

(defn parse
  "The model's answer as `{:summary :needs :version}`: the JSON object
   in it, a code fence around it stripped; nil for an answer that
   holds no status, which keeps the status as it was."
  [answer]
  (let [^js o (object-of (unfenced answer))]
    (when (and o (object? o) (string? (.-summary o)) (not (str/blank? (.-summary o))))
      {:summary (str/trim (.-summary o))
       :needs (vec (keep (fn [need] (not-empty (str/trim (str need))))
                         (array-seq (if (array? (.-needs o)) (.-needs o) #js []))))
       :version (let [v (.-version o)]
                  (when (string? v)
                    (not-empty (str/trim v))))})))
