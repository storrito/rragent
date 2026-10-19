(ns rragent.harness
  "pi-durable opened on the agent's folder: the SQLite storage, the
   models (OpenRouter with the key from the environment, or what a test
   passes), the registry with pi-durable's coding tools (read, write,
   edit, bash in `work/`), rragent's own (state, fetch, eval, schedule,
   hook, and the tools of the artifacts: project, render, image, voice,
   music; the pane shows what they make) and the integrations the
   environment names (Storrito's procedures as deferred tools with
   `tool_search` to find them, Composio's meta tools), the root
   conversation configured from the definition each time it opens, and
   the runs the last process left unfinished resumed. One process owns
   the storage at a time."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/openrouter" :refer [openrouterProvider]]
            ["@earendil-works/pi-durable" :refer [AssistantEntry Harness MemoryStorage createRegistry defineExtension]]
            ["@earendil-works/pi-durable/env/node" :refer [NodeExecutionEnv]]
            ["@earendil-works/pi-durable/storage/sqlite/node" :refer [openNodeSqliteStorage]]
            ["@earendil-works/pi-durable/tool-search" :refer [createToolSearchExtension]]
            ["@earendil-works/pi-durable/tools" :refer [CodingTools]]
            [rragent.composio :as composio]
            [rragent.definition :as definition]
            [rragent.home :as home]
            [rragent.schedule :as schedule]
            [rragent.storrito :as storrito]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.hook :as hook-tool]
            [rragent.tools.media :as media-tool]
            [rragent.tools.project :as project-tool]
            [rragent.tools.render :as render-tool]
            [rragent.tools.schedule :as schedule-tool]
            [rragent.tools.state :as state-tool]))

(def context
  "The Chord context every call takes; this one never cancels."
  BACKGROUND_CONTEXT)

(def extension
  (defineExtension #js {:name "rragent"
                        :tools #js [state-tool/tool fetch-tool/tool eval-tool/tool schedule-tool/tool hook-tool/tool
                                    project-tool/tool render-tool/tool media-tool/image media-tool/voice media-tool/music]
                        :tasks #js [schedule/Task]}))

(defn openrouter-models
  "The models through OpenRouter, the key from OPENROUTER_API_KEY."
  []
  (doto (createModels)
    (.setProvider (openrouterProvider))))

(defn registry
  "The registry: pi-durable's coding tools, rragent's own and the
   `extensions` of the integrations, in that order."
  [extensions]
  (let [registry (doto (createRegistry)
                   (.install CodingTools)
                   (.install extension))]
    (doseq [e extensions]
      (.install registry e))
    registry))

(defn ^:async integrations
  "The extensions of the integrations: Storrito's with `tool_search`
   before it, since its tools are deferred, and Composio's. Each from
   its environment variables, or from what a test passes as `:storrito`
   and `:composio` (nil for none)."
  [options]
  (let [storrito (await (storrito/extension (if (contains? options :storrito) (:storrito options) (storrito/config))))
        composio (composio/extension (if (contains? options :composio) (:composio options) (composio/config)))]
    (remove nil? [(when storrito (createToolSearchExtension)) storrito composio])))

(defn ^:async open
  "The harness of the agent in `home`: `{:harness :root :view :definition :home}`.
   `models` and `storage` are the production ones unless given (a test
   passes the faux provider and memory storage); `provider` names the
   models' provider, `openrouter` by default; `now` a clock for tests;
   `storrito` and `composio` the integrations' configs, from the
   environment unless given."
  [{:keys [home models storage provider now] :as options}]
  (let [home (or home (home/home))
        file (or (home/definition-file home)
                 (throw (ex-info (str "No AGENT.md or SKILL.md in " home) {:home home})))
        definition (definition/definition file)
        work (home/work-dir home)
        storage (or storage (await (openNodeSqliteStorage (home/database home))))
        extensions (await (integrations options))
        harness (await (.open Harness storage
                              #js {:models (or models (openrouter-models))
                                   :registry (registry extensions)
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
    {:harness harness :root root :view view :definition definition :home home}))

(defn busy?
  "Whether a run is going in the conversation: `pi.live` of the view
   holds one then."
  [{:keys [view]}]
  (some? (.-run (or (aget (.-docs (.-value view)) "pi.live") #js {}))))

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
  [{:keys [harness]}]
  (await (.close harness context)))

(def memory-storage
  "A storage that keeps nothing, for tests."
  (fn [] (MemoryStorage.)))
