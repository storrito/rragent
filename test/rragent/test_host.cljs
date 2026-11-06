(ns rragent.test-host
  "A host for the tests: runs a program as the eval tool does
   (`rragent.tools.eval`), form by form, each need answered and the
   form sent again, without pi-durable. `run` drives a REPL handle in
   this thread (`rragent.codemode/evaluate-form`) or a worker
   (`rragent.codemode-worker/attempt!`); `perform` answers a need against
   the fake gateway as the host tools would (`rragent.tools.host`),
   events kept in an atom."
  (:require [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.storrito :as storrito]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.host :as host]))

(defn perform
  "An answerer of needs over the REPL `codemode` (a handle in this
   thread or a worker handle's `:codemode`), the events in the atom
   `events` (maps with :event and :at), MCP calls through `registry`
   when there is one, the files of spit and slurp in the atom `files`
   (`{path content}`, a fresh one without), the seed of a form's
   random numbers 42 and the clock 1700000000000: the need's outcome,
   `{:ok :value}` or `{:ok false :error}`, and the images of an MCP
   call under `:images`."
  [codemode events & [registry files]]
  (let [files (or files (atom {}))]
   (fn [{:keys [tool args] :as request}]
    (-> ((^:async fn []
           (try
             (case tool
               "spit"
               (do (swap! files assoc (:path args) (:content args))
                   {:ok true :value (:path args)})

               "slurp"
               (if-let [content (get @files (:path args))]
                 {:ok true :value content}
                 {:ok false :error (str "No file " (:path args) " in your work folder.")})

               "seed"
               {:ok true :value {:seed 42}}

               "clock"
               {:ok true :value {:now 1700000000000}}

               ("gateway" "gateway-read")
               {:ok true :value (await (host/request codemode (:which args) (:path args) (:opts args)))}

               "remember"
               (do (swap! events conj (assoc (:data args) :event (:event args) :at (js/Date.now)))
                   {:ok true :value (str "Noted " (:event args))})

               "connectors"
               (let [discovery (await (gateway/discover! (:gateway codemode)))
                     found (await (storrito/find-catalog! discovery))]
                 (reset! (:discovery codemode) discovery)
                 (reset! (:storrito codemode) found)
                 {:ok true :value {:discovery discovery :storrito found}})

               "mcp"
               (if-not registry
                 {:ok false :error "There are no MCP servers here."}
                 (let [{:keys [value images error]} (await (mcp/call! registry (:server args) (:tool args) (:args args) {}))]
                   (if error
                     {:ok false :error error}
                     {:ok true :value {:value value} :images (vec images)})))

               "mcp-admin"
               (if-not registry
                 {:ok false :error "There are no MCP servers here."}
                 (let [{:keys [admin name opts on?]} args]
                   {:ok true :value {:value (case admin
                                              :add (await (mcp/add! registry name opts))
                                              :test (await (mcp/test! registry name))
                                              :enable (await (mcp/enable! registry name on?))
                                              :remove (await (mcp/remove! registry name)))}}))

               {:ok false :error (str "The test host does not answer " tool)})
             (catch :default e
               {:ok false :error (ex-message e)}))))
        (.then (fn [outcome] (assoc outcome :request (dissoc request :index))))))))

(defn ^:async run-with
  "`code` run form by form through `step`, a function of `{:code
   :index :answers :events}` answering a promise of `{:need}`,
   `{:done}` or `{:error}` (a handle's or a worker's), the needs
   answered by `perform`, the events read from the atom `events` when
   each form starts: `{:text :images :forms}`, the text as the
   eval tool shows it; a throw with the program's error. `count` is
   how many forms there are."
  [step count code perform events]
  (loop [index 0
         forms []
         images []]
    (if (= index count)
      {:text (eval-tool/text forms) :images images :forms forms}
      (let [snapshot @events
            {:keys [form found]} (await ((^:async fn []
                                           (loop [answers []
                                                  found []]
                                             (let [{:keys [need done error]} (await (step {:code code :index index :answers answers :events snapshot}))]
                                               (cond
                                                 error (throw (js/Error. error))
                                                 need (let [answer (await (perform need))]
                                                        (recur (conj answers answer) (into found (:images answer))))
                                                 :else {:form done :found found}))))))]
        (recur (inc index) (conj forms form) (into images found))))))

(defn ^:async run
  "`code` run in the REPL `handle` in this thread as one program, the
   needs answered by `perform` (`perform` over the handle without
   one), `events` the atom of the bot's events (empty without),
   `files` the atom of the files spit writes and slurp reads: the
   text."
  [handle code & [{:keys [perform events files]}]]
  (let [events (or events (atom []))
        perform (or perform (rragent.test-host/perform handle events nil files))]
    (codemode/begin! handle)
    (:text (await (run-with #(js/Promise.resolve (codemode/evaluate-form handle %))
                            (count (codemode/forms handle code))
                            code perform events)))))

(defn ^:async run-in-worker
  "`code` run in the `worker` (`rragent.codemode-worker/handle`) as
   one program, the needs answered by `perform` (`perform` over the
   worker's REPL without one), `env` what the bindings read in the
   worker, `files` the atom of the files spit writes and slurp
   reads: `{:text :images :forms}`, with `:error` instead of the
   throw."
  [worker code & [{:keys [perform events registry env files]}]]
  (let [events (or events (atom []))
        perform (or perform (rragent.test-host/perform (:codemode worker) events registry files))
        counted (await (codemode-worker/count! worker code nil))]
    (if (:error counted)
      (select-keys counted [:error])
      (try
        (let [mcp (when registry (await (mcp/catalog registry)))]
          (await (run-with #(codemode-worker/attempt! worker (cond-> (assoc % :mcp mcp) env (assoc :env env))) (:count counted) code perform events)))
        (catch :default e
          {:error (ex-message e)})))))
