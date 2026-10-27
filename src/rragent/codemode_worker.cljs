(ns rragent.codemode-worker
  "The programs of the REPL (`rragent.codemode`) run in a worker thread
   of the agent's process, one per agent, so that a `call`, which
   blocks its thread until it is answered (`rragent.sync-fetch`),
   stalls nothing of the agent: the page renders, the hooks and the
   schedules fire, Stop reaches the agent. The worker holds the SCI
   context, so a `def` of one program is there for the next; one per
   bot, by the id of its conversation (`of`). It is
   started at the first program over what the main thread discovered
   of the gateway (`rragent.codemode/snapshot`), and it runs this
   namespace's own module: the bundle in production, where
   `rragent.main` leaves the server to the main thread and the loop
   below runs instead, the compiled namespace under the test build. A
   program goes over the port as transit with the facts the program
   sees and the environment the bindings read, the answer comes back
   the same way: what it printed and its value, or its error, and what
   it remembered. Stop (the conversation aborted while a program runs)
   and the timeout (`RRAGENT_PROGRAM_TIMEOUT_MIN` minutes, ten) end
   the worker: the program is answered with the reason, the next
   program starts a new worker with a fresh context, and its answer
   says first that the defs are gone.

   A call of an MCP tool (`mcp.<server>/<tool>`) is answered by the
   main thread, which holds the connections (`rragent.mcp`): the
   worker posts it on a port of its own and waits on a shared flag, as
   `rragent.sync-fetch` waits for its helper, and the main thread
   calls the tool, posts the answer and raises the flag. A worker that
   ends aborts the calls it waits for, which the servers are told; the
   servers and their tools go with a program when they changed since
   the worker last had them, and the progress the servers report goes
   to the program's `on-progress`."
  (:require ["node:url" :as url]
            ["node:worker_threads" :refer [MessageChannel Worker isMainThread parentPort receiveMessageOnPort workerData]]
            [rragent.codemode :as codemode]
            [rragent.composio :as composio]
            [rragent.db :as db]
            [rragent.mcp :as mcp]
            [rragent.rframes.transit :as transit]))

(def mark
  "What the workerData of a worker of this namespace says under `rragent`."
  "codemode")

(def default-timeout-min
  10)

(def stopped-message
  "The program was stopped.")

(def restart-note
  "What the first answer after a stop says before the answer."
  "The REPL started over since the last program was stopped: what earlier programs defined is gone.")

(defn timeout-minutes
  "How long a program may run: RRAGENT_PROGRAM_TIMEOUT_MIN, ten
   without; read at every program, so that a test sets it."
  []
  (let [given (js/parseFloat (str (aget (.-env js/process) "RRAGENT_PROGRAM_TIMEOUT_MIN")))]
    (if (js/isNaN given) default-timeout-min given)))

(defn timeout-message
  [minutes]
  (str "The program ran longer than " minutes " minutes and was stopped."))

(defn environment
  "What the bindings read from the environment, as the main thread
   has it, for the worker: the Composio session and the agent's user
   id there (`rragent.composio-setup` sets them at start)."
  []
  {"COMPOSIO_SESSION_ID" (composio/session-id)
   "COMPOSIO_USER_ID" (composio/user-id)})

(defn worker-thread?
  "Whether this thread is a worker of this namespace: started with the
   mark in its workerData."
  []
  (and (not isMainThread)
       (some? workerData)
       (= mark (.-rragent workerData))))

(defn- set-env!
  "The environment of this thread as `env` says, `{name value}`; nil
   unsets."
  [env]
  (doseq [[k v] env]
    (if (nil? v)
      (js-delete (.-env js/process) k)
      (aset (.-env js/process) k v))))

(defn run-program
  "One program in `handle`: the environment set, the MCP servers
   installed when they came with it, the facts begun, the program
   evaluated. `{:text}` or `{:error}`, with `:pending`, what it
   remembered, and `:images`, the pictures its MCP calls answered,
   when there are any, either way."
  [handle {:keys [code facts env mcp]}]
  (set-env! env)
  (when mcp
    (codemode/install-mcp! handle mcp))
  (codemode/begin! handle facts)
  (let [answer (try
                 {:text (codemode/evaluate handle code)}
                 (catch :default e
                   {:error (.-message e)}))]
    (cond-> (assoc answer :pending (codemode/pending handle))
      (seq (codemode/images handle)) (assoc :images (codemode/images handle)))))

(defn rpc
  "The worker's half of an MCP call: `request` posted on `port`, the
   thread blocked on the shared `flag` until the main thread answered,
   the answer taken off the port. No deadline of its own: the main
   thread answers every call, its timeout is the server's, and the
   program's timeout ends the worker."
  [port flag]
  (let [view (js/Int32Array. flag)]
    (fn [request]
      (js/Atomics.store view 0 0)
      (.postMessage port (transit/write-str request))
      (loop []
        (js/Atomics.wait view 0 0 1000)
        (if-let [message (receiveMessageOnPort port)]
          (transit/read-str (.-message message))
          (recur))))))

(defn serve!
  "The worker's loop: the REPL over what the main thread discovered
   (`data` of the workerData), every program posted answered on the
   port, by its id."
  []
  (let [handle (codemode/handle-of (transit/read-str (.-data workerData)))]
    (reset! (:mcp-rpc handle) (rpc (.-mcpPort workerData) (.-mcpFlag workerData)))
    (.on parentPort "message"
         (fn [message]
           (let [{:keys [id] :as program} (transit/read-str message)]
             (.postMessage parentPort (transit/write-str (assoc (run-program handle program) :id id))))))))

(defn handle
  "The worker of the REPL `codemode`, for the eval tool: `{:codemode
   :state}`, the state an atom with the worker while there is one, the
   programs in it by id, the note the next answer carries after a
   stop, the MCP calls it waits for (their AbortControllers by id),
   the version of the MCP servers it has (`rragent.mcp/snapshot`) and
   where the progress of the running program goes. The MCP servers
   are the registry under `:mcp` of `codemode`, when there is one."
  [codemode]
  {:codemode codemode
   :state (atom {:worker nil :running {} :next-id 0 :note nil
                 :calls {} :mcp-version nil :on-progress nil})})

(defn- module
  "The module a worker runs: this namespace's own, the bundle in
   production, the compiled namespace under the test build."
  []
  (url/fileURLToPath (js* "import.meta.url")))

(defn- answered!
  "The worker's answer to a program: its timer cleared, `evaluate!`
   resolved; the worker lets the process end once no program runs in
   it."
  [{:keys [state]} {:keys [id] :as answer}]
  (when-let [{:keys [resolve timer]} (get-in @state [:running id])]
    (js/clearTimeout timer)
    (swap! state update :running dissoc id)
    (when (and (empty? (:running @state)) (:worker @state))
      (.unref (:worker @state)))
    (resolve (dissoc answer :id))))

(defn- ended!
  "`worker` is over, by a stop or by itself: the programs in it
   answered with `reason`, the next program starts a new worker and
   says that the defs are gone. Nothing for a worker that was
   replaced already."
  [{:keys [state]} worker reason]
  (when (identical? worker (:worker @state))
    (let [{:keys [running calls mcp-port]} @state]
      (swap! state assoc :worker nil :running {} :note restart-note :calls {} :mcp-version nil :mcp-port nil)
      (doseq [controller (vals calls)]
        (.abort controller reason))
      (when mcp-port
        (.close mcp-port))
      (doseq [{:keys [resolve timer]} (vals running)]
        (js/clearTimeout timer)
        (resolve {:error reason :pending []})))))

(defn- admin!
  "A change of the MCP servers a program asked for (`mcp/add!`,
   `mcp/test`, `mcp/enable!`, `mcp/remove!`): a promise of
   `{:value}`."
  [registry {:keys [admin name opts on?]}]
  (-> (js/Promise.resolve nil)
      (.then (fn []
               (case admin
                 :add (mcp/add! registry name opts)
                 :test (mcp/test! registry name)
                 :enable (mcp/enable! registry name on?)
                 :remove (mcp/remove! registry name))))
      (.then (fn [value] {:value value}))))

(defn- answer-mcp!
  "The main thread's half of an MCP request of `worker`: a tool called
   (`rragent.mcp/call!`), or the servers changed (`admin!`), the
   answer posted on `port` and the flag raised, unless the worker
   ended meanwhile; a call's AbortController kept under `:calls` while
   it runs."
  [{:keys [codemode state]} worker port view message]
  (let [{:keys [server tool args admin] :as request} (transit/read-str message)
        registry (:mcp codemode)
        controller (js/AbortController.)
        id (str (gensym "call"))
        on-progress (fn [line]
                      (when-let [f (:on-progress @state)]
                        (f line)))]
    (swap! state assoc-in [:calls id] controller)
    (-> (cond
          (nil? registry) (js/Promise.resolve {:error "There are no MCP servers here."})
          admin (admin! registry request)
          :else (mcp/call! registry server tool args {:signal (.-signal controller) :on-progress on-progress}))
        (.catch (fn [e] {:error (if admin
                                  (ex-message e)
                                  (str "mcp." server "/" tool ": " (ex-message e)))}))
        (.then (fn [answer]
                 (swap! state update :calls dissoc id)
                 (when (identical? worker (:worker @state))
                   (.postMessage port (transit/write-str answer))
                   (js/Atomics.store view 0 1)
                   (js/Atomics.notify view 0)))))))

(defn- start!
  "A worker over the REPL's discovery, its answers taken, the MCP calls
   it makes answered on a port of its own; one that ends by itself (a
   crash) answers the programs in it with the reason."
  [{:keys [codemode state] :as handle}]
  (let [channel (MessageChannel.)
        flag (js/SharedArrayBuffer. 4)
        port (.-port1 channel)
        worker (Worker. (module) #js {:workerData #js {:rragent mark
                                                       :data (transit/write-str (codemode/snapshot codemode))
                                                       :mcpPort (.-port2 channel)
                                                       :mcpFlag flag}
                                      :transferList #js [(.-port2 channel)]})]
    (.on port "message" (fn [message] (answer-mcp! handle worker port (js/Int32Array. flag) message)))
    (.unref port)
    (swap! state assoc :mcp-port port)
    (.on worker "message" (fn [message] (answered! handle (transit/read-str message))))
    (.on worker "error" (fn [error] (ended! handle worker (str "The REPL's worker failed: " (or (.-message error) error)))))
    (.on worker "exit" (fn [code] (ended! handle worker (str "The REPL's worker ended with exit " code "."))))
    (swap! state assoc :worker worker)
    worker))

(defn stop!
  "The worker terminated, the programs in it answered with `reason`;
   nothing without a worker."
  [{:keys [state] :as handle} reason]
  (when-let [worker (:worker @state)]
    (ended! handle worker reason)
    (.terminate worker)))

(defn of
  "The worker handle of the bot whose conversation is `id`, its
   `:worker` in the one atom (`rragent.db`), made over the REPL
   `codemode` on first use; one worker, one SCI context, per bot."
  [codemode id]
  (let [path (conj (db/bot-path id) :worker)]
    (or (get-in @db/db path)
        (get-in (swap! db/db (fn [value]
                               (if (get-in value path)
                                 value
                                 (assoc-in value path (handle codemode)))))
                path))))

(defn stop-of!
  "The bot's worker stopped and forgotten; nothing without one."
  [id reason]
  (let [path (conj (db/bot-path id) :worker)]
    (when-let [worker (get-in @db/db path)]
      (stop! worker reason)
      (swap! db/db update-in (db/bot-path id) dissoc :worker))))

(defn stop-all!
  [reason]
  (doseq [id (keys (:bots @db/db))]
    (stop-of! id reason)))

(defn- noted
  "`answer` with `note` in front, when there is one."
  [note answer]
  (if note
    (update answer (if (:error answer) :error :text) #(str note "\n" %))
    answer))

(defn- post!
  "The program posted to the worker, started when there is none: a
   promise of its answer (see `evaluate!`)."
  [{:keys [state] :as handle} {:keys [code facts env signal catalog on-progress]}]
  (js/Promise.
   (fn [resolve]
     (if (and signal (.-aborted signal))
       (resolve {:error stopped-message :pending []})
       (let [worker (or (:worker @state) (start! handle))
             id (:next-id (swap! state update :next-id inc))
             note (:note @state)
             minutes (timeout-minutes)
             timer (js/setTimeout #(stop! handle (timeout-message minutes)) (* 60000 minutes))
             on-abort (fn [] (stop! handle stopped-message))
             mcp (when (and catalog (not= (:version catalog) (:mcp-version @state)))
                   catalog)]
         (when signal
           (.addEventListener signal "abort" on-abort #js {:once true}))
         (swap! state assoc :note nil :on-progress on-progress)
         (when mcp
           (swap! state assoc :mcp-version (:version mcp)))
         (swap! state assoc-in [:running id] {:timer timer
                                              :resolve (fn [answer]
                                                         (when signal
                                                           (.removeEventListener signal "abort" on-abort))
                                                         (swap! state assoc :on-progress nil)
                                                         (resolve (noted note answer)))})
         (.ref worker)
         (.postMessage worker (transit/write-str (cond-> {:id id :code code :facts facts :env env}
                                                   mcp (assoc :mcp mcp)))))))))

(defn ^:async evaluate!
  "`{:code :facts :env :signal :on-progress}` run in the worker,
   started when there is none: a promise of `{:text :pending}` or
   `{:error :pending}`, with `:images` when MCP calls answered
   pictures; the error the program's, the stop's
   when `signal` (an AbortSignal, the call's) is aborted meanwhile, or
   the timeout's. The MCP servers of the REPL are connected first and
   go with the program when they changed (`rragent.mcp/catalog`); the
   progress of its MCP calls goes to `on-progress`, a line each. The
   first answer after a stop carries the note of the restart in front."
  [{:keys [codemode] :as handle} program]
  (let [catalog (when-let [registry (:mcp codemode)]
                  (await (mcp/catalog registry)))]
    (await (post! handle (assoc program :catalog catalog)))))

(when (worker-thread?)
  (serve!))
