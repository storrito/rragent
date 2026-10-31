(ns rragent.harness
  "pi-durable opened on the machine's folder: the one SQLite storage
   of every bot's conversation, the models (OpenRouter through the
   gateway's connector when there is one, found at start or at a
   model call after the gateway came up, else with the key from the
   environment, as `rragent.openrouter` routes every call to
   OpenRouter, or what a test passes), the registry with pi-durable's
   coding tools (read, write, edit, bash in the bot's `work/`) and
   rragent's own (remember and recall over the bot's events file,
   `rragent.events`, fetch, the REPL `eval` with
   the connectors of the gateway as functions, schedule, hook, report
   (a run's word to the bot's chat, `rragent.runs`), skill (the
   person's own skills on the machine, `rragent.skills`), and
   the tools of the artifacts: project, render, image, voice, music,
   transcribe;
   the pane shows what they make; subagent, which hands a task to a
   conversation of its own; and what the caller brings, the `bot`
   tool of `rragent.machine`), one registry for every bot. A bot is a
   conversation of the storage: the root one is the first bot, the
   others ownerless conversations the machine makes; each is
   configured from its own `AGENT.md` when it opens and again before
   every submission when the file changed (`rragent.reload`), its
   instructions, its model and its folder's `work/` as its `cwd`,
   which pi-durable keeps per conversation and hands to the
   environment of every call. The runs the last process left
   unfinished are resumed. One process owns the storage at a time."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/openrouter" :refer [openrouterProvider]]
            ["@earendil-works/pi-durable" :refer [AssistantEntry GenerationTask Harness LiveDoc MemoryStorage ToolTask UsageDoc createRegistry defineExtension hook]]
            ["@earendil-works/pi-durable/env/node" :refer [NodeExecutionEnv]]
            ["@earendil-works/pi-durable/storage/sqlite/node" :refer [openNodeSqliteStorage]]
            ["@earendil-works/pi-durable/tools" :refer [createCodingTools]]
            ["node:fs" :as fs]
            [rragent.away :as away]
            [rragent.binary :as binary]
            [rragent.bots :as bots]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.db :as db]
            [rragent.events :as events]
            [rragent.gateway :as gateway]
            [rragent.home :as home]
            [rragent.images :as images]
            [rragent.mcp :as mcp]
            [rragent.mcp-oauth :as mcp-oauth]
            [rragent.model-requests :as model-requests]
            [rragent.observations :as observations]
            [rragent.openrouter :as openrouter]
            [rragent.reload :as reload]
            [rragent.runs :as runs]
            [rragent.schedule :as schedule]
            [rragent.secrets :as secrets]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.events :as events-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.hook :as hook-tool]
            [rragent.tools.media :as media-tool]
            [rragent.tools.project :as project-tool]
            [rragent.tools.render :as render-tool]
            [rragent.tools.report :as report-tool]
            [rragent.tools.schedule :as schedule-tool]
            [rragent.tools.share :as share-tool]
            [rragent.tools.skill :as skill-tool]
            [rragent.tools.subagent :as subagent-tool]
            [rragent.tools.transcribe :as transcribe-tool]
            [rragent.warm-summary :as warm-summary]))

(def context
  "The Chord context every call takes; this one never cancels."
  BACKGROUND_CONTEXT)

(defn extension
  "rragent's own tools: the eval tool runs a program in the worker of
   the bot of the call, over the REPL `codemode`, the media tools
   reach OpenRouter the way it knows it (`rragent.openrouter`), the
   project tool holds a call to the bot's definition."
  [codemode]
  (defineExtension #js {:name "rragent"
                        :tools #js [events-tool/remember events-tool/recall fetch-tool/tool (eval-tool/tool codemode) schedule-tool/tool hook-tool/tool
                                    (project-tool/tool) render-tool/tool (media-tool/image codemode) (media-tool/voice codemode) (media-tool/music codemode)
                                    (transcribe-tool/tool codemode) report-tool/tool skill-tool/tool share-tool/tool]
                        :tasks #js [schedule/Task runs/Task]}))

(def redacted-keys
  "The parts of a tool's result that reach the model, a program or the
   page: what it said, its value for programs, its details and its
   diagnostics."
  ["output" "structuredOutput" "details" "diagnostics"])

(defn redact-result
  "The tool's `result` with every secret value of `pairs`
   (`rragent.secrets/redactions`) replaced by its name; nil when there
   was none in it."
  [pairs result]
  (when (seq pairs)
    (let [changed (into {}
                        (keep (fn [k]
                                (let [v (aget result k)]
                                  (when (some? v)
                                    (let [data (js->clj v)
                                          clean (secrets/redact pairs data)]
                                      (when (not= data clean)
                                        [k (clj->js clean)]))))))
                        redacted-keys)]
      (when (seq changed)
        (js/Object.assign #js {} result (clj->js changed))))))

(defn redaction
  "The extension that keeps the machine's secrets from the model: after
   every tool call, a secret's value in its result (`secrets.json` of
   `home`, the environment's tokens and keys, the MCP servers' OAuth
   tokens, `rragent.secrets`) is
   replaced by its name before the result is committed."
  [home]
  (let [source (secrets/source (secrets/file home) nil #(mcp-oauth/token-values (mcp-oauth/file home)))]
    (defineExtension #js {:name "rragent-redaction"
                          :hooks #js [(hook ToolTask #js {:afterTool (fn [_call result _api _context]
                                                                        (or (redact-result (secrets/current source) result)
                                                                            js/undefined))})]})))

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

(def default-context-tokens
  "The context a conversation keeps at most, in tokens, whatever window
   its model has: past it every call takes longer (a bot at 440,000
   tokens of a 1,050,000 window waited 12 to 35 seconds for each, at
   70,000 two to seven), costs more (OpenAI's price doubles past
   272,000) and the model reads its context worse."
  200000)

(defn context-tokens
  "The context budget, `RRAGENT_CONTEXT_TOKENS` or
   `default-context-tokens`."
  []
  (or (some-> (aget (.-env js/process) "RRAGENT_CONTEXT_TOKENS") js/parseInt (as-> n (when (pos? n) n)))
      default-context-tokens))

(defn context-read
  "The tokens of context the model read for an answer, by its `usage`:
   what it was sent, cached or not; 0 without usage."
  [usage]
  (if usage
    (+ (or (.-input usage) 0) (or (.-cacheRead usage) 0) (or (.-cacheWrite usage) 0))
    0))

(defn usage-cost
  "What a conversation's `pi.usage` (`usage`) says it cost, in dollars:
   its models' and its tools' buckets; 0 without one."
  [^js usage]
  (reduce + 0 (for [bucket ["models" "tools"]
                    ^js entry (some-> usage (aget bucket) js/Object.values array-seq)]
                (or (some-> entry .-cost .-total) 0))))

(defn ^:async owned
  "The ids of the conversations the conversation `id` owns: its
   subagents' and its runs', each made by a task of it."
  [harness id]
  (loop [cursor js/undefined
         found []]
    (let [page (await (.commit harness (fn [tx] (.scanConversations tx #js {:ownerConversationId id} 200 cursor)) context))
          items (array-seq (.-items page))
          found (into found (map #(.-id %)) items)]
      (if (and (.-next page) (seq items))
        (recur (.-next page) found)
        found))))

(defn ^:async spend
  "What the conversation `id` cost so far, in dollars, with every
   conversation it owns, theirs too (`owned`): the sum of their
   `pi.usage`, which pi-durable keeps across compactions, unlike the
   transcript, which a compaction shortens."
  [harness id]
  (loop [todo [id]
         total 0]
    (if-let [id (first todo)]
      (let [usage (await (.snapshot harness UsageDoc id context))
            children (await (owned harness id))]
        (recur (into (vec (rest todo)) children) (+ total (usage-cost usage))))
      total)))

(def compaction-focus
  "What a compaction rragent starts asks its summary for besides
   pi-durable's format: the files of the pictures the agent looked at,
   which the model gets whole until the compaction
   (`rragent.observations`) and not after it, so that the agent can
   read them again."
  (str "Under Critical Context, list the files of the pictures looked at in the summarized part "
       "(read and tool results) that the work may still need, each by its exact path with a few words "
       "on what it shows, so they can be read again; leave out pictures no longer relevant."))

(defn ^:async compact-unless-compacting!
  "A manual compaction of the conversation `id` of `harness` admitted,
   unless one runs for it already (`pi.live` of it lists it), its
   summary asked for the pictures' files (`compaction-focus`)."
  [harness id]
  (let [live (await (.snapshot harness LiveDoc id context))]
    (when (empty? (some-> live .-compactions array-seq))
      (when-let [conversation (await (.conversation harness id context))]
        (await (.compact conversation compaction-focus context))))))

(defn context-budget
  "The extension that keeps every conversation within `budget` tokens of
   context, whatever its model's window: an answer that read more
   (`context-read`) admits a compaction of the conversation of
   `@harness`, which summarizes while the conversation goes on and
   places its summary at the next boundary. pi-durable compacts by the
   model's window only (blocking at 16K below it), and its settings are
   one for every model; the window stays the model's, so pi-ai's room
   for the answer and a summary of a conversation past the budget stay
   right. One compaction at a time per conversation, `pending` covering
   the moment before `pi.live` lists it. Every answer is noted for the
   compaction while the person is away (`rragent.away/note!`)."
  [harness budget]
  (let [pending (atom #{})]
    (defineExtension #js {:name "rragent-context-budget"
                          :hooks #js [(hook GenerationTask #js {:afterResponse (fn [message ^js api _context]
                                                                                  (let [id (.-conversationId api)
                                                                                        read (context-read (.-usage message))]
                                                                                    (when (pos? read)
                                                                                      (away/note! id read (js/Date.now)))
                                                                                    (when (and @harness
                                                                                               (< budget read)
                                                                                               (not (contains? @pending id)))
                                                                                      (swap! pending conj id)
                                                                                      (-> (compact-unless-compacting! @harness id)
                                                                                          (.catch (fn [error] (js/console.error "[context]" error)))
                                                                                          (.finally (fn [] (swap! pending disj id))))))
                                                                                  js/undefined)})]})))

(defn registry
  "The registry: pi-durable's coding tools, rragent's own over the
   REPL `codemode`, the subagent tool in an extension of its own,
   which a subagent's agent leaves out, the redaction of the secrets
   of `home`, a binary file decoded as text left out of a result
   (`rragent.binary`), the earlier tool results masked in every request
   (`rragent.observations`), the summary of a compaction rragent starts
   read from the provider's cache (`rragent.warm-summary`), and
   `extensions` the caller brings."
  [codemode home extensions]
  (let [registry (doto (createRegistry)
                   (.install (createCodingTools #js {:images images/processor}))
                   (.install (extension codemode))
                   (.install subagent-tool/extension)
                   (.install (redaction home))
                   (.install binary/extension)
                   (.install observations/extension)
                   (.install warm-summary/extension))]
    (doseq [e extensions]
      (.install registry e))
    registry))

(defn- work-of
  "The `work/` of the bot whose conversation is `id`: what a call of a
   conversation not configured yet works in."
  [home id]
  (home/work-dir (home/bot-dir home id)))

(defn ^:async register-conversation!
  "The `conversation` of a bot known to the process: its folder,
   `bots/<id>/` of the machine's `home`, registered with the reload,
   which configures the conversation from the folder's definition
   now (its instructions, model and `cwd`) and again before every
   submission; its schedules registered with the machine's alarm
   (`rragent.schedule/watch!`), their next run noted in the bot's map
   as `:schedules/next` for the page; the events its conversation
   held as entries before the events file moved into the file
   (`rragent.events/migrate!`). Answers the definition."
  [{:keys [harness home provider]} conversation]
  (let [id (.-id conversation)
        dir (home/bot-dir home id)]
    (reload/register! id {:home dir :provider provider})
    (await (reload/reload! conversation context))
    (when-let [moved (await (events/migrate! conversation (home/events-file dir) context))]
      (js/console.error "[events]" (str moved " facts of the bot " id " moved into its events.edn")))
    (schedule/watch! id (fn []
                          (-> (.snapshot harness schedule/Schedules id context)
                              (.then (fn [schedules]
                                       (let [due (schedule/next-due schedules (js/Date.now))]
                                         (swap! db/db assoc-in (conj (db/bot-path id) :schedules/next) due)
                                         due))))))
    (reload/current id)))

(defn forget-conversation!
  "The bot of the conversation `id` forgotten by the process: its
   worker ended, its map taken out of the one atom (`rragent.db`),
   with its definition, its alarm source and its pane."
  [id]
  (codemode-worker/stop-of! id "The bot closed.")
  (swap! db/db update :bots dissoc (str id)))

(defn ^:async open
  "The harness of the machine in `home`, with the root conversation,
   the first bot, opened: `{:harness :root :view :definition :home
   :work :models :codemode :provider :migrated}`. `models` and `storage`
   are the production ones unless given (a test passes the faux
   provider and memory storage); `provider` names the models'
   provider, `openrouter` by default; `now` a clock for tests;
   `gateway` the Sprites gateway's base URL, `RRAGENT_GATEWAY` or
   `http://sprite.internal` unless given (a test passes its fake);
   `retry` how the gateway's list is tried again at start,
   `{:delays-ms :timeout-ms}` as `rragent.gateway/discover-at-start`
   takes it (a test shortens them); `extensions` more extensions for
   the registry, beside the one that keeps every conversation within
   `context-tokens` (`context-budget`). A home from before the bots is
   moved into the layout first (`rragent.bots/migrate!`, `:migrated`
   says so) and a home without a definition for the first bot gets the
   assistant's. The gateway's connectors are discovered here, tried again while the
   gateway comes up, for the REPL (`rragent.codemode`), with the MCP
   servers of `mcp.json` in `home` (`rragent.mcp`, connected at the
   first program), whose programs
   run in a worker thread per bot started at the first one
   (`rragent.codemode-worker`), and for the route to OpenRouter of the
   models and the media tools (`rragent.openrouter`), which asks the
   gateway again at a call when nothing was found. The other bots'
   conversations are opened by `rragent.machine` with
   `register-conversation!`."
  [{:keys [home models storage provider now gateway retry extensions]}]
  (let [home (or home (home/home))
        _ (fs/mkdirSync home #js {:recursive true})
        ;; every chat completion request measured, a refusal as too
        ;; large turned into the overflow pi-durable compacts on
        _ (model-requests/install!)
        migrated (bots/migrate! home)
        _ (when-not (home/definition-file (home/bot-dir home bots/root-id))
            (bots/write-definition! home bots/root-id (bots/assistant-text home)))
        storage (or storage (await (openNodeSqliteStorage (home/database home))))
        base (gateway/base gateway)
        codemode (assoc (codemode/handle base (await (gateway/discover-at-start base retry)))
                        :mcp (mcp/registry {:file (mcp/file home) :home home}))
        models (or models (openrouter-models codemode))
        opened (atom nil)
        harness (await (.open Harness storage
                              #js {:models models
                                   :registry (registry codemode home (cons (context-budget opened (context-tokens)) extensions))
                                   ;; the conversation's own cwd, the bot's work/ once it is
                                   ;; configured; the folder of its id before
                                   :env (fn [target] (NodeExecutionEnv. #js {:cwd (or (.-cwd target) (work-of home (.-conversationId target)))}))
                                   :settings #js {:compaction #js {:enabled true}}
                                   :now (or now js/undefined)
                                   ;; a failing hook, listener or task: said, not swallowed
                                   :onReport (fn [error] (js/console.error "[harness]" error))}
                              context))
        _ (reset! opened harness)
        root (await (.root harness context))
        view (await (.viewState root context))
        agent {:harness harness :home home :models models :codemode codemode :provider (or provider "openrouter")}
        definition (await (register-conversation! agent root))]
    (.resume harness)
    (assoc agent
           :root root
           :view view
           :definition definition
           :work (work-of home (.-id root))
           :migrated migrated)))

(defn busy?
  "Whether a run is going in the conversation of `view`: `pi.live` of
   it holds one then."
  [{:keys [view]}]
  (some? (.-run (or (aget (.-docs (.-value view)) "pi.live") #js {}))))

(defn compacting?
  "Whether a compaction of the conversation of `view` runs: `pi.live`
   of it lists one then."
  [{:keys [view]}]
  (boolean (seq (some-> (aget (.-docs (.-value view)) "pi.live") .-compactions array-seq))))

(defn ^:async sees-images?
  "Whether the conversation's model takes images, by its entry in the
   catalog of `models`; a model the catalog does not know counts as one
   that does, as pi-durable's `read` tool counts it (and pi-ai puts a
   placeholder where an image was when the model takes none)."
  [models conversation]
  (let [ref (.-model (await (.agent conversation context)))
        model (when ref (.getModel models (.-provider ref) (.-modelId ref)))]
    (if model
      (boolean (some #{"image"} (array-seq (.-input model))))
      true)))

(defn ^:async schedules
  "The schedules document of the conversation `id`."
  [harness id]
  (await (.snapshot harness schedule/Schedules id context)))

(defn ^:async submit
  "`draft` submitted to the bot's `conversation`, the definition read
   again first (`rragent.reload`), so that the run has the file as it
   is now. Answers the submission."
  [conversation draft]
  (await (reload/reload! conversation context))
  (await (.submit conversation draft context)))

(defn ^:async ask-in
  "`text` submitted to the `conversation` and answered: the answer's
   text, or `{:unanswered reason}`."
  [conversation text]
  (let [submission (await (submit conversation #js {:type "input" :content text}))
        settled (await (.wait submission context))]
    (if (and (= "done" (.-status settled)) (= "input" (.-type settled)))
      (subagent-tool/answer-text (await (.commit conversation (fn [tx] (.entry tx AssistantEntry (.-answer settled))) context)))
      {:unanswered (.-reason settled)})))

(defn ^:async ask
  "`text` asked of the first bot, the root conversation of `agent`."
  [{:keys [root]} text]
  (await (ask-in root text)))

(defn ^:async close
  "The harness closed, which aborts a running program with its call,
   every REPL worker ended, the MCP servers closed (`rragent.mcp`),
   every bot taken out of the one atom and its answers out of
   `rragent.away`."
  [{:keys [harness codemode]}]
  (await (.close harness context))
  (runs/reset-gate!)
  (codemode-worker/stop-all! "The agent closed.")
  (await (mcp/close! (:mcp codemode)))
  (swap! db/db assoc :bots {})
  (away/forget-all!))

(def memory-storage
  "A storage that keeps nothing, for tests."
  (fn [] (MemoryStorage.)))
