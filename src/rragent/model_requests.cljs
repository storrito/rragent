(ns rragent.model-requests
  "What rragent does around every chat completion request pi-ai sends
   (the OpenAI SDK uses the global `fetch`, which `install!` wraps):
   it measures the body, warns above `warn-bytes`, and turns an
   answer that says the request was too large into the 413
   `request_too_large` pi-ai knows as a context overflow
   (`isContextOverflow`), so that pi-durable compacts the conversation
   and asks again instead of retrying a hopeless request three times.
   Three answers mean too large: a 413; OpenRouter's 400 `The total
   text input size exceeds 8 MB`; and the Sprites gateway's 502
   `{\"code\":\"internal_error\",...,\"request_id\":null}` without
   OpenRouter's request id, its answer to a body past 8,000,000 bytes
   (seen 2026-10-23, a 413 only between 8,000,000 and some 8,200,000).
   Pictures are what fills a request: `rragent.images` keeps them
   small, this is the net under it.")

(def warn-bytes
  "The body size from which a request is logged, a sign that the
   conversation grows towards the gateway's cap."
  6000000)

(def cap-bytes
  "What the Sprites gateway forwards at most, for the message."
  8000000)

(defn completion-url?
  "Whether `input` of a fetch is a chat completion request."
  [input]
  (let [url (cond
              (string? input) input
              (instance? js/URL input) (.-href input)
              (and input (.-url input)) (.-url input)
              :else "")]
    (boolean (re-find #"/chat/completions(\?|$)" (str url)))))

(defn measure
  "`{:bytes :images}` of a request `body`: its length and how much of
   it is pictures, the data URLs of the image parts."
  [body]
  (let [body (str body)]
    {:bytes (count body)
     :images (transduce (map count) + 0 (re-seq #"data:image/[a-z+.-]+;base64,[A-Za-z0-9+/=]*" body))}))

(defn- mb
  [n]
  (str (.toFixed (/ n 1000000) 1) " MB"))

(defn too-large?
  "Whether an answer of `status` with the body `text` and `headers`
   (a Headers) says the request was too large: a 413; OpenRouter's
   400 for too much text; the Sprites gateway's 502 `internal_error`
   without an `x-request-id`, which every answer OpenRouter made
   carries."
  [status text headers]
  (let [text (str text)]
    (boolean
     (or (= 413 status)
         (and (= 400 status) (re-find #"(?i)input size exceeds" text))
         (and (= 502 status)
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

(defn wrap
  "`fetch` with the measuring and the translation around every chat
   completion request; any other request goes through as it is."
  [fetch]
  (fn [input & [init]]
    (if (and (completion-url? input) init (string? (.-body init)))
      (let [{:keys [bytes images] :as size} (measure (.-body init))]
        (when (< warn-bytes bytes)
          (js/console.warn (str "[model] a request of " (mb bytes) ", " (mb images) " of it pictures: the gateway forwards " (mb cap-bytes) " at most")))
        (.then (fetch input init)
               (fn [response]
                 (let [status (.-status response)]
                   (if (#{400 413 502} status)
                     (.then (.text (.clone response))
                            (fn [text]
                              (if (too-large? status text (.-headers response))
                                (do (js/console.error (str "[model] the request was refused as too large: HTTP " status ", "
                                                           (mb bytes) ", " (mb images) " of it pictures; answered as an overflow, the conversation is compacted"))
                                    (overflow-response status size))
                                response)))
                     response)))))
      (fetch input init))))

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
  (too-large? 502 "{\"code\":\"internal_error\",\"details\":{},\"message\":\"Internal server error.\",\"request_id\":null}" (js/Headers.))
  )
