(ns rragent.codemode-worker
  "The programs of the REPL (`rragent.codemode`) run in a worker thread
   of the agent's process, one per agent, so that a `call`, which
   blocks its thread until it is answered (`rragent.sync-fetch`),
   stalls nothing of the agent: the page renders, the hooks and the
   schedules fire, Stop reaches the agent. The worker holds the SCI
   context, so a `def` of one program is there for the next. It is
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
   says first that the defs are gone."
  (:require ["node:url" :as url]
            ["node:worker_threads" :refer [Worker isMainThread parentPort workerData]]
            [rragent.codemode :as codemode]
            [rragent.composio :as composio]
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
   has it, for the worker: the Composio session."
  []
  {"COMPOSIO_SESSION_ID" (composio/session-id)})

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
  "One program in `handle`: the environment set, the facts begun, the
   program evaluated. `{:text}` or `{:error}`, with `:pending`, what it
   remembered, either way."
  [handle {:keys [code facts env]}]
  (set-env! env)
  (codemode/begin! handle facts)
  (let [answer (try
                 {:text (codemode/evaluate handle code)}
                 (catch :default e
                   {:error (.-message e)}))]
    (assoc answer :pending (codemode/pending handle))))

(defn serve!
  "The worker's loop: the REPL over what the main thread discovered
   (`data` of the workerData), every program posted answered on the
   port, by its id."
  []
  (let [handle (codemode/handle-of (transit/read-str (.-data workerData)))]
    (.on parentPort "message"
         (fn [message]
           (let [{:keys [id] :as program} (transit/read-str message)]
             (.postMessage parentPort (transit/write-str (assoc (run-program handle program) :id id))))))))

(defn handle
  "The worker of the REPL `codemode`, for the eval tool: `{:codemode
   :state}`, the state an atom with the worker while there is one, the
   programs in it by id, and the note the next answer carries after a
   stop."
  [codemode]
  {:codemode codemode
   :state (atom {:worker nil :running {} :next-id 0 :note nil})})

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
    (let [running (:running @state)]
      (swap! state assoc :worker nil :running {} :note restart-note)
      (doseq [{:keys [resolve timer]} (vals running)]
        (js/clearTimeout timer)
        (resolve {:error reason :pending []})))))

(defn- start!
  "A worker over the REPL's discovery, its answers taken; one that
   ends by itself (a crash) answers the programs in it with the reason."
  [{:keys [codemode state] :as handle}]
  (let [worker (Worker. (module) #js {:workerData #js {:rragent mark
                                                       :data (transit/write-str (codemode/snapshot codemode))}})]
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

(defn- noted
  "`answer` with `note` in front, when there is one."
  [note answer]
  (if note
    (update answer (if (:error answer) :error :text) #(str note "\n" %))
    answer))

(defn evaluate!
  "`{:code :facts :env :signal}` run in the worker, started when there
   is none: a promise of `{:text :pending}` or `{:error :pending}`, the
   error the program's, the stop's when `signal` (an AbortSignal, the
   call's) is aborted meanwhile, or the timeout's. The first answer
   after a stop carries the note of the restart in front."
  [{:keys [state] :as handle} {:keys [code facts env signal]}]
  (js/Promise.
   (fn [resolve]
     (if (and signal (.-aborted signal))
       (resolve {:error stopped-message :pending []})
       (let [worker (or (:worker @state) (start! handle))
             id (:next-id (swap! state update :next-id inc))
             note (:note @state)
             minutes (timeout-minutes)
             timer (js/setTimeout #(stop! handle (timeout-message minutes)) (* 60000 minutes))
             on-abort (fn [] (stop! handle stopped-message))]
         (when signal
           (.addEventListener signal "abort" on-abort #js {:once true}))
         (swap! state assoc :note nil)
         (swap! state assoc-in [:running id] {:timer timer
                                              :resolve (fn [answer]
                                                         (when signal
                                                           (.removeEventListener signal "abort" on-abort))
                                                         (resolve (noted note answer)))})
         (.ref worker)
         (.postMessage worker (transit/write-str {:id id :code code :facts facts :env env})))))))

(when (worker-thread?)
  (serve!))
