(ns rragent.codemode
  "The agent's REPL: Clojure programs evaluated by SCI with the
   connectors of the Sprites gateway as functions and nothing else of
   the host. One context for the life of a handle, made at the first
   program, so that a `def` of one program is there for the next; a
   program's printed output and its value are its answer. The context
   is closed: no `js/`, no interop on values, no `require` of anything
   but Clojure's own namespaces, no reading of the environment; what a
   program reaches, it reaches through the namespace `agent`, referred
   in `user`: `connectors`, `connector`, `call`, `remember`, `recall`
   and `help`, with `composio/*`, `storrito/*` and `mcp/*` beside them
   (`rragent.gateway`, `rragent.composio`, `rragent.storrito`), and a
   namespace `mcp.<server>` per MCP server with its tools as functions
   (`rragent.mcp`). A call
   blocks its thread until it is answered (`rragent.sync-fetch`), so
   a program loops over calls as over anything else; the agent runs
   its programs in a worker thread for that (`rragent.codemode-worker`,
   a handle of `handle-of` over what the main thread discovered). The
   facts a program recalls are read before it runs, what it remembers
   is committed after it (`rragent.tools.eval`)."
  (:require [clojure.string :as str]
            [goog.string :as gstring]
            [rragent.composio :as composio]
            [rragent.facts :as facts]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.sprite :as sprite]
            [rragent.storrito :as storrito]
            [sci.core :as sci]))

(def max-chars
  10000)

(def denied
  "The functions of cljs.core that reach JavaScript objects and would
   open the host (`aget` on a function is its constructor, which makes
   functions from strings): a program has data, not objects. Each is
   replaced by one that says so."
  '[aget aset js-invoke unchecked-get unchecked-set js-obj js-keys js-delete clj->js js->clj])

(defn- denied-fn
  [sym]
  (fn [& _]
    (throw (js/Error. (str sym " is not available in this REPL: it reaches JavaScript objects, and the REPL has data, not objects.")))))

(def denied-core
  "What `clojure.core` holds for the denied names."
  (into {} (map (fn [sym] [sym (denied-fn sym)])) denied))

(def preamble
  "What every program starts from: the agent namespace referred, doc
   at hand."
  "(ns user (:require [agent :refer :all] [clojure.repl :refer [doc]]))")

(def agent-ns
  (sci/create-ns 'agent))

(def composio-ns
  (sci/create-ns 'composio))

(def storrito-ns
  (sci/create-ns 'storrito))

(def mcp-ns
  (sci/create-ns 'mcp))

(defn- out!
  "`text` printed where the program's output goes."
  [text]
  (@sci/print-fn (str text "\n"))
  nil)

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn- storrito-vars
  "One var per procedure of `catalog`, named as the procedure, its
   docstring from the catalog, a call through the `connection`."
  [call connection catalog]
  (into {}
        (map (fn [{:keys [name] :as procedure}]
               (let [sym (symbol name)]
                 [sym (sci/new-var sym
                                   (fn [& [args]] (storrito/call call connection name args))
                                   {:ns storrito-ns :name sym :doc (storrito/docstring procedure) :arglists '([] [args])})])))
        (:procedures catalog)))

(defn- install-storrito!
  "The `storrito/` functions of the catalog of the Storrito connection
   among the gateway's, when there is one (`rragent.storrito/find-catalog`);
   what went wrong kept for `procedures` to say. Into the context when
   there is one already; else the context takes them when it is made."
  [handle call]
  (let [{:keys [connection catalog] :as found} (storrito/find-catalog @(:discovery handle))]
    (reset! (:storrito handle) found)
    (when (and catalog @(:context handle))
      (swap! (:context handle) sci/merge-opts {:namespaces {'storrito (storrito-vars call connection catalog)}}))))

(defn call
  "An HTTP request through the gateway to a connection: `which` is
   its id, or its provider name when one connection has it (several
   custom_api connections: name the id). `path` is appended to the
   connection's base, the gateway adds the credential. `opts`:
   :method (GET), :body (data goes as JSON, a string as it is),
   :headers (a map), :query (a map, appended to the URL). Answers
   {:status :headers :body}, the body as data when it is JSON; look at
   :status, a failed request throws."
  [handle which path & [opts]]
  (gateway/call @(:discovery handle) which path opts))

(defn connectors
  "The connectors of the gateway, read now: {:connections [...]
   :available [...]}; a connection has :id, :provider,
   :gateway_base_url, :notes and :provider_info (a custom_api one
   says its :base_api_url there), an available provider :provider,
   :display_name and :description (the person sets it up in the
   Sprites dashboard). An :error says why the lists are empty when
   the gateway did not answer. The storrito/ functions follow the
   reading; the time of the reading is kept for `asked-at`."
  [handle]
  (reset! (:asked handle) (js/Date.now))
  (let [discovery (gateway/discover (:gateway handle))]
    (reset! (:discovery handle) discovery)
    (install-storrito! handle (partial call handle))
    discovery))

(defn connector
  "One connection: by its id, or by its provider name when one
   connection has it; nil when there is none."
  [handle which]
  (gateway/connection @(:discovery handle) which))

(defn remember
  "A fact for later runs: `event`, a short name such as
   \"review-posted\", and `data`, a map of your own keys. Facts are
   never changed or lost, the conversation's summaries leave them
   alone; recall sees this one at once, it is committed when the
   program ends."
  [handle event data]
  (when (str/blank? (str event))
    (throw (js/Error. "remember takes an event name")))
  (when-not (or (nil? data) (map? data))
    (throw (js/Error. "remember takes a map of data")))
  (let [fact (assoc (or data {}) :event (str event) :at (js/Date.now))]
    (swap! (:facts handle) #(-> % (update :known conj fact) (update :pending conj fact)))
    (str "Noted " event)))

(defn recall
  "Your facts, oldest first: maps with :event, :at (milliseconds) and
   your own keys. `opts`: :event (only that kind), :since (an ISO
   date or milliseconds)."
  [handle & [opts]]
  (facts/select (:known @(:facts handle)) (or opts {})))

(defn search
  "The tools of the organization's services (Gmail, Google Drive,
   Notion, Slack, GitHub and more) for a use case in plain words, such
   as \"send an email\"; `toolkits` narrows it to those services
   (slugs such as \"gmail\"). Answers tool slugs with their input
   schemas and which services are connected; call it first, the
   session it answers is kept for the calls after it."
  [handle use-case & [toolkits]]
  (let [data (composio/meta-call (partial call handle) @(:discovery handle) "COMPOSIO_SEARCH_TOOLS"
                                 (composio/search-arguments use-case toolkits))]
    (when-let [session (composio/search-session data)]
      (reset! (:composio-session handle) session))
    data))

(defn schemas
  "The input schemas of tools by slug, as a search answered them."
  [handle slugs]
  (composio/meta-call (partial call handle) @(:discovery handle) "COMPOSIO_GET_TOOL_SCHEMAS"
                      (composio/schemas-arguments slugs @(:composio-session handle))))

(defn execute
  "One tool found with search run with the arguments its schema asks
   for: the results of the run; a failed run throws with Composio's
   error (a service that is not connected, for one: connect it with
   connections first)."
  [handle slug & [args]]
  (composio/meta-call (partial call handle) @(:discovery handle) "COMPOSIO_MULTI_EXECUTE_TOOL"
                      (composio/execute-arguments slug args @(:composio-session handle))))

(defn connections
  "The organization's connections of services, by Composio toolkit
   slug (googlesheets, gmail, googledrive, googledocs, googlecalendar,
   slack, notion, github), a missing one started: the answer carries a
   link the person opens to sign in at the service, good for ten
   minutes. Call it in the answer in which the person names a service
   of theirs, give them the link and go on once they say they are
   connected."
  [handle toolkits]
  (composio/meta-call (partial call handle) @(:discovery handle) "COMPOSIO_MANAGE_CONNECTIONS"
                      (composio/connections-arguments toolkits @(:composio-session handle))))

(defn accounts
  "Your connected accounts at Composio, {:id :toolkit :status} each:
   what connections made, by service."
  [handle]
  (composio/account-list
   (composio/rest-call (partial call handle) @(:discovery handle) "GET" composio/accounts-path
                       {:query (composio/accounts-query (composio/user-id!))})))

(defn trigger-types
  "The events a service offers, {:slug :name :type :config} each, the
   config the schema of what add-trigger takes for it:
   (composio/trigger-types \"googledrive\")."
  [handle toolkit]
  (composio/trigger-type-list
   (composio/rest-call (partial call handle) @(:discovery handle) "GET" composio/trigger-types-path
                       {:query (composio/trigger-types-query toolkit)})))

(defn triggers
  "Your triggers at Composio, {:id :slug :connected-account :state}
   each: every event of a trigger comes to you as a message, with the
   service's payload."
  [handle]
  (composio/trigger-list
   (composio/rest-call (partial call handle) @(:discovery handle) "GET" composio/triggers-path {})))

(defn add-trigger
  "A trigger of the kind `slug` on the connected account
   `connected-account-id` (one of accounts), with `config` as its type
   asks: from now on every event of it is posted to this machine and
   arrives in your conversation. Answers the trigger's id, which is
   noted as a fact so that the events find you:
   (composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\")."
  [handle slug connected-account-id & [config]]
  (let [body (composio/rest-call (partial call handle) @(:discovery handle) "POST" (composio/upsert-trigger-path slug)
                                 {:body (composio/upsert-trigger-body connected-account-id (composio/user-id!) config)})
        id (or (:trigger_id body) (:id body))]
    (remember handle composio/trigger-fact {:id id :slug (str slug) :account (str connected-account-id)})
    id))

(defn delete-trigger
  "The trigger `id` deleted at Composio; its events stop."
  [handle id]
  (composio/rest-call (partial call handle) @(:discovery handle) "DELETE" (composio/delete-trigger-path id) {})
  (remember handle composio/trigger-deleted-fact {:id (str id)})
  (str "Deleted " id))

(defn procedures
  "The procedures of the organization's Storrito API, {:name :doc}
   each, every one a function storrito/<name> that takes a map:
   (storrito/list-instagram-users {}); (doc storrito/<name>) says its
   parameters."
  [handle]
  (let [{:keys [catalog link error]} @(:storrito handle)]
    (cond
      catalog (storrito/summary catalog)
      error (throw (js/Error. (str "The Storrito catalog at " link " could not be read: " error)))
      :else (throw (js/Error. (str "No Storrito among the connections: no custom_api connection of the gateway "
                                   "has a catalog at its base. (connectors) lists what there is."))))))

(defn agent-uuid
  "The uuid of this agent at Storrito, the `agentUuid` the
   storrito/ integration procedures take
   (storrito/add-integration-trigger, storrito/list-integration-triggers,
   ...): the `agent:` label of the machine. Throws on a machine
   without one."
  [_handle]
  (or (sprite/label (:labels (sprite/discover)) "agent")
      (throw (js/Error. "No agent uuid: this machine has no `agent:` label, it is no Sprite that Storrito made for an agent."))))

;;; MCP

(defn- invoke
  "The tool `tool` of the MCP server `server` called with `args`
   through the handle's `:mcp-rpc`, which blocks until the main thread
   answered (`rragent.codemode-worker`): the value, its pictures kept
   for the answer of the program; an error thrown."
  [handle server tool args]
  (when-not (or (nil? args) (map? args))
    (throw (js/Error. (str "mcp." server "/" tool " takes a map of arguments, such as {:query \"x\"}"))))
  (let [rpc @(:mcp-rpc handle)]
    (when-not rpc
      (throw (js/Error. "The MCP servers are not reachable from this REPL.")))
    (let [{:keys [value images error]} (rpc {:server server :tool tool :args args})]
      (when error
        (throw (js/Error. error)))
      (swap! (:images handle) into images)
      value)))

(defn- param-line
  [required [k schema]]
  (let [{:strs [type description enum]} (when (map? schema) schema)]
    (str "  :" k
         " (" (cond (string? type) type (sequential? type) (str/join "|" type) :else "any")
         (when (contains? required k) ", required")
         (when (seq enum) (str ", one of " (str/join " " (map pr-str enum))))
         ")"
         (when (seq description) (str " " description)))))

(defn tool-doc
  "The docstring of a tool's function: its description and its
   arguments from the input schema."
  [server {:keys [name title description input-schema]}]
  (let [{:strs [properties required]} input-schema
        required (set required)]
    (str (when title (str title "\n"))
         (if (str/blank? description) (str "The tool " name " of " server ".") description)
         "\n\nThe tool " name " of the MCP server " server ". "
         (if (seq properties)
           (str "It takes a map:\n" (str/join "\n" (map (partial param-line required) properties)))
           "It takes no arguments."))))

(defn- tool-vars
  "The functions of the tools of one server, in its namespace; of two
   tools with the same symbol, the first (mcp/call reaches the other)."
  [handle {server :name ns-name :ns tools :tools}]
  (let [sci-ns (sci/create-ns (symbol ns-name))]
    (reduce (fn [vars {tool :name :as t}]
              (let [sym (symbol (mcp/symbol-name tool))
                    takes (seq (get-in t [:input-schema "properties"]))]
                (if (contains? vars sym)
                  vars
                  (assoc vars sym (sci/new-var sym
                                               (fn [& [args]] (invoke handle server tool args))
                                               {:ns sci-ns :name sym :doc (tool-doc server t)
                                                :arglists (if takes '([args]) '([] [args]))})))))
            {}
            tools)))

(defn- mcp-namespaces
  "`{mcp.<server> {tool var}}` of `catalog`."
  [handle catalog]
  (into {}
        (keep (fn [{:keys [ns tools] :as server}]
                (when (seq tools)
                  [(symbol ns) (tool-vars handle server)])))
        (:servers catalog)))

(defn install-mcp!
  "`catalog` (`rragent.mcp/snapshot`) as the MCP servers of the
   handle: into the context when there is one already; else the
   context takes them when it is made."
  [handle catalog]
  (reset! (:mcp-catalog handle) catalog)
  (when @(:context handle)
    (swap! (:context handle) sci/merge-opts {:namespaces (mcp-namespaces handle catalog)})))

(defn- catalog-server
  [server-name]
  (fn [handle]
    (or (some #(when (= (str server-name) (:name %)) %) (:servers @(:mcp-catalog handle)))
        (throw (js/Error. (str "There is no MCP server " server-name "; (mcp/servers) lists them."))))))

(defn mcp-servers
  "The MCP servers of the machine, as the last program found them:
   {:name :ns :tools :connected :error :instructions} each, :tools how
   many there are, :ns the namespace of their functions,
   :instructions what the server says about using it."
  [handle]
  (mapv (fn [server]
          (-> server
              (update :tools count)
              (select-keys [:name :ns :tools :connected :connecting :off :error :instructions])))
        (:servers @(:mcp-catalog handle))))

(defn mcp-tools
  "The tools of the MCP server `server`: {:name :fn :description}
   each, :fn the function that calls it, nil for a name whose symbol
   an earlier tool has (mcp/call reaches it); (doc <fn>) says its
   arguments."
  [handle server]
  (let [{:keys [ns tools]} ((catalog-server server) handle)]
    (first
     (reduce (fn [[out seen] {:keys [name description]}]
               (let [sym-name (mcp/symbol-name name)]
                 [(conj out {:name name
                             :fn (when-not (seen sym-name) (symbol ns sym-name))
                             :description description})
                  (conj seen sym-name)]))
             [[] #{}]
             tools))))

(defn mcp-call
  "The tool `tool` of the MCP server `server` called with the map
   `args`: what (mcp.<server>/<tool> args) answers, for a name that
   is no symbol. The structured content when the tool gives one, else
   its text, as data when it is JSON; pictures are shown to you after
   the program's answer; a failed call throws with what the server
   said."
  [handle server tool & [args]]
  (invoke handle (str server) (str tool) args))

(def help-lines
  "The bindings, one form and one line each."
  [["(help)" "this text"]
   ["(doc f)" "the documentation of any function: (doc call), (doc storrito/list-instagram-users)"]
   ["(connectors)" "the connectors of the gateway, read now: :connections (:id, :provider, :gateway_base_url, :provider_info) and :available"]
   ["(connector \"github\")" "one connection, by provider name or by id (several custom_api connections: the id)"]
   ["(call \"github\" \"/user/repos\" {:query {:per_page 5}})" "an HTTP call through the gateway, by provider name or connection id: {:status :headers :body}, JSON as data; :method, :body (data as JSON), :headers"]
   ["(composio/search \"send an email\" [\"gmail\"])" "the tools of the organization's services for a use case: slugs, schemas, what is connected"]
   ["(composio/schemas [\"GMAIL_SEND_EMAIL\"])" "the input schemas of tools by slug"]
   ["(composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\"})" "runs one tool with its arguments"]
   ["(composio/connections [\"googlesheets\"])" "the connections of services by toolkit slug (googlesheets, gmail, googledrive, slack, notion, github), a missing one started: a sign-in link for the person, good for ten minutes; the answer to give as soon as they name a service of theirs"]
   ["(composio/accounts)" "your connected accounts, by service: {:id :toolkit :status}"]
   ["(composio/trigger-types \"googledrive\")" "the events a service offers and the config each takes"]
   ["(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\" {})" "every event of that kind on the connected account arrives in your conversation from now on; answers the trigger's id"]
   ["(composio/triggers)" "your triggers: {:id :slug :connected-account :state}; (composio/delete-trigger \"ti_1\") ends one"]
   ["(storrito/procedures)" "the organization's Storrito API, one function storrito/<name> per procedure, a map of arguments each"]
   ["(storrito/agent-uuid)" "your uuid at Storrito, the agentUuid some storrito/ procedures take"]
   ["(mcp/servers)" "the MCP servers of the machine: their namespaces, how many tools, whether connected, their instructions"]
   ["(mcp/tools \"github\")" "the tools of one server: {:name :fn :description}, (doc mcp.github/<tool>) says its arguments"]
   ["(mcp.github/search_issues {:query \"bug\"})" "one tool of a server, a map of arguments: its structured content, else its text (JSON as data); pictures are shown to you after the answer; (mcp/call \"github\" \"search_issues\" {...}) the same by name"]
   ["(remember \"review-posted\" {:id 4711})" "a fact for later runs, kept through every summary"]
   ["(recall {:event \"review-posted\"})" "your facts, oldest first; :since narrows them"]])

(defn- mcp-status
  "The MCP servers, for help."
  [servers]
  (if (seq servers)
    (str "\nMCP: "
         (str/join ", " (map (fn [{:keys [name ns tools error off connecting]}]
                               (str name (cond
                                           off " (off)"
                                           (seq tools) (str " as " ns "/<tool>, " (count tools) " tools"
                                                            (when error (str ", now failing: " error)))
                                           error (str " (failed: " error ")")
                                           connecting " (still connecting)"
                                           :else " (no tools)")))
                             servers))
         ".")
    "\nMCP: no servers (mcp.json of the machine's folder names them)."))

(defn- status-text
  "What is connected now, for help."
  [handle]
  (let [{:keys [gateway connections available error] :as discovery} @(:discovery handle)
        {:keys [catalog]} @(:storrito handle)]
    (str (cond
           error (str "The gateway " gateway " did not answer (" error "): no connectors here.")
           (seq connections) (str "Connected: " (str/join ", " (map gateway/describe connections)) ".")
           :else "Nothing is connected.")
         (when (seq available)
           (str " Available, for the person to set up: " (str/join ", " (map :provider available)) "."))
         (if catalog
           (str "\nStorrito: " (count (:procedures catalog)) " procedures as storrito/<name>, through " (:id (:connection @(:storrito handle))) ".")
           "\nStorrito: no connection with a catalog, the storrito/ functions are not here.")
         (cond
           (not (composio/connection discovery)) "\nComposio: no connection, the composio/ functions will say so."
           (not (composio/session-id)) "\nComposio: no session yet (it is made when the agent starts), the composio/ functions will say so."
           :else (str "\nComposio: through " (:id (composio/connection discovery)) ", session " (composio/session-id)
                      (when-let [user (composio/user-id)] (str ", your accounts and triggers under " user)) "."))
         (mcp-status (:servers @(:mcp-catalog handle))))))

(defn help-text
  [handle]
  (str "Your REPL: Clojure with the organization's connectors as functions, nothing of the host. "
       "Chain calls, loop and filter in one program, print only what matters.\n\n"
       (str/join "\n" (map (fn [[form what]] (str form "\n    " what)) help-lines))
       "\n\n"
       (status-text handle)))

(defn help
  "The functions of this REPL, one line each, and what is connected."
  [handle]
  (out! (help-text handle)))

(defn- arglists
  "The arglists of a var's meta, which the compiler keeps quoted."
  [m]
  (let [lists (:arglists m)]
    (if (and (seq? lists) (= 'quote (first lists))) (second lists) lists)))

(defn- binding-var
  "A var of `ns` for the function of `v` with `handle` as its first
   argument: the function's name, doc and arglists without the handle,
   so that (doc f) reads them."
  [ns handle v]
  (let [{:keys [name doc] :as m} (meta v)]
    (sci/new-var name (partial @v handle) {:ns ns :name name :doc doc :arglists (map rest (arglists m))})))

(defn- vars
  [ns handle fns]
  (into {} (map (fn [v] [(:name (meta v)) (binding-var ns handle v)])) fns))

(defn- named-vars
  "As `vars`, under the names of `named`, `{sym var}`: the mcp/
   functions, whose names the agent namespace has already."
  [ns handle named]
  (into {}
        (map (fn [[sym v]]
               (let [{:keys [doc] :as m} (meta v)]
                 [sym (sci/new-var sym (partial @v handle) {:ns ns :name sym :doc doc :arglists (map rest (arglists m))})])))
        named))

(defn- context
  "The closed SCI context with the namespaces of `handle`, the
   storrito/ functions of the catalog found by now among them, and the
   tools of the MCP servers the last program knew of."
  [handle]
  (let [{:keys [connection catalog]} @(:storrito handle)
        ctx (sci/init {:features #{:cljs}
                       :namespaces (merge
                                    {'clojure.core denied-core
                                     'agent (vars agent-ns handle [#'connectors #'connector #'call #'remember #'recall #'help])
                                     'composio (vars composio-ns handle [#'search #'schemas #'execute #'connections #'accounts #'trigger-types #'triggers #'add-trigger #'delete-trigger])
                                     'storrito (cond-> (vars storrito-ns handle [#'procedures #'agent-uuid])
                                                 catalog (merge (storrito-vars (partial call handle) connection catalog)))
                                     'mcp (named-vars mcp-ns handle {'servers #'mcp-servers 'tools #'mcp-tools 'call #'mcp-call})}
                                    (mcp-namespaces handle @(:mcp-catalog handle)))})]
    (sci/eval-string* ctx preamble)
    ctx))

(defn- context!
  "The context of `handle`, made at the first program."
  [handle]
  (or @(:context handle)
      (reset! (:context handle) (context handle))))

(defn handle-of
  "The REPL over what another thread found: `{:gateway :discovery
   :storrito}` as `snapshot` answers them, no request made. `{:gateway
   :discovery :storrito :composio-session :facts :context :asked}`, the
   last six atoms; the context is made at the first program, `asked`
   is when `connectors` last read the list again, 0 before it did."
  [{:keys [gateway discovery storrito]}]
  {:gateway gateway
   :discovery (atom discovery)
   :storrito (atom storrito)
   :composio-session (atom nil)
   :facts (atom {:known [] :pending []})
   :images (atom [])
   :mcp-catalog (atom nil)
   :mcp-rpc (atom nil)
   :context (atom nil)
   :asked (atom 0)})

(defn handle
  "The REPL of the agent over the gateway at `base`: its connectors
   discovered now, one request, or `discovery` as another reading of
   them found it (`rragent.gateway/discover-at-start`, which tries
   again while the gateway comes up); the Storrito catalog read; the
   context is made at the first program."
  ([base]
   (handle base (gateway/discover base)))
  ([base discovery]
   (let [handle (handle-of {:gateway base :discovery discovery})]
     (install-storrito! handle (partial call handle))
     handle)))

(defn snapshot
  "What a handle in another thread starts from (`handle-of`): the
   gateway, its discovery and the Storrito catalog as they are, data."
  [handle]
  {:gateway (:gateway handle)
   :discovery @(:discovery handle)
   :storrito @(:storrito handle)})

(defn discovery
  [handle]
  @(:discovery handle))

(defn asked-at
  "When `connectors` last read the gateway's list again, in
   milliseconds; 0 while only the reading at start was made. For
   `rragent.openrouter`, which asks again at most every ten seconds
   while no `openrouter` connection is known."
  [handle]
  @(:asked handle))

(defn begin!
  "The facts a program sees: `known`, the conversation's as they are
   now; nothing pending, no pictures."
  [handle known]
  (reset! (:images handle) [])
  (reset! (:facts handle) {:known (vec known) :pending []}))

(defn images
  "The pictures the program's MCP calls answered, `{:data :mime-type}`
   each, for the model after the program's answer."
  [handle]
  @(:images handle))

(defn pending
  "What the program remembered, oldest first, for the commit."
  [handle]
  (:pending @(:facts handle)))

(defn- location
  [e]
  (let [{:keys [line column]} (ex-data e)]
    (when line
      (str " (line " line (when column (str ", column " column)) ")"))))

(defn- error-text
  [e printed]
  (str (or (ex-message e) (str e))
       (location e)
       (when (seq printed)
         (str "\nPrinted before the error:\n" printed))))

(defn evaluate
  "`code` evaluated in the context of `handle`: what it printed, then
   its value after `=>`, cut at ten thousand characters; a throw with
   the error's message, where it was, and what was printed before it."
  [handle code]
  (let [sb (gstring/StringBuffer.)
        value (try
                (sci/binding [sci/print-newline true
                              sci/print-fn (fn [x] (.append sb x))]
                  (sci/eval-string* (context! handle) (str code)))
                (catch :default e
                  (throw (js/Error. (cut (error-text e (str sb)))))))
        printed (str sb)]
    (cut (str printed
              (when (and (seq printed) (not (str/ends-with? printed "\n"))) "\n")
              "=> " (pr-str value)))))

(defn- tries-text
  "How many tries the list took at start, when more than one."
  [tries]
  (when (< 1 (or tries 1))
    (str " after " tries " tries")))

(defn report
  "One line for the console at start: what the gateway has, or why
   nothing, and the tries the list took when it took more than one."
  [handle]
  (let [{:keys [gateway connections available error tries]} @(:discovery handle)
        {:keys [catalog connection link] catalog-error :error} @(:storrito handle)]
    (if error
      (str "no gateway at " gateway " (" error ")" (tries-text tries) ": no connectors")
      (str "connectors at " gateway (tries-text tries) ": "
           (if (seq connections)
             (str/join ", " (map (fn [c]
                                   (str (gateway/describe c)
                                        (cond
                                          (= c connection) (str " = Storrito, " (count (:procedures catalog)) " procedures")
                                          (composio/connection? c) " = Composio")))
                                 connections))
             "none connected")
           (when catalog-error
             (str "; the Storrito catalog at " link " was not read: " catalog-error))
           (when (seq available)
             (str "; available: " (str/join ", " (map :provider available))))))))
