(ns rragent.composio
  "The organization's third-party services through Composio
   (composio.dev): Google Drive, Gmail, Notion, Slack and the like,
   connected through Composio's managed OAuth. The agent reaches them
   through a tool-router session and the session's four meta tools,
   named here as Composio names them: `COMPOSIO_SEARCH_TOOLS` finds a
   service's tools for a use case, `COMPOSIO_GET_TOOL_SCHEMAS` fetches
   their schemas, `COMPOSIO_MULTI_EXECUTE_TOOL` runs them,
   `COMPOSIO_MANAGE_CONNECTIONS` connects an account. Each is one
   `POST /api/v3/tool_router/session/<id>/execute_meta` through the
   gateway's connection to Composio: a `custom_api` connection whose
   base is `backend.composio.dev`, which adds the key of the agent's
   own Composio project. The session is the agent's own: made when
   the agent starts (`rragent.composio-setup`) for the agent's user
   id, the machine's `agent:` label, or taken from
   `COMPOSIO_SESSION_ID` when the environment names one; both live in
   the environment of the process (`set-session!`, `set-user-id!`),
   where the REPL's worker reads them per program
   (`rragent.codemode-worker/environment`). The agent's triggers and
   connected accounts are its own too, managed through the same
   connection with Composio's REST (`rest-call`, the paths and bodies
   below); the events of its triggers come straight to its page
   (`rragent.composio-events`). In the REPL the functions are
   `composio/search`, `composio/schemas`, `composio/execute`,
   `composio/connections`, `composio/triggers`,
   `composio/trigger-types`, `composio/add-trigger`,
   `composio/delete-trigger` and `composio/accounts`
   (`rragent.codemode`)."
  (:require [rragent.gateway :as gateway]))

(def host
  "backend.composio.dev")

(defn connection?
  "Whether a connection of the gateway is Composio."
  [connection]
  (and (= "custom_api" (:provider connection))
       (= host (gateway/host (gateway/base-api-url connection)))))

(defn connection
  "The gateway's connection to Composio; nil without."
  [{:keys [connections]}]
  (some #(when (connection? %) %) connections))

(defn connection!
  [discovery]
  (or (connection discovery)
      (throw (js/Error. (str "No connection to Composio: no custom_api connection of the gateway goes to " host ".")))))

(def session-path
  "Where a tool-router session is made."
  "/api/v3.1/tool_router/session")

(def session-config
  "How the agent's session is made: no Instant tools (they charge the
   project's Composio balance), no workbench sandbox and no raw proxy
   (the agent gets the services' tools, nothing more), the connection
   manager on, so that the agent connects an account in the chat."
  {:instant false
   :workbench {:enable false}
   :proxy_execute {:enable false}
   :manage_connections {:enable true}})

(defn session-body
  "The request that makes the session of `user-id`."
  [user-id]
  (assoc session-config :user_id (str user-id)))

(defn- env
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn- set-env!
  [name value]
  (if (seq (str value))
    (aset (.-env js/process) name (str value))
    (js-delete (.-env js/process) name)))

(defn session-id
  "The session from `COMPOSIO_SESSION_ID`; nil without."
  []
  (env "COMPOSIO_SESSION_ID"))

(defn set-session!
  "The session the Composio functions use from now on, in the
   environment of the process: what the setup made or found."
  [id]
  (set-env! "COMPOSIO_SESSION_ID" id))

(defn session-id!
  "The session, or a throw that says what is missing."
  []
  (or (session-id)
      (throw (js/Error. (str "No Composio session: it is made when the agent starts, through the gateway's connection to Composio; "
                             "without that connection there is none (COMPOSIO_SESSION_ID names one made elsewhere).")))))

(defn user-id
  "The agent's user id at Composio, `COMPOSIO_USER_ID`: what its
   connected accounts and triggers belong to; nil without."
  []
  (env "COMPOSIO_USER_ID"))

(defn set-user-id!
  [id]
  (set-env! "COMPOSIO_USER_ID" id))

(defn user-id!
  []
  (or (user-id)
      (throw (js/Error. "No Composio user id: the machine has no `agent:` label and COMPOSIO_USER_ID is not set."))))

(defn meta-path
  [session-id]
  (str "/api/v3/tool_router/session/" session-id "/execute_meta"))

(defn search-arguments
  "`COMPOSIO_SEARCH_TOOLS` for one use case, a new search session;
   `toolkits` narrows it to those services."
  [use-case toolkits]
  (cond-> {:queries [{:use_case (str use-case)}]
           :session {:generate_id true}}
    (seq toolkits) (assoc :toolkits (vec toolkits))))

(defn schemas-arguments
  [slugs session]
  (cond-> {:tool_slugs (vec slugs)}
    session (assoc :session_id session)))

(defn execute-arguments
  "`COMPOSIO_MULTI_EXECUTE_TOOL` for one tool."
  [slug args session]
  (cond-> {:tools [{:tool_slug (str slug) :arguments (or args {})}]}
    session (assoc :session_id session)))

(defn connections-arguments
  [toolkits session]
  (cond-> {:toolkits (vec toolkits)}
    session (assoc :session_id session)))

(defn- answer-text
  [body]
  (or (get-in body [:error :message])
      (if (string? body) body (js/JSON.stringify (clj->js body)))))

(defn- error-message
  [slug status body]
  (str "Composio answered HTTP " status " to " slug ": " (answer-text body)))

(defn result
  "What the meta tool `slug` answered, for the program: the data of a
   completed run; a throw with the message of a failed one (the data
   in the ex-data, for a batch whose parts failed); the whole answer
   of a run that needs the person's input (`:result_type
   \"input_required\"`), which the agent passes on."
  [slug {:keys [status body]}]
  (when-not (<= 200 status 299)
    (throw (ex-info (error-message slug status body) {:status status :body body})))
  (case (:result_type body)
    "completed" (:data body)
    "failed" (throw (ex-info (str slug " failed: " (:error body)
                                  (when-let [data (:data body)]
                                    (str "\n" (js/JSON.stringify (clj->js data)))))
                             {:data (:data body)}))
    body))

(defn meta-call
  "The meta tool `slug` run with `args` in the session, through `call`
   (`(call connection-id path opts)`, the REPL's) and the Composio
   connection of `discovery`; the result."
  [call discovery slug args]
  (let [connection (connection! discovery)
        session (session-id!)]
    (result slug (call (:id connection) (meta-path session) {:method "POST" :body {:slug slug :arguments args}}))))

(defn search-session
  "The search session a `COMPOSIO_SEARCH_TOOLS` answer carries, for the
   calls after it; nil without."
  [data]
  (not-empty (str (get-in data [:session :id]))))

(def triggers-path
  "The agent's active triggers."
  "/api/v3/trigger_instances/active")

(def trigger-types-path
  "/api/v3/triggers_types")

(defn trigger-types-query
  [toolkit]
  {:toolkit_slugs (str toolkit) :limit 50})

(defn upsert-trigger-path
  [slug]
  (str "/api/v3/trigger_instances/" slug "/upsert"))

(defn upsert-trigger-body
  "The request that subscribes the trigger on the connected account:
   the agent's user id goes with it, a project with 2FA wants the
   account's owner named."
  [connected-account-id user-id config]
  {:connected_account_id (str connected-account-id)
   :user_id (str user-id)
   :trigger_config (or config {})})

(defn delete-trigger-path
  [id]
  (str "/api/v3/trigger_instances/manage/" id))

(def accounts-path
  "/api/v3/connected_accounts")

(defn accounts-query
  "The connected accounts of `user-id`."
  [user-id]
  {:user_ids (str user-id) :limit 50})

(def trigger-fact
  "The fact a bot remembers when it adds a trigger, `{:id :slug
   :account}`: the events of the trigger go to that bot
   (`rragent.composio-events`)."
  "composio-trigger")

(def trigger-deleted-fact
  "The fact a bot remembers when it deletes one, `{:id}`."
  "composio-trigger-deleted")

(defn rest-call
  "One call of Composio's REST through `call` (the REPL's) and the
   Composio connection of `discovery`: the body of a 2xx answer; a
   throw with what Composio said otherwise. `opts`: `:body` (data),
   `:query` (a map)."
  [call discovery method path {:keys [body query]}]
  (let [connection (connection! discovery)
        {:keys [status] answered :body} (call (:id connection) path (cond-> {:method method}
                                                                      body (assoc :body body)
                                                                      query (assoc :query query)))]
    (when-not (<= 200 status 299)
      (throw (ex-info (error-message (str method " " path) status answered) {:status status :body answered})))
    answered))

(defn trigger-list
  "The triggers of an answer, `{:id :slug :connected-account :state}`
   each."
  [body]
  (mapv (fn [{:keys [id trigger_name slug connected_account_id state status disabled_at]}]
          (cond-> {:id id :slug (or trigger_name slug) :connected-account connected_account_id :state (or state status)}
            disabled_at (assoc :disabled-at disabled_at)))
        (:items body)))

(defn trigger-type-list
  "The events a service offers, `{:slug :name :type :config}` each,
   the config the schema of what `add-trigger` takes."
  [body]
  (mapv (fn [{:keys [slug name type config]}]
          {:slug slug :name name :type type :config config})
        (:items body)))

(defn account-list
  "The connected accounts of an answer, `{:id :toolkit :status}`
   each."
  [body]
  (mapv (fn [{:keys [id toolkit status]}]
          {:id id :toolkit (:slug toolkit) :status status})
        (:items body)))
