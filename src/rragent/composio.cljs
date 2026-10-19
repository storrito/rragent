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
   base is `backend.composio.dev`, which adds the API key, and whose
   policy allows that one path, nothing else of Composio's API. The
   session is `COMPOSIO_SESSION_ID`, made for the agent by whoever
   provisions it; rragent never makes one. In the REPL the four are
   `composio/search`, `composio/schemas`, `composio/execute` and
   `composio/connections` (`rragent.codemode`)."
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

(defn session-id
  "The session from `COMPOSIO_SESSION_ID`; nil without."
  []
  (not-empty (aget (.-env js/process) "COMPOSIO_SESSION_ID")))

(defn session-id!
  "The session, or a throw that says what is missing."
  []
  (or (session-id)
      (throw (js/Error. (str "COMPOSIO_SESSION_ID is not set: the Composio functions need the tool-router session "
                             "that was made for this agent when it was provisioned.")))))

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

(defn- error-message
  [slug status body]
  (str "Composio answered HTTP " status " to " slug ": "
       (or (get-in body [:error :message])
           (if (string? body) body (js/JSON.stringify (clj->js body))))))

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
