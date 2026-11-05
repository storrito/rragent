(ns rragent.tools.storrito
  "The `storrito` tool: one command of the organization's Storrito API
   run, the effect of a `[:storrito/<procedure> args]` action of a
   plan (`rragent.plan`). Only tools call it, never the model: the eval
   tool runs it as a nested call under the action's key, so that a
   plan a restart interrupted finds the command done and does not run
   it again (`rragent.tools.eval`). The call goes through the Storrito
   connection of the gateway the REPL found (`rragent.storrito/call!`),
   its answer the tool's value, a failed call an error with what the
   API said and the status and body as its value."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.codemode :as codemode]
            [rragent.storrito :as storrito]))

(defn- connection!
  "The Storrito connection of `codemode`, as it was found; a throw
   without one."
  [codemode]
  (let [{:keys [connection catalog link error]} (codemode/storrito codemode)]
    (cond
      (and connection catalog) connection
      error (throw (js/Error. (str "The Storrito catalog at " link " could not be read: " error)))
      :else (throw (js/Error. "No Storrito among the connections: no custom_api connection of the gateway has a catalog at its base.")))))

(defn ^:async run
  "The procedure `name` called with `args` (data) for the eval tool's
   plan: `#js {:output :structuredOutput}`, the answer as the value;
   a failed call an error result whose value is `{:status :body}`."
  [codemode name args]
  (let [connection (connection! codemode)]
    (try
      (let [answer (await (storrito/call! (codemode/discovery codemode) connection name args))]
        #js {:output #js [#js {:type "text" :text (pr-str answer)}]
             :structuredOutput (clj->js answer)})
      (catch :default e
        (let [{:keys [status body]} (ex-data e)]
          (if status
            #js {:output #js [#js {:type "text" :text (.-message e)}]
                 :structuredOutput (clj->js {:status status :body body :error (.-message e)})
                 :isError true}
            (throw e)))))))

(defn tool
  [codemode]
  (defineTool
   #js {:name "storrito"
        :description "One command of the organization's Storrito API, run for a plan's action: the procedure's name and its arguments."
        :callers #js ["tools"]
        :parameters (.Object Type #js {:procedure (.String Type #js {:description "The procedure's name"})
                                       :args (.Optional Type (.Any Type #js {:description "The procedure's arguments, a JSON object"}))})
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args _api _call-context]
                   (let [given (.-args args)]
                     (when (and given (not (object? given)))
                       (throw (js/Error. "args must be a JSON object")))
                     (await (run codemode (.-procedure args) (js->clj (or given #js {}) :keywordize-keys true)))))}))

