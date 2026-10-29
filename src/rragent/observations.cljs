(ns rragent.observations
  "What the model is sent of its tools' earlier results. A tool result
   of a turn before the last `kept-turns` (a turn: a message of the
   person and what followed it) goes as less: a picture as a line that
   names its file, a text longer than `text-chars` as its start and end.
   The conversation keeps every result whole, the chat shows it whole,
   the summary of a compaction reads it whole; only the request is
   lighter (pi-durable's `beforeRequest`, for that request only).
   Results are most of what fills a conversation (pictures the agent
   looked at, a command's output, a page it fetched) and what an
   earlier turn needed of them is in the agent's answers; the agent
   reads a file or runs the tool again for the rest. Without a model
   call and the same for every model, as observation masking is in
   Lindenbauer et al. 2025 (\"The Complexity Trap\"): half the cost of
   the whole results, and as good as a summary. The line between kept and masked moves only
   with a new message of the person, so within a run every request
   starts as the one before and the provider's cache holds."
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
  "The arguments of every tool call of the agent's `messages`, by the
   call's id."
  [messages]
  (into {}
        (for [^js m messages
              :when (= "assistant" (.-role m))
              ^js c (array-seq (.-content m))
              :when (= "toolCall" (.-type c))]
          [(.-id c) (.-arguments c)])))

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

(defn picture-note
  "What stands for a picture of an earlier tool result, naming the file
   when the call's `args` have its `path`."
  [args]
  (if-let [path (some-> args (aget "path"))]
    (str "[The picture " path " was here; pictures of earlier turns are left out. Read the file again to see it.]")
    "[A picture was here; pictures of earlier turns are left out.]"))

(defn mask-result
  "The tool result `m` with its pictures as notes and its long texts
   shortened, `args` those of its call; `m` itself when nothing
   changed."
  [^js m args]
  (let [content (vec (array-seq (.-content m)))
        masked (mapv (fn [^js c]
                       (case (.-type c)
                         "image" #js {:type "text" :text (picture-note args)}
                         "text" (let [text (.-text c)
                                      short (shorten text)]
                                  (if (identical? short text) c #js {:type "text" :text short}))
                         c))
                     content)]
    (if (every? true? (map identical? content masked))
      m
      (js/Object.assign #js {} m #js {:content (into-array masked)}))))

(defn mask
  "The JS array `messages` with the tool results before `boundary`
   masked (`mask-result`); the same array when nothing changed."
  [messages]
  (let [ms (vec (array-seq messages))
        b (boundary ms)
        args (calls (subvec ms 0 b))
        out (into []
                  (map-indexed (fn [i ^js m]
                                 (if (and (< i b) (= "toolResult" (.-role m)))
                                   (mask-result m (get args (.-toolCallId m)))
                                   m)))
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
