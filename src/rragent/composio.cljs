(ns rragent.composio
  "The organization's third-party services through Composio
   (composio.dev): Google Drive, Gmail, Notion, Slack and the like,
   connected through Composio's managed OAuth. An agent reaches them
   through a tool-router session of its own and the session's four meta
   tools, named here as Composio names them: `COMPOSIO_SEARCH_TOOLS`
   finds a service's tools for a use case, `COMPOSIO_GET_TOOL_SCHEMAS`
   fetches their schemas, `COMPOSIO_MULTI_EXECUTE_TOOL` runs them,
   `COMPOSIO_MANAGE_CONNECTIONS` connects an account in the chat. Each
   is one `POST .../tool_router/session/<id>/execute_meta`. The session
   is made at the first call for `COMPOSIO_USER_ID`, the user the agent
   acts as, and kept in the document `rragent.composio`; one Composio
   no longer knows is replaced.

   `COMPOSIO_URL` is the base the calls go to. On a Sprite it is the
   connector's gateway, `https://api.sprites.dev/v1/gateway/composio/<connection id>`,
   which adds the API key itself, so no key is in the request;
   elsewhere it is `https://backend.composio.dev` with `COMPOSIO_API_KEY`
   sent as `x-api-key`. Without `COMPOSIO_URL` there are no Composio
   tools."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineDoc defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.docs :as docs]))

(def timeout-ms
  120000)

(def Session
  (defineDoc #js {:kind "rragent.composio"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "current"
                  :initial (fn [] #js {})}))

(defn config
  "`{:url :api-key :user-id}` from `COMPOSIO_URL`, `COMPOSIO_API_KEY` and
   `COMPOSIO_USER_ID`; nil without the URL."
  []
  (let [env (.-env js/process)
        url (not-empty (str/replace (str (aget env "COMPOSIO_URL")) #"/+$" ""))]
    (when url
      {:url url
       :api-key (not-empty (aget env "COMPOSIO_API_KEY"))
       :user-id (not-empty (aget env "COMPOSIO_USER_ID"))})))

(defn session-body
  "How the agent's session is made, as Storrito makes them: no Instant
   tools (they charge the organization's Composio balance), no
   workbench sandbox, no raw proxy, the connection manager on so that
   the agent connects an account in the chat."
  [user-id]
  #js {:user_id user-id
       :instant false
       :workbench #js {:enable false}
       :proxy_execute #js {:enable false}
       :manage_connections #js {:enable true}})

(defn- headers
  [api-key]
  (cond-> #js {:content-type "application/json" :accept "application/json"}
    api-key (doto (aset "x-api-key" api-key))))

(defn ^:async request
  "One request to Composio's v3.1 API at `path`: `{:status :body}`, the
   body parsed when it is JSON."
  [{:keys [url api-key]} method path body]
  (let [response (await (js/fetch (str url "/api/v3.1" path)
                                  #js {:method method
                                       :headers (headers api-key)
                                       :body (if body (js/JSON.stringify body) js/undefined)
                                       :signal (js/AbortSignal.timeout timeout-ms)}))
        text (await (.text response))]
    {:status (.-status response)
     :body (try (js/JSON.parse text) (catch :default _ text))}))

(defn- error-message
  [what status body]
  (str "Composio answered HTTP " status " to " what ": "
       (or (some-> body .-error .-message) (js/JSON.stringify body))))

(defn ^:async create-session!
  "A new session at Composio for the user of `config`: its id."
  [{:keys [user-id] :as config}]
  (let [{:keys [status body]} (await (request config "POST" "/tool_router/session" (session-body user-id)))]
    (when-not (<= 200 status 299)
      (throw (js/Error. (error-message "creating the session" status body))))
    (.-session_id body)))

(defn- ^:async remember-session!
  "`id` (nil forgets) committed into the document."
  [api id call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (let [doc (await (.doc tx Session (.-conversationId api)))]
                      (if id
                        (aset doc "sessionId" id)
                        (js-delete doc "sessionId"))))
                  call-context)))

(defn ^:async session-id
  "The conversation's session id, a new session made and remembered
   when there is none."
  [config api call-context]
  (or (.-sessionId (await (docs/read api Session call-context)))
      (let [id (await (create-session! config))]
        (await (remember-session! api id call-context))
        id)))

(defn ^:async execute-meta!
  "The meta tool `slug` run in the session with `args`: `{:status :body}`."
  [config id slug args]
  (await (request config "POST" (str "/tool_router/session/" id "/execute_meta") #js {:slug slug :arguments args})))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(defn result
  "The tool's result from Composio's answer: a completed run's data,
   an error with the message of a failed one (the data with it, for a
   batch whose parts failed), the questions of a run that needs the
   person's input, which the model passes on."
  [slug {:keys [status body]}]
  (when-not (<= 200 status 299)
    (throw (js/Error. (error-message slug status body))))
  (case (.-result_type body)
    "completed" (text (js/JSON.stringify (.-data body)))
    "failed" (throw (js/Error. (str slug " failed: " (.-error body)
                                    (when-let [data (.-data body)]
                                      (str "\n" (js/JSON.stringify data))))))
    "input_required" (text (str slug " needs an answer from the person before it runs. Ask them what it asks:\n"
                                (js/JSON.stringify body)))
    (text (js/JSON.stringify body))))

(defn ^:async meta-tool!
  "The meta tool `slug` with `args` for the tool's conversation. A
   session Composio no longer knows (404, 410) is forgotten and made
   anew, once."
  [config slug args api call-context]
  (let [id (await (session-id config api call-context))
        answer (await (execute-meta! config id slug args))]
    (if (contains? #{404 410} (:status answer))
      (do (await (remember-session! api nil call-context))
          (let [id (await (session-id config api call-context))]
            (result slug (await (execute-meta! config id slug args)))))
      (result slug answer))))

(def session-id-parameter
  (.Optional Type (.String Type #js {:description "The session id a COMPOSIO_SEARCH_TOOLS answer gave, to correlate the calls of one task"})))

(def definitions
  "The four meta tools: Composio's slug, what the model reads, the
   parameters as Composio documents them."
  [{:name "COMPOSIO_SEARCH_TOOLS"
    :description (str "Finds the tools of the organization's connected services (Google Drive, Gmail, Notion, Slack, GitHub and more) for a use case. "
                      "Call it first: it answers the matching tool slugs with their input schemas, which services are connected, and a session id for the next calls. "
                      "Several independent use cases go in one call.")
    :parameters (.Object Type #js {:queries (.Array Type (.Object Type #js {:use_case (.String Type #js {:description "The complete use case in plain English, for example \"find the newest file in a Google Drive folder and share it\""})
                                                                             :known_fields (.Optional Type (.String Type #js {:description "Known inputs as comma-separated key:value pairs in one string, one or two short stable ones"}))})
                                                     #js {:description "The use cases to search for, each answered with four to six tools"})
                                   :session (.Optional Type (.Object Type #js {:id (.Optional Type (.String Type #js {:description "An existing session id to continue"}))
                                                                                :generate_id (.Optional Type (.Boolean Type #js {:description "True for the first search of a new task"}))}
                                                                      #js {:description "The search session: pass generate_id true on a new task, the id you got afterwards"}))})}
   {:name "COMPOSIO_GET_TOOL_SCHEMAS"
    :description "The input schemas of tools by slug, when a search did not give them or you need the output schema too."
    :parameters (.Object Type #js {:tool_slugs (.Array Type (.String Type) #js {:description "The slugs of the tools, as a search answered them; never invented"})
                                   :include (.Optional Type (.Array Type (.String Type) #js {:description "Which schemas to answer: input_schema (the default) and output_schema"}))
                                   :session_id session-id-parameter})}
   {:name "COMPOSIO_MULTI_EXECUTE_TOOL"
    :description (str "Runs one or more tools found with COMPOSIO_SEARCH_TOOLS, each with its slug and the arguments its schema asks for; "
                      "independent ones in one call run in parallel. Answers every tool's response or error, in order. "
                      "A tool whose service is not connected fails: connect it with COMPOSIO_MANAGE_CONNECTIONS first.")
    :parameters (.Object Type #js {:tools (.Array Type (.Object Type #js {:tool_slug (.String Type #js {:description "The tool's slug, as a search answered it"})
                                                                           :arguments (.Record Type (.String Type) (.Any Type) #js {:description "The tool's arguments, as its input schema says"})})
                                                   #js {:description "The tools to run, logically independent of each other"})
                                   :thought (.Optional Type (.String Type #js {:description "One sentence on why these calls"}))
                                   :current_step (.Optional Type (.String Type #js {:description "A short label of the step, such as FETCHING_EMAILS"}))
                                   :session_id session-id-parameter})}
   {:name "COMPOSIO_MANAGE_CONNECTIONS"
    :description (str "Checks the organization's connections of services (toolkit slugs such as gmail, googledrive, slack, github) and starts "
                      "the connection of a missing one: the answer carries a link the person opens to sign in at the service. "
                      "Give them the link and go on once they say they are connected.")
    :parameters (.Object Type #js {:toolkits (.Array Type (.String Type) #js {:description "The toolkit slugs to check or connect, as a search named them; never invented"})
                                   :reinitiate_all (.Optional Type (.Boolean Type #js {:description "True reconnects the listed toolkits even where a connection exists"}))
                                   :session_id session-id-parameter})}])

(defn tool
  [config {:keys [name description parameters]}]
  (defineTool
   #js {:name name
        :description description
        :parameters parameters
        :execute (^:async fn [args api call-context]
                   (await (meta-tool! config name args api call-context)))}))

(defn extension
  "The `composio` extension for `config`; nil without one (no `COMPOSIO_URL`)."
  [config]
  (when config
    (when (empty? (:user-id config))
      (throw (js/Error. "COMPOSIO_URL is set but COMPOSIO_USER_ID is not; the Composio tools need the user the agent acts as")))
    (defineExtension #js {:name "composio"
                          :tools (into-array (map #(tool config %) definitions))})))
