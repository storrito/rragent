(ns rragent.tools.eval
  "The `eval` tool: the agent's REPL, a Clojure program run by SCI
   with the connectors of the gateway as functions
   (`rragent.codemode`), in a worker thread of the bot's own
   (`rragent.codemode-worker`), so that a call that blocks stalls
   nothing else. One context for the life of the worker, so a `def` of
   one call is there for the next: the REPL half of the name. The
   answer is what the program printed and its value, cut at ten
   thousand characters; an error is the tool's error. The events of the
   bot are read before the program runs, so that it recalls them.

   A program reads; what changes something is an action it answers
   (`rragent.plan`): a command of the Storrito API answers
   `[:storrito/<procedure> args]` instead of calling, and a vector of
   actions as the program's value is a plan. The tool runs the plan
   after the program, one action after the other, each as a nested
   call of its own under the action's key, the `storrito` tool for a
   command (`rragent.tools.storrito`), the `remember` tool for an
   event, what the program remembered as it ran first. The tool is
   replay-safe: the program's answer is kept as the call's memo the
   moment it is there, and a call the machine's restart interrupted
   runs again from the memo, not the program, and finds by their keys
   the actions that were done. So nothing runs twice, and the
   program, which only reads, is the one thing that may. Stop ends
   the program with its worker, so does the timeout; the next call
   starts a fresh worker and says that the defs are gone."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.events :as events]
            [rragent.plan :as plan]
            [rragent.tools.events :as events-tool]
            [rragent.tools.work :as work]))

(def max-images
  "The pictures of one program's MCP calls that reach the model."
  8)

(def memo-name
  "Under which name the call keeps the program's answer."
  "program")

(defn output
  "The tool's result for the program's `text` and the `images` its MCP
   calls answered: the text, then the pictures, at most `max-images`;
   an error result when `error?`, a plan that failed."
  [text images & [error?]]
  (cond-> #js {:output (clj->js (into [{:type "text" :text (str text)}]
                                      (map (fn [{:keys [data mime-type]}] {:type "image" :data data :mimeType mime-type}))
                                      (take max-images images)))}
    error? (doto (aset "isError" true))))

(defn- progress!
  "A line of progress of an MCP call as the running output of the call,
   for the page while the program runs."
  [api line]
  (try
    (.output api (str line "\n"))
    (catch :default _ nil)))

(defn ^:async answer!
  "The program's answer: `{:text :plan :pending :images}`. The first
   time the call runs it, in `worker`, the events of the bot read
   first, and keeps `:text`, `:plan` and `:pending` as the call's memo
   (`memo-name`) before anything of the plan runs; a rerun of the
   call takes the memo and does not run the program again, without
   the pictures. A program that failed is the tool's error, and keeps
   nothing: a rerun runs it again."
  [worker api call-context code]
  (let [kept (await (.memo api memo-name call-context))]
    (if (some? kept)
      (assoc (reader/read-string kept) :images [])
      (let [file (await (events-tool/file api call-context))
            {:keys [text error plan pending images]} (await (codemode-worker/evaluate! worker {:code code
                                                                                          :events (events/read file)
                                                                                          :env (codemode-worker/environment)
                                                                                          :signal (.-abortSignal call-context)
                                                                                          :on-progress (partial progress! api)}))]
        (when error
          (throw (js/Error. error)))
        (let [stored (await (.memo api memo-name (pr-str {:text text :plan plan :pending (vec pending)}) call-context))]
          (assoc (reader/read-string stored) :images (vec images)))))))

(defn outcome
  "A nested call's result as `rragent.plan/perform!` takes it: the value
   is the tool's structured output as data; an error's text the
   value's `:error`, else its diagnostics, else the value itself when
   it is text."
  [^js result]
  (let [value (when (some? (.-structuredOutput result))
                (js->clj (.-structuredOutput result) :keywordize-keys true))]
    (if (.-isError result)
      {:ok false
       :error (or (and (map? value) (:error value))
                  (some->> (.-diagnostics result) array-seq (map #(.-message %)) seq (str/join "; "))
                  (when (string? value) value)
                  "failed")}
      {:ok true :value value})))

(defn remembered
  "What the program remembered as actions, `[:remember event data]`
   each, oldest first."
  [pending]
  (mapv (fn [{:keys [event] :as noted}] [:remember event (dissoc noted :event :at)]) pending))

(defn ^:async evaluate
  "`code` run in the `worker` of the REPL for the tool's conversation
   (`answer!`), then its actions: what it remembered, then its plan,
   each a nested call under its key (`rragent.plan/perform!`); a plan an
   action of which is wrong runs not at all, and the answer says what
   is wrong. Answers the tool's result: the program's text, the lines
   of what each action did when there was a plan or an action failed,
   and the pictures of its MCP calls (`output`), an error result when
   an action failed."
  [worker codemode api call-context code]
  (let [{:keys [text plan pending images]} (await (answer! worker api call-context code))
        actions (into (remembered pending) plan)
        problems (plan/check (codemode/known codemode) actions)]
    (when (seq problems)
      (throw (js/Error. (str text "\nNothing of the plan ran, it is not right:\n" (str/join "\n" problems)))))
    (let [outcomes (await (plan/perform! actions {:execute! (fn [key tool args]
                                                            (-> (.executeTool api tool (clj->js args) call-context #js {:key key})
                                                                (.then outcome)))
                                               :aborted? #(some-> (.-abortSignal call-context) .-aborted)}))
          failed? (plan/failed? outcomes)]
      (output (str text (when (or (seq plan) failed?) (str "\n" (plan/report outcomes))))
              images
              failed?))))

(def description
  (str "Your REPL: run a Clojure program (SCI) in your own process. Start with (help): it lists the functions that reach "
       "the organization's connectors through the Sprites gateway, (connectors), (call provider path opts), composio/search, "
       "composio/execute, storrito/<procedure>, the tools of the machine's MCP servers as mcp.<server>/<tool> (mcp/servers lists them), "
       "remember and recall, and (doc f) explains any of them. "
       "A program reads and decides; what changes something is an action it answers: a command of the organization's Storrito API, "
       "(storrito/schedule-social-post {...}), answers the action [:storrito/schedule-social-post {...}] instead of calling, and a vector "
       "of actions as the program's value is a plan, which runs after the program, in order, each action once, also when the machine "
       "restarts in the middle; the answer lists what each did, and the first failure ends the plan. [:plan/result 0 :socialPostUuid] "
       "in an action's arguments stands for that part of the result of the action at that index, from 0. A query of the API "
       "(list-…, status-…, get-…) is called and answers at once, so a program lists, filters and answers the actions for what is left. "
       "A service of the person's that is not connected yet (a Google spreadsheet, Gmail, Drive, Slack, Notion, GitHub) is connected with "
       "(composio/connections [\"googlesheets\"]), by its slug at Composio: a sign-in link for the person, good for ten minutes, which you "
       "give them in the same answer in which they name the service, before you ask anything else about it. "
       "Chain calls, loop and filter in one program and print only what matters: the answer is what you printed and the "
       "value of the last form, cut at 10000 characters. What you def stays for the next call, unless a program was stopped "
       "(the person's Stop, or the timeout of " (codemode-worker/timeout-minutes) " minutes): the next call then starts over and says so. "
       "There is no JavaScript, no file system and no environment in it: files are for read, write, edit and bash, public web pages for fetch. "
       "What a service finishes later (a story or post being published, a render, an import) you do not wait for: once the service "
       "has taken it, answer with what it said, a preview link included, and look at its status again when the person asks; "
       "no sleep in between, the person waits for every second of your turn."))

(defn tool
  "The tool over the REPL `codemode`: a program runs in the worker of
   the bot of the call (`rragent.codemode-worker/of`), its actions as
   nested calls of this one; replay-safe, see the namespace."
  [codemode]
  (defineTool
   #js {:name "eval"
        :description description
        :replay "safe"
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})})
        :execute (^:async fn [args api call-context]
                   (await (evaluate (codemode-worker/of codemode (await (work/bot-id api call-context)))
                                    codemode api call-context (.-code args))))}))
