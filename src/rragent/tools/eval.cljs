(ns rragent.tools.eval
  "The `eval` tool: the agent's REPL, a Clojure program run by SCI
   with the connectors of the gateway as functions
   (`rragent.codemode`), in a worker thread of the bot's own
   (`rragent.codemode-worker`). A program is one of its own: it starts
   from a fresh context, and data for a later program goes into a
   file of the bot's work folder, `spit` and `slurp` in the program.
   The answer is what the program printed and its value, cut at ten
   thousand characters; an error is the tool's error.

   The program is a sequence of forms, run one at a time, and nothing
   of it reaches outside the worker: a call of a form is a need
   (`rragent.need`), which this tool answers as a nested call of its
   own under the key of the form and the call (`c<form>-<call>`,
   the tools of `rragent.tools.host`, `remember` for an event), and
   the form runs again with the answer, until every call of the form
   is answered. A form that is done is kept as the call's memo
   (`f<form>`): what it printed, its value, and the vars it defined
   as EDN, or that it is to be run again when a var cannot be kept.
   The tool is replay-safe: a call the machine's restart interrupted
   runs again, finds the forms that were done in its memos, gives
   their vars back to the new worker, and runs the rest, where the
   calls that were made come back by their keys. Nothing changes
   anything twice; a form that was in the middle of its calls walks
   them from the keys and goes on. The events of the bot are read
   when a form starts, so that every attempt of it recalls the same.
   Stop ends the program with its worker, so does the timeout; the
   next call starts a fresh worker."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.events :as events]
            [rragent.mcp :as mcp]
            [rragent.tools.events :as events-tool]
            [rragent.tools.work :as work]))

(def max-images
  "The pictures of one program's MCP calls that reach the model."
  8)

(def max-chars
  10000)

(defn memo-name
  "Under which name the call keeps the form at `index` once it is done."
  [index]
  (str "f" index))

(defn key-of
  "The key of the nested call that answers the call at `call` of the
   form at `index`."
  [index call]
  (str "c" index "-" call))

(defn output
  "The tool's result for the program's `text` and the `images` its MCP
   calls answered: the text, then the pictures, at most `max-images`."
  [text images]
  #js {:output (clj->js (into [{:type "text" :text (str text)}]
                              (map (fn [{:keys [data mime-type]}] {:type "image" :data data :mimeType mime-type}))
                              (take max-images images)))})

(defn- cut
  [s]
  (if (< max-chars (count s))
    (str (subs s 0 max-chars) " … (cut at " max-chars " characters)")
    s))

(defn text
  "The program's answer from its forms: what each printed, then `=> `
   and the value of the last, as the REPL shows it."
  [forms]
  (let [printed (apply str (map :printed forms))
        value (:value (last forms))]
    (cut (str printed
              (when (and (seq printed) (not (str/ends-with? printed "\n"))) "\n")
              "=> " value))))

(defn outcome
  "A nested call's `result` as an answer of `rragent.need`: `{:ok true
   :value}` with the tool's structured output as data, the pictures of
   an MCP call taken out (`:images`), or `{:ok false :error}` with the
   value's `:error`, else the diagnostics, else the value when it is
   text."
  [request ^js result]
  (let [value (when (some? (.-structuredOutput result))
                (js->clj (.-structuredOutput result) :keywordize-keys true))]
    (if (.-isError result)
      {:request request
       :ok false
       :error (or (and (map? value) (:error value))
                  (some->> (.-diagnostics result) array-seq (map #(.-message %)) seq (str/join "; "))
                  (when (string? value) value)
                  "failed")}
      {:request request
       :ok true
       :value (if (map? value) (dissoc value :images) value)
       :images (when (map? value) (vec (:images value)))})))

(defn- ^:async answer!
  "`need` of the form at `index` answered: the nested call under its
   key, its outcome."
  [api call-context index {:keys [tool args] :as request}]
  (let [result (await (.executeTool api tool (clj->js args) call-context #js {:key (key-of index (:index request))}))]
    (outcome (dissoc request :index) result)))

(defn- ^:async form!
  "The form at `index` run until it is done: every need answered, the
   form sent again with the answer, the events of the bot as they were
   when the form started with every attempt; `{:printed :value
   :vars|:redo}` and the pictures its calls answered; a throw with the
   form's error."
  [worker api call-context {:keys [code file mcp]} index]
  (let [events (events/read file)]
    (loop [answers []
           images []]
      (let [{:keys [need done error]} (await (codemode-worker/attempt! worker {:code code
                                                                               :index index
                                                                               :answers answers
                                                                               :events events
                                                                               :signal (.-abortSignal call-context)
                                                                               :mcp mcp}))]
        (cond
          error (throw (js/Error. error))
          need (let [answer (await (answer! api call-context index need))]
                 (recur (conj answers answer) (into images (:images answer))))
          :else {:form done :images images})))))

(defn ^:async evaluate
  "`code` run in the `worker` of the REPL for the tool's conversation,
   `file` the bot's events file: form by form, each from its memo
   when it was done by an earlier attempt of this call, its vars given
   to the worker, else run until done (`form!`) and kept. `api` is
   the call's (`memo`, `executeTool`). Answers the tool's result: the
   text of the forms, and the pictures of their MCP calls (`output`)."
  [worker codemode api call-context {:keys [code file]}]
  (let [mcp (when-let [registry (:mcp codemode)]
              (await (mcp/catalog registry)))
        program {:code code :file file :mcp mcp}
        counted (await (codemode-worker/count! worker code (.-abortSignal call-context)))
        _ (when (:error counted)
            (throw (js/Error. (:error counted))))
        count (:count counted)]
    (loop [index 0
           forms []
           images []]
      (if (= index count)
        (output (text forms) images)
        (let [kept (await (.memo api (memo-name index) call-context))
              done (when (some? kept) (reader/read-string kept))]
          (if (and done (not (:redo done)))
            (do (await (codemode-worker/restore! worker (:vars done) (.-abortSignal call-context)))
                (recur (inc index) (conj forms done) images))
            (let [{:keys [form] found :images} (await (form! worker api call-context program index))
                  stored (await (.memo api (memo-name index) (pr-str form) call-context))]
              (recur (inc index) (conj forms (reader/read-string stored)) (into images found)))))))))

(def description
  (str "Your REPL: run a Clojure program (SCI) in your own process. Start with (help): it lists the functions that reach "
       "the organization's connectors through the Sprites gateway, (connectors), (call provider path opts), composio/search, "
       "composio/execute, storrito/<procedure>, the tools of the machine's MCP servers as mcp.<server>/<tool> (mcp/servers lists them), "
       "remember and recall, and (doc f) explains any of them. "
       "A service of the person's that is not connected yet (a Google spreadsheet, Gmail, Drive, Slack, Notion, GitHub) is connected with "
       "(composio/connections [\"googlesheets\"]), by its slug at Composio: a sign-in link for the person, good for ten minutes, which you "
       "give them in the same answer in which they name the service, before you ask anything else about it. "
       "Chain calls, loop and filter in one program and print only what matters: the answer is what you printed and the "
       "value of the last form, cut at 10000 characters. Every call is made by the host, once, also when the machine restarts "
       "in the middle of the program: a form runs again from its start until its calls are answered, so keep what a form "
       "works out in defs and let it ask the same calls in the same order; a failed call throws where it is, which you may catch. "
       "A program is one of its own: what it defs is gone with it. Data for a later program goes into a file of your work "
       "folder, (spit \"data/accounts.edn\" accounts), and comes back with (edn/read-string (slurp \"data/accounts.edn\")): "
       "a file you named is in your conversation and in the pane, where a def is not. A program runs at most "
       (codemode-worker/timeout-minutes) " minutes, the person's Stop ends it. "
       "There is no JavaScript, no file system and no environment in it: files are for read, write, edit and bash, public web pages for fetch. "
       "What a service finishes later (a story or post being published, a render, an import) you do not wait for: once the service "
       "has taken it, answer with what it said, a preview link included, and look at its status again when the person asks; "
       "no sleep in between, the person waits for every second of your turn."))

(defn tool
  "The tool over the REPL `codemode`: a program runs in the worker of
   the bot of the call (`rragent.codemode-worker/of`), its calls as
   nested calls of this one; replay-safe, see the namespace. The
   timeout ends the worker, as Stop does."
  [codemode]
  (defineTool
   #js {:name "eval"
        :description description
        :replay "safe"
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})})
        :execute (^:async fn [args api call-context]
                   (let [worker (codemode-worker/of codemode (await (work/bot-id api call-context)))
                         minutes (codemode-worker/timeout-minutes)
                         timer (js/setTimeout #(codemode-worker/stop! worker (codemode-worker/timeout-message minutes)) (* 60000 minutes))]
                     (try
                       (await (evaluate worker codemode api call-context {:code (.-code args)
                                                                           :file (await (events-tool/file api call-context))}))
                       (finally
                         (js/clearTimeout timer)))))}))
