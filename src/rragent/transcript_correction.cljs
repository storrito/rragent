(ns rragent.transcript-correction
  "A transcript corrected with what the agent knows, for the
   `transcribe` tool (`rragent.tools.transcribe`): the speech model
   writes names the way they sound (`storrito_com` came back as
   `streeto_com`, `strito_com`, `storytell.com`, whichever model), and
   a name hint does not reach the models behind OpenRouter. So a small
   model reads the transcript with the agent's system prompt, what the
   person and the agent wrote (as the dictation's correction has them,
   `rragent.correction`) and the names the agent passes, and lists the
   words that were misheard, each as it was heard and as it was meant.

   The model is RRAGENT_TRANSCRIPT_MODEL, Claude Haiku 5.5 by default,
   asked through OpenRouter's chat completions the way the transcription
   is (`rragent.openrouter`). On a recording of four minutes (October
   2026) it fixed the three names and nothing else, in four seconds for
   $0.0004; Gemini 3.1 Flash Lite, the dictation's, also turned `That,`
   into `rragent` and `will` into `it`.

   Only those words change, where they stand, and only fixes that look
   like a mishearing: the heard and the meant words alike in their
   letters (`similar?`), not the same but for case and punctuation; at a
   place where the speech model was unsure of a word, or with the meant
   words among the names and the conversation. A fix of as many words
   as were heard keeps every word's time, one of more or fewer words
   shares the time of the words it replaces by their lengths; a fix
   whose heard words are not in the transcript changes nothing. The cut
   points of an edit stay where the speech model put them.

   A long transcript goes in pieces of `piece-words`. An answer that
   is no list of fixes, a failed or late request, leaves the piece as
   it was."
  (:require [clojure.string :as str]
            [rragent.correction :as correction]
            [rragent.openrouter :as openrouter]))

(def model-id
  "The model that corrects."
  (or (not-empty (aget (.-env js/process) "RRAGENT_TRANSCRIPT_MODEL"))
      "anthropic/claude-haiku-5.5"))

(def piece-words
  "The words one request corrects."
  1500)

(def timeout-ms
  "How long one piece's correction may take."
  45000)

(def max-fixes
  "More fixes than this in one piece are no correction of names."
  60)

(defn prompt
  "The ask for one piece of the transcript, after the conversation."
  [words names]
  (str "A speech-to-text model wrote down the recording below; it was unsure of the words in [[double brackets]]. "
       "It writes names the way they sound: products, companies, accounts, handles, people, places and terms. "
       "Using what you know from our conversation"
       (when-not (str/blank? names) " and the names below")
       ", find the words it misheard. Change nothing that was heard right, not the wording, not the grammar. "
       "Reply with JSON only, no other text: {\"fixes\": [{\"heard\": \"...\", \"meant\": \"...\"}]}, "
       "where `heard` is copied exactly from the transcript, one word or a few in a row, and `meant` is how it is written; "
       "{\"fixes\": []} when nothing was misheard."
       (when-not (str/blank? names)
         (str "\n\nNames and terms that may be spoken:\n<names>\n" (str/trim names) "\n</names>"))
       "\n\nThe transcript:\n<transcript>\n"
       (correction/marked words)
       "\n</transcript>"))

(defn fixes
  "The fixes of an answer, `[{:heard :meant}]`; nil when it is no list
   of fixes."
  [answer]
  (let [json (some-> answer
                     (str/replace #"(?s)^\s*```(?:json)?\s*|\s*```\s*$" "")
                     (str/trim))
        parsed (try (js->clj (js/JSON.parse json) :keywordize-keys true) (catch :default _ nil))
        fixes (:fixes parsed)]
    (when (and (sequential? fixes) (<= (count fixes) max-fixes))
      (->> fixes
           (keep (fn [{:keys [heard meant]}]
                   (let [heard (some-> heard str (str/replace #"\[\[|\]\]" "") str/trim)
                         meant (some-> meant str str/trim)]
                     (when (and (not (str/blank? heard)) (not (str/blank? meant)) (not= heard meant))
                       {:heard heard :meant meant}))))
           (vec)))))

(defn- bare
  "A word as it is compared: lower case, without the punctuation
   around it."
  [word]
  (-> (str word)
      (str/lower-case)
      (str/replace #"^[\"'„“(\[@#]+|[\"'”)\].,!?;:…]+$" "")))

(def ^:private not-letters
  "What is no letter or digit, in any script: a Unicode class, which a
   regex literal of ClojureScript cannot flag."
  (js/RegExp. "[^\\p{L}\\p{N}]+" "gu"))

(defn- letters
  "The letters and digits of a text, lower case."
  [text]
  (.replace (str/lower-case (str text)) not-letters ""))

(defn similar?
  "Whether `heard` sounds like it could be `meant`: their letters share
   more than half (the longest common subsequence against their mean
   length). A misheard name is near its name, \"streeto_com\" to
   \"storrito_com\"; a rewording is not, \"will\" to \"it\"."
  [heard meant]
  (let [a (letters heard)
        b (letters meant)]
    (and (seq a)
         (seq b)
         (let [row (reduce (fn [prev x]
                             (reduce (fn [row [j y]]
                                       (conj row (if (= x y)
                                                   (inc (nth prev j))
                                                   (max (nth prev (inc j)) (peek row)))))
                                     [0]
                                     (map-indexed vector b)))
                           (vec (repeat (inc (count b)) 0))
                           a)]
           (< 0.5 (/ (* 2 (peek row)) (+ (count a) (count b))))))))

(defn- tokens
  [text]
  (remove str/blank? (str/split (str text) #"[\s,;]+")))

(defn known
  "The names a fix may turn a sure word into, as `bare` has them: every
   word of `names`, and the words of `conversation` that look like a
   name (a capital, a digit, an underscore, an @, a dot inside) and that
   it does not also write in lower case, an ordinary word at the start
   of a sentence (\"There\" beside \"there\")."
  [names conversation]
  (let [said (tokens conversation)
        ;; ordinary words: letters alone, in lower case
        plain (into #{} (comp (map bare) (filter #(re-matches #"[a-zäöüßàâçéèêëîïôûùüÿñæœ'’-]+" %))) said)
        named (->> said
                   (filter #(re-find #"[A-Z0-9_@]|\w\.\w" %))
                   (map bare)
                   ;; the labels of the conversation's text (`rragent.correction/conversation`)
                   (remove #{"person" "agent"})
                   (remove plain))]
    (into #{} (remove str/blank?) (concat (map bare (tokens names)) named))))

(defn- trailing
  "The punctuation at the end of a word."
  [word]
  (or (re-find #"[\"'”)\].,!?;:…]+$" (str word)) ""))

(defn- leading
  "The punctuation at the start of a word."
  [word]
  (or (re-find #"^[\"'„“(\[]+" (str word)) ""))

(defn- replaced
  "The words of `span` replaced by the words of `meant`: as many, each
   keeps its time; else the span's time shared by the words' lengths.
   The punctuation around the span stays when `meant` has none."
  [span meant]
  (let [tokens (str/split (str/trim meant) #"\s+")
        tail (trailing (:word (peek span)))
        lead (leading (:word (first span)))
        tokens (if (and (seq tail) (= "" (trailing (peek tokens))))
                 (conj (pop tokens) (str (peek tokens) tail))
                 tokens)
        tokens (if (and (seq lead) (= "" (leading (first tokens))))
                 (assoc tokens 0 (str lead (first tokens)))
                 tokens)]
    (if (= (count tokens) (count span))
      (mapv (fn [word token] (assoc word :word token :heard (:word word))) span tokens)
      (let [start (:start (first span))
            end (:end (peek span))
            heard (str/join " " (map :word span))
            total (reduce + (map count tokens))
            confidence (some->> (seq (keep :confidence span)) (apply min))]
        (loop [tokens tokens
               at start
               out []]
          (if-let [token (first tokens)]
            (let [until (if (next tokens)
                          (+ at (* (- end start) (/ (count token) total)))
                          end)]
              (recur (rest tokens) until (conj out (cond-> {:word token :start at :end until :heard heard}
                                                     confidence (assoc :confidence confidence)))))
            out))))))

(defn- unsure?
  [{:keys [confidence]}]
  (and (number? confidence) (< confidence correction/unsure-below)))

(defn apply-fixes
  "The `words` with the `fixes` applied where their heard words stand,
   `{:words :applied}`; `:applied` is `[{:heard :meant :times}]` of the
   fixes that changed something. A fix applies when it looks like a
   mishearing (`similar?`, more than case and punctuation), at a place
   where a heard word is unsure or with its meant words all `known`. The
   fixes of more words go first, and a word one fix changed no other
   changes: \"story to com\" before \"story\", no a → b → c."
  [words fixes & [known]]
  (reduce (fn [{:keys [words applied] :as acc} {:keys [heard meant] :as fix}]
            (let [pattern (mapv bare (str/split heard #"\s+"))
                  n (count pattern)
                  named? (and (seq known) (every? (comp known bare) (str/split meant #"\s+")))]
              (if (or (= (letters heard) (letters meant)) (not (similar? heard meant)))
                acc
              (loop [i 0
                     out []
                     times 0]
                (if (< i (count words))
                  (if (and (<= (+ i n) (count words))
                           (= pattern (mapv (comp bare :word) (subvec words i (+ i n))))
                           (not-any? :heard (subvec words i (+ i n)))
                           (or named? (some unsure? (subvec words i (+ i n)))))
                    (recur (+ i n) (into out (replaced (subvec words i (+ i n)) meant)) (inc times))
                    (recur (inc i) (conj out (nth words i)) times))
                  {:words out
                   :applied (cond-> applied
                              (pos? times) (conj (assoc fix :times times)))})))))
          {:words (vec words) :applied []}
          (sort-by #(- (count (str/split (:heard %) #"\s+"))) fixes)))

(defn request-messages
  "The agent's system prompt, then the conversation and the ask for the
   piece, as the dictation's correction asks (`rragent.correction`), as
   OpenRouter's chat completions take them."
  [messages words names]
  [{:role "system" :content (correction/instructions messages)}
   {:role "user"
    :content (str "Our conversation so far:\n<conversation>\n"
                  (correction/conversation messages)
                  "\n</conversation>\n\n"
                  (prompt words names))}])

(defn openrouter-complete
  "A function of the request messages to a promise of `{:text :cost}`
   of `model-id`'s answer through OpenRouter, where the REPL `codemode`
   knows it (`rragent.openrouter/route!`)."
  [codemode]
  (^:async fn [messages]
    (let [{:keys [base-url headers]} (openrouter/route! codemode "transcript correction")
          response (await (js/fetch (str base-url "/chat/completions")
                                    #js {:method "POST"
                                         :headers (clj->js (assoc headers "Content-Type" "application/json"))
                                         :body (js/JSON.stringify (clj->js {:model model-id :messages messages :max_tokens 4000 :temperature 0}))
                                         :signal (js/AbortSignal.timeout timeout-ms)}))
          text (await (.text response))
          body (try (js->clj (js/JSON.parse text) :keywordize-keys true) (catch :default _ nil))]
      (when-not (and (.-ok response) body)
        (throw (js/Error. (str "OpenRouter answered " (.-status response) ": " (or (get-in body [:error :message]) (subs text 0 (min 200 (count text))) "no reason")))))
      {:text (get-in body [:choices 0 :message :content])
       :cost (or (get-in body [:usage :cost]) 0)})))

(defn ^:async correct!
  "The `words` of a transcript (`{:word :start :end :confidence}`)
   corrected with the conversation's `messages` and the `names`, by
   `complete` (`openrouter-complete`). `{:words :applied :cost
   :failed}`: the words with the fixes, the fixes that changed
   something, what the requests cost, and why a piece stayed as it was."
  [complete messages words names]
  (let [known (known names (correction/conversation messages))
        one (^:async fn [piece]
              (try
                (let [{:keys [text cost]} (await (complete (request-messages messages piece names)))
                      found (fixes text)]
                  {:piece piece
                   :result (when found (apply-fixes piece found known))
                   :cost cost
                   :error (when (nil? found) "the answer was no list of fixes")})
                (catch :default e
                  {:piece piece :error (.-message e) :cost 0})))
        ;; all pieces at once: a long recording waits for the slowest, not for their sum
        done (array-seq (await (js/Promise.all (into-array (map (comp one vec) (partition-all piece-words words))))))]
    {:words (vec (mapcat #(if (:result %) (:words (:result %)) (:piece %)) done))
     :applied (vec (mapcat (comp :applied :result) done))
     :cost (reduce + (map #(or (:cost %) 0) done))
     :failed (some :error done)}))

(defn summary
  "The fixes as one line for the agent: what was heard and what was
   meant, how often."
  [applied]
  (->> applied
       (group-by (juxt :heard :meant))
       (map (fn [[[heard meant] fixes]]
              (let [times (reduce + (map :times fixes))]
                (str heard " → " meant (when (< 1 times) (str " (" times "×)"))))))
       (str/join ", ")))
