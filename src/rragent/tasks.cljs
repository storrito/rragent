(ns rragent.tasks
  "The tasks of a bot (docs/bots.md §3): a task is a fresh-context
   helper a bot starts with its `task` tool (`rragent.tools.task`), a
   background task of the bot's conversation (`Task`, kind
   `rragent.task`) that makes a conversation of its own, owned by the
   task, configured with the shared prompt and the bundle's `TASK.md`
   (`rragent.bots/task-instructions`), a task's tools
   (`rragent.roles`), the model of the call or the bot's own, and the
   bot's `work/` as cwd, so that what it makes lands in the bot's
   artifacts; submits the text to it, waits for the answer and keeps
   it as its result, the answer's first line as the line the activity
   shows. A `continue` id makes the task submit into an earlier
   task's conversation instead, which keeps what it knew.

   The result reaches the bot one of two ways, never both: the bot's
   `task` call waits for it (`wait: true`, up to a minute) and takes
   it as the call's result, or the task delivers it into the bot's
   chat as a follow-up input, kind `rragent.task-done`, which wakes
   the bot for one turn. The `Taken` document decides: the call marks
   the task `taken` in one transaction, the task marks itself
   `delivering` in another, and each gives way to the mark it finds.
   The result is in the task's checkpoint before either happens, so a
   call that polls finds it.

   A stop (`task stop`) aborts the task: its conversation's turn is
   aborted through the harness (`harness`, set at open), the task ends
   aborted and delivers nothing. The tasks of a bot are its tasks of
   the kind, newest first (`listed`), which the activity of the pane
   shows beside its runs, each with its conversation to open."
  (:refer-clojure :exclude [run!])
  (:require ["@earendil-works/pi-durable" :refer [configure defineDoc defineTask]]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.definition :as definition]
            [rragent.models :as models]
            [rragent.roles :as roles]
            [rragent.runs :as runs]
            [rragent.tools.subagent :as subagent-tool]))

(def kind
  "rragent.task")

(def done-kind
  "The entry of a task's result in the bot's chat."
  "rragent.task-done")

(def input-kind
  "The entry of the task's text in its own conversation."
  "rragent.task-input")

(def wait-ms
  "How long a `wait: true` call waits before the task goes on alone."
  60000)

(defonce harness
  (atom nil))

(def Taken
  "A session document: the tasks whose result the bot's call took
   (`taken`), the tasks a waiting call let go of when its minute ran
   out (`released`), and the tasks that deliver theirs into the bot's
   chat (`delivering`), each task id to true. A waited task (`wait` in its
   input) holds its result until the call took or released it, so the
   call wins while it waits."
  (defineDoc #js {:kind "rragent.tasks"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {:taken #js {} :released #js {} :delivering #js {}})}))

(def hold-ms
  "How long a waited task holds its result for the call before it
   delivers anyway: the call's minute and a margin."
  (+ wait-ms 10000))

(defn- ^:async marks
  "The `Taken` document's marks for the task `id`: `{:taken? :released?}`,
   read through a commit of the task that changes nothing."
  [runtime id context]
  (let [found (atom nil)]
    (await (.commit runtime
                    (^:async fn [tx]
                      (let [^js doc (await (.doc tx Taken))
                            key (str id)]
                        (reset! found {:taken? (boolean (aget (.-taken doc) key))
                                       :released? (boolean (aget (or (.-released doc) #js {}) key))}))
                      js/undefined)
                    context))
    @found))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- terminal
  [outcome]
  #js {:status "terminal" :outcome outcome})

(defn done-content
  "What the bot reads when a task is done."
  [id result]
  (str "The task " id " is done. Its result:\n\n" result
       "\n\nTell the person what matters to them of it, in a sentence or a card, and file in the wiki what is worth keeping. "
       "When the task asks for an answer from the person, ask them, and continue the task with its id once you have it."))

(defn failed-content
  "What the bot reads when a task did not answer."
  [id reason]
  (str "The task " id " did not answer: " reason
       ". Tell the person in a sentence what did not happen and what you propose."))

(defn- conversation-id
  "An id as the storage keys it, a conversation's or a task's: a
   number when the text is one."
  [value]
  (let [text (str/trim (str value))]
    (if (re-matches #"\d+" text) (js/Number text) text)))

(defn- continued-conversation
  "The conversation of the task `record` a new one continues: the one
   its outcome names, else its checkpoint's; nil before it has one."
  [^js record]
  (or (some-> record .-state .-outcome .-result .-conversation)
      (some-> record .-state .-checkpoint .-conversation)))

(defn- reason-of
  [error]
  (or (not-empty (str (some-> error .-message))) (str error)))

(defn- ^:async fail!
  "A phase that threw: the task moves to `deliver` with a failed
   result, so that the bot hears of it (docs/bots.md §3) instead
   of a task that ends in silence; an abort under way (the runtime's
   signal) is left to the abort handler."
  [runtime ^js checkpoint error context]
  (when (some-> (.-signal runtime) .-aborted)
    (throw error))
  (js/console.error "[task]" (str "the task " (.-taskId runtime) " failed: " (reason-of error)))
  (await (.commit runtime
                  (fn [] #js {:status "running"
                              :checkpoint (doto (or checkpoint #js {:started (.now runtime)})
                                            (aset "phase" "deliver")
                                            (aset "done" #js {:state "failed"
                                                              :line (str "did not run: " (reason-of error))
                                                              :reason (reason-of error)}))})
                  context)))

(defn- ^:async make!
  "The task's conversation: the one it owns when a rerun finds it, the
   earlier task's when the input continues one (by the task's id, the
   conversation its record names), else a new one configured as a
   task's; and the checkpoint that names it."
  [task runtime context]
  (let [id (.-taskId runtime)
        ^js input (.-input task)
        ^js agent (await (.agent runtime context))
        provider (or (some-> agent .-model .-provider) "openrouter")
        own (some-> agent .-model .-modelId)
        asked (not-empty (str (.-model input)))
        ;; a task runs on the bot's own model unless the call named another
        ;; the page offers; the tool refuses any other id, and a record of
        ;; before that carries one runs on the bot's own
        model (cond
                (nil? asked) (or own definition/default-model)
                (models/task-model? asked own) asked
                :else (do (js/console.warn "[tasks]" (str "the task " id " asked for the model " asked ", which this machine does not offer; it runs on " (or own definition/default-model)))
                          (or own definition/default-model)))
        thinking (not-empty (str (.-thinking input)))
        continued (not-empty (str (.-continue input)))]
    (await (.commit runtime
                    (^:async fn [tx]
                      (let [existing (aget (.-items (await (.scanConversations tx #js {:ownerTaskId id} 1))) 0)
                            conversation (cond
                                           existing (.-id existing)
                                           continued (let [^js earlier (await (.task tx (conversation-id continued)))]
                                                       (or (some-> earlier continued-conversation)
                                                           (throw (js/Error. (str "No task " continued " with a conversation to continue.")))))
                                           :else (let [created (.-id (await (.createConversation tx #js {:ownership #js {:kind "task" :taskId id}})))]
                                                   (await (configure tx created #js {:model #js {:provider provider :modelId model}
                                                                                     :thinkingLevel (or thinking js/undefined)
                                                                                     :instructions (bots/task-instructions)
                                                                                     :modelTools (roles/model-tools :task)
                                                                                     :cwd (or (.-cwd agent) js/undefined)}))
                                                   created))]
                        #js {:status "running"
                             :checkpoint #js {:phase "run" :conversation conversation :started (.now runtime)}}))
                    context))))

(defn- ^:async made!
  "`make!`, a failure of it delivered (`fail!`)."
  [task runtime context]
  (try
    (await (make! task runtime context))
    (catch :default error
      (await (fail! runtime nil error context))))
  (runs/changed! (.-conversationId runtime)))

(defn- ^:async answer-of
  "The text of the answer entry `id`, read through a commit of the task
   that changes nothing."
  [runtime id context]
  (let [found (atom nil)]
    (await (.commit runtime
                    (^:async fn [tx]
                      (reset! found (await (.entry tx id)))
                      js/undefined)
                    context))
    (when @found
      (subagent-tool/answer-text @found))))

(defn- ^:async answered!
  "The task's text submitted to its conversation (deduplicated by the
   task's id, so a rerun waits on the same submission), the answer
   kept in the checkpoint as the result."
  [task runtime ^js checkpoint context]
  (let [id (.-taskId runtime)
        ^js input (.-input task)
        conversation (await (.conversation runtime (.-conversation checkpoint) context))
        submission (await (.submit conversation
                                   #js {:type "input"
                                        :content (.-text input)
                                        :requestId (str "task:" id)
                                        :entry #js {:kind input-kind :data #js {}}}
                                   context))
        ^js settled (await (.wait submission context))
        done? (= "done" (.-status settled))
        answer (when done?
                 (or (not-empty (await (answer-of runtime (.-answer settled) context)))
                     "(The task answered without text.)"))
        line (if done? (runs/first-line answer) (str "did not answer: " (.-reason settled)))]
    (await (.commit runtime
                    (fn [] #js {:status "running"
                                :checkpoint (doto checkpoint
                                              (aset "phase" "deliver")
                                              (aset "done" #js {:state (if done? "done" "failed")
                                                                :line line
                                                                :result (or answer js/undefined)
                                                                :reason (if done? js/undefined (.-reason settled))}))})
                    context))))

(defn- ^:async run!
  "`answered!`, a failure of it delivered (`fail!`): a conversation
   that cannot be submitted to, a model that keeps failing."
  [task runtime context]
  (let [^js checkpoint (.. task -state -checkpoint)]
    (try
      (await (answered! task runtime checkpoint context))
      (catch :default error
        (await (fail! runtime checkpoint error context))))))

(defn- ^:async deliver!
  "The result into the bot's chat, unless the bot's call took it: the
   claim in one transaction on the `Taken` document, then the input,
   which the request id deduplicates."
  [runtime ^js done context]
  (let [id (.-taskId runtime)
        key (str id)
        claimed (atom false)]
    (await (.commit runtime
                    (^:async fn [tx]
                      (let [^js doc (await (.doc tx Taken))]
                        (when-not (aget (.-taken doc) key)
                          (aset (.-delivering doc) key true)
                          (reset! claimed true)))
                      js/undefined)
                    context))
    (when @claimed
      (let [bot (await (.conversation runtime (.-conversationId runtime) context))
            state (.-state done)]
        (await (.submit bot
                        #js {:type "input"
                             :content (if (= "done" state)
                                        (done-content id (.-result done))
                                        (failed-content id (.-reason done)))
                             :whenBusy "followUp"
                             :requestId (str "task-done:" id)
                             :entry #js {:kind done-kind
                                         :data #js {:task id :state state :line (.-line done)}}}
                        context))))))

(defn- ^:async held!
  "A waited task's pause: until the call took the result (nothing to
   deliver then) or released it, at most `hold-ms`; answers whether to
   deliver."
  [runtime context]
  (let [id (.-taskId runtime)
        started (js/Date.now)]
    (loop []
      (let [{:keys [taken? released?]} (await (marks runtime id context))]
        (cond
          taken? false
          released? true
          (< hold-ms (- (js/Date.now) started)) true
          :else (do (await (sleep 250))
                    (recur)))))))

(defn- ^:async finish!
  "The result delivered, unless a waiting call takes it, and the task
   ended with it."
  [task runtime context]
  (let [^js checkpoint (.. task -state -checkpoint)
        ^js done (.-done checkpoint)
        ^js input (.-input task)]
    (when (or (not (true? (.-wait input)))
              (await (held! runtime context)))
      (await (deliver! runtime done context)))
    (await (.commit runtime
                    (fn [] (terminal #js {:status "completed"
                                          :result #js {:state (.-state done)
                                                       :line (.-line done)
                                                       :result (.-result done)
                                                       :text (.-text input)
                                                       :conversation (.-conversation checkpoint)
                                                       :started (.-started checkpoint)
                                                       :ended (.now runtime)}}))
                    context))
    (runs/changed! (.-conversationId runtime))))

(def Task
  (defineTask
   #js {:name kind
        :version 1
        :initial (fn [_input] #js {:phase "make"})
        :phases
        #js {:make (fn [task runtime context] (made! task runtime context))
             :run (fn [task runtime context] (run! task runtime context))
             :deliver (fn [task runtime context] (finish! task runtime context))}
        :abort (fn [task runtime context]
                 (let [conversation (some-> (.. task -state -checkpoint) .-conversation)]
                   (-> (if (and conversation @harness)
                         (-> (.conversation @harness conversation context)
                             (.then (fn [handle] (when handle (.abort handle context))))
                             (.catch (fn [_] nil)))
                         (js/Promise.resolve nil))
                       (.then (fn [] (.commit runtime (fn [] (terminal #js {:status "aborted"})) context)))
                       (.then (fn [] (runs/changed! (.-conversationId runtime)))))))}))

(defn task-of
  "A task record as the page and the model read it: `{:id :bot :text
   :state :line :result :started :ended :conversation}`, `:bot` the
   conversation that started it, the state `:queued` before the
   conversation is made, `:running` while the answer is waited for,
   then `:done`, `:failed` or `:aborted`."
  [record]
  (let [^js state (.-state record)
        ^js checkpoint (.-checkpoint state)
        ^js outcome (.-outcome state)
        ^js result (some-> outcome .-result)
        ^js done (or result (some-> checkpoint .-done))
        ^js input (.-input record)]
    {:id (.-id record)
     :bot (.-conversationId record)
     :text (.-text input)
     :state (cond
              (some? outcome) (case (.-status outcome)
                                "completed" (keyword (or (some-> result .-state) "done"))
                                "aborted" :aborted
                                :failed)
              done (keyword (.-state done))
              (some-> checkpoint .-conversation) :running
              :else :queued)
     :line (or (some-> done .-line) (when (and outcome (= "failed" (.-status outcome))) (some-> outcome .-error .-message)))
     :result (some-> done .-result)
     :started (or (some-> checkpoint .-started) (.-startedAt record))
     :ended (or (some-> result .-ended) (.-endedAt record))
     :conversation (or (some-> result .-conversation) (some-> checkpoint .-conversation))}))

(defn as-run
  "A task as the activity of the pane lists a run
   (`rragent.runs/run-of`): `{:id :bot :trigger :state :line :started
   :ended :conversation}`, the trigger a task's with the text's first
   sentence as its line, which the page's mark of what a bot works on
   names too (`rragent.page/working-tasks`)."
  [{:keys [id bot text state line started ended conversation]}]
  {:id id
   :bot bot
   :trigger {:kind "task" :name "task" :line (runs/short-line text)}
   :state state
   :line line
   :started started
   :ended ended
   :conversation conversation})

(defn ^:async listed
  "The newest `n` tasks of the bot whose conversation is `bot-id`,
   through a commit of `harness` that changes nothing."
  [harness bot-id n context]
  (let [page (await (.commit harness
                             (fn [tx] (.scanTasks tx #js {:conversationId (conversation-id bot-id) :kind kind :order "descending"} n))
                             context))]
    (mapv #(dissoc (task-of %) :result) (array-seq (.-items page)))))

(defn ^:async task
  "One task by its id, nil for none."
  [harness id context]
  (when-let [record (await (.getTask harness (conversation-id id) context))]
    (when (= kind (.-kind record))
      (task-of record))))
