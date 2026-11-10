(ns rragent.tools.task
  "The `task` tool of the loop (`rragent.tasks`, docs/one-loop.md §2):
   `run` (the default) starts a task with `text`, everything the task
   must know, an optional `topic`, `model` and `thinking`, and `id` to
   continue an earlier task in its conversation; the task is made in
   the call's commit, a background task of the loop's conversation,
   and the call answers its id at once, or, with `wait`, waits up to a
   minute for the result and answers it, the task marked taken so
   that it is not delivered twice; a wait that runs out leaves the
   task to deliver its result as a message. `stop` with `id` aborts a
   task; `list` names the loop's tasks with their state. A rerun of
   the call after a crash finds the task it made by the call's id in
   the task's input."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [clojure.string :as str]
            [rragent.definition :as definition]
            [rragent.models :as models]
            [rragent.runs :as runs]
            [rragent.tasks :as tasks]
            [rragent.tools.topic :as topic-tool]
            [rragent.tools.work :as work]))

(def description
  (str "A task: a conversation of its own that does one piece of work for you with the working tools and answers with a result. "
       "It knows nothing of this conversation, so `text` says everything it needs: the goal, the files under artifacts/, the rules, what the result must hold. "
       "`topic` names the topic the work belongs to. `model` picks a stronger model when the work needs one, " (str/join " or " (map #(str "`" % "`") (models/stronger))) ", the default (`" definition/default-model "`) without and no other id; `thinking` its level. "
       "Without `wait` the call answers the task's id at once and the result arrives here later as a message from the task; "
       "with `wait: true` the call waits up to a minute and answers the result, for a short check you need in this very answer. "
       "`id` with `text` continues an earlier task in its own conversation, which keeps what it knew: its answer to a question the person answered. "
       "`stop` with `id` stops a task. `list` names your tasks with their state."))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- done-of
  "The result of a task record, the checkpoint's or the outcome's;
   nil while it runs."
  [record]
  (let [^js state (.-state record)]
    (or (some-> state .-outcome .-result)
        (some-> state .-checkpoint .-done))))

(defn- ^:async take!
  "The result of the task `id` claimed for the call, unless the task
   delivers it: `\"taken\"` or `\"delivered\"`."
  [api id call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (let [^js doc (await (.doc tx tasks/Taken))
                          key (str id)]
                      (if (aget (.-delivering doc) key)
                        "delivered"
                        (do (aset (.-taken doc) key true)
                            "taken"))))
                  call-context)))

(defn- ^:async release!
  "The task `id` let go of by the call whose minute ran out: the task
   delivers its result as a message."
  [api id call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (let [^js doc (await (.doc tx tasks/Taken))]
                      (when-not (.-released doc)
                        (aset doc "released" #js {}))
                      (aset (.-released doc) (str id) true))
                    js/undefined)
                  call-context)))

(defn- ^:async made-by-call
  "The task an earlier run of this call made, by the call's id in its
   input; nil for none."
  [api loop-id call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (let [page (await (.scanTasks tx #js {:conversationId loop-id :kind tasks/kind :order "descending"} 20))]
                      (some #(when (= (.-callId api) (some-> (.-input %) .-call)) (.-id %)) (array-seq (.-items page)))))
                  call-context)))

(defn- ^:async start!
  [^js args api call-context]
  (let [text (str/trim (str (.-text args)))
        topic (topic-tool/resolve-or-keep (.-topic args))
        continued (not-empty (str/trim (str (.-id args))))
        wait? (true? (.-wait args))
        loop-id (await (work/bot-id api call-context))]
    (when (str/blank? text)
      (throw (js/Error. "task needs the text: everything the task must know")))
    (when continued
      (let [found (when @tasks/harness (await (tasks/task @tasks/harness (js/Number continued) call-context)))]
        (when-not (and found (= (str loop-id) (str (:loop found))))
          (throw (js/Error. (str "No task " continued " of this conversation; `list` names them."))))
        (when-not (#{:done :failed :aborted} (:state found))
          (throw (js/Error. (str "The task " continued " still runs: wait for its result, or stop it first."))))
        (when-not (:conversation found)
          (throw (js/Error. (str "The task " continued " ended before it had a conversation; nothing to continue. Start a new task."))))))
    (let [model (not-empty (str/trim (str (.-model args))))
          thinking (not-empty (str/trim (str (.-thinking args))))
          ;; the model must be one the page offers, or this conversation's own:
          ;; an id from the model's training ran tasks at a price
          own (when model (some-> (await (.agent api call-context)) .-model .-modelId))
          _ (when (and model (not (models/task-model? model own)))
              (throw (js/Error. (str "No model " model " on this machine: a task runs on the default, " definition/default-model
                                     ", or on " (str/join " or " (models/stronger)) " when the work needs a stronger one. Call again with one of them, or without `model`."))))
          _ (when (and thinking (not (models/level? thinking)))
              (throw (js/Error. (str "No thinking level " thinking ": " (str/join ", " models/levels) ". Call again with one of them, or without `thinking`."))))
          id (or (await (made-by-call api loop-id call-context))
                 (await (.commit api
                                 (fn [tx]
                                   (.createTask tx tasks/Task
                                                #js {:text text
                                                     :topic (or topic js/undefined)
                                                     :model (or model js/undefined)
                                                     :thinking (or thinking js/undefined)
                                                     :continue (or continued js/undefined)
                                                     :wait wait?
                                                     :call (.-callId api)}
                                                #js {:ownership #js {:kind "conversation"}
                                                     :conversationId loop-id
                                                     :background true}))
                                 call-context)))]
      (runs/changed! loop-id)
      (if-not wait?
        (work/text (str "Started the task " id (when topic (str " for " topic)) ". Its result arrives here as a message from the task; go on meanwhile."))
        (loop [waited 0]
          (let [record (await (.commit api (fn [tx] (.task tx id)) call-context))
                ^js done (some-> record done-of)]
            (cond
              done
              (case (await (take! api id call-context))
                "taken" (work/text (if (= "done" (.-state done))
                                     (str "The task " id " is done. Its result:\n\n" (.-result done))
                                     (str "The task " id " did not answer: " (.-reason done))))
                (work/text (str "The task " id " is done; its result arrives here as a message from the task.")))

              (some-> record .-state .-outcome)
              (work/text (str "The task " id " ended " (some-> record .-state .-outcome .-status) " without a result."))

              (< waited tasks/wait-ms)
              (do (await (sleep 500))
                  (recur (+ waited 500)))

              :else
              (do (await (release! api id call-context))
                  (work/text (str "The task " id " is still running after a minute; its result arrives here as a message from the task when it is done. Go on meanwhile."))))))))))

(defn- ^:async stop!
  [^js args api call-context]
  (let [id (str/trim (str (.-id args)))
        harness @tasks/harness
        loop-id (await (work/bot-id api call-context))]
    (when (str/blank? id)
      (throw (js/Error. "stop needs the id of the task")))
    (when-not harness
      (throw (js/Error. "The machine cannot stop tasks yet.")))
    (let [found (await (tasks/task harness (js/Number id) call-context))]
      (when-not (and found (= (str loop-id) (str (:loop found))))
        (throw (js/Error. (str "No task " id " of this conversation."))))
      (if (#{:done :failed :aborted} (:state found))
        (work/text (str "The task " id " had ended already: " (name (:state found)) "."))
        (do (await (.abortTask harness (js/Number id) call-context))
            (work/text (str "Stopped the task " id ".")))))))

(defn- ^:async list!
  [api call-context]
  (let [loop-id (await (work/bot-id api call-context))
        harness @tasks/harness
        tasks (if harness (await (tasks/listed harness loop-id 20 call-context)) [])]
    (work/text (if (empty? tasks)
                 "No tasks."
                 (str/join "\n" (map (fn [{:keys [id state topic text line]}]
                                       (str id ": " (name state)
                                            (when topic (str " · " topic))
                                            " · " (or (runs/short-line text) "")
                                            (when (seq line) (str " → " line))))
                                     tasks))))))

(def tool
  (defineTool
   #js {:name "task"
        :description description
        :parameters (.Object Type #js {:action (.Optional Type (.Union Type #js [(.Literal Type "run") (.Literal Type "stop") (.Literal Type "list")]))
                                       :text (.Optional Type (.String Type #js {:description "run: the task, complete; the task knows nothing of this conversation"}))
                                       :topic (.Optional Type (.String Type #js {:description "run: the topic the work belongs to, by name or id"}))
                                       :model (.Optional Type (.String Type #js {:description (str "run: the model the task runs on when the work needs a stronger one: " (str/join " or " (models/stronger)) "; the default without, no other id")}))
                                       :thinking (.Optional Type (.String Type #js {:description (str "run: the thinking level, " (str/join ", " models/levels) "; the default without")}))
                                       :wait (.Optional Type (.Boolean Type #js {:description "run: wait up to a minute for the result and answer it; false without"}))
                                       :id (.Optional Type (.String Type #js {:description "run: an earlier task to continue in its conversation; stop: the task to stop"}))})
        ;; a rerun after a crash finds the task it made by the call's id
        :replay "safe"
        :execute (^:async fn [args api call-context]
                   (case (or (.-action args) "run")
                     "run" (await (start! args api call-context))
                     "stop" (await (stop! args api call-context))
                     "list" (await (list! api call-context))))}))
