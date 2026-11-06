(ns rragent.codemode
  "The core of a machine (`rragent.machine`): Clojure programs
   evaluated by SCI in a closed context that reaches nothing of the
   host. No `js/`, no interop on values, no `require` of anything but
   Clojure's own namespaces, no reading of the environment, and no
   call: the functions of the connectors answer the call as data,
   `(storrito/list-social-accounts {})` is `[:storrito/list-social-accounts
   {}]`, and a program puts such a call under `:next` of its state
   map for the shell to run (`rragent.tools.machine`). A program is
   loaded for its value, a map, the initial state (`load`); when it
   defines `step`, a function of the state, the shell calls it with
   the state after each answer (`step`). Every machine starts from a
   fresh fork of the base context, nothing of an earlier program is
   there, and the state is data that crosses to the host as EDN. What
   a program reaches, it reaches through the namespace `agent`,
   referred in `user`: `connectors`, `connector`, `call`, `remember`,
   `recall`, `spit`, `slurp`, `help` and `doc`, with `composio/*`,
   `storrito/*` and `mcp/*` beside them (`rragent.gateway`,
   `rragent.composio`, `rragent.storrito`), and a namespace
   `mcp.<server>` per MCP server with its tools as functions
   (`rragent.mcp`). The handle is the main thread's view of the
   gateway, the worker takes a snapshot of it (`rragent.codemode-worker`)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [goog.string :as gstring]
            [rragent.composio :as composio]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.sprite :as sprite]
            [rragent.state-machine :as state-machine]
            [rragent.storrito :as storrito]
            [sci.core :as sci]))

(def max-chars
  10000)

(def max-state-chars
  "How long the EDN of a state may be: it crosses to the host and is
   kept with every step."
  1000000)

(def denied
  "The functions of cljs.core that reach JavaScript objects and would
   open the host (`aget` on a function is its constructor, which makes
   functions from strings): a program has data, not objects. Each is
   replaced by one that says so."
  '[aget aset js-invoke unchecked-get unchecked-set js-obj js-keys js-delete clj->js js->clj])

(defn- denied-fn
  [sym]
  (fn [& _]
    (throw (js/Error. (str sym " is not available in a machine: it reaches JavaScript objects, and a machine has data, not objects.")))))

(def denied-core
  "What `clojure.core` holds for the denied names."
  (into {} (map (fn [sym] [sym (denied-fn sym)])) denied))

(def preamble
  "What every program starts from: the agent namespace referred, EDN
   at hand, `doc` a macro over `doc-of`."
  "(ns user (:require [agent :refer :all] [clojure.edn :as edn])) (defmacro doc [sym] (list 'agent/doc-of (list 'quote sym)))")

(def ^:dynamic *fork*
  "The context of the program that runs, for `doc-of`; nil outside
   one."
  nil)

(def agent-ns
  (sci/create-ns 'agent))

(def composio-ns
  (sci/create-ns 'composio))

(def storrito-ns
  (sci/create-ns 'storrito))

(def mcp-ns
  (sci/create-ns 'mcp))

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn edn
  "`value` as EDN that reads back, for the host; nil when it does not
   (a function, a regex, an object, a record) or is longer than
   `max-state-chars`."
  [value]
  (let [s (binding [*print-length* 1000000
                    *print-level* 1000]
            (pr-str value))]
    (when (<= (count s) max-state-chars)
      (try
        (when (= value (reader/read-string s))
          s)
        (catch :default _ nil)))))

(defn- map-of-data!
  "`value` when it is a map that reads back as EDN; a throw that says
   `what` otherwise."
  [what value]
  (when-not (map? value)
    (throw (js/Error. (str what " is a map, the state of the machine; this is " (pr-str (type value)) ": " (cut (pr-str value))))))
  (when-not (edn value)
    (throw (js/Error. (str what " must be data that reads back as EDN, at most " max-state-chars " characters: a function, a regex or an object in it does not."))))
  value)

;;; The connectors as calls

(defn- storrito-vars
  "One var per procedure of `catalog`, named as the procedure, its
   docstring from the catalog, each answering the call
   `[:storrito/<name> args]`."
  [catalog]
  (into {}
        (map (fn [{:keys [name] :as procedure}]
               (let [sym (symbol name)]
                 [sym (sci/new-var sym
                                   (fn [& [args]]
                                     (when-not (or (nil? args) (map? args))
                                       (throw (js/Error. (str "storrito/" name " takes a map of arguments"))))
                                     [(keyword "storrito" name) (or args {})])
                                   {:ns storrito-ns :name sym
                                    :doc (str "A call, for :next: answers [:storrito/" name " args].\n\n" (storrito/docstring procedure))
                                    :arglists '([] [args])})])))
        (:procedures catalog)))

(defn- install-storrito!
  "The `storrito/` functions of the catalog of `found`, the Storrito
   connection among the gateway's with its catalog
   (`rragent.storrito/find-catalog`), when there is one; what went
   wrong kept for `procedures` to say. Into the base context when
   there is one already; else the context takes them when it is made."
  [handle found]
  (let [{:keys [catalog]} found]
    (reset! (:storrito handle) found)
    (when (and catalog @(:context handle))
      (swap! (:context handle) sci/merge-opts {:namespaces {'storrito (storrito-vars catalog)}}))))

(defn call
  "A call, for :next: an HTTP request through the gateway to a
   connection, `which` its id, or its provider name when one
   connection has it (several custom_api connections: name the id).
   `path` is appended to the connection's base, the gateway adds the
   credential. `opts`: :method (GET), :body (data goes as JSON, a
   string as it is), :headers (a map), :query (a map, appended to the
   URL), :to-file (a path under your work folder: the body is written
   there, the answer carries the file instead of the body; for
   anything big or binary). The answer under :as is {:status :headers
   :body}, the body as data when it is JSON; a failed request
   {:error ...}. A connection that is not there is refused here."
  [handle which path & [opts]]
  (let [discovery @(:discovery handle)]
    (when-not (gateway/connection discovery which)
      (throw (js/Error. (gateway/no-connector-message discovery which))))
    (when-not (or (nil? opts) (map? opts))
      (throw (js/Error. "call takes a map of options")))
    [:call (str which) (str path) (or opts {})]))

(defn connectors
  "A call, for :next: the connectors of the gateway, read again:
   {:connections [...] :available [...]}; a connection has :id,
   :provider, :gateway_base_url, :notes and :provider_info, an
   available provider :provider, :display_name and :description.
   The storrito/ functions follow the reading, in the next machine."
  [_handle]
  [:connectors])

(defn connector
  "One connection as the machine started: by its id, or by its
   provider name when one connection has it; nil when there is none."
  [handle which]
  (gateway/connection @(:discovery handle) which))

(defn refresh!
  "The gateway's list read again by the main thread, one blocking
   request, the Storrito catalog with it, for `rragent.openrouter`,
   which asks again while no `openrouter` connection is known."
  [handle]
  (reset! (:asked handle) (js/Date.now))
  (let [discovery (gateway/discover (:gateway handle))]
    (reset! (:discovery handle) discovery)
    (install-storrito! handle (storrito/find-catalog discovery))
    discovery))

(defn remember
  "A call, for :next: an event for later runs, `event` a short name
   such as \"review-posted\", `data` a map of your own keys. Events
   are never changed or lost, the conversation's summaries leave them
   alone, and your runs see them as you do."
  [_handle event data]
  (when (str/blank? (str event))
    (throw (js/Error. "remember takes an event name")))
  (when-not (or (nil? data) (map? data))
    (throw (js/Error. "remember takes a map of data")))
  [:remember (str event) (or data {})])

(defn recall
  "A call, for :next: your events, oldest first, maps with :event, :at
   (milliseconds) and your own keys. `opts`: :event (only that kind),
   :since (an ISO date or milliseconds)."
  [_handle & [opts]]
  (when-not (or (nil? opts) (map? opts))
    (throw (js/Error. "recall takes a map of options")))
  [:recall (or opts {})])

(defn- file-path
  [path]
  (let [p (str path)]
    (when (or (str/blank? p) (str/starts-with? p "/") (re-find #"(^|/)\.\.(/|$)" p) (str/includes? p "\\"))
      (throw (js/Error. (str "spit and slurp take a path inside your work folder, such as \"data/accounts.edn\", not " (pr-str path)))))
    p))

(defn spit
  "A call, for :next: `data` written to the file `path` of your work
   folder, a string as it is, anything else as EDN, which
   (edn/read-string (slurp path)) reads back in a later machine;
   data that would not read back is refused here. The folders are
   made; the answer is the path."
  [_handle path data]
  (let [path (file-path path)
        content (if (string? data) data (edn data))]
    (when-not content
      (throw (js/Error. (str "spit writes data that reads back as EDN: " (pr-str (type data)) " does not, or it is longer than " max-state-chars " characters."))))
    [:spit path content]))

(defn slurp
  "A call, for :next: the text of the file `path` of your work folder;
   a file that is not there answers {:error ...}."
  [_handle path]
  [:slurp (file-path path)])

(defn search
  "A call, for :next: the tools of the organization's services (Gmail,
   Google Drive, Notion, Slack, GitHub and more) for a use case in
   plain words, such as \"send an email\"; `toolkits` narrows it to
   those services (slugs such as \"gmail\"). Answers tool slugs with
   their input schemas and which services are connected; the session
   it answers is kept for the calls after it."
  [_handle use-case & [toolkits]]
  [:composio/search (str use-case) (vec toolkits)])

(defn schemas
  "A call, for :next: the input schemas of tools by slug, as a search
   answered them."
  [_handle slugs]
  [:composio/schemas (vec slugs)])

(defn execute
  "A call, for :next: one tool found with search run with the
   arguments its schema asks for: the results of the run; a failed
   run {:error ...} with Composio's error (a service that is not
   connected, for one: connect it with connections first)."
  [_handle slug & [args]]
  (when-not (or (nil? args) (map? args))
    (throw (js/Error. "composio/execute takes a map of arguments")))
  [:composio/execute (str slug) (or args {})])

(defn connections
  "A call, for :next: the organization's connections of services, by
   Composio toolkit slug (googlesheets, gmail, googledrive, googledocs,
   googlecalendar, slack, notion, github), a missing one started: the
   answer carries a link the person opens to sign in at the service,
   good for ten minutes. Run it in the answer in which the person
   names a service of theirs, give them the link and go on once they
   say they are connected."
  [_handle toolkits]
  (when-not (and (sequential? toolkits) (seq toolkits))
    (throw (js/Error. "composio/connections takes a vector of toolkit slugs")))
  [:composio/connections (vec toolkits)])

(defn accounts
  "A call, for :next: your connected accounts at Composio, {:id
   :toolkit :status} each: what connections made, by service."
  [_handle]
  [:composio/accounts])

(defn trigger-types
  "A call, for :next: the events a service offers, {:slug :name :type
   :config} each, the config the schema of what add-trigger takes for
   it: (composio/trigger-types \"googledrive\")."
  [_handle toolkit]
  [:composio/trigger-types (str toolkit)])

(defn triggers
  "A call, for :next: your triggers at Composio, {:id :slug
   :connected-account :state} each; every event of a trigger comes to
   you as a message, with the service's payload."
  [_handle]
  [:composio/triggers])

(defn add-trigger
  "A call, for :next: a trigger of the kind `slug` on the connected
   account `connected-account-id` (one of accounts), with `config` as
   its type asks: from then on every event of it is posted to this
   machine and arrives in your conversation. Answers {:id :slug
   :account}, the id noted as a fact so that the events find you:
   (composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\")."
  [_handle slug connected-account-id & [config]]
  (when-not (or (nil? config) (map? config))
    (throw (js/Error. "composio/add-trigger takes a map of config")))
  [:composio/add-trigger (str slug) (str connected-account-id) (or config {})])

(defn delete-trigger
  "A call, for :next: the trigger `id` deleted at Composio; its events
   stop."
  [_handle id]
  [:composio/delete-trigger (str id)])

(defn procedures
  "The procedures of the organization's Storrito API, {:name :doc}
   each, every one a function storrito/<name> that takes a map and
   answers a call for :next; (doc storrito/<name>) says its
   parameters. Pure: answers at once."
  [handle]
  (let [{:keys [catalog link error]} @(:storrito handle)]
    (cond
      catalog (storrito/summary catalog)
      error (throw (js/Error. (str "The Storrito catalog at " link " could not be read: " error)))
      :else (throw (js/Error. (str "No Storrito among the connections: no custom_api connection of the gateway "
                                   "has a catalog at its base. (connectors) lists what there is."))))))

(defn agent-uuid
  "The uuid of this agent at Storrito, the `agentUuid` the
   storrito/ integration procedures take: the `agent:` label of the
   machine. Throws on a machine without one."
  [_handle]
  (or (sprite/label (:labels (sprite/discover)) "agent")
      (throw (js/Error. "No agent uuid: this machine has no `agent:` label, it is no Sprite that Storrito made for an agent."))))

;;; MCP

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
         "\n\nA call, for :next: answers [:mcp/call \"" server "\" \"" name "\" args]. "
         (if (seq properties)
           (str "It takes a map:\n" (str/join "\n" (map (partial param-line required) properties)))
           "It takes no arguments."))))

(defn- mcp-call-vector
  [server tool args]
  (when-not (or (nil? args) (map? args))
    (throw (js/Error. (str "mcp." server "/" tool " takes a map of arguments, such as {:query \"x\"}"))))
  [:mcp/call (str server) (str tool) (or args {})])

(defn- tool-vars
  "The functions of the tools of one server, in its namespace; of two
   tools with the same symbol, the first (mcp/call reaches the other)."
  [{server :name ns-name :ns tools :tools}]
  (let [sci-ns (sci/create-ns (symbol ns-name))]
    (reduce (fn [vars {tool :name :as t}]
              (let [sym (symbol (mcp/symbol-name tool))
                    takes (seq (get-in t [:input-schema "properties"]))]
                (if (contains? vars sym)
                  vars
                  (assoc vars sym (sci/new-var sym
                                               (fn [& [args]] (mcp-call-vector server tool args))
                                               {:ns sci-ns :name sym :doc (tool-doc server t)
                                                :arglists (if takes '([args]) '([] [args]))})))))
            {}
            tools)))

(defn- mcp-namespaces
  "`{mcp.<server> {tool var}}` of `catalog`."
  [catalog]
  (into {}
        (keep (fn [{:keys [ns tools] :as server}]
                (when (seq tools)
                  [(symbol ns) (tool-vars server)])))
        (:servers catalog)))

(defn install-mcp!
  "`catalog` (`rragent.mcp/snapshot`) as the MCP servers of the
   handle: into the base context when there is one already; else the
   context takes them when it is made."
  [handle catalog]
  (reset! (:mcp-catalog handle) catalog)
  (when @(:context handle)
    (swap! (:context handle) sci/merge-opts {:namespaces (mcp-namespaces catalog)})))

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
   server says about using it. Pure: answers at once."
  [handle]
  (mapv (fn [server]
          (-> server
              (update :tools count)
              (select-keys [:name :ns :tools :connected :connecting :off :needs :oauth :sign-in :error :instructions])))
        (:servers @(:mcp-catalog handle))))

(defn mcp-tools
  "The tools of the MCP server `server`: {:name :fn :description}
   each, :fn the function that answers its call, nil for a name whose
   symbol an earlier tool has (mcp/call reaches it); (doc <fn>) says
   its arguments. Pure: answers at once."
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
  "A call, for :next: the tool `tool` of the MCP server `server` with
   the map `args`, what (mcp.<server>/<tool> args) answers, for a name
   that is no symbol. The answer is the structured content when the
   tool gives one, else its text, as data when it is JSON; pictures
   are shown to you after the machine's answer; a failed call
   {:error ...}."
  [_handle server tool & [args]]
  (mcp-call-vector server tool args))

(defn mcp-add!
  "A call, for :next: the MCP server `server` added to the machine, or
   its entry replaced, and connected right away. `opts`: {:url
   \"https://…/mcp\"} for a Streamable HTTP server, :token \"NAME\"
   when it takes a bearer token (the header Authorization: Bearer
   ${NAME}), :headers {\"X-Api-Key\" \"${NAME}\"} for another header;
   or {:command \"npx\" :args [...] :env {...} :cwd \"folder\"} for a
   program; :oauth true for a server the person signs in to in their
   browser (OAuth): the answer has :sign-in true until they did, with
   the link [:sign-in {:server \"github\"}] in a ```hiccup block of
   your answer; :oauth {:client-id \"…\" :client-secret \"${NAME}\"
   :scope \"…\"} for a service that does not register clients by
   itself; :timeout-ms, :enabled. A credential is always a ${NAME},
   never its value: the value is typed by the person into a field,
   [:secret {:name \"NAME\"}] in a ```hiccup block of your answer, and
   you never see it. Answers {:name :connected :tools [names] :needs
   [secret names not saved yet] :sign-in :error}; its functions
   mcp.<server>/<tool> are there from the next machine on."
  [_handle server opts]
  (when-not (map? opts)
    (throw (js/Error. "mcp/add! takes a map of options")))
  [:mcp/add! (str server) opts])

(defn mcp-test
  "A call, for :next: the MCP server `server` connected anew: {:name
   :connected :tools [names] :needs :error}, the tools it has or why
   it failed."
  [_handle server]
  [:mcp/test (str server)])

(defn mcp-enable!
  "A call, for :next: the MCP server `server` switched on (true,
   connected right away) or off (false, closed, its entry kept)."
  [_handle server on?]
  [:mcp/enable! (str server) (boolean on?)])

(defn mcp-remove!
  "A call, for :next: the MCP server `server` removed from the
   machine. Its secrets stay saved; the person removes them in the
   settings."
  [_handle server]
  [:mcp/remove! (str server)])

;;; Help and doc

(def help-lines
  "The bindings, one form and one line each."
  [["(help)" "this text, as {:help ...}"]
   ["(doc f)" "the documentation of any function, as {:doc ...}: (doc call), (doc storrito/list-instagram-users)"]
   ["{:next {:call (storrito/list-social-accounts {}) :as :accounts}}" "a program's value is its state, a map; :next names one call and the key its answer goes under; a failed call answers {:error ...} under that key"]
   ["(defn step [state] ...)" "with step defined, the shell calls it with the state after every answer, until it answers a state without :next or with :done; one call per step, the state is data that is kept with every step"]
   ["(connectors)" "a call: the connectors of the gateway, read again: :connections (:id, :provider, :gateway_base_url, :provider_info) and :available"]
   ["(connector \"github\")" "one connection as the machine started, by provider name or by id (several custom_api connections: the id); pure"]
   ["(call \"github\" \"/user/repos\" {:query {:per_page 5}})" "a call: an HTTP request through the gateway, by provider name or connection id: {:status :headers :body}, JSON as data; :method, :body (data as JSON), :headers, :to-file for a big or binary body"]
   ["(composio/search \"send an email\" [\"gmail\"])" "a call: the tools of the organization's services for a use case: slugs, schemas, what is connected"]
   ["(composio/schemas [\"GMAIL_SEND_EMAIL\"])" "a call: the input schemas of tools by slug"]
   ["(composio/execute \"GMAIL_SEND_EMAIL\" {:to \"a@example.com\"})" "a call: one tool run with its arguments"]
   ["(composio/connections [\"googlesheets\"])" "a call: the connections of services by toolkit slug (googlesheets, gmail, googledrive, slack, notion, github), a missing one started: a sign-in link for the person, good for ten minutes; the machine to run as soon as they name a service of theirs"]
   ["(composio/accounts)" "a call: your connected accounts, by service: {:id :toolkit :status}"]
   ["(composio/trigger-types \"googledrive\")" "a call: the events a service offers and the config each takes"]
   ["(composio/add-trigger \"GOOGLEDRIVE_FILE_CREATED_TRIGGER\" \"ca_1\" {})" "a call: every event of that kind on the connected account arrives in your conversation from then on; answers the trigger's :id"]
   ["(composio/triggers)" "a call: your triggers: {:id :slug :connected-account :state}; (composio/delete-trigger \"ti_1\") ends one"]
   ["(storrito/procedures)" "the organization's Storrito API, one function storrito/<name> per procedure, a map of arguments each, each a call; pure"]
   ["(storrito/agent-uuid)" "your uuid at Storrito, the agentUuid some storrito/ procedures take; pure"]
   ["(mcp/servers)" "the MCP servers of the machine: their namespaces, how many tools, whether connected, their instructions; pure"]
   ["(mcp/tools \"github\")" "the tools of one server: {:name :fn :description}, (doc mcp.github/<tool>) says its arguments; pure"]
   ["(mcp/add! \"github\" {:url \"https://…/mcp\" :token \"GITHUB_TOKEN\"})" "a call: connects an MCP server for the machine; :needs names the secrets the person types into a [:secret {:name \"GITHUB_TOKEN\"}] field of a ```hiccup block in your answer, never into the chat; (mcp/test \"github\"), (mcp/enable! \"github\" false), (mcp/remove! \"github\") are calls too"]
   ["(mcp.github/search_issues {:query \"bug\"})" "a call: one tool of a server, a map of arguments: its structured content, else its text (JSON as data); pictures are shown to you after the answer; (mcp/call \"github\" \"search_issues\" {...}) the same by name"]
   ["(remember \"review-posted\" {:id 4711})" "a call: an event for later runs, kept through every summary and seen by your runs"]
   ["(recall {:event \"review-posted\"})" "a call: your events, oldest first; :since narrows them"]
   ["(spit \"data/accounts.edn\" accounts)" "a call: data for a later machine, as EDN in a file of your work folder, which you see in the pane"]
   ["(edn/read-string (slurp \"data/accounts.edn\"))" "(slurp path) is a call whose answer is the file's text; read it back in the step after"]])

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
           (not (composio/connection discovery)) "\nComposio: no connection, the composio/ calls will say so."
           (not (composio/session-id)) "\nComposio: no session yet (it is made when the agent starts), the composio/ calls will say so."
           :else (str "\nComposio: through " (:id (composio/connection discovery)) ", session " (composio/session-id)
                      (when-let [user (composio/user-id)] (str ", your accounts and triggers under " user)) "."))
         (mcp-status (:servers @(:mcp-catalog handle))))))

(defn help-text
  [handle]
  (str "A machine: Clojure with the organization's connectors as calls, nothing of the host. "
       "A program's value is its state, a map; a call goes under :next, the shell runs it and puts the answer under :as; "
       "with step defined the shell calls (step state) after every answer. Nothing runs inside the program.\n\n"
       (str/join "\n" (map (fn [[form what]] (str form "\n    " what)) help-lines))
       "\n\n"
       (status-text handle)))

(defn help
  "The functions of a machine, one line each, and what is connected:
   {:help text}. Pure."
  [handle]
  {:help (help-text handle)})

(defn- arglists
  "The arglists of a var's meta, which the compiler keeps quoted."
  [m]
  (let [lists (:arglists m)]
    (if (and (seq? lists) (= 'quote (first lists))) (second lists) lists)))

(defn doc-of
  "The documentation of the function `sym` names, {:doc text}, as
   (doc f) gives it; a throw for a name that is none."
  [_handle sym]
  (let [v (when *fork* (sci/resolve *fork* sym))]
    (when-not v
      (throw (js/Error. (str "No such function: " sym))))
    (let [{:keys [ns name doc] :as m} (meta v)]
      {:doc (str (when ns (str ns "/")) name
                 (when-let [lists (seq (arglists m))] (str "\n" (pr-str lists)))
                 "\n  " (or doc "No documentation."))})))

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
  (let [{:keys [catalog]} @(:storrito handle)
        ctx (sci/init {:features #{:cljs}
                       :namespaces (merge
                                    {'clojure.core denied-core
                                     'agent (vars agent-ns handle [#'connectors #'connector #'call #'remember #'recall #'spit #'slurp #'help #'doc-of])
                                     'composio (vars composio-ns handle [#'search #'schemas #'execute #'connections #'accounts #'trigger-types #'triggers #'add-trigger #'delete-trigger])
                                     'storrito (cond-> (vars storrito-ns handle [#'procedures #'agent-uuid])
                                                 catalog (merge (storrito-vars catalog)))
                                     'mcp (named-vars mcp-ns handle {'servers #'mcp-servers 'tools #'mcp-tools 'call #'mcp-call
                                                                    'add! #'mcp-add! 'test #'mcp-test 'enable! #'mcp-enable! 'remove! #'mcp-remove!})}
                                    (mcp-namespaces @(:mcp-catalog handle)))})]
    (sci/eval-string* ctx preamble)
    ctx))

(defn- context!
  "The base context of `handle`, made at the first program; never
   evaluated in, forked for every machine."
  [handle]
  (or @(:context handle)
      (reset! (:context handle) (context handle))))

;;; The handle

(defn handle-of
  "The core over what another thread found: `{:gateway :discovery
   :storrito}` as `snapshot` answers them, no request made.
   `{:gateway :discovery :storrito :mcp-catalog :context :program
   :asked}`, the last six atoms; the base context is made at the first
   program and stays as it is, `program` the context of the machine
   that runs, a fork of it; `asked` is when `refresh!` last read the
   list again, 0 before it did."
  [{:keys [gateway discovery storrito]}]
  {:gateway gateway
   :discovery (atom discovery)
   :storrito (atom storrito)
   :mcp-catalog (atom nil)
   :context (atom nil)
   :program (atom nil)
   :asked (atom 0)})

(defn handle
  "The core of the agent over the gateway at `base`: its connectors
   discovered now, one request, or `discovery` as another reading of
   them found it (`rragent.gateway/discover-at-start`, which tries
   again while the gateway comes up); the Storrito catalog read, one
   request; the context is made at the first program."
  ([base]
   (handle base (gateway/discover base)))
  ([base discovery]
   (let [handle (handle-of {:gateway base :discovery discovery})]
     (install-storrito! handle (storrito/find-catalog discovery))
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

(defn storrito
  "The Storrito connection and catalog as the handle found them:
   `{:connection :catalog}`, or `{:link :error}`, or nil."
  [handle]
  @(:storrito handle))

(defn asked-at
  "When `refresh!` last read the gateway's list again, in
   milliseconds; 0 while only the reading at start was made."
  [handle]
  @(:asked handle))

;;; Running a program

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

(defn- evaluating
  "`f` called with a fresh StringBuffer the program prints into, in
   `fork`: `{:value :printed}`; a throw with the error's text, where it
   was, and what was printed before."
  [fork f]
  (let [sb (gstring/StringBuffer.)]
    (try
      (binding [*fork* fork]
        (sci/binding [sci/print-newline true
                      sci/print-fn (fn [x] (.append sb x))]
          {:value (f) :printed (cut (str sb))}))
      (catch :default e
        (throw (js/Error. (cut (error-text e (str sb)))))))))

(defn- forms
  "The forms of `code`, read in `fork`; a throw with where the reading
   failed."
  [fork code]
  (let [reader (sci/reader (str code))]
    (loop [out []]
      (let [form (sci/parse-next fork reader)]
        (if (= ::sci/eof form)
          out
          (recur (conj out form)))))))

(defn- discarded-call!
  "A throw when `value`, the value of a form that is not the last, is
   a call: written as a statement, it would do nothing, since a call
   is data for :next."
  [form value]
  (when (and (state-machine/call? value)
             (state-machine/known-call? value))
    (throw (js/Error. (str (pr-str form) " is a call, data that does nothing by itself: put it under :next of the state, "
                           "{:next {:call " (pr-str form) " :as :" (name (first value)) "}}, and the host runs it.")))))

(defn load
  "`code` evaluated as a machine of its own, in a fresh fork of the
   base context, which is the program's context from then on, one
   form after the other: the value of the last, a map that reads back
   as EDN, is the initial state; `{:state :step? :printed}`, `step?`
   whether the program defined `step`. A throw with the error's text
   for a program that fails, whose value is no map, whose step is no
   function, or one of whose other forms is a call written as a
   statement (`discarded-call!`)."
  [handle code]
  (let [fork (sci/fork (context! handle))
        {:keys [value printed]} (evaluating fork (fn []
                                                   (let [forms (forms fork code)]
                                                     (loop [[form & more] forms
                                                            value nil]
                                                       (if-not form
                                                         value
                                                         (let [value (sci/eval-form fork form)]
                                                           (when (seq more)
                                                             (discarded-call! form value))
                                                           (recur more value)))))))
        state (map-of-data! "A program's value" value)
        step (sci/resolve fork 'user/step)
        step? (some? step)]
    (when (and step? (not (fn? @step)))
      (throw (js/Error. "step is a function of the state, (defn step [state] ...)")))
    (reset! (:program handle) fork)
    {:state state :step? step? :printed printed}))

(defn step
  "`(step state)` evaluated in the context of the machine that was
   loaded: `{:state :printed}`, the state a map that reads back as
   EDN; a throw with the error's text for a step that fails or answers
   no such map."
  [handle state]
  (let [fork (or @(:program handle)
                 (throw (js/Error. "No program is loaded in this worker.")))
        step (or (sci/resolve fork 'user/step)
                 (throw (js/Error. "The program defines no step.")))
        {:keys [value printed]} (evaluating fork #(@step state))]
    {:state (map-of-data! "A step's value" value) :printed printed}))

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
