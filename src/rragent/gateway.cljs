(ns rragent.gateway
  "The Sprites gateway (sprites.dev connectors). Every credential of
   the agent lives in a connector of the organization, never on the
   machine: a call to `<gateway>/v1/gateway/<provider>/<connection id>/<path>`
   from inside the sprite is forwarded to the provider with the
   credential added, the gateway knowing the sprite by the request.
   `GET <gateway>/v1/gateway/list` answers `{\"connections\": [...],
   \"available\": [...]}`: the connections the sprite may use, each
   with its `id`, `provider`, `gateway_base_url`, `notes` and
   `provider_info` (a `custom_api` connection says its
   `base_api_url` there), and the providers that could be set up. The
   gateway is `RRAGENT_GATEWAY`, `http://sprite.internal` by default,
   which answers only inside a sprite: elsewhere the list is empty and
   says why, and the agent runs with what the environment holds. The
   requests block (`rragent.sync-fetch`), so that a program of the
   REPL makes them in the middle of a loop."
  (:require [clojure.string :as str]
            [rragent.sync-fetch :as sync-fetch]))

(def default-base
  "http://sprite.internal")

(def list-timeout-ms
  5000)

(def call-timeout-ms
  120000)

(defn trim-slash
  [url]
  (str/replace (str url) #"/+$" ""))

(defn base
  "The gateway's base URL: `given`, else `RRAGENT_GATEWAY`, else the
   default; without a trailing slash."
  [given]
  (trim-slash (or (not-empty given)
                  (not-empty (aget (.-env js/process) "RRAGENT_GATEWAY"))
                  default-base)))

(defn list-url
  [base]
  (str base "/v1/gateway/list"))

(defn parse
  "`text` as data when it is JSON, keywordized; the text itself when
   it is not."
  [text]
  (try
    (js->clj (js/JSON.parse text) :keywordize-keys true)
    (catch :default _ text)))

(defn- listing
  [base]
  (try
    (let [{:keys [status text]} (sync-fetch/request {:url (list-url base) :timeout-ms list-timeout-ms})
          body (parse text)]
      (cond
        (not= 200 status) {:error (str "HTTP " status " from " (list-url base))}
        (not (map? body)) {:error (str "no list from " (list-url base))}
        :else {:connections (vec (:connections body))
               :available (vec (:available body))}))
    (catch :default e
      {:error (.-message e)})))

(defn discover
  "The connectors of the gateway at `base`, read now: `{:gateway
   :connections [...] :available [...]}`, each entry as the gateway
   lists it, keywordized; with an `:error` and empty lists when the
   gateway does not answer (not inside a sprite) or answers no list."
  [base]
  (merge {:gateway base :connections [] :available []}
         (listing base)))

(defn host
  "The host of `url`; nil for no URL."
  [url]
  (try
    (.-hostname (js/URL. (str url)))
    (catch :default _ nil)))

(defn base-api-url
  "Where a `custom_api` connection goes, as its provider info says."
  [connection]
  (not-empty (str (get-in connection [:provider_info :base_api_url]))))

(defn describe
  "A connection in a few words: its provider and id, the host of a
   custom API."
  [connection]
  (str (:provider connection) " " (:id connection)
       (when-let [h (host (base-api-url connection))]
         (str " (" h ")"))))

(defn connections-of
  "The connections of the provider `provider`."
  [{:keys [connections]} provider]
  (filterv #(= provider (:provider %)) connections))

(defn connection
  "The connection `which` names: a connection id, or a provider name
   where one connection has it; nil for none. Several connections of
   the provider are a throw that names them, so that the program says
   which."
  [{:keys [connections] :as discovery} which]
  (or (some #(when (= which (:id %)) %) connections)
      (let [found (connections-of discovery which)]
        (when (< 1 (count found))
          (throw (js/Error. (str (count found) " connections have the provider " (pr-str which) ", name one by its id: "
                                 (str/join ", " (map describe found))))))
        (first found))))

(defn connection-base
  "Where the connection's requests go: its `gateway_base_url`, or the
   gateway's path for it."
  [{:keys [gateway]} connection]
  (trim-slash (or (not-empty (:gateway_base_url connection))
                  (str gateway "/v1/gateway/" (:provider connection) "/" (:id connection)))))

(defn no-connector-message
  "Why there is no `which` to call, with what there is instead."
  [{:keys [connections available error]} which]
  (str "No connection " (pr-str which) ". "
       (if (seq connections)
         (str "Connected: " (str/join ", " (map describe connections)) ". ")
         "Nothing is connected. ")
       (when-let [entry (some #(when (= which (:provider %)) %) available)]
         (str (or (:display_name entry) which) " is available, the person can set it up in the Sprites dashboard. "))
       (when error
         (str "The gateway did not answer: " error))))

(defn- key-name
  [k]
  (if (keyword? k) (name k) (str k)))

(defn- query-string
  [query]
  (when (seq query)
    (let [params (js/URLSearchParams.)]
      (doseq [[k v] query]
        (.append params (key-name k) (str v)))
      (str "?" (.toString params)))))

(defn- request-body
  [body]
  (cond
    (nil? body) nil
    (string? body) body
    :else (js/JSON.stringify (clj->js body))))

(defn- request-headers
  "The headers lower-cased, `content-type` JSON for a body that is
   data unless given."
  [headers body]
  (let [headers (into {} (map (fn [[k v]] [(str/lower-case (key-name k)) (str v)])) headers)]
    (cond-> headers
      (and (some? body) (not (string? body)) (not (contains? headers "content-type")))
      (assoc "content-type" "application/json"))))

(defn url
  "The URL of `path` at the connection's base; the path with a slash
   in front when it has none, the query appended."
  [discovery connection path query]
  (let [path (str path)]
    (str (connection-base discovery connection)
         (if (str/starts-with? path "/") path (str "/" path))
         (query-string query))))

(defn call
  "One request through the gateway to the connection `which` (an id,
   or the provider name of a single connection of `discovery`) at
   `path`: `{:status :headers :body}`, the body as data when it is
   JSON, else as text. `opts`: `:method` (GET), `:body` (data goes as
   JSON, a string as it is), `:headers`, `:query` (a map, appended to
   the URL), `:retry` (as `rragent.sync-fetch/request` takes it). A
   throw with the reason when there is no such connection or the
   request failed."
  [discovery which path {:keys [method body headers query retry]}]
  (let [connection (or (connection discovery which)
                       (throw (js/Error. (no-connector-message discovery which))))
        {:keys [status headers text]} (sync-fetch/request {:url (url discovery connection path query)
                                                           :method (str/upper-case (key-name (or method "GET")))
                                                           :headers (request-headers headers body)
                                                           :body (request-body body)
                                                           :timeout-ms call-timeout-ms
                                                           :retry retry})]
    {:status status :headers headers :body (parse text)}))
