(ns rragent.model-requests
  "What rragent does around every request pi-ai sends to a model, a
   chat completion (`/chat/completions`, the OpenAI SDK) or a message
   the Anthropic way (`/v1/messages`, the Anthropic SDK, which pi-ai's
   catalog names for most of OpenRouter's Claude models, the default
   model among them); both SDKs use the global `fetch`, which
   `install!` wraps. It measures the body, warns above `warn-bytes`,
   and turns an answer that says the request was too large into the
   413 `request_too_large` pi-ai knows as a context overflow
   (`isContextOverflow`). Neither SDK's wording of the gateway's own
   answers is one to pi-ai: its 413,
   `{\"error\":\"request body too large\"}`, would end the turn as it
   is, and its 502 be retried three times, a hopeless request each.
   On an overflow pi-durable compacts the conversation and asks again,
   once: a request still refused after that ends the turn with the
   overflow's words, as does one of a conversation with nothing to
   cut. A compaction keeps the last messages whole, so pictures of
   megabytes in them stay too large, at every message again.
   Three answers mean too large: a 413; OpenRouter's 400 `The total
   text input size exceeds 8 MB`; and the Sprites gateway's 502
   `{\"code\":\"internal_error\",...,\"request_id\":null}` without
   OpenRouter's request id, its answer to a body past 8,000,000 bytes
   (seen 2026-10-23, a 413 only between 8,000,000 and some 8,200,000).
   That 502 says nothing of the size, and the gateway may answer it
   for reasons of its own, so it counts for a request above
   `cap-bytes` only, which the gateway does not forward: a smaller one
   goes back as it is, for the retry.
   Pictures are what fills a request: `rragent.images` keeps them
   small, this is the net under it. The last chat completion that
   carried a provider session's id, a conversation's, is kept
   (`last-request`), for a summary that asks the same again and reads
   it from the provider's cache (`rragent.warm-summary`). A message
   the Anthropic way is not kept, since the summary asks a chat
   completion, and has what its session kept forgotten (`forget!`):
   that is no longer the conversation's last request. The stream of a
   chat completion's answer is split for OpenRouter's charge in it
   (`rragent.costs/tee`, which reads a chat completion's chunks),
   unless its status is 400, 413 or 502: those are read for a refusal
   and handed on whole."
  (:require [rragent.costs :as costs]))

(def warn-bytes
  "The body size above which a request is logged, a sign that the
   conversation grows towards the gateway's cap."
  6000000)

(def cap-bytes
  "What the Sprites gateway forwards at most: said in the message of
   an overflow, and the size above which its 502 is read as the
   refusal of a request too large (`too-large?`)."
  8000000)

(defn- url-of
  "The address of a fetch's `input`: a string, a URL or a Request."
  [input]
  (str (cond
         (string? input) input
         (instance? js/URL input) (.-href input)
         (and input (.-url input)) (.-url input)
         :else "")))

(defn kind
  "What request to a model `input` of a fetch is: `:completion`, a
   chat completion; `:messages`, a message the Anthropic way
   (`/v1/messages`, not what is below it, as `count_tokens`); nil for
   any other request."
  [input]
  (let [url (url-of input)]
    (cond
      (re-find #"/chat/completions(\?|$)" url) :completion
      (re-find #"/v1/messages(\?|$)" url) :messages)))

(defn measure
  "`{:bytes :images}` of a request `body`: its size as it goes out,
   the bytes of its UTF-8 (a text that is not ASCII is more bytes than
   characters), and how much of it is pictures, the data URLs of a
   chat completion's image parts and the base64 of a message's the
   Anthropic way."
  [body]
  (let [body (str body)]
    {:bytes (js/Buffer.byteLength body "utf8")
     :images (+ (transduce (map count) + 0 (re-seq #"data:image/[a-z+.-]+;base64,[A-Za-z0-9+/=]*" body))
                (transduce (map (comp count second)) + 0
                           (re-seq #"\"type\":\"base64\",\"media_type\":\"image/[a-z+.-]+\",\"data\":\"([A-Za-z0-9+/=]*)\"" body)))}))

(defn- mb
  [n]
  (str (.toFixed (/ n 1000000) 1) " MB"))

(defn too-large?
  "Whether an answer of `status` with the body `text` and `headers`
   (a Headers) to a request of `bytes` says the request was too large:
   a 413; OpenRouter's 400 for too much text; the Sprites gateway's
   502 `internal_error` without an `x-request-id`, which every answer
   OpenRouter made carries, to a request above `cap-bytes`. The 502
   names no reason, so the size of the request decides: one the
   gateway forwards was not refused for its size and is worth the
   retry, which an overflow never gets."
  [status text headers bytes]
  (let [text (str text)]
    (boolean
     (or (= 413 status)
         (and (= 400 status) (re-find #"(?i)input size exceeds" text))
         (and (= 502 status)
              (< cap-bytes bytes)
              (re-find #"\"code\":\"internal_error\"" text)
              (not (some-> headers (.get "x-request-id"))))))))

(defn overflow-response
  "The 413 given to pi-ai in place of the answer: `request_too_large`
   in its body, pi-ai's pattern for a request over the byte limit,
   with the sizes for the transcript."
  [status {:keys [bytes images]}]
  (js/Response. (js/JSON.stringify #js {:error #js {:type "request_too_large"
                                                    :message (str "The request to the model was too large: " (mb bytes)
                                                                  ", " (mb images) " of it pictures; the gateway forwards "
                                                                  (mb cap-bytes) " at most (it answered HTTP " status ").")}})
                #js {:status 413
                     :headers #js {"content-type" "application/json"}}))

(def kept-sessions
  "How many sessions' last requests are kept: up to the gateway's
   8 MB each."
  8)

(defonce last-requests
  ;; the last chat completion request by provider session:
  ;; {session {:url :headers :body :at}}
  (atom {}))

(defn session-of
  "The provider session of a request with `headers` (a plain object)
   and `body`: OpenRouter's `x-session-id`, else OpenAI's
   `prompt_cache_key`; nil for none."
  [headers body]
  (or (some-> headers (aget "x-session-id") str not-empty)
      (some-> (re-find #"\"prompt_cache_key\":\"([^\"]+)\"" (str body)) second)))

(defn plain-headers
  "The headers of a fetch's `init` as a plain object, lower-case names."
  [^js init]
  (let [headers (js/Headers. (or (.-headers init) #js {}))
        out #js {}]
    (.forEach headers (fn [v k] (aset out k v)))
    out))

(defn remember!
  "The request `{:url :headers :body}` of `session` as its last, the
   oldest session forgotten past `kept-sessions`."
  [session request now]
  (swap! last-requests
         (fn [requests]
           (let [requests (assoc requests session (assoc request :at now))]
             (if (< kept-sessions (count requests))
               (dissoc requests (key (apply min-key (comp :at val) requests)))
               requests)))))

(defn last-request
  "The last request of the provider session `session`, nil for none."
  [session]
  (get @last-requests session))

(defn forget!
  "The kept request of `session` forgotten: a request of the
   conversation went out since that is not kept, so the kept one is
   no longer its last. Without it the summary of a bot on Claude
   would be asked of whatever chat completion last carried its
   session: the correction of a dictation (`rragent.correction`, a
   small model with the end of the conversation), or the last request
   of a GPT model the conversation was switched from, neither of
   which knows the conversation as it is. What is left, on any
   model: a correction that is the last request with the session's id
   before the summary is asked."
  [session]
  (swap! last-requests dissoc session))

(defn wrap
  "`fetch` with the measuring and the translation around every request
   to a model (`kind`). A chat completion is remembered as its
   session's last (`remember!`), and its answer split for the charge
   unless its status is 400, 413 or 502; a message the Anthropic way
   has its session's kept request forgotten (`forget!`) and its answer
   handed on as it came. Any other request goes through as it is."
  [fetch]
  (fn [input & [init]]
    (let [what (kind input)]
      (if (and what init (string? (.-body init)))
        (let [{:keys [bytes images] :as size} (measure (.-body init))
              headers (plain-headers init)
              session (session-of headers (.-body init))]
          (when session
            (if (= :completion what)
              (remember! session {:url (url-of input)
                                  :headers headers
                                  :body (.-body init)}
                         (js/Date.now))
              (forget! session)))
          (when (< warn-bytes bytes)
            (js/console.warn (str "[model] a request of " (mb bytes) ", " (mb images) " of it pictures: the gateway forwards " (mb cap-bytes) " at most")))
          (.then (fetch input init)
                 (fn [response]
                   (let [status (.-status response)]
                     (if (#{400 413 502} status)
                       (.then (.text (.clone response))
                              (fn [text]
                                (if (too-large? status text (.-headers response) bytes)
                                  (do (js/console.error (str "[model] the request was refused as too large: HTTP " status ", "
                                                             (mb bytes) ", " (mb images) " of it pictures; answered as an overflow, for a compaction"))
                                      (overflow-response status size))
                                  response)))
                       (if (= :completion what) (costs/tee response) response))))))
        (fetch input init)))))

(def original-fetch
  "The global `fetch` as it was when this namespace loaded, before
   `install!` wrapped it; what a test wraps itself. (A local named
   `fetch` would shadow the global in the compiled JS.)"
  (.-fetch js/globalThis))

(defonce ^:private installed
  (atom false))

(defn install!
  "The global `fetch` wrapped, once per process."
  []
  (when-not @installed
    (reset! installed true)
    (set! (.-fetch js/globalThis) (wrap (fn [input init] (original-fetch input init))))))

(comment
  (measure "{\"messages\":[{\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}]}")
  (too-large? 502 "{\"code\":\"internal_error\",\"details\":{},\"message\":\"Internal server error.\",\"request_id\":null}" (js/Headers.) 8300000)
  )
