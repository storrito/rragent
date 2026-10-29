(ns rragent.runs
  "The runs of a bot: what a hook's call or a schedule's fire starts
   instead of a message in the bot's chat. A run is a background task
   of the bot's conversation (`Task`, kind `rragent.run`) that makes a
   conversation of its own, owned by the task and a copy of the bot's
   agent (its instructions with `note` on top, its model, its tools,
   its `work/` as cwd), submits the trigger's text to it, waits for
   the answer and ends with one line about it as its result: the line
   the person reads in the bot's activity. The bot's chat gets nothing
   of it, unless the run failed: then the task submits a report into
   the bot's conversation, as an input the bot answers, deduplicated
   by its request id. The run's own `report` tool
   (`rragent.tools.report`) does the same for what the person must
   know now.

   One run goes at a time on a machine (Max, 2026-10-28): `enter!`
   and `leave!` are a gate in memory, held by a task id, which a
   restart re-enters; a task that holds it already passes at once, so
   the phase may run twice. The runs of a bot are its tasks of the
   kind, newest first (`listed`, a scan of the storage), so nothing
   grows but the storage and nothing is deleted. The bot's events
   file and its artifacts are the run's too: a run writes where the
   bot and its other runs read (`rragent.events`,
   `rragent.tools.work/bot-dir`)."
  (:require ["@earendil-works/pi-durable" :refer [configure defineTask]]
            [clojure.string :as str]
            [rragent.tools.subagent :as subagent-tool]))

(def kind
  "rragent.run")

(def report-kind
  "The entry of a run's report in the bot's chat."
  "rragent.run-report")

(def max-line-chars
  160)

(defonce gate
  (atom {:holder nil :queue []}))

;; the runs started or resumed in this process that are not over: for
;; `active?`, since a task made just now holds the gate only once its
;; phase runs
(defonce live
  (atom #{}))

(defn enter!
  "A promise of the gate: true once the run `id` holds it (at once when
   it does already, or when it is free), false when `signal` aborts the
   wait."
  [id signal]
  (swap! live conj id)
  (js/Promise.
   (fn [resolve]
     (let [{:keys [holder]} @gate]
       (cond
         (= holder id) (resolve true)
         (nil? holder) (do (swap! gate assoc :holder id)
                           (resolve true))
         :else (do (swap! gate update :queue conj [id resolve])
                   (when signal
                     (.addEventListener signal "abort"
                                        (fn []
                                          (swap! gate update :queue (fn [queue] (vec (remove #(= id (first %)) queue))))
                                          (swap! live disj id)
                                          (resolve false))
                                        #js {:once true}))))))))

(defn leave!
  "The gate let go by the run `id` when it holds it: the next in the
   queue takes it."
  [id]
  (swap! live disj id)
  (let [{:keys [holder queue]} @gate]
    (when (= holder id)
      (if-let [[next resolve] (first queue)]
        (do (swap! gate assoc :holder next :queue (vec (rest queue)))
            (resolve true))
        (swap! gate assoc :holder nil)))))

(defonce on-change
  (atom nil))

(defn watch!
  "`f`, told the bot's conversation id whenever a run of the bot
   starts, gets its conversation or ends; `rragent.machine` refreshes
   the bot's runs for the page with it."
  [f]
  (reset! on-change f))

(defn changed!
  [bot-id]
  (when-let [f @on-change]
    (try
      (f bot-id)
      (catch :default e
        (js/console.error "[runs]" (str e))))))

(defonce on-change
  (atom nil))

(defn watch!
  "`f`, told the bot's conversation id whenever a run of the bot
   starts, gets its conversation or ends; `rragent.machine` refreshes
   the bot's runs for the page with it."
  [f]
  (reset! on-change f))

(defn changed!
  [bot-id]
  (when-let [f @on-change]
    (try
      (f bot-id)
      (catch :default e
        (js/console.error "[runs]" (str e))))))

(defn active?
  "Whether a run goes, waits for its turn, or was just started."
  []
  (boolean (or (:holder @gate) (seq @live))))

(defn reset-gate!
  "The gate as new: for a harness that closes (tests open several)."
  []
  (reset! gate {:holder nil :queue []})
  (reset! live #{}))

(def note
  "What a run is told after the bot's instructions."
  (str "You are a run of the bot, started by what the message below says, not the bot itself: the definition above is yours for this one job, "
       "the bot's events (remember, recall) and its artifacts are yours to read and write, and this conversation ends with your answer. "
       "Nobody reads along and nobody answers questions, so do the job with your tools, deciding what is unclear yourself, and act as the job says: post, write, send. "
       "Hooks and schedules are the bot's, not yours: set none. "
       "When the person must know something now, call `report` with one or two sentences; it reaches the bot's chat. "
       "End with one line that says what you did, or what you could not do and why: it is the line the person sees in the bot's activity."))

(defn instructions
  "The run's instructions: the bot's, then the note."
  [bot-instructions]
  (if (seq bot-instructions)
    (str bot-instructions "\n\n" note)
    note))

(defn trigger-line
  "What started the run, for a person: the event's line when the
   trigger has one, else its kind and name."
  [{:keys [kind name line]}]
  (or (not-empty line)
      (str (case kind "hook" "hook" "schedule" "schedule" (str kind)) " " name)))

(defn first-line
  "The first line of the answer with something in it, cut."
  [text]
  (let [line (or (some #(when-not (str/blank? %) (str/trim %)) (str/split-lines (str text))) "")]
    (if (< max-line-chars (count line))
      (str (subs line 0 max-line-chars) "…")
      line)))

(defn url
  "The page of the run of the bot."
  [bot-id run-id]
  (str "/bot/" bot-id "/run/" run-id))

(defn- terminal
  [outcome]
  #js {:status "terminal" :outcome outcome})

(defn- ^:async made!
  "The run's conversation, made in a commit of the task (the one it owns
   when a rerun finds it) as a copy of the bot's agent with the run's
   instructions, and the checkpoint that names it. Answers nothing;
   the task is invoked again with the new checkpoint."
  [runtime context]
  (let [id (.-taskId runtime)
        agent (await (.agent runtime context))]
    (await (.commit runtime
                    (^:async fn [tx]
                      (let [existing (aget (.-items (await (.scanConversations tx #js {:ownerTaskId id} 1))) 0)
                            conversation (if existing
                                           (.-id existing)
                                           (let [created (.-id (await (.createConversation tx #js {:ownership #js {:kind "task" :taskId id}})))]
                                             (await (configure tx created #js {:instructions (instructions (.-instructions agent))}))
                                             created))]
                        #js {:status "running"
                             :checkpoint #js {:phase "run" :conversation conversation :started (.now runtime)}}))
                    context))
    (changed! (.-conversationId runtime))))

(defn- ^:async answer-of
  "The text of the answer entry `id`, read through a commit of the task
   that changes nothing (the entry is the run's conversation's, not
   the task's)."
  [runtime id context]
  (let [found (atom nil)]
    (await (.commit runtime
                    (^:async fn [tx]
                      (reset! found (await (.entry tx id)))
                      js/undefined)
                    context))
    (when @found
      (subagent-tool/answer-text @found))))

(defn report-text
  "What the bot reads when a run of its failed."
  [trigger line bot-id run-id]
  (str "A run of yours failed. It was started by " (trigger-line trigger) " and " line ". "
       "Its transcript is at " (url bot-id run-id) ". Tell the person in a sentence what did not happen; "
       "when the run shows a flaw in your definition, fix the definition."))

(defn report-content
  "What the bot reads when a run of its reports `text`."
  [run text bot-id]
  (str "A run of yours reports: " text
       (when run (str " Its transcript is at " (url bot-id run) "."))
       " Tell the person in a sentence or two; when something must be decided, ask them."))

(defn- ^:async report-failure!
  "The failed run reported into the bot's chat: an input the bot
   answers, once (its request id is the run's)."
  [runtime trigger line context]
  (let [bot-id (.-conversationId runtime)
        id (.-taskId runtime)
        bot (await (.conversation runtime bot-id context))]
    (await (.submit bot
                    #js {:type "input"
                         :content (report-text trigger line bot-id id)
                         :whenBusy "followUp"
                         :requestId (str "run-report:" id)
                         :entry #js {:kind report-kind
                                     :data #js {:run id :state "failed" :line line :about (trigger-line trigger)}}}
                    context))))

(defn- ^:async run!
  "The run's conversation asked the trigger's text (deduplicated by the
   run's id, so a rerun waits on the same submission), the answer's
   first line its result; a failure reported to the bot."
  [task runtime context]
  (let [id (.-taskId runtime)
        checkpoint (.. task -state -checkpoint)
        input (.-input task)
        trigger (js->clj (.-trigger input) :keywordize-keys true)
        conversation (await (.conversation runtime (.-conversation checkpoint) context))
        submission (await (.submit conversation
                                   #js {:type "input"
                                        :content (.-content input)
                                        :requestId (str "run:" id)
                                        :entry #js {:kind "rragent.run-input" :data (.-trigger input)}}
                                   context))
        settled (await (.wait submission context))
        done? (= "done" (.-status settled))
        line (if done?
               (first-line (await (answer-of runtime (.-answer settled) context)))
               (str "did not answer: " (.-reason settled)))
        result #js {:state (if done? "done" "failed")
                    :line line
                    :conversation (.-conversation checkpoint)
                    :started (.-started checkpoint)
                    :ended (.now runtime)}]
    (when-not done?
      (await (report-failure! runtime trigger line context)))
    ;; the gate goes before the record is terminal: whoever waits for the
    ;; task finds the machine free of this run
    (leave! id)
    (await (.commit runtime (fn [] (terminal #js {:status "completed" :result result})) context))
    (changed! (.-conversationId runtime))))

(def Task
  (defineTask
   #js {:name kind
        :version 1
        :initial (fn [_input] #js {:phase "run"})
        :phases
        #js {:run
             (^:async fn [task runtime context]
               (let [id (.-taskId runtime)]
                 (when (await (enter! id (.-signal runtime)))
                   (if (.-conversation (.. task -state -checkpoint))
                     (try
                       (await (run! task runtime context))
                       (finally
                         (leave! id)))
                     ;; the gate stays held between the two invocations
                     (try
                       (await (made! runtime context))
                       (catch :default e
                         (leave! id)
                         (throw e)))))))}
        :abort (fn [_task runtime context]
                 (leave! (.-taskId runtime))
                 (-> (.commit runtime (fn [] (terminal #js {:status "aborted"})) context)
                     (.then (fn [] (changed! (.-conversationId runtime))))))}))

(defn ^:async start!
  "A run of the bot's `conversation` for `trigger` (`{:kind :name
   :line}`) with `content`, the text the run's model gets: its task,
   made in a commit of the conversation. Answers the task's id."
  [conversation {:keys [trigger content]} context]
  (let [id (await (.commit conversation
                           (fn [tx]
                             (.createTask tx Task
                                          #js {:trigger (clj->js trigger) :content content}
                                          #js {:ownership #js {:kind "conversation"}
                                               :conversationId (.-id conversation)
                                               :background true}))
                           context))]
    (swap! live conj id)
    (changed! (.-id conversation))
    id))

(defn create-in
  "The same from inside a transaction `tx` of the bot's conversation
   (a schedule's task commits it with its own next step). Answers a
   promise of the task's id."
  [tx conversation-id {:keys [trigger content]}]
  (.then (.createTask tx Task
                      #js {:trigger (clj->js trigger) :content content}
                      #js {:ownership #js {:kind "conversation"}
                           :conversationId conversation-id
                           :background true})
         (fn [id]
           (swap! live conj id)
           (changed! conversation-id)
           id)))

(defn run-of
  "A task record as the run the page and the model read: `{:id
   :trigger :state :line :started :ended :conversation}`; the state
   `:queued` before the conversation is made, `:running` while the
   answer is waited for, then `:done`, `:failed` or `:aborted`."
  [record]
  (let [state (.-state record)
        checkpoint (.-checkpoint state)
        outcome (.-outcome state)
        result (some-> outcome .-result)]
    {:id (.-id record)
     :bot (.-conversationId record)
     :trigger (js->clj (.-trigger (.-input record)) :keywordize-keys true)
     :state (cond
              (some? outcome) (case (.-status outcome)
                                "completed" (keyword (or (some-> result .-state) "done"))
                                "aborted" :aborted
                                :failed)
              (some-> checkpoint .-conversation) :running
              :else :queued)
     :line (or (some-> result .-line) (when (and outcome (= "failed" (.-status outcome))) (some-> outcome .-error .-message)))
     :started (or (some-> result .-started) (some-> checkpoint .-started) (.-startedAt record))
     :ended (or (some-> result .-ended) (.-endedAt record))
     :conversation (or (some-> result .-conversation) (some-> checkpoint .-conversation))}))

(defn ^:async listed
  "The newest `n` runs of the bot whose conversation is `conversation-id`,
   through a commit of `harness` that changes nothing."
  [harness conversation-id n context]
  (let [page (await (.commit harness
                             (fn [tx] (.scanTasks tx #js {:conversationId conversation-id :kind kind :order "descending"} n))
                             context))]
    (mapv run-of (array-seq (.-items page)))))

(defn ^:async run
  "One run of the bot by its task `id`, nil for none."
  [harness id context]
  (when-let [record (await (.getTask harness id context))]
    (when (= kind (.-kind record))
      (run-of record))))
