(ns rragent.openrouter
  "Where a call to OpenRouter goes, one answer for the models
   (`rragent.harness`), dictation (`rragent.dictation`) and the media
   tools (`rragent.tools.media`): through the gateway's `openrouter`
   connection when the REPL `codemode` knows one, the connection's
   base and `/v1` with no Authorization header, since the gateway adds
   the credential; else to OpenRouter itself, `OPENROUTER_BASE_URL`
   (`openrouter.ai/api/v1` by default) with `OPENROUTER_API_KEY` as
   the bearer, the laptop's way; else nowhere, which the caller says
   with what it wanted. The discovery at start may have found no
   gateway, or no such connection (the gateway of a fresh sprite comes
   up after the service), so nothing is fixed: every call looks at
   the discovery as it is and, while no connection is known, asks the
   gateway again, one blocking request (`rragent.codemode/connectors`),
   at most every ten seconds; what it lists is the REPL's from then
   on. Nothing else reads the two variables."
  (:require [rragent.codemode :as codemode]
            [rragent.gateway :as gateway]))

(def default-base-url
  "OpenRouter's own API."
  "https://openrouter.ai/api/v1")

(def probe-interval-ms
  "How long a call waits before it asks the gateway again for an
   `openrouter` connection it has not found: the gateway of a fresh
   sprite comes up seconds after the service, and the first call after
   that should go through it, without a request before every call
   meanwhile."
  10000)

(defn gateway-base-url
  "Where OpenRouter's API is behind the gateway's `connection`: its
   base and `/v1`, as `openrouter.ai/api/v1` is behind OpenRouter's
   host."
  [discovery connection]
  (str (gateway/connection-base discovery connection) "/v1"))

(defn known-connection
  "The gateway's `openrouter` connection as the REPL `codemode` has
   it; nil while it has none."
  [codemode]
  (gateway/connection (codemode/discovery codemode) "openrouter"))

(defn connection
  "The gateway's `openrouter` connection: the one the REPL `codemode`
   knows, else the gateway asked again, one request, when the last
   asking (`rragent.codemode/asked-at`) is `interval-ms`
   (`probe-interval-ms` without) ago; what it lists is the REPL's from
   then on (`rragent.codemode/connectors`)."
  [codemode & [interval-ms]]
  (or (known-connection codemode)
      (when (<= (or interval-ms probe-interval-ms) (- (js/Date.now) (codemode/asked-at codemode)))
        (gateway/connection (codemode/connectors codemode) "openrouter"))))

(defn- env
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn environment-base-url
  "OpenRouter itself, or what `OPENROUTER_BASE_URL` points at (a test's
   fake); without a trailing slash."
  []
  (gateway/trim-slash (or (env "OPENROUTER_BASE_URL") default-base-url)))

(defn route
  "Where a call to OpenRouter goes, `{:base-url :headers :source}`:
   the gateway's `openrouter` connection (`connection`), no header,
   `:gateway`; else OpenRouter itself with the key of the environment
   as the bearer, `:environment`; nil when there is neither.
   `interval-ms` is how often the gateway is asked again, for a test."
  [codemode & [interval-ms]]
  (if-let [connection (connection codemode interval-ms)]
    {:base-url (gateway-base-url (codemode/discovery codemode) connection)
     :headers {}
     :source :gateway}
    (when-let [key (env "OPENROUTER_API_KEY")]
      {:base-url (environment-base-url)
       :headers {"Authorization" (str "Bearer " key)}
       :source :environment})))

(defn no-route-message
  "Why there is no OpenRouter for `what` (\"dictation\", \"picture\")."
  [what]
  (str "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no " what "."))

(defn route!
  "`route`, or a throw that says why there is none, for the caller that
   wanted `what`."
  [codemode what & [interval-ms]]
  (or (route codemode interval-ms)
      (throw (js/Error. (no-route-message what)))))

(defn report
  "One line for the console at start: where the requests to OpenRouter
   go, the models', dictation's and the media tools', as `discovery`
   has it."
  [discovery]
  (if-let [connection (gateway/connection discovery "openrouter")]
    (str "OpenRouter through the Sprites gateway (" (gateway-base-url discovery connection) ")")
    "OpenRouter with OPENROUTER_API_KEY; the gateway is asked again for an openrouter connection before a model, dictation or media call"))
