(ns rragent.storrito
  "Storrito as typed tools. The organization's API is a catalog of
   procedures, each with a JSON schema of its input, served by the
   organization's own host at `/documentation/api/v1/catalog.json`.
   Read once when the harness opens, it becomes one tool per procedure,
   named as the procedure, with the procedure's schema as the
   parameters as it is: pi-ai validates and coerces a plain JSON
   schema, no TypeBox needed. A call is the procedure's
   `POST /api/v1/<name>` with the agent's credential as the Bearer
   token, the JSON answer the tool's text. The tools are deferred:
   with dozens of them, the model is offered none until it finds the
   one it needs through `tool_search`; a section of the system prompt
   names them so that it knows what to search for. The catalog lists
   the procedures hidden from the documentation page too, without a
   mark; they are tools like the others.

   `STORRITO_API_URL` is the organization's base URL
   (`https://acme.storrito.com`), `STORRITO_CREDENTIAL` the agent's
   `<id>:<secret>`. Without the URL there are no Storrito tools."
  (:require ["@earendil-works/pi-durable" :refer [defineExtension defineTool section]]
            [clojure.string :as str]))

(def timeout-ms
  120000)

(def retry-statuses
  "A rate limit, or a deploy behind the load balancer: retried, as the
   API documentation asks."
  #{429 502 503 504})

(def retries
  5)

(def max-error-chars
  10000)

(defn config
  "`{:url :credential}` from `STORRITO_API_URL` and `STORRITO_CREDENTIAL`;
   nil without the URL."
  []
  (let [env (.-env js/process)
        url (not-empty (str/replace (str (aget env "STORRITO_API_URL")) #"/+$" ""))]
    (when url
      {:url url
       :credential (not-empty (aget env "STORRITO_CREDENTIAL"))})))

(defn catalog-url
  [url]
  (str url "/documentation/api/v1/catalog.json"))

(defn procedure-url
  [url name]
  (str url "/api/v1/" name))

(defn ^:async catalog
  "The catalog of the organization at `url`, as parsed."
  [url]
  (let [response (await (js/fetch (catalog-url url)
                                  #js {:headers #js {:accept "application/json"}
                                       :signal (js/AbortSignal.timeout timeout-ms)}))]
    (when-not (.-ok response)
      (throw (js/Error. (str "The Storrito catalog at " (catalog-url url) " answered HTTP " (.-status response)))))
    (let [catalog (await (.json response))]
      (when-not (array? (.-procedures catalog))
        (throw (js/Error. (str "No procedures in the Storrito catalog at " (catalog-url url)))))
      catalog)))

(defn description
  "The procedure's doc without its fenced code blocks, which are curl
   examples for the documentation page, as the CLI and the MCP server
   show it."
  [doc]
  (-> (str doc)
      (str/replace #"```[\s\S]*?```" "")
      (str/replace #"\n[ \t]*\n[ \t\n]*" "\n\n")
      (str/trim)))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- retry-delay-ms
  "Two seconds and a bit, so that retries of several agents spread."
  []
  (+ 2000 (rand-int 1000)))

(defn ^:async request
  "One POST of the procedure `name` with `args`: `{:status :text}`."
  [{:keys [url credential]} name args]
  (let [response (await (js/fetch (procedure-url url name)
                                  #js {:method "POST"
                                       :headers #js {:authorization (str "Bearer " credential)
                                                     :content-type "application/json"
                                                     :accept "application/json"}
                                       :body (js/JSON.stringify args)
                                       :signal (js/AbortSignal.timeout timeout-ms)}))]
    {:status (.-status response)
     :text (await (.text response))}))

(defn- error-message
  "What the API answered to a failed call, for the model: a 400 carries
   the schema and what was wrong, a 401 says the credential is wrong."
  [name status text]
  (let [text (str text)]
    (str "Storrito answered HTTP " status " to " name ": "
         (if (< max-error-chars (count text)) (str (subs text 0 max-error-chars) " …") text))))

(defn ^:async call
  "The procedure `name` called with `args` (a JS object): the answer's
   JSON text, or an error with what the API said. A 429, 502, 503 or
   504 is tried again, `retries` times, after `delay-ms` (a test passes
   zero; the default is two seconds and a bit)."
  [{:keys [delay-ms] :as config} name args]
  (loop [attempt 0]
    (let [{:keys [status text]} (await (request config name args))]
      (cond
        (= 200 status)
        text

        (and (contains? retry-statuses status) (< attempt retries))
        (do (await (sleep (or delay-ms (retry-delay-ms))))
            (recur (inc attempt)))

        :else
        (throw (js/Error. (error-message name status text)))))))

(defn tool
  "The deferred tool of one catalog `procedure` (a JS object with
   `name`, `doc` and `inputSchema`)."
  [config procedure]
  (let [name (.-name procedure)]
    (defineTool
     #js {:name name
          :description (description (.-doc procedure))
          :parameters (or (.-inputSchema procedure) #js {:type "object" :properties #js {}})
          :deferred true
          :execute (^:async fn [args _api _call-context]
                     #js {:output #js [#js {:type "text" :text (await (call config name args))}]})})))

(defn section-text
  "What the model is told about the deferred tools: that they exist,
   how to find one, and their names."
  [names]
  (str "The organization's Storrito account (Instagram stories and reels, TikTok posts, drafts, the gallery, "
       "the connected accounts) is reachable through one tool per API procedure. They are not offered until you "
       "load them: search with tool_search for what you need, for example \"schedule instagram story\" or "
       "\"list instagram users\". The procedures: " (str/join ", " names) "."))

(defn ^:async extension
  "The `storrito` extension for `config`: the catalog read, a tool per
   procedure and the section. Nil without a config (no `STORRITO_API_URL`)."
  [config]
  (when config
    (when (empty? (:credential config))
      (throw (js/Error. "STORRITO_API_URL is set but STORRITO_CREDENTIAL is not; the Storrito tools need the agent's credential")))
    (let [procedures (array-seq (.-procedures (await (catalog (:url config)))))
          names (mapv #(.-name %) procedures)]
      (defineExtension #js {:name "storrito"
                            :tools (into-array (map #(tool config %) procedures))
                            :sections #js [(section "storrito" (fn [_input _context] (section-text names)))]}))))
