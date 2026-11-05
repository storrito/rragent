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
   (`rragent.mcp`).

   A program is a sequence of forms, evaluated one at a time
   (`evaluate-form`), and nothing of it reaches outside the worker it
   runs in: a call is a need (`rragent.need`), answered by the host,
   which runs the form again with the answer, until every call of the
   form is answered and the form is done. Each attempt runs in a fork
   of the program's context; the fork of the attempt that finished is
   the program's context from then on, what an unfinished attempt
   defined is gone with it. A program is one of its own: it starts
   from a fresh fork of the base context (`begin!`), nothing a
   program defined is there for the next, since what the model cannot
   see in its conversation, once it is summarized, it cannot use;
   data for later programs goes into a file of the bot's work folder,
   `(spit \"data/x.edn\" v)`, read back with `(edn/read-string (slurp
   \"data/x.edn\"))`, a file the model names and sees. A form that is
   done answers what it printed, its value, and the vars it defined,
   as EDN when they can be read back, so that the host keeps them and
   gives them back to a worker that starts over in the middle of the
   program (`restore!`), or says that the form is to be run again.
   The events a program recalls come with every attempt
   (`rragent.tools.eval`)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [goog.string :as gstring]
            [rragent.composio :as composio]
            [rragent.events :as events]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.need :as need]
            [rragent.sprite :as sprite]
            [rragent.storrito :as storrito]
            [sci.core :as sci]))

(def max-chars
  10000)

(def max-var-chars
  "How long the EDN of a var a form defined may be for the host to
   keep it; a longer one has the form run again instead."
  1000000)

(def ^:dynamic *fork*
  "The context of the attempt that runs, for what installs namespaces
   while a form runs; nil outside one."
  nil)

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
  "(ns user (:require [agent :refer :all] [clojure.edn :as edn] [clojure.repl :refer [doc]]))")

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
  "The `storrito/` functions of the catalog of `found`, the Storrito
   connection among the gateway's with its catalog
   (`rragent.storrito/find-catalog`), when there is one; what went
   wrong kept for `procedures` to say. Into the context when there is
   one already, and into the fork of the attempt that runs; else the
   context takes them when it is made."
  [handle call found]
  (let [{:keys [connection catalog]} found]
    (reset! (:storrito handle) found)
    (when (and catalog @(:context handle))
      (let [namespaces {:namespaces {'storrito (storrito-vars call connection catalog)}}]
        (swap! (:context handle) sci/merge-opts namespaces)
        (when @(:program handle)
          (swap! (:program handle) sci/merge-opts namespaces))
        (when *fork*
          (sci/merge-opts *fork* namespaces))))))

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
  (let [discovery @(:discovery handle)
        connection (or (gateway/connection discovery which)
                       (throw (js/Error. (gateway/no-connector-message discovery which))))
        method (str/upper-case (name (or (:method opts) "GET")))
        read? (or (= "GET" method) (true? (:read? opts)))]
    (need/ask {:tool (if read? "gateway-read" "gateway")
               :args {:which (:id connection) :path (str path) :opts (dissoc (or opts {}) :read?)}})))

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
  (let [{:keys [discovery storrito]} (need/ask {:tool "connectors" :args {}})]
    (reset! (:asked handle) (js/Date.now))
    (reset! (:discovery handle) discovery)
    (install-storrito! handle (partial call handle) storrito)
    discovery))

(defn refresh!
  "The gateway's list read again by the main thread, one blocking
   request, the Storrito catalog with it: what `connectors` does for a
   program, for `rragent.openrouter`, which asks again while no
   `openrouter` connection is known."
  [handle]
  (reset! (:asked handle) (js/Date.now))
  (let [discovery (gateway/discover (:gateway handle))]
    (reset! (:discovery handle) discovery)
    (install-storrito! handle (partial call handle) (storrito/find-catalog discovery))
    discovery))

(defn connector
  "One connection: by its id, or by its provider name when one
   connection has it; nil when there is none."
  [handle which]
  (gateway/connection @(:discovery handle) which))

(defn remember
  "An event for later runs: `event`, a short name such as
   \"review-posted\", and `data`, a map of your own keys. Events are
   never changed or lost, the conversation's summaries leave them
   alone, and your runs see them as you do; recall sees it at once."
  [handle event data]
  (when (str/blank? (str event))
    (throw (js/Error. "remember takes an event name")))
  (when-not (or (nil? data) (map? data))
    (throw (js/Error. "remember takes a map of data")))
  (let [answer (need/ask {:tool "remember" :args {:event (str event) :data (or data {})}})]
    (swap! (:events handle) update :known conj (assoc (or data {}) :event (str event) :at (js/Date.now)))
    answer))

(defn recall
  "Your events, oldest first: maps with :event, :at (milliseconds) and
   your own keys. `opts`: :event (only that kind), :since (an ISO
   date or milliseconds)."
  [handle & [opts]]
  (events/select (:known @(:events handle)) (or opts {})))

(defn- edn
  "`value` as EDN the host can keep and `restore!` can read back; nil
   when it cannot be read back (a function, a regex, an object, a
   record), or is longer than `max-var-chars`."
  [value]
  (let [s (binding [*print-length* 100000
                    *print-level* 200]
            (pr-str value))]
    (when (<= (count s) max-var-chars)
      (try
        (when (= value (reader/read-string s))
          s)
        (catch :default _ nil)))))

(defn- file-path
  "`path` as a file of the bot's work folder: relative, nowhere
   above it; a throw for any other."
  [path]
  (let [p (str path)]
    (when (or (str/blank? p) (str/starts-with? p "/") (re-find #"(^|/)\.\.(/|$)" p) (str/includes? p "\\"))
      (throw (js/Error. (str "spit and slurp take a path inside your work folder, such as \"data/accounts.edn\", not " (pr-str path)))))
    p))

(defn spit
  "`data` written to the file `path` of your work folder, for a later
   program: a string as it is, anything else as EDN, which
   (edn/read-string (slurp path)) reads back; data that would not
   read back (a function, a regex, an object) is refused. The folders
   are made. Answers the path. Keep what later programs need in
   data/<name>.edn: a file you named is in your conversation and in
   the pane, a def is not."
  [_handle path data]
  (let [path (file-path path)
        content (if (string? data) data (edn data))]
    (when-not content
      (throw (js/Error. (str "spit writes data that reads back as EDN: " (pr-str (type data)) " does not, or it is longer than " max-var-chars " characters."))))
    (need/ask {:tool "spit" :args {:path path :content content}})))

(defn slurp
  "The text of the file `path` of your work folder; a throw when
   there is none. (edn/read-string (slurp \"data/x.edn\")) reads data
   a program wrote with spit."
  [_handle path]
  (need/ask {:tool "slurp" :args {:path (file-path path)}}))

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
  "The tool `tool` of the MCP server `server` called with `args`: a
   need the main thread answers, which holds the connections
   (`rragent.mcp`); the value, its pictures shown to the model by the
   host; a failed call thrown."
  [_handle server tool args]
  (when-not (or (nil? args) (map? args))
    (throw (js/Error. (str "mcp." server "/" tool " takes a map of arguments, such as {:query \"x\"}"))))
  (:value (need/ask {:tool "mcp" :args {:server (str server) :tool (str tool) :args (or args {})}})))

(defn- admin
  "A change of the MCP servers, made by the main thread, which owns
   `mcp.json` and the connections (`rragent.mcp`): `request` is
   `{:admin :add|:test|:enable|:remove …}`, a need; the answer's
   value, a failure thrown."
  [_handle request]
  (:value (need/ask {:tool "mcp-admin" :args request})))

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
    (let [namespaces {:namespaces (mcp-namespaces handle catalog)}]
      (swap! (:context handle) sci/merge-opts namespaces)
      (when @(:program handle)
        (swap! (:program handle) sci/merge-opts namespaces))
      (when *fork*
        (sci/merge-opts *fork* namespaces)))))

(defn- catalog-server
  [server-name]
  (fn [handle]
    (or (some #(when (= (str server-name) (:name %)) %) (:servers @(:mcp-catalog handle)))
        (throw (js/Error. (str "There is no MCP server " server-name "; (mcp/servers) lists them."))))))

(defn mcp-servers
  "The MCP servers of the machine, as they were when this program
   started: {:name :ns :tools :connected :needs :error :instructions}
   each, :tools how many there are, :ns the namespace of their
   functions, :needs the secrets not saved yet, :instructions what the
   server says about using it."
  [handle]
  (mapv (fn [server]
          (-> server
              (update :tools count)
              (select-keys [:name :ns :tools :connected :connecting :off :needs :oauth :sign-in :error :instructions])))
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

(defn mcp-add!
  "Adds the MCP server `server` to the machine, or replaces its entry,
   and connects it right away. `opts`: {:url \"https://…/mcp\"} for a
   Streamable HTTP server, :token \"NAME\" when it takes a bearer
   token (the header Authorization: Bearer ${NAME}), :headers {\"X-Api-Key\"
   \"${NAME}\"} for another header; or {:command \"npx\" :args [...]
   :env {...} :cwd \"folder\"} for a program; :oauth true for a server
   the person signs in to in their browser (OAuth): the answer has
   :sign-in true until they did, with the link [:sign-in {:server
   \"github\"}] in a ```hiccup block of your answer; :oauth {:client-id
   \"…\" :client-secret \"${NAME}\" :scope \"…\"} for a service that does
   not register clients by itself; :timeout-ms, :enabled.
   A credential is always a ${NAME}, never its value: the value is
   typed by the person into a field, [:secret {:name \"NAME\"}] in a
   ```hiccup block of your answer, and you never see it. Answers
   {:name :connected :tools [names] :needs [secret names not saved yet]
   :sign-in :error}; its functions mcp.<server>/<tool> are there from the next
   program on, (mcp/call …) reaches them now."
  [handle server opts]
  (admin handle {:admin :add :name (str server) :opts opts}))

(defn mcp-test
  "Connects the MCP server `server` anew: {:name :connected :tools
   [names] :needs :error}, the tools it has or why it failed."
  [handle server]
  (admin handle {:admin :test :name (str server)}))

(defn mcp-enable!
  "Switches the MCP server `server` on (true, connected right away) or
   off (false, closed, its entry kept)."
  [handle server on?]
  (admin handle {:admin :enable :name (str server) :on? (boolean on?)}))

(defn mcp-remove!
  "Removes the MCP server `server` from the machine. Its secrets stay
   saved; the person removes them in the settings."
  [handle server]
  (admin handle {:admin :remove :name (str server)}))

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
   ["(mcp/add! \"github\" {:url \"https://…/mcp\" :token \"GITHUB_TOKEN\"})" "connects an MCP server for the machine; :needs names the secrets the person types into a [:secret {:name \"GITHUB_TOKEN\"}] field of a ```hiccup block in your answer, never into the chat; (mcp/test \"github\") tries it again, (mcp/enable! \"github\" false), (mcp/remove! \"github\")"]
   ["(mcp.github/search_issues {:query \"bug\"})" "one tool of a server, a map of arguments: its structured content, else its text (JSON as data); pictures are shown to you after the answer; (mcp/call \"github\" \"search_issues\" {...}) the same by name"]
   ["(spit \"data/accounts.edn\" accounts)" "data for a later program, as EDN in a file of your work folder, which you see in the pane; a program is one of its own, what it defs is gone with it"]
   ["(edn/read-string (slurp \"data/accounts.edn\"))" "the data back, in a later program; (slurp path) is the file's text"]
   ["(remember \"review-posted\" {:id 4711})" "an event for later runs, kept through every summary and seen by your runs"]
   ["(recall {:event \"review-posted\"})" "your events, oldest first; :since narrows them"]])

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
    "\nMCP: no servers yet; (mcp/add! …) connects one."))

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
       "Chain calls, loop and filter in one program, print only what matters; a program is one of its own, "
       "data for a later one goes into a file with spit.\n\n"
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
                                     'agent (vars agent-ns handle [#'connectors #'connector #'call #'remember #'recall #'spit #'slurp #'help])
                                     'composio (vars composio-ns handle [#'search #'schemas #'execute #'connections #'accounts #'trigger-types #'triggers #'add-trigger #'delete-trigger])
                                     'storrito (cond-> (vars storrito-ns handle [#'procedures #'agent-uuid])
                                                 catalog (merge (storrito-vars (partial call handle) connection catalog)))
                                     'mcp (named-vars mcp-ns handle {'servers #'mcp-servers 'tools #'mcp-tools 'call #'mcp-call
                                                                    'add! #'mcp-add! 'test #'mcp-test 'enable! #'mcp-enable! 'remove! #'mcp-remove!})}
                                    (mcp-namespaces handle @(:mcp-catalog handle)))})]
    (sci/eval-string* ctx preamble)
    ctx))

(defn- context!
  "The base context of `handle`, made at the first program; never
   evaluated in, forked for every program."
  [handle]
  (or @(:context handle)
      (reset! (:context handle) (context handle))))

(defn begin!
  "A program begins: its context a fresh fork of the base, nothing of
   an earlier program in it."
  [handle]
  (reset! (:program handle) (sci/fork (context! handle))))

(defn- program!
  "The context of the program that runs, begun when none has."
  [handle]
  (or @(:program handle)
      (begin! handle)))

(defn handle-of
  "The REPL over what another thread found: `{:gateway :discovery
   :storrito}` as `snapshot` answers them, no request made. `{:gateway
   :discovery :storrito :composio-session :events :mcp-catalog :context
   :program :asked}`, the last seven atoms; the base context is made
   at the first program and stays as it is, `program` the context of
   the program that runs, a fork of it; `asked` is when `connectors`
   last read the list again, 0 before it did."
  [{:keys [gateway discovery storrito]}]
  {:gateway gateway
   :discovery (atom discovery)
   :storrito (atom storrito)
   :composio-session (atom nil)
   :events (atom {:known []})
   :mcp-catalog (atom nil)
   :context (atom nil)
   :program (atom nil)
   :asked (atom 0)})

(defn handle
  "The REPL of the agent over the gateway at `base`: its connectors
   discovered now, one request, or `discovery` as another reading of
   them found it (`rragent.gateway/discover-at-start`, which tries
   again while the gateway comes up); the Storrito catalog read, one
   request; the context is made at the first program."
  ([base]
   (handle base (gateway/discover base)))
  ([base discovery]
   (let [handle (handle-of {:gateway base :discovery discovery})]
     (install-storrito! handle (partial call handle) (storrito/find-catalog discovery))
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

(defn events!
  "The events an attempt sees: `known`, the bot's as they are now."
  [handle known]
  (reset! (:events handle) {:known (vec known)}))

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
  "`code` evaluated as one program of its own, in a fresh fork of the
   base context of `handle`, with no host to answer a call: what it
   printed, then its value after `=>`, cut at ten thousand
   characters; a throw with the error's message, where it was, and
   what was printed before it. For what needs no host: help, doc, the
   closed context."
  [handle code]
  (let [sb (gstring/StringBuffer.)
        value (try
                (sci/binding [sci/print-newline true
                              sci/print-fn (fn [x] (.append sb x))]
                  (sci/eval-string* (sci/fork (context! handle)) (str code)))
                (catch :default e
                  (throw (js/Error. (cut (error-text e (str sb)))))))
        printed (str sb)]
    (cut (str printed
              (when (and (seq printed) (not (str/ends-with? printed "\n"))) "\n")
              "=> " (pr-str value)))))

(defn storrito
  "The Storrito connection and catalog as the handle found them:
   `{:connection :catalog}`, or `{:link :error}`, or nil."
  [handle]
  @(:storrito handle))

(defn reading-error
  "What a throw of `forms` says: the message and where."
  [e]
  (cut (error-text e "")))

(defn forms
  "The forms of `code`, each with its line and column; a throw with
   where the reading failed."
  [handle code]
  (let [ctx (context! handle)
        reader (sci/reader (str code))]
    (loop [out []]
      (let [form (sci/parse-next ctx reader)]
        (if (= ::sci/eof form)
          out
          (recur (conj out form)))))))

(defn- user-vars
  "The vars of the `user` namespace of `ctx`, `{sym value}`."
  [ctx]
  (into {}
        (keep (fn [[sym v]]
                (when (and (symbol? sym) (satisfies? IDeref v))
                  [sym @v])))
        (get-in @(:env ctx) [:namespaces 'user])))

(defn defined
  "What the form defined in `user`, `before` and `after` its vars:
   `{:vars {sym edn}}` when every value can be kept, else `{:redo
   true}`, the form to be run again by a worker that starts over."
  [before after]
  (let [changed (into {}
                      (keep (fn [[sym value]]
                              (when (or (not (contains? before sym))
                                        (not (identical? (get before sym) value)))
                                [sym (edn value)])))
                      after)]
    (if (some nil? (vals changed))
      {:redo true}
      {:vars changed})))

(defn restore!
  "The vars `vars` (`{sym edn}`, as `defined` answered them) defined
   in `user` of the program's context again, for a worker that starts
   over in the middle of a program."
  [handle vars]
  (let [ctx (program! handle)]
    (doseq [[sym s] vars]
      (sci/intern ctx 'user (symbol sym) (reader/read-string s)))))

(defn evaluate-form
  "The form at `index` of `code` evaluated in a fork of the program's
   context, over `answers`, the host's to the calls of the form so far
   (`rragent.need`), and `events`, the bot's as they are: `{:need}`
   with the first call the host has not answered, the attempt thrown
   away; `{:done {:printed :value :vars|:redo}}` when the form ran to
   its end, the fork the program's context from now on, the value as
   text cut at ten thousand characters; `{:error}` for a form that
   failed, with where and what was printed before. The forms are read
   first, a reading error is the answer."
  [handle {:keys [code index answers events]}]
  (let [forms (try
                {:forms (forms handle code)}
                (catch :default e
                  {:error (reading-error e)}))]
    (if (:error forms)
      forms
      (let [form (nth (:forms forms) index)
            base (program! handle)
            fork (sci/fork base)
            before (user-vars fork)
            sb (gstring/StringBuffer.)
            attempt (need/attempt answers)
            outcome (try
                      (binding [need/*attempt* attempt
                                *fork* fork]
                        (events! handle events)
                        (sci/binding [sci/print-newline true
                                      sci/print-fn (fn [x] (.append sb x))]
                          (let [value (sci/eval-form fork form)
                                text (pr-str value)]
                            {:value (cut text)})))
                      (catch :default e
                        {:error (cut (error-text e (str sb)))}))
            noted (need/noted attempt)]
      (cond
        noted {:need noted}
        (:error outcome) outcome
        :else (do (reset! (:program handle) fork)
                  {:done (merge {:printed (cut (str sb)) :value (:value outcome)}
                                (defined before (user-vars fork)))}))))))

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
