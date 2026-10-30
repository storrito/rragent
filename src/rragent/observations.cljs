(ns rragent.observations
  "What the model is sent of its tools' earlier results. A text of a
   tool result of a turn before the last `kept-turns` (a turn: a
   message of the person and what followed it) longer than `text-chars`
   goes as its start and end. Pictures stay whole until a compaction
   takes them into its summary, which names their files
   (`rragent.harness/compaction-focus`): a picture left out of a request
   changes its start and the provider reads all after it anew, while a
   cached picture costs little (some 1,000 to 1,600 tokens read at a
   tenth or less of the price), and the compaction while the person is
   away (`rragent.away`) and the one at the budget clear them anyway.
   The conversation keeps every result whole, the chat shows it whole,
   the summary of a compaction reads it whole; only the request is
   lighter (pi-durable's `beforeRequest`, for that request only).
   Results are most of what fills a conversation (a command's output, a
   page it fetched) and what an earlier turn needed of them is in the
   agent's answers; the agent runs the tool again for the rest. What
   the agent read of a skill (`instructions?`) is no result but its
   instructions, and stays whole. Without a model call and the same for
   every model, as observation masking is in Lindenbauer et al. 2025
   (\"The Complexity Trap\"): half the cost of the whole results, and as
   good as a summary. The line between kept and masked moves only with
   a new message of the person, so the requests of a run start as the
   one before and the provider's cache holds."
  (:require ["@earendil-works/pi-durable" :refer [GenerationTask defineExtension hook]]))

(def kept-turns
  "The last turns whose tool results the model gets whole: the one that
   runs and the one before, which the person's message often answers."
  2)

(def text-chars
  "A text of an earlier tool result longer than this is shortened."
  4000)

(def kept-chars
  "What a shortened text keeps of its start and of its end each."
  1000)

(defn boundary
  "The index of the first message of the last `kept-turns` turns of
   `messages` (a vector), the index of a message of the person; 0 when
   there are not more turns than that."
  [messages]
  (let [starts (into [] (keep-indexed (fn [i ^js m] (when (= "user" (.-role m)) i))) messages)
        n (count starts)]
    (if (<= n kept-turns)
      0
      (nth starts (- n kept-turns)))))

(defn calls
  "Every tool call of the agent's `messages` by its id: `{:name :args}`."
  [messages]
  (into {}
        (for [^js m messages
              :when (= "assistant" (.-role m))
              ^js c (array-seq (.-content m))
              :when (= "toolCall" (.-type c))]
          [(.-id c) {:name (.-name c) :args (.-arguments c)}])))

(defn instructions?
  "Whether the call `{:name :args}` read instructions: a file of a
   skills folder (ours, `/home/sprite/skills`, or the person's, the
   machine's `skills/`), or a `SKILL.md` or `AGENT.md` anywhere."
  [{:keys [name args]}]
  (boolean
   (when (= "read" name)
     (when-let [path (some-> args (aget "path"))]
       (re-find #"(^|/)skills/|(^|/)(SKILL|AGENT)\.md$" (str path))))))

(defn shorten
  "`text` as its first and last `kept-chars` with a line between that
   says what was left out; `text` itself when not longer than
   `text-chars`."
  [text]
  (let [n (count text)]
    (if (<= n text-chars)
      text
      (str (subs text 0 kept-chars)
           "\n\n[… " (- n (* 2 kept-chars)) " characters of this earlier result are left out here; run the tool again for all of it …]\n\n"
           (subs text (- n kept-chars))))))

(defn mask-result
  "The tool result `m` with its long texts shortened, its pictures as
   they are; `m` itself when nothing changed."
  [^js m]
  (let [content (vec (array-seq (.-content m)))
        shortened (mapv (fn [^js c]
                          (if (= "text" (.-type c))
                            (let [text (.-text c)
                                  short (shorten text)]
                              (if (identical? short text) c #js {:type "text" :text short}))
                            c))
                        content)]
    (if (every? true? (map identical? content shortened))
      m
      (js/Object.assign #js {} m #js {:content (into-array shortened)}))))

(defn mask
  "The JS array `messages` with the texts of the tool results before
   `boundary` shortened (`mask-result`), instructions aside
   (`instructions?`); the same array when nothing changed."
  [messages]
  (let [ms (vec (array-seq messages))
        b (boundary ms)
        by-id (calls ms)
        out (into []
                  (map-indexed (fn [i ^js m]
                                 (let [call (get by-id (.-toolCallId m))]
                                   (if (and (< i b) (= "toolResult" (.-role m)) (not (instructions? call)))
                                     (mask-result m)
                                     m))))
                  ms)]
    (if (every? true? (map identical? ms out))
      messages
      (into-array out))))

(def extension
  "The extension that sends every request with the earlier tool results
   masked (`mask`)."
  (defineExtension #js {:name "rragent-observations"
                        :hooks #js [(hook GenerationTask #js {:beforeRequest (fn [^js request _api _context]
                                                                                (let [messages (.-messages request)
                                                                                      masked (mask messages)]
                                                                                  (if (identical? masked messages)
                                                                                    js/undefined
                                                                                    #js {:messages masked})))})]}))
