(ns rragent.compaction
  "The compaction of a bot (docs/bots.md §5): before the model's
   window shrinks, the bot writes what must survive into the wiki of
   the machine (`rragent.wiki`). A background task of the bot's
   conversation (`Task`, kind `rragent.compaction`) submits one input
   that says so (`instruction`, the entry `rragent.compaction-notice`),
   waits for the bot's turn, then asks the harness for the compaction
   itself (`conversation.compact`, with `focus` on top of the
   pictures' files, `pictures-focus`, which every compaction rragent
   starts asks for); pi-durable summarizes while the bot goes on and
   places the summary at the next boundary. One at a time: `start!`
   makes no task while one runs or a compaction is under way. The
   budget hook, the away watch and the page's button all come here
   for a bot (`rragent.harness/compact-unless-compacting!`, by `bot?`);
   another conversation, a run's, a task's, a subagent's, is compacted
   at once: nobody talks to it, and what it learned goes back in its
   answer."
  (:require ["@earendil-works/pi-durable" :refer [LiveDoc defineTask]]
            [rragent.away :as away]))

(def kind
  "rragent.compaction")

(def notice-kind
  "The entry of the notice in the bot's chat."
  "rragent.compaction-notice")

(defonce harness
  (atom nil))

(defn ^:async bot?
  "Whether the conversation `id` of `harness` is a bot's: nobody owns
   it and it is no fork (`rragent.machine/conversations` lists the
   bots the same way). Read from the storage, so that a compaction the
   harness admits right after a restart, before the machine has its
   bots open, finds the same answer."
  [harness id context]
  (let [record (await (.commit harness (fn [tx] (.conversation tx id)) context))]
    (boolean (and record (not (.-owner record)) (not (.-parent record))))))

(def pictures-focus
  "What a compaction rragent starts asks its summary for besides
   pi-durable's format: the files of the pictures the agent looked at,
   which the model gets whole until the compaction
   (`rragent.observations`) and not after it, so that the agent can
   read them again."
  (str "Under Critical Context, list the files of the pictures looked at in the summarized part "
       "(read and tool results) that the work may still need, each by its exact path with a few words "
       "on what it shows, so they can be read again; leave out pictures no longer relevant."))

(def focus
  "What a bot's summary is asked for on top: the wiki holds the
   detail, the summary points at it."
  (str "The wiki of this machine holds the state of the matters worked on here, the decisions and the people: point at its pages "
       "(topics/<name>.md, decisions.md, people.md, index.md) instead of repeating what they say, and name what still waits for the person, "
       "an open question or an approval."))

(defn instruction
  "What a bot reads before it is compacted."
  [reason]
  (str "Compaction coming (" reason "): the older part of this conversation is about to be replaced by a summary. "
       "Before anything else, write into the wiki everything that must survive and is not there yet: the page of each matter "
       "you work on with its state, decisions, approvals, open questions and next step; what you learned that another bot or a later you "
       "should find; people.md and preferences.md when something of them changed; the index and the log; commit your pages. "
       "Then answer \"ready\" and nothing else."))

(defn- terminal
  [outcome]
  #js {:status "terminal" :outcome outcome})

(defn- ^:async ask!
  "The notice submitted into the bot's chat, once (its request id is
   the task's), and waited for; the checkpoint moves to the
   compaction."
  [task runtime context]
  (let [id (.-taskId runtime)
        reason (str (.-reason (.-input task)))
        conversation (await (.conversation runtime (.-conversationId runtime) context))
        submission (await (.submit conversation
                                   #js {:type "input"
                                        :content (or (not-empty (str (.-instruction (.-input task)))) (instruction reason))
                                        :whenBusy "followUp"
                                        :requestId (str "compaction:" id)
                                        :entry #js {:kind notice-kind :data #js {:reason reason}}}
                                   context))
        ^js settled (await (.wait submission context))]
    (await (.commit runtime
                    (fn [] #js {:status "running"
                                :checkpoint #js {:phase "compact" :answered (= "done" (.-status settled))}})
                    context))))

(defn- ^:async compact!
  "The compaction itself, through the harness, unless one runs; the
   task ends."
  [runtime context]
  (let [bot-id (.-conversationId runtime)
        compacted (atom false)]
    (when-let [h @harness]
      (let [live (await (.snapshot h LiveDoc bot-id context))]
        (when (empty? (some-> live .-compactions array-seq))
          (when-let [conversation (await (.conversation h bot-id context))]
            (await (.compact conversation (str pictures-focus " " focus) context))
            (reset! compacted true)))))
    ;; the bot's answer to the notice is no reason for another compaction
    (away/forget! bot-id)
    (await (.commit runtime
                    (fn [] (terminal #js {:status "completed" :result #js {:compacted @compacted}}))
                    context))))

(def Task
  (defineTask
   #js {:name kind
        :version 1
        :initial (fn [_input] #js {:phase "ask"})
        :phases #js {:ask (fn [task runtime context] (ask! task runtime context))
                     :compact (fn [_task runtime context] (compact! runtime context))}
        :abort (fn [_task runtime context]
                 (.commit runtime (fn [] (terminal #js {:status "aborted"})) context))}))

(defn ^:async running?
  "Whether a compaction task of the bot `bot-id` is under way."
  [harness bot-id context]
  (let [page (await (.commit harness
                             (fn [tx] (.scanTasks tx #js {:conversationId bot-id :kind kind :order "descending"} 3))
                             context))]
    (boolean (some #(nil? (some-> (.-state %) .-outcome)) (array-seq (.-items page))))))

(defonce starting
  (atom #{}))

(defn ^:async start!
  "The compaction of the bot `bot-id` begun for `reason` (`budget`,
   `away`, `asked`): the task made in a commit of the bot's
   conversation, unless one runs or a compaction is under way, or a
   start of one is (`starting`: the check and the commit are two
   steps, and two callers in one tick, the away watch and the page's
   button, would make two tasks); with an `:instruction` the notice
   says that instead of the usual. Answers the task's id, nil when
   nothing was started."
  [harness bot-id reason context & [{:keys [instruction]}]]
  (when-not (contains? @starting bot-id)
    (swap! starting conj bot-id)
    (try
      (let [live (await (.snapshot harness LiveDoc bot-id context))]
        (when (and (empty? (some-> live .-compactions array-seq))
                   (not (await (running? harness bot-id context))))
          (when-let [conversation (await (.conversation harness bot-id context))]
            (await (.commit conversation
                            (fn [tx]
                              (.createTask tx Task
                                           (let [input #js {:reason (str reason)}]
                                             (when (seq (str instruction))
                                               (aset input "instruction" (str instruction)))
                                             input)
                                           #js {:ownership #js {:kind "conversation"}
                                                :conversationId bot-id
                                                :background true}))
                            context)))))
      (finally
        (swap! starting disj bot-id)))))
