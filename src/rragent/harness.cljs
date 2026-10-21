(ns rragent.harness
  "pi-durable opened on the agent's folder: the SQLite storage, the
   models (OpenRouter through the gateway's connector when there is
   one, found at start or at a model call after the gateway came up,
   else with the key from the environment, as `rragent.openrouter`
   routes every call to OpenRouter, or what a test passes),
   the registry with pi-durable's coding tools (read, write,
   edit, bash in `work/`) and rragent's own (remember and recall,
   fetch, the REPL `eval` with the connectors of the gateway as
   functions, schedule, hook, and the tools of the artifacts: project,
   render, image, voice, music; the pane shows what they make; and
   subagent, which hands a task to a conversation of its own), the
   root conversation configured from the definition when it opens and
   again before every submission when the file changed
   (`rragent.reload`), and the runs the last process left unfinished
   resumed. One process owns the storage at a time."
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
            [rragent.openrouter :as openrouter]
            [rragent.reload :as reload]
            [rragent.schedule :as schedule]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.hook :as hook-tool]
            [rragent.tools.media :as media-tool]
            [rragent.tools.project :as project-tool]
            [rragent.tools.render :as render-tool]
            [rragent.tools.schedule :as schedule-tool]
            [rragent.tools.subagent :as subagent-tool]
            [rragent.tools.facts :as facts-tool]))

(def context
  "The Chord context every call takes; this one never cancels."
  BACKGROUND_CONTEXT)

(defn extension
  "rragent's own tools for the agent of `definition`: the project tool
   names the templates the definition allows, the eval tool runs its
   programs in the `worker` of the REPL, the media tools reach
   OpenRouter the way the REPL `codemode` knows it
   (`rragent.openrouter`)."
  [definition worker codemode]
  (defineExtension #js {:name "rragent"
                        :tools #js [facts-tool/remember facts-tool/recall fetch-tool/tool (eval-tool/tool worker) schedule-tool/tool hook-tool/tool
                                    (project-tool/tool definition) render-tool/tool (media-tool/image codemode) (media-tool/voice codemode) (media-tool/music codemode)]
                        :tasks #js [schedule/Task]}))

(defn gateway-auth
  "The resolved auth of a request through the gateway's `connection`:
   its base URL, and a key that is only a placeholder, since the
   gateway adds the real one. Every request of pi-ai takes the base
   URL from the resolved auth, so it is here, not only on the
   provider."
  [discovery connection]
  #js {:auth #js {:apiKey "sprite" :baseUrl (openrouter/gateway-base-url discovery connection)}
       :source (str "the Sprites gateway, connection " (:id connection))})

(defn- own-auth
  "The provider's own auth resolution of `input`, as pi-ai asks for
   it: OpenRouter's reads OPENROUTER_API_KEY; nothing is a provider
   that is not configured, which pi-ai says."
  [provider input]
  (if-let [api-key (.. provider -auth -apiKey)]
    (.resolve api-key input)
    (js/Promise.resolve js/undefined)))

(defn gateway-provider
  "`provider` with its requests sent through the gateway's `openrouter`
   connection when the REPL `codemode` knows one. The discovery at
   start may have found no gateway, or no such connection (the
   gateway of a fresh sprite comes up after the service), so nothing
   is fixed: pi-ai resolves the auth before every request, and this
   one looks at the discovery as it is and asks the gateway again,
   one blocking request, at most every `interval-ms`
   (`rragent.openrouter/connection`, ten seconds without), until it
   lists the connection; without one the provider's own auth, the key
   from the environment. The provider's `baseUrl` follows the same."
  [provider codemode & [interval-ms]]
  (let [wrapped (js/Object.assign #js {} provider
                                  #js {:auth #js {:apiKey #js {:name (str (.-name provider) " through the Sprites gateway, or its own key")
                                                               :resolve (fn [input]
                                                                          (if-let [connection (openrouter/connection codemode interval-ms)]
                                                                            (js/Promise.resolve (gateway-auth (codemode/discovery codemode) connection))
                                                                            (own-auth provider input)))}}})]
    (js/Object.defineProperty wrapped "baseUrl"
                              #js {:enumerable true
                                   :get (fn []
                                          (if-let [connection (openrouter/known-connection codemode)]
                                            (openrouter/gateway-base-url (codemode/discovery codemode) connection)
                                            (.-baseUrl provider)))})
    wrapped))

(defn openrouter-models
  "The models through OpenRouter: through the gateway's connector when
   the REPL `codemode` knows one, now or at a later model call
   (`gateway-provider`), else with the key from OPENROUTER_API_KEY."
  [codemode]
  (doto (createModels)
    (.setProvider (gateway-provider (openrouterProvider) codemode))))

(defn registry
  "The registry: pi-durable's coding tools, rragent's own for the
   `definition`, with the `worker` and the `codemode` of the REPL, and
   the subagent tool in an extension of its own, which a subagent's
   agent leaves out."
  [definition worker codemode]
  (doto (createRegistry)
    (.install CodingTools)
    (.install (extension definition worker codemode))
    (.install subagent-tool/extension)))

(defn ^:async open
  "The harness of the agent in `home`: `{:harness :root :view :definition
   :home :models :codemode :worker}`. `models` and `storage` are the production
   ones unless given (a test passes the faux provider and memory
   storage); `provider` names the models' provider, `openrouter` by
   default; `now` a clock for tests; `gateway` the Sprites gateway's
   base URL, `RRAGENT_GATEWAY` or `http://sprite.internal` unless
   given (a test passes its fake); `retry` how the gateway's list is
   tried again at start, `{:delays-ms :timeout-ms}` as
   `rragent.gateway/discover-at-start` takes it (a test shortens
   them). The gateway's connectors are discovered here, tried again
   while the gateway comes up, for the REPL (`rragent.codemode`),
   whose programs run in a worker thread started at the first one
   (`rragent.codemode-worker`), and for the route to OpenRouter of the
   models and the media tools (`rragent.openrouter`), which asks the
   gateway again at a call when nothing was found. The
   definition read here names the templates of the project tool; the
   root conversation is configured from the file by `rragent.reload`,
   which reads it again before every submission."
  [{:keys [home models storage provider now gateway retry]}]
  (let [home (or home (home/home))
        file (or (home/definition-file home)
                 (throw (ex-info (str "No AGENT.md or SKILL.md in " home) {:home home})))
        definition (definition/definition file)
        work (home/work-dir home)
        storage (or storage (await (openNodeSqliteStorage (home/database home))))
        base (gateway/base gateway)
        codemode (codemode/handle base (await (gateway/discover-at-start base retry)))
        worker (codemode-worker/handle codemode)
        models (or models (openrouter-models codemode))
        harness (await (.open Harness storage
                              #js {:models models
                                   :registry (registry definition worker codemode)
                                   :env (fn [target] (NodeExecutionEnv. #js {:cwd (or (.-cwd target) work)}))
                                   :settings #js {:compaction #js {:enabled true}}
                                   :now (or now js/undefined)
                                   ;; a failing hook, listener or task: said, not swallowed
                                   :onReport (fn [error] (js/console.error "[harness]" error))}
                              context))
        root (await (.root harness context))
        view (await (.viewState root context))]
    ;; the definition may have changed since the last run: applied on every
    ;; open, and again before every submission when the file changed
    (reload/register! (.-id root) {:home home :work work :provider (or provider "openrouter")})
    (await (reload/reload! root context))
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

(defn ^:async submit
  "`draft` submitted to the root conversation, the definition read
   again first (`rragent.reload`), so that the run has the file as it
   is now. Answers the submission."
  [{:keys [root]} draft]
  (await (reload/reload! root context))
  (await (.submit root draft context)))

(defn ^:async ask
  "`text` submitted to the conversation and answered: the answer's
   text, or `{:unanswered reason}`."
  [{:keys [root] :as agent} text]
  (let [submission (await (submit agent #js {:type "input" :content text}))
        settled (await (.wait submission context))]
    (if (and (= "done" (.-status settled)) (= "input" (.-type settled)))
      (subagent-tool/answer-text (await (.commit root (fn [tx] (.entry tx AssistantEntry (.-answer settled))) context)))
      {:unanswered (.-reason settled)})))

(defn ^:async close
  "The harness closed, which aborts a running program with its call,
   and the REPL's worker ended with it."
  [{:keys [harness root worker]}]
  (await (.close harness context))
  (reload/forget! (.-id root))
  (codemode-worker/stop! worker "The agent closed."))

(def memory-storage
  "A storage that keeps nothing, for tests."
  (fn [] (MemoryStorage.)))
