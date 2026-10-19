(ns rragent.harness
  "pi-durable opened on the agent's folder: the SQLite storage, the
   models (OpenRouter through the gateway's connector when there is
   one, else with the key from the environment, or what a test
   passes), the registry with pi-durable's coding tools (read, write,
   edit, bash in `work/`) and rragent's own (remember and recall,
   fetch, the REPL `eval` with the connectors of the gateway as
   functions, schedule, hook, and the tools of the artifacts: project,
   render, image, voice, music; the pane shows what they make), the
   root conversation configured from the definition each time it
   opens, and the runs the last process left unfinished resumed. One
   process owns the storage at a time."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/openrouter" :refer [openrouterProvider]]
            ["@earendil-works/pi-durable" :refer [AssistantEntry Harness MemoryStorage createRegistry defineExtension]]
            ["@earendil-works/pi-durable/env/node" :refer [NodeExecutionEnv]]
            ["@earendil-works/pi-durable/storage/sqlite/node" :refer [openNodeSqliteStorage]]
            ["@earendil-works/pi-durable/tools" :refer [CodingTools]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.definition :as definition]
            [rragent.gateway :as gateway]
            [rragent.home :as home]
            [rragent.schedule :as schedule]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.hook :as hook-tool]
            [rragent.tools.media :as media-tool]
            [rragent.tools.project :as project-tool]
            [rragent.tools.render :as render-tool]
            [rragent.tools.schedule :as schedule-tool]
            [rragent.tools.facts :as facts-tool]))

(def context
  "The Chord context every call takes; this one never cancels."
  BACKGROUND_CONTEXT)

(defn extension
  "rragent's own tools for the agent of `definition`: the project tool
   names the templates the definition allows, the eval tool runs its
   programs in the `worker` of the REPL."
  [definition worker]
  (defineExtension #js {:name "rragent"
                        :tools #js [facts-tool/remember facts-tool/recall fetch-tool/tool (eval-tool/tool worker) schedule-tool/tool hook-tool/tool
                                    (project-tool/tool definition) render-tool/tool media-tool/image media-tool/voice media-tool/music]
                        :tasks #js [schedule/Task]}))

(defn gateway-base-url
  "Where OpenRouter's API is behind the gateway's `connection`: its
   base and `/v1`, as `openrouter.ai/api/v1` is behind OpenRouter's
   host."
  [discovery connection]
  (str (gateway/connection-base discovery connection) "/v1"))

(defn gateway-provider
  "`provider` with its requests sent to the gateway's `connection`:
   its base URL, and a key that is only a placeholder, since the
   gateway adds the real one. Every request of pi-ai takes the base
   URL from the resolved auth, so it is here, not only on the
   provider."
  [provider discovery connection]
  (let [base (gateway-base-url discovery connection)
        source (str "the Sprites gateway, connection " (:id connection))]
    (js/Object.assign #js {} provider
                      #js {:baseUrl base
                           :auth #js {:apiKey #js {:name (str (.-name provider) " through the Sprites gateway")
                                                   :resolve (fn [_input]
                                                              (js/Promise.resolve #js {:auth #js {:apiKey "sprite" :baseUrl base}
                                                                                       :source source}))}}})))

(defn model-route
  "Where the models' requests go: the gateway's `openrouter`
   connector when `discovery` has one, else OpenRouter itself with
   `OPENROUTER_API_KEY`; one line for the console."
  [discovery]
  (if-let [connection (gateway/connection discovery "openrouter")]
    (str "OpenRouter through the Sprites gateway (" (gateway-base-url discovery connection) ")")
    "OpenRouter with OPENROUTER_API_KEY"))

(defn openrouter-models
  "The models through OpenRouter: through the gateway's connector when
   `discovery` has one, else with the key from OPENROUTER_API_KEY."
  [discovery]
  (let [provider (openrouterProvider)]
    (doto (createModels)
      (.setProvider (if-let [connection (gateway/connection discovery "openrouter")]
                      (gateway-provider provider discovery connection)
                      provider)))))

(defn registry
  "The registry: pi-durable's coding tools and rragent's own for the
   `definition`, with the `worker` of the REPL."
  [definition worker]
  (doto (createRegistry)
    (.install CodingTools)
    (.install (extension definition worker))))

(defn ^:async open
  "The harness of the agent in `home`: `{:harness :root :view :definition
   :home :models :codemode :worker}`. `models` and `storage` are the production
   ones unless given (a test passes the faux provider and memory
   storage); `provider` names the models' provider, `openrouter` by
   default; `now` a clock for tests; `gateway` the Sprites gateway's
   base URL, `RRAGENT_GATEWAY` or `http://sprite.internal` unless
   given (a test passes its fake). The gateway's connectors are
   discovered here, for the REPL (`rragent.codemode`), whose programs
   run in a worker thread started at the first one
   (`rragent.codemode-worker`), and for the models' route."
  [{:keys [home models storage provider now gateway]}]
  (let [home (or home (home/home))
        file (or (home/definition-file home)
                 (throw (ex-info (str "No AGENT.md or SKILL.md in " home) {:home home})))
        definition (definition/definition file)
        work (home/work-dir home)
        storage (or storage (await (openNodeSqliteStorage (home/database home))))
        codemode (codemode/handle (gateway/base gateway))
        worker (codemode-worker/handle codemode)
        models (or models (openrouter-models (codemode/discovery codemode)))
        harness (await (.open Harness storage
                              #js {:models models
                                   :registry (registry definition worker)
                                   :env (fn [target] (NodeExecutionEnv. #js {:cwd (or (.-cwd target) work)}))
                                   :settings #js {:compaction #js {:enabled true}}
                                   :now (or now js/undefined)
                                   ;; a failing hook, listener or task: said, not swallowed
                                   :onReport (fn [error] (js/console.error "[harness]" error))}
                              context))
        root (await (.root harness context))
        view (await (.viewState root context))]
    ;; the definition may have changed since the last run: applied on every open
    (await (.configure root #js {:model #js {:provider (or provider "openrouter") :modelId (:model definition)}
                                 :thinkingLevel (or (:thinking definition) js/undefined)
                                 :instructions (:instructions definition)
                                 :cwd work}
                       context))
    (.resume harness)
    {:harness harness :root root :view view :definition definition :home home :models models :codemode codemode :worker worker}))

(defn busy?
  "Whether a run is going in the conversation: `pi.live` of the view
   holds one then."
  [{:keys [view]}]
  (some? (.-run (or (aget (.-docs (.-value view)) "pi.live") #js {}))))

(defn ^:async sees-images?
  "Whether the conversation's model takes images, by its entry in the
   catalog of `models`; a model the catalog does not know counts as one
   that does, as pi-durable's `read` tool counts it (and pi-ai puts a
   placeholder where an image was when the model takes none)."
  [{:keys [root models]}]
  (let [ref (.-model (await (.agent root context)))
        model (when ref (.getModel models (.-provider ref) (.-modelId ref)))]
    (if model
      (boolean (some #{"image"} (array-seq (.-input model))))
      true)))

(defn ^:async schedules
  "The schedules document, for the alarm."
  [{:keys [harness root]}]
  (await (.snapshot harness schedule/Schedules (.-id root) context)))

(defn answer-text
  "The text of an assistant entry's model message."
  [entry]
  (->> (.-content (aget (.-model entry) 0))
       (filter #(= "text" (.-type %)))
       (map #(.-text %))
       (apply str)))

(defn ^:async ask
  "`text` submitted to the conversation and answered: the answer's
   text, or `{:unanswered reason}`."
  [{:keys [root]} text]
  (let [submission (await (.submit root #js {:type "input" :content text} context))
        settled (await (.wait submission context))]
    (if (and (= "done" (.-status settled)) (= "input" (.-type settled)))
      (answer-text (await (.commit root (fn [tx] (.entry tx AssistantEntry (.-answer settled))) context)))
      {:unanswered (.-reason settled)})))

(defn ^:async close
  "The harness closed, which aborts a running program with its call,
   and the REPL's worker ended with it."
  [{:keys [harness worker]}]
  (await (.close harness context))
  (codemode-worker/stop! worker "The agent closed."))

(def memory-storage
  "A storage that keeps nothing, for tests."
  (fn [] (MemoryStorage.)))
