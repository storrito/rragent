(ns rragent.storrito
  "Storrito's API in the REPL. The organization's API is a catalog of
   procedures, each with a JSON schema of its input, at
   `/documentation/api/v1/catalog.json` of the organization's own
   host, public. Storrito is a `custom_api` connection of the gateway
   whose base is the organization's host (`acme.storrito.com`, or
   `.../api/v1` of it): found among the custom API connections by the
   catalog at its base, or by a note of the connection that carries
   the catalog's link. At discovery the catalog becomes one function
   per procedure, `storrito/<name>`, with a docstring from the
   procedure's doc and schema, each a `POST .../api/v1/<name>` through
   the connection with the arguments as JSON and the answer as data
   (`rragent.codemode`). The catalog lists the procedures hidden from
   the documentation page too, without a mark; they are functions
   like the others."
  (:require [clojure.string :as str]
            [rragent.composio :as composio]
            [rragent.gateway :as gateway]
            [rragent.sync-fetch :as sync-fetch]))

(def retry
  "A rate limit, or a deploy behind the load balancer: tried again, as
   the API documentation asks."
  {:statuses #{429 502 503 504} :times 5 :delay-ms 2000})

(def catalog-timeout-ms
  30000)

(def catalog-path
  "/documentation/api/v1/catalog.json")

(def max-error-chars
  10000)

(defn candidate?
  "A custom API connection that is not Composio: it may be Storrito."
  [connection]
  (and (= "custom_api" (:provider connection))
       (not (composio/connection? connection))))

(defn api-root
  "The organization's host and the prefix of the procedures from a
   connection's base, which is the host or `/api/v1` of it:
   `{:host :prefix}`; nil without a base."
  [connection]
  (when-let [base (some-> (gateway/base-api-url connection) gateway/trim-slash)]
    (if (str/ends-with? base "/api/v1")
      {:host (subs base 0 (- (count base) (count "/api/v1"))) :prefix ""}
      {:host base :prefix "/api/v1"})))

(defn storrito-host?
  [connection]
  (str/ends-with? (str (gateway/host (gateway/base-api-url connection))) "storrito.com"))

(defn note-link
  "A catalog link in the body of a note of the connection; nil
   without."
  [connection]
  (some #(re-find #"https?://[^\s\"'<>]+catalog\.json" (str (:body %))) (:notes connection)))

(defn catalog-link
  "Where the connection's catalog would be: the link of a note, else
   the catalog path at the connection's host; nil without either."
  [connection]
  (or (note-link connection)
      (some-> (api-root connection) :host (str catalog-path))))

(defn catalog
  "The catalog at `url`, read now (blocking): `{:procedures [...]}`,
   keywordized; a throw when it is no catalog."
  [url]
  (let [{:keys [status text]} (sync-fetch/request {:url url
                                                   :headers {"accept" "application/json"}
                                                   :timeout-ms catalog-timeout-ms})]
    (when-not (= 200 status)
      (throw (js/Error. (str "The Storrito catalog at " url " answered HTTP " status))))
    (let [catalog (try (js->clj (js/JSON.parse text) :keywordize-keys true) (catch :default _ nil))]
      (when-not (sequential? (:procedures catalog))
        (throw (js/Error. (str "No procedures in the Storrito catalog at " url))))
      catalog)))

(defn find-catalog
  "Storrito among the connections of `discovery`: the first custom API
   connection whose catalog can be read, `{:connection :link
   :catalog}`; without one, `{:link :error}` of a connection that
   looks like Storrito (its host, or a note with the link) whose
   catalog could not be read, so that `procedures` says so; nil when
   nothing points at Storrito."
  [discovery]
  (loop [[connection & more] (filter candidate? (:connections discovery))
         failed nil]
    (if-not connection
      failed
      (let [link (catalog-link connection)
            read (when link
                   (try
                     {:connection connection :link link :catalog (catalog link)}
                     (catch :default e
                       {:link link :error (.-message e)})))]
        (cond
          (:catalog read) read
          (and read (or (storrito-host? connection) (note-link connection))) (recur more (or failed read))
          :else (recur more failed))))))

(defn description
  "The procedure's doc without its fenced code blocks, which are curl
   examples for the documentation page, as the CLI and the MCP server
   show it."
  [doc]
  (-> (str doc)
      (str/replace #"```[\s\S]*?```" "")
      (str/replace #"\n[ \t]*\n[ \t\n]*" "\n\n")
      (str/trim)))

(defn- type-name
  [type]
  (cond
    (string? type) type
    (sequential? type) (str/join " or " type)
    :else nil))

(defn- parameter-line
  [[k {:keys [type description]}] required]
  (let [n (name k)]
    (str "  " n
         (when-let [t (type-name type)] (str " (" t ")"))
         (when (contains? required n) ", required")
         (when (seq (str description)) (str ": " description)))))

(defn docstring
  "The function's docstring from the procedure: its doc without the
   curl examples, and its parameters from the schema."
  [{:keys [doc inputSchema]}]
  (let [properties (:properties inputSchema)
        required (set (:required inputSchema))]
    (str (description doc)
         (if (seq properties)
           (str "\n\nParameters, a map:\n" (str/join "\n" (map #(parameter-line % required) properties)))
           "\n\nTakes no parameters."))))

(defn procedure-path
  "The path of the procedure at the connection's base."
  [connection name]
  (str (:prefix (api-root connection)) "/" name))

(defn- error-message
  "What the API answered to a failed call, for the model: a 400 carries
   the schema and what was wrong, a 401 says the credential is wrong."
  [name status body]
  (let [text (if (string? body) body (js/JSON.stringify (clj->js body)))]
    (str "Storrito answered HTTP " status " to " name ": "
         (if (< max-error-chars (count text)) (str (subs text 0 max-error-chars) " …") text))))

(defn call
  "The procedure `name` called with `args` (a map) through `call` (the
   REPL's) and the `connection`: the answer as data; a throw with what
   the API said to a failed call. A 429, 502, 503 or 504 is tried
   again (`retry`)."
  [call connection name args]
  (let [{:keys [status body]} (call (:id connection) (procedure-path connection name) {:method "POST" :body (or args {}) :retry retry})]
    (if (= 200 status)
      body
      (throw (ex-info (error-message name status body) {:status status :body body})))))

(defn summary
  "`{:name :doc}` per procedure, the doc's first line, for
   `(storrito/procedures)`."
  [catalog]
  (mapv (fn [{:keys [name doc]}]
          {:name name :doc (first (str/split-lines (description doc)))})
        (:procedures catalog)))
