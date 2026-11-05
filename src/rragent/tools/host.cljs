(ns rragent.tools.host
  "The tools that answer a program's needs (`rragent.need`): what a
   program of the REPL asks the host for, each a nested call of the
   eval tool under the key of the form and the call
   (`rragent.tools.eval`), so that a call the machine's restart
   interrupted is found by its key and not made again. Only tools call
   them, never the model (`callers [\"tools\"]`); their value is for
   the program. `gateway` and `gateway-read` are one HTTP request
   through the gateway, the second one the host may make again after a
   restart (a query: a GET, or a Storrito procedure that only reads);
   `mcp` a tool of an MCP server, its pictures in the value for the
   host to show; `mcp-admin` a change of the servers; `connectors` the
   gateway's list read again, with the Storrito catalog; `remember` is
   the bot's own tool (`rragent.tools.events`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.codemode :as codemode]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.storrito :as storrito]))

(def names
  ["gateway" "gateway-read" "mcp" "mcp-admin" "connectors"])

(defn- answer
  "A result whose value is `data`, for the program."
  [data]
  #js {:output #js [#js {:type "text" :text (pr-str data)}]
       :structuredOutput (clj->js data)})

(defn- failure
  "An error result whose value says why, for the program to catch."
  [message]
  #js {:output #js [#js {:type "text" :text (str message)}]
       :structuredOutput (clj->js {:error (str message)})
       :isError true})

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async request
  "One request through the gateway of `codemode` to the connection
   `which` at `path` with `opts` (`rragent.gateway/fetch!`), tried
   again as `:retry` of the opts says (`{:statuses :times :delay-ms}`):
   `{:status :headers :body}`."
  [codemode which path opts]
  (let [{:keys [statuses times delay-ms]} (:retry opts)]
    (loop [tries 1]
      (let [reply (await (gateway/fetch! (codemode/discovery codemode) which path (dissoc opts :retry)))]
        (if (and (contains? (set statuses) (:status reply)) (< tries (or times 1)))
          (do (await (sleep (or delay-ms 1000)))
              (recur (inc tries)))
          reply)))))

(defn- gateway-tool
  [codemode name replay]
  (defineTool
   #js {:name name
        :description "One HTTP request through the Sprites gateway, for a program of the REPL."
        :callers #js ["tools"]
        :replay replay
        :parameters (.Object Type #js {:which (.String Type #js {:description "The connection's id"})
                                       :path (.String Type #js {:description "The path at the connection's base"})
                                       :opts (.Optional Type (.Any Type #js {:description "method, body, headers, query, retry"}))})
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args _api _call-context]
                   (let [opts (js->clj (or (.-opts args) #js {}) :keywordize-keys true)]
                     (try
                       (answer (await (request codemode (.-which args) (.-path args) opts)))
                       (catch :default e
                         (failure (ex-message e))))))}))

(defn gateway
  "The `gateway` tool: a request the host makes once."
  [codemode]
  (gateway-tool codemode "gateway" "unsafe"))

(defn gateway-read
  "The `gateway-read` tool: a request the host may make again after a
   restart."
  [codemode]
  (gateway-tool codemode "gateway-read" "safe"))

(defn mcp-tool
  "The `mcp` tool: the tool `tool` of the server `server` called with
   `args` (`rragent.mcp/call!`); `{:value :images}`, the pictures
   `{:data :mime-type}` each; a failed call an error result."
  [codemode]
  (defineTool
   #js {:name "mcp"
        :description "One tool of an MCP server of the machine, for a program of the REPL."
        :callers #js ["tools"]
        :replay "safe"
        :parameters (.Object Type #js {:server (.String Type)
                                       :tool (.String Type)
                                       :args (.Optional Type (.Any Type))})
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args _api call-context]
                   (let [registry (:mcp codemode)]
                     (if-not registry
                       (failure "There are no MCP servers here.")
                       (let [{:keys [value images error]} (await (mcp/call! registry (.-server args) (.-tool args)
                                                                             (js->clj (or (.-args args) #js {}) :keywordize-keys true)
                                                                             {:signal (.-abortSignal call-context)}))]
                         (if error
                           (failure error)
                           (answer {:value value :images (vec images)}))))))}))

(defn mcp-admin
  "The `mcp-admin` tool: a change of the MCP servers
   (`rragent.mcp/add!`, `test!`, `enable!`, `remove!`): `{:value}`."
  [codemode]
  (defineTool
   #js {:name "mcp-admin"
        :description "A change of the machine's MCP servers, for a program of the REPL."
        :callers #js ["tools"]
        :parameters (.Object Type #js {:admin (.String Type #js {:description "add, test, enable or remove"})
                                       :name (.String Type)
                                       :opts (.Optional Type (.Any Type))
                                       :on? (.Optional Type (.Boolean Type))})
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args _api _call-context]
                   (let [registry (:mcp codemode)
                         name (.-name args)
                         opts (js->clj (or (.-opts args) #js {}) :keywordize-keys true)]
                     (if-not registry
                       (failure "There are no MCP servers here.")
                       (try
                         (answer {:value (case (.-admin args)
                                          "add" (await (mcp/add! registry name opts))
                                          "test" (await (mcp/test! registry name))
                                          "enable" (await (mcp/enable! registry name (true? (.-on? args))))
                                          "remove" (await (mcp/remove! registry name))
                                          (throw (js/Error. (str "No such change of the MCP servers: " (.-admin args)))))})
                         (catch :default e
                           (failure (ex-message e)))))))}))

(defn connectors
  "The `connectors` tool: the gateway's list read again
   (`rragent.gateway/discover!`) and the Storrito catalog among it
   (`rragent.storrito/find-catalog!`), the REPL of the host taking
   both: `{:discovery :storrito}`."
  [codemode]
  (defineTool
   #js {:name "connectors"
        :description "The connectors of the Sprites gateway, read again, for a program of the REPL."
        :callers #js ["tools"]
        :replay "safe"
        :parameters (.Object Type #js {})
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [_args _api _call-context]
                   (let [discovery (await (gateway/discover! (:gateway codemode)))
                         found (await (storrito/find-catalog! discovery))]
                     (reset! (:discovery codemode) discovery)
                     (reset! (:storrito codemode) found)
                     (reset! (:asked codemode) (js/Date.now))
                     (answer {:discovery discovery :storrito found})))}))

(defn tools
  "All of them, over the REPL `codemode`."
  [codemode]
  [(gateway codemode) (gateway-read codemode) (mcp-tool codemode) (mcp-admin codemode) (connectors codemode)])
