(ns rragent.warm-summary
  "The summary of a compaction read from the provider's cache. pi-durable
   summarizes in a request of its own (its own system prompt, the
   conversation as one text, no cache), so it reads the whole context at
   the full input price, and for OpenAI's newer models at 1.25 times it,
   the cache write. The conversation's last request is still in the
   provider's cache, though: GPT-5.6 and later keep a prefix at least 30
   minutes after its last read. So a compaction rragent starts (the
   budget's, `rragent.harness/context-budget`, and the one while the
   person is away, `rragent.away`) asks that last request again, as
   `rragent.model-requests` kept it, with the request for the summary
   appended and no tools to call (`summary-body`): the same start, read
   from the cache at a twentieth of the price for GPT-6.1 Sol (on a test
   of 27,895 tokens, 27,767 of them cached: $0.0047 instead of $0.0698).
   Its answer is the summary pi-durable places (`beforeCompact`). Without
   a kept request (the process started since), or when the request
   fails, pi-durable summarizes as before."
  (:require ["@earendil-works/pi-durable" :refer [CompactionTask ProviderDoc defineExtension hook]]
            [rragent.model-requests :as model-requests]))

(def max-tokens
  "The most the summary may take, thinking included: pi-durable's own
   (four fifths of its reserve of 16,384)."
  13107)

(def prompt
  "What the summary is asked for: pi-durable's request for its summary
   (MIT, `@earendil-works/pi-durable`), the same format, after the
   conversation instead of around it."
  "The messages above are a conversation to summarize. Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary. Create a structured context checkpoint summary that another LLM will use to continue the work. If the conversation starts with an earlier summary, preserve its information and fold the newer messages into it.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or \"(none)\" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or \"(none)\" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(defn summary-body
  "The JSON `body` of the conversation's last request with the summary
   asked for after it: its messages, tools and model as they were, so
   the provider reads them from its cache; a message with `prompt` (and
   `instructions` as its focus) appended; no tool to call, no stream,
   at most `max-tokens`."
  [body instructions]
  (let [^js request (js/JSON.parse body)
        ask (str prompt (when instructions (str "\n\nAdditional focus: " instructions)))]
    (.push (.-messages request) #js {:role "user" :content ask})
    (set! (.-tool_choice request) "none")
    (set! (.-stream request) false)
    (js-delete request "stream_options")
    (js-delete request "max_completion_tokens")
    (set! (.-max_tokens request) max-tokens)
    (js/JSON.stringify request)))

(defn summary-of
  "The summary in the provider's answer `answer` (its JSON): the text
   of a finished answer; nil for anything else (an error, a tool call,
   an answer cut off)."
  [^js answer]
  (let [^js choice (some-> answer .-choices (aget 0))
        text (some-> choice .-message .-content str .trim)]
    (when (and (= "stop" (some-> choice .-finish_reason)) (seq text))
      text)))

(def timeout-ms
  "How long the summary may take before pi-durable summarizes instead:
   a compaction that hangs would keep the machine awake
   (`rragent.away`)."
  (* 5 60000))

(defonce post
  ;; how the request goes out: the fetch from before rragent wrapped it,
  ;; so the summary is not kept as the session's last request; a test
  ;; puts its own
  (atom (fn [url init] (model-requests/original-fetch url init))))

(defn ^:async summarize
  "The summary of the conversation whose last request was `request`
   (`{:url :headers :body}`), asked again with the summary appended
   (`summary-body`); nil when the provider gave none. Says what it
   cost and how much came from the cache."
  [{:keys [url headers body]} instructions]
  (let [headers (js/Object.assign #js {} headers)
        ;; the old length is not the new body's; the answer is JSON, not a stream
        _ (js-delete headers "content-length")
        _ (aset headers "accept" "application/json")
        response (await (@post url #js {:method "POST"
                                        :headers headers
                                        :body (summary-body body instructions)
                                        :signal (js/AbortSignal.timeout timeout-ms)}))
        text (await (.text response))
        answer (try (js/JSON.parse text) (catch :default _ nil))
        summary (when (.-ok response) (summary-of answer))
        ^js usage (some-> answer .-usage)]
    (if summary
      (js/console.error (str "[compaction] summary from the cache: "
                             (some-> usage .-prompt_tokens_details .-cached_tokens) " of " (some-> usage .-prompt_tokens) " tokens cached"
                             (when-let [cost (some-> usage .-cost)] (str ", $" (.toFixed cost 4)))))
      (js/console.error (str "[compaction] no summary from the cache (HTTP " (.-status response) "), pi-durable summarizes: "
                             (subs text 0 (min 300 (count text))))))
    summary))

(def reasons
  "The compactions whose summary is asked from the cache: rragent's own
   (`compact()`), which come after an answer. pi-durable's threshold and
   overflow compactions come before a request, whose context the kept
   one is not, or after a request that was too large."
  #{"manual"})

(def extension
  "The extension that gives a compaction rragent started its summary
   from the provider's cache (`summarize`), when the conversation's
   last request is kept; pi-durable's own summary otherwise."
  (defineExtension #js {:name "rragent-warm-summary"
                        :hooks #js [(hook CompactionTask #js {:beforeCompact (fn [^js compaction ^js api context]
                                                                                (if-not (reasons (.-reason compaction))
                                                                                  js/undefined
                                                                                  (-> (.snapshot api ProviderDoc (.-conversationId api) context)
                                                                                      (.then (fn [^js provider]
                                                                                               (when-let [request (some-> provider .-sessionId model-requests/last-request)]
                                                                                                 (summarize request (.-instructions compaction)))))
                                                                                      (.then (fn [summary]
                                                                                               (if summary #js {:summary summary} js/undefined)))
                                                                                      (.catch (fn [error]
                                                                                                (js/console.error "[compaction] the summary from the cache failed, pi-durable summarizes:" error)
                                                                                                js/undefined)))))})]}))
