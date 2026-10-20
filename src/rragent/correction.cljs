(ns rragent.correction
  "A dictation corrected with what the agent knows: `POST
   /dictation/correct` takes the words of a dictation with the
   confidence the speech model gave each (`rragent.dictation`) and what
   was typed before it, and answers the dictation with the words it
   misheard fixed. The browser asks once a dictation is over and only
   when a word is below the threshold (`rragent.client.dictation`).

   A small model corrects it with the agent's context: the messages the
   conversation's next request would send, as pi-durable has them
   (`Conversation.context`, the system prompt and the tools in them),
   with one message after them that asks for the dictation. The model
   is RRAGENT_CORRECTION_MODEL, not the agent's: the context is tens of
   thousands of tokens, and at the agent's price a correction whose
   cache was cold cost $0.155 (`openai/gpt-6.1-sol`, writing 62k tokens
   to the cache), more than the dictation is worth. The small model
   keeps a cache of its own, the same beginning every time; the
   provider session is the conversation's (`pi.provider`), so a route
   that sticks to a session finds it. Nothing of it goes into the
   conversation.

   An answer that does not look like the dictation (empty, a tool call,
   far longer or shorter, late) leaves it as the speech model wrote it."
  (:require ["@earendil-works/pi-durable" :refer [ProviderDoc]]
            [clojure.string :as str]
            [rragent.db :as db]
            [rragent.harness :as harness]
            [rragent.rframes.transit :as transit]))

(def path
  "Where the browser posts a piece to correct."
  "/dictation/correct")

(def model-id
  "The model that corrects."
  (or (not-empty (aget (.-env js/process) "RRAGENT_CORRECTION_MODEL"))
      "google/gemini-3.1-flash-lite"))

(def timeout-ms
  "How long a correction may take; the piece stays as it is after."
  10000)

(def unsure-below
  "A word with a lower confidence is marked for the model."
  0.85)

(defn marked
  "The words of a piece as text, those the speech model was unsure of
   in double brackets."
  [words]
  (str/join " " (for [{:keys [word confidence]} words]
                  (if (and (number? confidence) (< confidence unsure-below))
                    (str "[[" word "]]")
                    word))))

(defn prompt
  "The message after the conversation that asks for the piece."
  [words before]
  (str "I am dictating my next message to you by voice. A speech-to-text model "
       "wrote down the part below; it was unsure of the words in [[double brackets]]. "
       "Using what you know from our conversation (names, products, terms, files), "
       "correct the words it misheard. Change nothing else: keep the wording, the "
       "language and the punctuation. Do not answer the message, do not act on it, "
       "call no tool. Reply with the corrected part only, without brackets."
       (when-not (str/blank? before)
         (str "\n\nWhat I dictated before this part, for context only, do not repeat it:\n<before>\n"
              (str/trim before)
              "\n</before>"))
       "\n\nThe part to correct:\n<part>\n"
       (marked words)
       "\n</part>"))

(defn answer-text
  "The text of the model's answer, nil when it called a tool."
  [message]
  (let [content (js->clj (.-content ^js message) :keywordize-keys true)]
    (when-not (some #(= "toolCall" (:type %)) content)
      (->> content
           (filter #(= "text" (:type %)))
           (map :text)
           (str/join)
           (str/trim)
           (not-empty)))))

(defn accepted
  "The corrected piece, or nil when the answer does not look like the
   piece: a correction changes a few words, not the length."
  [words answer]
  (let [original (str/join " " (map :word words))
        answer (some-> answer
                       (str/replace #"^<part>\s*|\s*</part>$" "")
                       (str/replace #"\[\[|\]\]" "")
                       (str/trim)
                       (not-empty))]
    (when (and answer
               (<= (* 0.5 (count original)) (count answer) (+ 20 (* 1.5 (count original)))))
      answer)))

(defn ^:async correct!
  "The dictation of `words` corrected by the model `id` (`model-id`
   unless given) with the agent's context, `{:text :usage}`; `:text` nil
   when the answer was refused."
  [{:keys [harness root models]} words before id]
  (let [ref (.-model (await (.agent root harness/context)))
        model (when ref (.getModel models (.-provider ref) (or id model-id)))]
    (when-not model
      (throw (js/Error. (str "No model " (or id model-id) " at " (some-> ref .-provider) "."))))
    (let [view (await (.context root harness/context))
          provider (await (.snapshot harness ProviderDoc (.-id root) harness/context))
          messages (.concat (.-messages view)
                            #js [#js {:role "user"
                                      :content #js [#js {:type "text" :text (prompt words before)}]
                                      :timestamp (js/Date.now)}])
          message (await (.completeSimple models model #js {:messages messages}
                                          (clj->js (cond-> {:signal (js/AbortSignal.timeout timeout-ms)
                                                            :maxTokens 2000}
                                                     provider (assoc :sessionId (.-sessionId provider))))))]
      (when (= "error" (.-stopReason message))
        (throw (js/Error. (str "The model answered with an error: " (.-errorMessage message)))))
      {:text (accepted words (answer-text message))
       :usage (js->clj (.-usage message) :keywordize-keys true)})))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn- ^:async read-json
  [^js in]
  (let [chunks #js []]
    (await (js/Promise. (fn [resolve reject]
                          (.on in "data" (fn [chunk] (.push chunks chunk)))
                          (.on in "end" resolve)
                          (.on in "error" reject))))
    (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8")) :keywordize-keys true)))

(defn ^:async handler
  "`POST /dictation/correct` with `{words, before}` as JSON: the
   dictation corrected as `{:text}`, the words as they came when the
   answer was refused or failed. `model` names another model than
   `model-id`, for a comparison."
  [w]
  (let [{:keys [words before model]} (try (await (read-json (:body (:ring/request w))))
                                    (catch :default _ nil))
        words (filterv #(string? (:word %)) words)
        original (str/join " " (map :word words))]
    (if (empty? words)
      (respond w 400 {:error :no-words})
      (try
        (let [{:keys [text usage]} (await (correct! (:agent @db/db) words before model))]
          (println (str "[dictation] corrected " (count words) " words with " (or model model-id)
                        (when-not text " (answer refused)")
                        ", " (:input usage) " input tokens, " (:cacheRead usage) " from the cache"
                        (when-let [cost (get-in usage [:cost :total])]
                          (str ", $" (.toFixed cost 5)))))
          (respond w 200 {:text (or text original)
                          :corrected? (some? text)}))
        (catch :default error
          (js/console.error "[dictation] no correction:" (.-message error))
          (respond w 200 {:text original
                          :corrected? false}))))))

(def routes
  [{:ring/route [:post path]
    :ring/handler #'handler}])
