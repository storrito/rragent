(ns rragent.codemode-worker
  "The programs of the machines (`rragent.codemode`) run in a worker
   thread of the agent's process, one per bot, so that a long step
   stalls nothing of the agent: the page renders, the hooks and the
   schedules fire, Stop reaches the agent. The worker reaches nothing
   outside itself: a program is loaded for its initial state, a step
   is evaluated on a state the host sends, and the answer comes back
   as transit, data both ways. It is started at the first program
   over what the main thread discovered of the gateway
   (`rragent.codemode/snapshot`), and it runs this namespace's own
   module: the bundle in production, where `rragent.main` leaves the
   server to the main thread and the loop below runs instead, the
   compiled namespace under the test build. Stop (the conversation
   aborted while a step runs) and the host's timeout end the worker:
   the message is answered with the reason, the next program starts
   a new worker. The servers and their tools of the MCP registry go
   with a program when they changed since the worker last had them."
  (:require ["node:url" :as url]
            ["node:worker_threads" :refer [Worker isMainThread parentPort workerData]]
            [rragent.codemode :as codemode]
            [rragent.db :as db]
            [rragent.rframes.transit :as transit]))

(def mark
  "What the workerData of a worker of this namespace says under `rragent`."
  "codemode")

(def default-timeout-min
  10)

(def stopped-message
  "The program was stopped.")

(defn timeout-minutes
  "How long a machine may run: RRAGENT_PROGRAM_TIMEOUT_MIN, ten
   without; read at every program, so that a test sets it."
  []
  (let [given (js/parseFloat (str (aget (.-env js/process) "RRAGENT_PROGRAM_TIMEOUT_MIN")))]
    (if (js/isNaN given) default-timeout-min given)))

(defn timeout-message
  [minutes]
  (str "The machine ran longer than " minutes " minutes and was stopped."))

(defn worker-thread?
  "Whether this thread is a worker of this namespace: started with the
   mark in its workerData."
  []
  (and (not isMainThread)
       (some? workerData)
       (= mark (.-rragent workerData))))

(defn serve-message
  "One message of the host answered: a program loaded (`:load`), the
   MCP servers installed first when they came with it; a step
   evaluated on a state (`:step`)."
  [handle {:keys [op mcp code state]}]
  (case op
    :load (do (when mcp
                (codemode/install-mcp! handle mcp))
              (codemode/load handle code))
    :step (codemode/step handle state)
    {:error (str "The worker does not know the message " (pr-str op))}))

(defn serve!
  "The worker's loop: the core over what the main thread discovered
   (`data` of the workerData), every message posted answered on the
   port, by its id."
  []
  (let [handle (codemode/handle-of (transit/read-str (.-data workerData)))]
    (.on parentPort "message"
         (fn [message]
           (let [{:keys [id] :as request} (transit/read-str message)
                 answer (try
                          (serve-message handle request)
                          (catch :default e
                            {:error (ex-message e)}))]
             (.postMessage parentPort (transit/write-str (assoc answer :id id))))))))

(defn handle
  "The worker of the core `codemode`, for the machine tool:
   `{:codemode :state}`, the state an atom with the worker while there
   is one, the messages in it by id, and the version of the MCP
   servers it has (`rragent.mcp/snapshot`)."
  [codemode]
  {:codemode codemode
   :state (atom {:worker nil :running {} :next-id 0 :mcp-version nil})})

(defn- module
  "The module a worker runs: this namespace's own, the bundle in
   production, the compiled namespace under the test build."
  []
  (url/fileURLToPath (js* "import.meta.url")))

(defn- answered!
  "The worker's answer to a message: its promise resolved; the worker
   lets the process end once nothing runs in it."
  [{:keys [state]} {:keys [id] :as answer}]
  (when-let [{:keys [resolve]} (get-in @state [:running id])]
    (swap! state update :running dissoc id)
    (when (and (empty? (:running @state)) (:worker @state))
      (.unref (:worker @state)))
    (resolve (dissoc answer :id))))

(defn- ended!
  "`worker` is over, by a stop or by itself: the messages in it
   answered with `reason`, the next program starts a new worker.
   Nothing for a worker that was replaced already."
  [{:keys [state]} worker reason]
  (when (identical? worker (:worker @state))
    (let [{:keys [running]} @state]
      (swap! state assoc :worker nil :running {} :mcp-version nil)
      (doseq [{:keys [resolve]} (vals running)]
        (resolve {:error reason})))))

(defn- start!
  "A worker over the core's discovery, its answers taken; one that
   ends by itself (a crash) answers the messages in it with the
   reason."
  [{:keys [codemode state] :as handle}]
  (let [worker (Worker. (module) #js {:workerData #js {:rragent mark
                                                       :data (transit/write-str (codemode/snapshot codemode))}})]
    (.on worker "message" (fn [message] (answered! handle (transit/read-str message))))
    (.on worker "error" (fn [error] (ended! handle worker (str "The machine's worker failed: " (or (.-message error) error)))))
    (.on worker "exit" (fn [code] (ended! handle worker (str "The machine's worker ended with exit " code "."))))
    (swap! state assoc :worker worker)
    worker))

(defn stop!
  "The worker terminated, the messages in it answered with `reason`;
   nothing without a worker."
  [{:keys [state] :as handle} reason]
  (when-let [worker (:worker @state)]
    (ended! handle worker reason)
    (.terminate worker)))

(defn of
  "The worker handle of the bot whose conversation is `id`, its
   `:worker` in the one atom (`rragent.db`), made over the core
   `codemode` on first use; one worker per bot."
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

(defn- post!
  "`message` posted to the worker, started when there is none: a
   promise of its answer, `{:error stopped-message}` at once when
   `signal` is aborted already, the stop's reason when it is aborted
   meanwhile."
  [{:keys [state] :as handle} message signal]
  (js/Promise.
   (fn [resolve]
     (if (and signal (.-aborted signal))
       (resolve {:error stopped-message})
       (let [worker (or (:worker @state) (start! handle))
             id (:next-id (swap! state update :next-id inc))
             on-abort (fn [] (stop! handle stopped-message))]
         (when signal
           (.addEventListener signal "abort" on-abort #js {:once true}))
         (swap! state assoc-in [:running id] {:resolve (fn [answer]
                                                         (when signal
                                                           (.removeEventListener signal "abort" on-abort))
                                                         (resolve answer))})
         (.ref worker)
         (.postMessage worker (transit/write-str (assoc message :id id))))))))

(defn ^:async load!
  "`code` loaded in the worker as a machine of its own
   (`rragent.codemode/load`): a promise of `{:state :step? :printed}`
   or `{:error}`, the error the program's, the stop's when `signal`
   (an AbortSignal, the call's) is aborted meanwhile. `mcp`, the
   servers of the core (`rragent.mcp/catalog`), goes with the program
   when it changed since the worker last had it."
  [{:keys [state] :as handle} {:keys [code signal mcp]}]
  (let [changed (when (and mcp (not= (:version mcp) (:mcp-version @state)))
                  mcp)]
    (when changed
      (swap! state assoc :mcp-version (:version changed)))
    (await (post! handle (cond-> {:op :load :code code}
                           changed (assoc :mcp changed))
                  signal))))

(defn ^:async step!
  "`(step state)` evaluated in the worker on the machine that was
   loaded (`rragent.codemode/step`): a promise of `{:state :printed}`
   or `{:error}`."
  [handle {:keys [state signal]}]
  (await (post! handle {:op :step :state state} signal)))

(when (worker-thread?)
  (serve!))
