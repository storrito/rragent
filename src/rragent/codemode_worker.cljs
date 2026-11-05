(ns rragent.codemode-worker
  "The programs of the REPL (`rragent.codemode`) run in a worker thread
   of the agent's process, one per bot, so that a long form stalls
   nothing of the agent: the page renders, the hooks and the schedules
   fire, Stop reaches the agent. The worker holds the SCI context, so
   a `def` of one program is there for the next; one per bot, by the
   id of its conversation (`of`). It is started at the first program
   over what the main thread discovered of the gateway
   (`rragent.codemode/snapshot`), and it runs this namespace's own
   module: the bundle in production, where `rragent.main` leaves the
   server to the main thread and the loop below runs instead, the
   compiled namespace under the test build.

   The worker reaches nothing outside itself. A form goes over the
   port as transit with the answers the host gave to its calls so far
   and the events the program sees, and the answer comes back the same
   way: the first call the host has not answered (a need,
   `rragent.need`), or the form done with what it printed, its value
   and the vars it defined, or its error (`rragent.codemode/evaluate-form`);
   the host answers the need and sends the form again
   (`rragent.tools.eval`). The vars of a form the host kept go to a
   worker that starts over (`restore!`). Stop (the conversation aborted
   while a form runs) and the host's timeout end the worker: the form
   is answered with the reason, the next program starts a new worker
   with a fresh context, and its first answer says that the defs are
   gone. The servers and their tools of the MCP registry go with a
   form when they changed since the worker last had them."
  (:require ["node:url" :as url]
            ["node:worker_threads" :refer [Worker isMainThread parentPort workerData]]
            [rragent.codemode :as codemode]
            [rragent.composio :as composio]
            [rragent.db :as db]
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

(defn serve-message
  "One message of the host answered: a form evaluated (`:run`), the
   environment set and the MCP servers installed first when they came
   with it; the forms of a program counted (`:count`), a reading error
   the answer; vars restored (`:restore`)."
  [handle {:keys [op mcp vars code env] :as message}]
  (case op
    :run (do (set-env! env)
             (when mcp
               (codemode/install-mcp! handle mcp))
             (codemode/evaluate-form handle message))
    :count (try
             {:count (count (codemode/forms handle code))}
             (catch :default e
               {:error (codemode/reading-error e)}))
    :restore (do (codemode/restore! handle vars)
                 {:done true})
    {:error (str "The worker does not know the message " (pr-str op))}))

(defn serve!
  "The worker's loop: the REPL over what the main thread discovered
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
                            {:error (str "The REPL's worker failed: " (ex-message e))}))]
             (.postMessage parentPort (transit/write-str (assoc answer :id id))))))))

(defn handle
  "The worker of the REPL `codemode`, for the eval tool: `{:codemode
   :state}`, the state an atom with the worker while there is one, the
   messages in it by id, the note the next answer carries after a
   stop, and the version of the MCP servers it has (`rragent.mcp/snapshot`)."
  [codemode]
  {:codemode codemode
   :state (atom {:worker nil :running {} :next-id 0 :note nil :mcp-version nil})})

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
   answered with `reason`, the next program starts a new worker and
   says that the defs are gone. Nothing for a worker that was
   replaced already."
  [{:keys [state]} worker reason]
  (when (identical? worker (:worker @state))
    (let [{:keys [running]} @state]
      (swap! state assoc :worker nil :running {} :note restart-note :mcp-version nil)
      (doseq [{:keys [resolve]} (vals running)]
        (resolve {:error reason})))))

(defn- start!
  "A worker over the REPL's discovery, its answers taken; one that
   ends by itself (a crash) answers the messages in it with the
   reason."
  [{:keys [codemode state] :as handle}]
  (let [worker (Worker. (module) #js {:workerData #js {:rragent mark
                                                       :data (transit/write-str (codemode/snapshot codemode))}})]
    (.on worker "message" (fn [message] (answered! handle (transit/read-str message))))
    (.on worker "error" (fn [error] (ended! handle worker (str "The REPL's worker failed: " (or (.-message error) error)))))
    (.on worker "exit" (fn [code] (ended! handle worker (str "The REPL's worker ended with exit " code "."))))
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

(defn- post!
  "`message` posted to the worker, started when there is none: a
   promise of its answer, `{:error stopped-message}` at once when
   `signal` is aborted already, the stop's reason when it is aborted
   meanwhile. The first answer after a stop carries `:note`."
  [{:keys [state] :as handle} message signal]
  (js/Promise.
   (fn [resolve]
     (if (and signal (.-aborted signal))
       (resolve {:error stopped-message})
       (let [worker (or (:worker @state) (start! handle))
             id (:next-id (swap! state update :next-id inc))
             note (:note @state)
             on-abort (fn [] (stop! handle stopped-message))]
         (when signal
           (.addEventListener signal "abort" on-abort #js {:once true}))
         (swap! state assoc :note nil)
         (swap! state assoc-in [:running id] {:resolve (fn [answer]
                                                         (when signal
                                                           (.removeEventListener signal "abort" on-abort))
                                                         (resolve (cond-> answer note (assoc :note note))))})
         (.ref worker)
         (.postMessage worker (transit/write-str (assoc message :id id))))))))

(defn ^:async attempt!
  "The form at `index` of `code` run in the worker over `answers` and
   `events` (`rragent.codemode/evaluate-form`): a promise of
   `{:need}`, `{:done}` or `{:error}`, the error the form's, the stop's
   when `signal` (an AbortSignal, the call's) is aborted meanwhile.
   `mcp`, the servers of the REPL (`rragent.mcp/catalog`), goes with
   the form when it changed since the worker last had it; the
   environment the bindings read goes with every form (`environment`,
   or `env` given)."
  [{:keys [state] :as handle} {:keys [code index answers events signal mcp env]}]
  (let [changed (when (and mcp (not= (:version mcp) (:mcp-version @state)))
                  mcp)]
    (when changed
      (swap! state assoc :mcp-version (:version changed)))
    (await (post! handle (cond-> {:op :run :code code :index index :answers answers :events events :env (or env (environment))}
                           changed (assoc :mcp changed))
                  signal))))

(defn ^:async restore!
  "`vars` (`{sym edn}`) defined in the worker again, for a worker that
   starts over: a promise of `{:done true}` or `{:error}`."
  [handle vars signal]
  (await (post! handle {:op :restore :vars vars} signal)))

(defn ^:async count!
  "How many forms `code` has, read in the worker: a promise of
   `{:count}`, or `{:error}` with where the reading failed."
  [handle code signal]
  (await (post! handle {:op :count :code code} signal)))

(when (worker-thread?)
  (serve!))
