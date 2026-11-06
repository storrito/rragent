(ns rragent.tools.machine
  "The `machine` tool: the imperative shell of a machine
   (`rragent.machine`). A program, Clojure evaluated by SCI in a
   worker of the bot's own (`rragent.codemode-worker`), is loaded for
   its value, a state map; while the state names a call under `:next`,
   the shell runs it as a nested call of its own under the step's key
   (the tools of `rragent.tools.host`, `remember`), puts the answer
   under `:as`, and, when the program defined `step`, has the worker
   evaluate `(step state)` for the next state; `:done`, or a state
   without `:next`, ends it. A program with `step` whose initial
   state asks nothing starts with one step. The state after every step is kept as a
   memo of the call, `s<step>`, and the tool is replay-safe: a call
   the machine's restart interrupted runs again, loads the program
   again (pure), takes each state from its memo and each call from
   its key, and goes on where it was. Nothing runs twice. What the
   model reads is what the program printed and the final state. Stop
   ends the worker and the call in flight; so does the timeout."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.state-machine :as machine]
            [rragent.mcp :as mcp]
            [rragent.tools.work :as work]))

(def max-images
  "The pictures of one machine's MCP calls that reach the model."
  8)

(defn memo-name
  "Under which name the call keeps the state after `n` answered
   calls."
  [n]
  (str "s" n))

(defn key-of
  "The key of the nested call that runs the machine's call number `n`."
  [n]
  (str "c" n))

(defn output
  "The tool's result for `text` and the `images` of the machine's MCP
   calls, at most `max-images`; an error result when `error?`."
  [text images & [error?]]
  (cond-> #js {:output (clj->js (into [{:type "text" :text (str text)}]
                                      (map (fn [{:keys [data mime-type]}] {:type "image" :data data :mimeType mime-type}))
                                      (take max-images images)))}
    error? (doto (aset "isError" true))))

(defn outcome
  "A nested call's `result` as `rragent.machine/answered` takes it:
   the tool's structured output as data, the pictures of an MCP call
   taken out under `:images`; `{:ok false :error :status :body}` for
   a failed call."
  [^js result]
  (let [value (when (some? (.-structuredOutput result))
                (js->clj (.-structuredOutput result) :keywordize-keys true))]
    (if (.-isError result)
      {:ok false
       :error (or (and (map? value) (:error value))
                  (some->> (.-diagnostics result) array-seq (map #(.-message %)) seq (str/join "; "))
                  (when (string? value) value)
                  "failed")
       :status (when (map? value) (:status value))
       :body (when (map? value) (:body value))}
      {:ok true
       :value (if (and (map? value) (contains? value :images)) (dissoc value :images) value)
       :images (when (map? value) (vec (:images value)))})))

(defn- ^:async kept
  "The state after `n` answered calls as the call keeps it: `candidate`
   stored under its memo when there is none yet, else what was stored
   by an earlier attempt of this call."
  [api call-context n candidate]
  (reader/read-string (await (.memo api (memo-name n) (pr-str candidate) call-context))))

(defn ^:async run
  "`code` run as a machine in the `worker` of the bot: loaded for its
   state, then one call per step through `api` (`executeTool` under
   the step's key, `memo` for the state), `(step state)` in the worker
   after every answer. Answers the tool's result; a throw with the
   program's error and the state at that point."
  [worker codemode api call-context code]
  (let [signal (.-abortSignal call-context)
        mcp (when-let [registry (:mcp codemode)]
              (await (mcp/catalog registry)))
        loaded (await (codemode-worker/load! worker {:code code :signal signal :mcp mcp}))]
    (when (:error loaded)
      (throw (js/Error. (:error loaded))))
    ;; a program with step and an initial state that asks nothing: the
    ;; first step is the machine's start
    (let [started (if (and (:step? loaded) (nil? (:next (:state loaded))) (not (contains? (:state loaded) :done)))
                    (let [stepped (await (codemode-worker/step! worker {:state (:state loaded) :signal signal}))]
                      (when (:error stepped)
                        (throw (js/Error. (str (:error stepped) "\nState: " (pr-str (:state loaded))))))
                      stepped)
                    {:state (:state loaded) :printed ""})]
    (loop [n 0
           state (:state started)
           session nil
           printed [(:printed loaded) (:printed started)]
           images []
           last-as nil]
      (let [{:keys [state session]} (await (kept api call-context n {:state state :session session}))]
        (cond
          (machine/stuck? state last-as)
          (output (str "The call under " last-as " failed and the machine neither handled it nor asked for anything else.\n"
                       (machine/text printed state n))
                  images true)

          (machine/done? state)
          (output (machine/text printed state n) images)

          :else
          (let [next (try (machine/next-of state) (catch :default e (throw (js/Error. (str (ex-message e) "\nState: " (pr-str state))))))
                {:keys [tool args]} (try (machine/tool-call (:call next) session)
                                         (catch :default e (throw (js/Error. (str (ex-message e) "\nState: " (pr-str state))))))
                result (await (.executeTool api tool (clj->js args) call-context #js {:key (key-of n)}))
                answer (outcome result)
                answered (machine/answered state next answer)
                session (or (when (:ok answer) (machine/session-of (:call next) (:value answer))) session)
                stepped (if (:step? loaded)
                          (await (codemode-worker/step! worker {:state answered :signal signal}))
                          {:state answered :printed ""})]
            (when (:error stepped)
              (throw (js/Error. (str (:error stepped) "\nState: " (pr-str answered)))))
            (recur (inc n) (:state stepped) session (conj printed (:printed stepped)) (into images (:images answer)) (:as next)))))))))

(def description
  (str "Run a machine: a Clojure program (SCI) whose value is its state, a map. Nothing runs inside the program; a call is data. "
       "(storrito/list-social-accounts {}), (call \"github\" \"/user/repos\"), (composio/execute \"GMAIL_SEND_EMAIL\" {...}), "
       "(mcp.github/search_issues {...}), (remember \"posted\" {...}), (recall {:event \"posted\"}), (spit \"data/x.edn\" v), (slurp \"data/x.edn\") "
       "each answer a call, a vector led by a keyword. Put one under :next with the key its answer goes under: "
       "{:next {:call (storrito/list-social-accounts {}) :as :accounts}}. The host runs it and puts the answer under :as, "
       "a failed call {:error ...}. With (defn step [state] ...) defined, the host calls step with the state after every answer "
       "and runs the next :next, until a state without :next, or with :done (an initial state without :next is stepped first); "
       "one call per step, the state is data and is kept "
       "with every step, so a machine goes on where it was after a restart and no call is made twice. "
       "Reads of a call vector as if it were the answer give nil: the answer is in the state of the next step. "
       "Keep big data in files, (spit) and (slurp), and a big or binary HTTP body with :to-file in the call's opts; "
       "an answer over a megabyte fails the call. (help) and (doc f) are pure and answer {:help ...} and {:doc ...}: "
       "a program that is just (help) is a machine that runs nothing and shows them. "
       "A service of the person's that is not connected yet (a Google spreadsheet, Gmail, Drive, Slack, Notion, GitHub) is "
       "connected with (composio/connections [\"googlesheets\"]) under :next, by its slug at Composio: a sign-in link for the "
       "person, good for ten minutes, which you give them in the same answer in which they name the service. "
       "What you read is what the program printed and the final state, cut at 10000 characters. The program's defs are gone "
       "with the machine. There is no JavaScript, no file system and no environment in it: files are for read, write, edit and bash, "
       "public web pages for fetch. A machine runs at most " (codemode-worker/timeout-minutes) " minutes, the person's Stop ends it. "
       "What a service finishes later (a story or post being published, a render, an import) you do not wait for: once the "
       "service has taken it, answer with what it said and look at its status again when the person asks."))

(defn tool
  "The tool over the core `codemode`: a machine runs in the worker of
   the bot of the call (`rragent.codemode-worker/of`), its calls as
   nested calls of this one; replay-safe, see the namespace. The
   timeout ends the worker, as Stop does."
  [codemode]
  (defineTool
   #js {:name "machine"
        :description description
        :replay "safe"
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code: a program whose value is the state map"})})
        :execute (^:async fn [args api call-context]
                   (let [worker (codemode-worker/of codemode (await (work/bot-id api call-context)))
                         minutes (codemode-worker/timeout-minutes)
                         timer (js/setTimeout #(codemode-worker/stop! worker (codemode-worker/timeout-message minutes)) (* 60000 minutes))]
                     (try
                       (await (run worker codemode api call-context (.-code args)))
                       (finally
                         (js/clearTimeout timer)))))}))
