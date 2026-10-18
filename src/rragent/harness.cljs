(ns rragent.harness
  "pi-durable opened on the agent's folder: the SQLite storage, the
   models (OpenRouter with the key from the environment, or what a test
   passes), the registry with pi-durable's coding tools (read, write,
   edit, bash in `work/`) and rragent's own (state, fetch, eval), the
   root conversation configured from the definition each time it opens,
   and the runs the last process left unfinished resumed. One process
   owns the storage at a time."
  (:require ["@earendil-works/chord/context" :refer [BACKGROUND_CONTEXT]]
            ["@earendil-works/pi-ai/models" :refer [createModels]]
            ["@earendil-works/pi-ai/providers/openrouter" :refer [openrouterProvider]]
            ["@earendil-works/pi-durable" :refer [AssistantEntry Harness MemoryStorage createRegistry defineExtension]]
            ["@earendil-works/pi-durable/env/node" :refer [NodeExecutionEnv]]
            ["@earendil-works/pi-durable/storage/sqlite/node" :refer [openNodeSqliteStorage]]
            ["@earendil-works/pi-durable/tools" :refer [CodingTools]]
            [rragent.definition :as definition]
            [rragent.home :as home]
            [rragent.tools.eval :as eval-tool]
            [rragent.tools.fetch :as fetch-tool]
            [rragent.tools.state :as state-tool]))

(def context
  "The Chord context every call takes; this one never cancels."
  BACKGROUND_CONTEXT)

(def extension
  (defineExtension #js {:name "rragent"
                        :tools #js [state-tool/tool fetch-tool/tool eval-tool/tool]}))

(defn openrouter-models
  "The models through OpenRouter, the key from OPENROUTER_API_KEY."
  []
  (doto (createModels)
    (.setProvider (openrouterProvider))))

(defn registry
  []
  (doto (createRegistry)
    (.install CodingTools)
    (.install extension)))

(defn ^:async open
  "The harness of the agent in `home`: `{:harness :root :definition :home}`.
   `models` and `storage` are the production ones unless given (a test
   passes the faux provider and memory storage); `provider` names the
   models' provider, `openrouter` by default."
  [{:keys [home models storage provider]}]
  (let [home (or home (home/home))
        file (or (home/definition-file home)
                 (throw (ex-info (str "No AGENT.md or SKILL.md in " home) {:home home})))
        definition (definition/definition file)
        work (home/work-dir home)
        storage (or storage (await (openNodeSqliteStorage (home/database home))))
        harness (await (.open Harness storage
                              #js {:models (or models (openrouter-models))
                                   :registry (registry)
                                   :env (fn [target] (NodeExecutionEnv. #js {:cwd (or (.-cwd target) work)}))
                                   :settings #js {:compaction #js {:enabled true}}}
                              context))
        root (await (.root harness context))]
    ;; the definition may have changed since the last run: applied on every open
    (await (.configure root #js {:model #js {:provider (or provider "openrouter") :modelId (:model definition)}
                                 :thinkingLevel (or (:thinking definition) js/undefined)
                                 :instructions (:instructions definition)
                                 :cwd work}
                       context))
    (.resume harness)
    {:harness harness :root root :definition definition :home home}))

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
