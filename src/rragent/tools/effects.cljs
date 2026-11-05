(ns rragent.tools.effects
  "The tools that run a plan's effects (`rragent.plan`): what a program
   of the REPL answered as data instead of doing. Only tools call
   them, never the model (`callers [\"tools\"]`): the eval tool runs
   each as a nested call under the effect's key, so that a plan a
   restart interrupted finds the effects done and does not run them
   again (`rragent.tools.eval`). Their value is for the program's
   answer; a failed one is an error result whose value says why.
   `storrito` a command of the organization's API through its
   connection of the gateway (`rragent.storrito/call!`); `gateway` a
   request through any connection, as `(call ...)` makes it
   (`rragent.gateway/fetch!`); `composio-execute`,
   `composio-connections`, `composio-add-trigger`,
   `composio-delete-trigger` what the composio/ functions of the REPL
   would do (`rragent.composio`), through the Composio connection and
   the agent's session; `mcp` a tool of an MCP server of the machine
   (`rragent.mcp/call!`), its pictures in the value for the model.
   `remember` is the bot's own tool (`rragent.tools.events`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.codemode :as codemode]
            [rragent.composio :as composio]
            [rragent.events :as events]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.storrito :as storrito]
            [rragent.tools.events :as events-tool]))

(def names
  ["storrito" "gateway" "composio-execute" "composio-connections" "composio-add-trigger" "composio-delete-trigger" "mcp"])

(defn- answer
  "A result whose value is `data`, for the program's answer."
  [data]
  #js {:output #js [#js {:type "text" :text (pr-str data)}]
       :structuredOutput (clj->js data)})

(defn- failure
  "An error result whose value says why."
  [message & [more]]
  #js {:output #js [#js {:type "text" :text (str message)}]
       :structuredOutput (clj->js (merge more {:error (str message)}))
       :isError true})

(defn- effect-tool
  "A tool of `name` over `parameters` whose `run` (an async function
   of the arguments as data, the call's api and context) answers the
   value; a throw is the failure, with the status and body of a
   failed request."
  [name description parameters run]
  (defineTool
   #js {:name name
        :description description
        :callers #js ["tools"]
        :parameters parameters
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args api call-context]
                   (try
                     (answer (await (run (js->clj args :keywordize-keys true) api call-context)))
                     (catch :default e
                       (let [{:keys [status body]} (ex-data e)]
                         (failure (ex-message e) (when status {:status status :body body}))))))}))

(defn- connection!
  "The Storrito connection of `codemode`, as it was found; a throw
   without one."
  [codemode]
  (let [{:keys [connection catalog link error]} (codemode/storrito codemode)]
    (cond
      (and connection catalog) connection
      error (throw (js/Error. (str "The Storrito catalog at " link " could not be read: " error)))
      :else (throw (js/Error. "No Storrito among the connections: no custom_api connection of the gateway has a catalog at its base.")))))

(defn storrito
  "The `storrito` tool: the procedure called with its arguments."
  [codemode]
  (effect-tool "storrito" "One command of the organization's Storrito API, run for an effect of a plan."
               (.Object Type #js {:procedure (.String Type) :args (.Optional Type (.Any Type))})
               (^:async fn [{:keys [procedure args]} _ _]
                 (await (storrito/call! (codemode/discovery codemode) (connection! codemode) procedure (or args {}))))))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async request
  "One request through the gateway of `codemode` to the connection
   `which` at `path` with `opts`, tried again as `:retry` of the opts
   says: `{:status :body}`."
  [codemode which path opts]
  (let [{:keys [statuses times delay-ms]} (:retry opts)]
    (loop [tries 1]
      (let [reply (await (gateway/fetch! (codemode/discovery codemode) which path (dissoc opts :retry)))]
        (if (and (contains? (set statuses) (:status reply)) (< tries (or times 1)))
          (do (await (sleep (or delay-ms 1000)))
              (recur (inc tries)))
          reply)))))

(defn gateway-tool
  "The `gateway` tool: a request through a connection, as `(call ...)`
   makes it, its `{:status :body}` the value."
  [codemode]
  (effect-tool "gateway" "One request through a connection of the Sprites gateway, run for an effect of a plan."
               (.Object Type #js {:which (.String Type) :path (.String Type) :opts (.Optional Type (.Any Type))})
               (^:async fn [{:keys [which path opts]} _ _]
                 (await (request codemode which path (or opts {}))))))

(defn- ^:async meta-call!
  "The meta tool `slug` run with `args` in the session of the
   environment, through the Composio connection, awaited; the result
   as `rragent.composio/result` reads it."
  [codemode slug args]
  (let [discovery (codemode/discovery codemode)
        connection (composio/connection! discovery)
        session (composio/session-id!)]
    (composio/result slug (await (gateway/fetch! discovery (:id connection) (composio/meta-path session)
                                                 {:method "POST" :body {:slug slug :arguments args}})))))

(defn- ^:async rest-call!
  [codemode method path opts]
  (let [discovery (codemode/discovery codemode)
        connection (composio/connection! discovery)
        {:keys [status body]} (await (gateway/fetch! discovery (:id connection) path (assoc opts :method method)))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Composio answered HTTP " status " to " method " " path ": "
                           (or (get-in body [:error :message]) (if (string? body) body (js/JSON.stringify (clj->js body)))))
                      {:status status :body body})))
    body))

(defn composio-execute
  "The `composio-execute` tool: one tool found with search run with
   its arguments, `session` the search session the program made."
  [codemode]
  (effect-tool "composio-execute" "One tool of the organization's services run at Composio, for an effect of a plan."
               (.Object Type #js {:slug (.String Type) :args (.Optional Type (.Any Type)) :session (.Optional Type (.String Type))})
               (^:async fn [{:keys [slug args session]} _ _]
                 (await (meta-call! codemode "COMPOSIO_MULTI_EXECUTE_TOOL" (composio/execute-arguments slug args session))))))

(defn composio-connections
  "The `composio-connections` tool: the connections of services by
   toolkit, a missing one started."
  [codemode]
  (effect-tool "composio-connections" "The connections of the organization's services at Composio, a missing one started, for an effect of a plan."
               (.Object Type #js {:toolkits (.Array Type (.String Type)) :session (.Optional Type (.String Type))})
               (^:async fn [{:keys [toolkits session]} _ _]
                 (await (meta-call! codemode "COMPOSIO_MANAGE_CONNECTIONS" (composio/connections-arguments toolkits session))))))

(defn composio-add-trigger
  "The `composio-add-trigger` tool: the trigger subscribed, the fact
   noted in the bot's events so that its events find the bot
   (`rragent.composio-events`); answers its id."
  [codemode]
  (effect-tool "composio-add-trigger" "A trigger of a service subscribed at Composio, for an effect of a plan."
               (.Object Type #js {:slug (.String Type) :account (.String Type) :config (.Optional Type (.Any Type))})
               (^:async fn [{:keys [slug account config]} api call-context]
                 (let [body (await (rest-call! codemode "POST" (composio/upsert-trigger-path slug)
                                               {:body (composio/upsert-trigger-body account (composio/user-id!) config)}))
                       id (or (:trigger_id body) (:id body))]
                   (events/append! (await (events-tool/file api call-context)) composio/trigger-fact {:id id :slug (str slug) :account (str account)} (js/Date.now))
                   {:id id :slug slug :account account}))))

(defn composio-delete-trigger
  "The `composio-delete-trigger` tool: the trigger deleted, the fact
   noted."
  [codemode]
  (effect-tool "composio-delete-trigger" "A trigger deleted at Composio, for an effect of a plan."
               (.Object Type #js {:id (.String Type)})
               (^:async fn [{:keys [id]} api call-context]
                 (await (rest-call! codemode "DELETE" (composio/delete-trigger-path id) {}))
                 (events/append! (await (events-tool/file api call-context)) composio/trigger-deleted-fact {:id (str id)} (js/Date.now))
                 {:id id :deleted true})))

(defn mcp-tool
  "The `mcp` tool: the tool `tool` of the server `server` called with
   `args`; `{:value :images}`, the pictures `{:data :mime-type}`
   each."
  [codemode]
  (effect-tool "mcp" "One tool of an MCP server of the machine, for an effect of a plan."
               (.Object Type #js {:server (.String Type) :tool (.String Type) :args (.Optional Type (.Any Type))})
               (^:async fn [{:keys [server tool args]} _ call-context]
                 (let [registry (:mcp codemode)]
                   (when-not registry
                     (throw (js/Error. "There are no MCP servers here.")))
                   (let [{:keys [value images error]} (await (mcp/call! registry server tool (or args {}) {:signal (.-abortSignal call-context)}))]
                     (when error
                       (throw (js/Error. error)))
                     {:value value :images (vec images)})))))

(defn tools
  "All of them, over the REPL `codemode`."
  [codemode]
  [(storrito codemode) (gateway-tool codemode) (composio-execute codemode) (composio-connections codemode)
   (composio-add-trigger codemode) (composio-delete-trigger codemode) (mcp-tool codemode)])
