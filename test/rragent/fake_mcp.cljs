(ns rragent.fake-mcp
  "An MCP server for the tests, on pi-mcp's in-memory transport pair
   (`@earendil-works/pi-mcp/testing`): `transport-fn` is what
   `rragent.mcp/registry` takes as `:transport`, every connection a
   new pair with a fresh server on its other end. The server speaks
   just enough of the protocol: initialize (with instructions),
   tools/list in two pages, tools/call, and it notes the cancellations
   it is told. Its tools: `echo` answers `echo: <text>`, `json` a
   text that is JSON, `structured` structured content beside a text,
   `fail` a result with `isError`, `picture` a text and a PNG,
   `slow` reports progress twice and answers after `ms`, `hang` never
   answers, `files.read` has a name that is no symbol, `nothing`
   answers no content at all."
  (:require ["@earendil-works/pi-mcp" :refer [McpSessionExpiredError]]
            ["@earendil-works/pi-mcp/testing" :refer [createInMemoryTransportPair]]))

(def png
  "A PNG of one pixel, base64."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

(def tools
  [{:name "echo"
    :description "Answers with the text it was given."
    :inputSchema {:type "object"
                  :properties {:text {:type "string" :description "The text to echo"}}
                  :required ["text"]}}
   {:name "json" :description "Answers JSON as text." :inputSchema {:type "object"}}
   {:name "structured" :description "Answers structured content." :inputSchema {:type "object"}}
   {:name "fail" :description "Fails." :inputSchema {:type "object"}}
   {:name "picture" :title "A picture" :description "Answers a picture." :inputSchema {:type "object"}}
   {:name "slow"
    :description "Reports progress, then answers."
    :inputSchema {:type "object"
                  :properties {:ms {:type "number"}
                               :unit {:type "string" :enum ["ms" "s"]}}}}
   {:name "hang" :description "Never answers." :inputSchema {:type "object"}}
   {:name "files.read" :description "A dotted name." :inputSchema {:type "object"}}
   {:name "nothing" :description "Answers no content." :inputSchema {:type "object"}}])

(defn- send!
  [transport message]
  (-> (.send transport (clj->js message))
      (.catch (fn [_] nil))))

(defn- result!
  [transport id result]
  (send! transport {:jsonrpc "2.0" :id id :result result}))

(defn- call!
  "The answer to a tools/call, by the tool's name."
  [{:keys [calls]} transport id {:keys [name arguments _meta]}]
  (swap! calls conj {:name name :arguments arguments})
  (case name
    "echo" (result! transport id {:content [{:type "text" :text (str "echo: " (:text arguments))}]})
    "json" (result! transport id {:content [{:type "text" :text "{\"items\": [{\"id\": 1}, {\"id\": 2}], \"next\": null}"}]})
    "structured" (result! transport id {:content [{:type "text" :text "two"}] :structuredContent {:n 2 :names ["a" "b"]}})
    "fail" (result! transport id {:content [{:type "text" :text "it broke"}] :isError true})
    "picture" (result! transport id {:content [{:type "text" :text "a dot"} {:type "image" :data png :mimeType "image/png"}]})
    "files.read" (result! transport id {:content [{:type "text" :text "read"}]})
    "nothing" (result! transport id {:content []})
    "slow" (let [token (:progressToken _meta)
                 ms (or (:ms arguments) 50)]
             (when token
               (send! transport {:jsonrpc "2.0" :method "notifications/progress"
                                 :params {:progressToken token :progress 1 :total 2 :message "halfway"}})
               (js/setTimeout #(send! transport {:jsonrpc "2.0" :method "notifications/progress"
                                                 :params {:progressToken token :progress 2 :total 2}})
                              (/ ms 2)))
             (js/setTimeout #(result! transport id {:content [{:type "text" :text "done"}]}) ms))
    "hang" nil
    (send! transport {:jsonrpc "2.0" :id id :error {:code -32602 :message (str "Unknown tool " name)}})))

(defn- answer!
  [{:keys [requests cancelled tools init-delay-ms] :as fake} transport message]
  (let [{:keys [id method params]} (js->clj message :keywordize-keys true)]
    (swap! requests conj method)
    (case method
      "initialize" (js/setTimeout #(result! transport id {:protocolVersion (:protocolVersion params)
                                                          :capabilities {:tools {:listChanged true}}
                                                          :serverInfo {:name "fake-mcp" :version "1"}
                                                          :instructions "Use echo to test."})
                                  (or init-delay-ms 0))
      "tools/list" (let [all @tools
                         half (quot (count all) 2)]
                     (if (:cursor params)
                       (result! transport id {:tools (vec (drop half all))})
                       (result! transport id {:tools (vec (take half all)) :nextCursor "page-2"})))
      "tools/call" (call! fake transport id params)
      "notifications/cancelled" (swap! cancelled conj (:requestId params))
      "ping" (result! transport id {})
      nil)))

(defn fake
  "A fake whose connections `transport-fn` makes: `{:transport-fn
   :requests :calls :cancelled :servers :tools}`, the atoms of every
   connection together: the methods it got, the tools/call params, the
   request ids it was told to cancel, the server ends of the
   connections, the tools it lists. `expire-once` makes the first
   tools/call of the first connection fail as an HTTP session the
   server forgot; `init-delay-ms` delays the answer to initialize."
  [& [{:keys [expire-once init-delay-ms]}]]
  (let [this {:init-delay-ms init-delay-ms
              :requests (atom [])
              :calls (atom [])
              :cancelled (atom [])
              :servers (atom [])
              :tools (atom tools)
              :specs (atom [])}
        expire (atom expire-once)]
    (assoc this :transport-fn
           (fn [server-name spec]
             (swap! (:specs this) conj [server-name spec])
             (let [^js pair (createInMemoryTransportPair)
                   client (.-client pair)
                   server (.-server pair)]
               (.onMessage server (fn [message] (answer! this server message)))
               (.start server)
               (swap! (:servers this) conj server)
               (when @expire
                 (reset! expire false)
                 (let [send (.bind (.-send client) client)
                       once (atom true)]
                   (set! (.-send client)
                         (fn [message options]
                           (if (and @once (= "tools/call" (.-method message)))
                             (do (reset! once false)
                                 (js/Promise.reject (McpSessionExpiredError.)))
                             (send message options))))))
               client)))))

(defn change-tools!
  "The fake's tools replaced, every connection told that they changed."
  [{:keys [tools servers]} new-tools]
  (reset! tools new-tools)
  (doseq [server @servers]
    (send! server {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))

(defn drop-connections!
  "Every connection closed from the server's end, as a program that
   ended."
  [{:keys [servers]}]
  (doseq [server @servers]
    (.close server))
  (reset! servers []))
