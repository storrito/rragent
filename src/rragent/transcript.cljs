(ns rragent.transcript
  "A bot's chat as text for another bot: the entries of a conversation
   after an entry id, oldest first, folded to lines a model reads
   (`fold`) and cut to a budget of characters, with the id of the last
   entry read, so that the next read goes on from there (`read!`). A
   bot reads another bot's chat with it, also what the person said
   there (`rragent.tools.bot`, its `transcript`), when the wiki does
   not answer, instead of carrying that chat in its own. What is folded: the person's messages, the bot's answers with
   the names of the tools it called, a message from another bot, a
   run's report; a tool's result is one line with its size, the rest
   (system prompts, compaction notes) nothing."
  (:require [clojure.string :as str]
            [rragent.harness :as harness]))

(def default-max-chars
  20000)

(defn- text-of
  "The text of a message content: the string, or the text blocks of a
   list of blocks joined."
  [content]
  (cond
    (string? content) content
    (sequential? content) (str/join "\n" (keep (fn [block]
                                                  (when (= "text" (:type block))
                                                    (:text block)))
                                                content))
    :else ""))

(defn- calls-of
  [content]
  (when (sequential? content)
    (seq (keep (fn [block]
                 (when (= "toolCall" (:type block))
                   (:name block)))
               content))))

(defn line
  "One entry as a line, nil for one that says nothing to a reader."
  [{:keys [id kind model data]}]
  (let [message (first model)
        prefix (str "[" id "] ")]
    (case kind
      "pi.user" (str prefix "Person: " (text-of (:content message)))
      "pi.assistant" (let [text (text-of (:content message))
                           calls (calls-of (:content message))]
                       (str prefix "Bot: " text
                            (when calls (str (when (seq text) " ") "(calls " (str/join ", " calls) ")"))))
      "pi.tool-result" (str prefix "(result of " (:toolName message) ": " (count (text-of (:content message))) " characters)")
      "rragent.message" (str prefix (if (:answer data) "Answer from " "Message from ") (:name data) " (bot " (:from data) "): " (:text data))
      "rragent.run-report" (str prefix "Report of a run: " (text-of (:content message)))
      nil)))

(defn fold
  "The entries, maps as the storage gives them, as lines; `since` the
   id after which they are taken."
  [entries]
  (str/join "\n" (keep line entries)))

(defn ^:async read!
  "The entries of `conversation` after the entry `since` (0 for all),
   oldest first, folded, at most `max-chars` of text: `{:text :last
   :more?}`, `:last` the id of the last entry folded, `:more?` whether
   the budget cut the rest, which a read from `:last` goes on with."
  ([conversation since]
   (read! conversation since default-max-chars))
  ([conversation since max-chars]
   (loop [cursor js/undefined
          lines []
          chars 0
          last-id (or since 0)]
     (let [page (await (.entries conversation #js {:minEntryId (inc (or since 0)) :order "ascending"} 200 cursor harness/context))
           items (map #(js->clj % :keywordize-keys true) (array-seq (.-items page)))
           [lines chars last-id cut?] (reduce (fn [[lines chars last-id cut?] entry]
                                               (if cut?
                                                 [lines chars last-id cut?]
                                                 (let [l (line entry)
                                                       n (if l (inc (count l)) 0)]
                                                   (if (< max-chars (+ chars n))
                                                     [lines chars last-id true]
                                                     [(cond-> lines l (conj l)) (+ chars n) (:id entry) false]))))
                                             [lines chars last-id false]
                                             items)]
       (if (and (not cut?) (.-next page) (seq items))
         (recur (.-next page) lines chars last-id)
         {:text (str/join "\n" lines)
          :last last-id
          :more? (boolean cut?)})))))
