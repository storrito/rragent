(ns rragent.tools.eval
  "The `eval` tool: the agent's REPL, a Clojure program run by SCI
   with the connectors of the gateway as functions
   (`rragent.codemode`), in a worker thread of the bot's own
   (`rragent.codemode-worker`), so that a call that blocks stalls
   nothing else. One context for the life of the worker, so a `def` of
   one call is there for the next: the REPL half of the name. The
   answer is what the program printed and its value, cut at ten
   thousand characters; an error is the tool's error. The facts of the
   conversation are read before the program runs, so that it recalls
   them; what it remembers is committed after it. Stop ends the
   program with its worker, so does the timeout; the next call starts
   a fresh worker and says that the defs are gone. With `project` the
   code goes to that artifact's process instead
   (`rragent.projects/eval!`), live coding against the pane for a
   template that takes an eval."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.codemode :as codemode]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.db :as db]
            [rragent.facts :as facts]
            [rragent.projects :as projects]
            [rragent.tools.work :as work]))

(def max-chars
  codemode/max-chars)

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn ^:async evaluate
  "`code` run in the `worker` of the REPL for the tool's conversation:
   the facts read first, what the program remembered committed after
   it, in order, also when it failed after remembering; a program the
   call's abort (Stop) or the timeout ended remembered nothing."
  [worker api call-context code]
  (let [facts (await (facts/facts (facts/from-tool api call-context)))
        {:keys [text error pending]} (await (codemode-worker/evaluate! worker {:code code
                                                                          :facts facts
                                                                          :env (codemode-worker/environment)
                                                                          :signal (.-abortSignal call-context)}))]
    (loop [[fact & more] pending]
      (when fact
        (await (facts/remember! api (:event fact) (dissoc fact :event :at) (:at fact) call-context))
        (recur more)))
    (if error
      (throw (js/Error. error))
      text)))

(defn ^:async evaluate-in-project
  "`code` evaluated by the project's process; its answer, or a throw
   with the error, or with the word that the template takes no eval."
  [api call-context project code]
  (let [handle (projects/handle {:db db/db :path (await (work/bot-path api call-context)) :dir (work/artifacts-dir api)})
        {:keys [status text]} (await (projects/eval! handle project code))]
    (cond
      (= 200 status) (cut text)
      (#{404 405} status) (throw (js/Error. (str "artifacts/" project " takes no eval: its template has no eval endpoint.")))
      :else (throw (js/Error. text)))))

(def description
  (str "Your REPL: run a Clojure program (SCI) in your own process. Start with (help): it lists the functions that reach "
       "the organization's connectors through the Sprites gateway, (connectors), (call provider path opts), composio/search, "
       "composio/execute, storrito/<procedure>, remember and recall, and (doc f) explains any of them. "
       "A service of the person's that is not connected yet (a Google spreadsheet, Gmail, Drive, Slack, Notion, GitHub) is connected with "
       "(composio/connections [\"googlesheets\"]), by its slug at Composio: a sign-in link for the person, good for ten minutes, which you "
       "give them in the same answer in which they name the service, before you ask anything else about it. "
       "Chain calls, loop and filter in one program and print only what matters: the answer is what you printed and the "
       "value of the last form, cut at 10000 characters. What you def stays for the next call, unless a program was stopped "
       "(the person's Stop, or the timeout of " (codemode-worker/timeout-minutes) " minutes): the next call then starts over and says so. "
       "There is no JavaScript, no file system and no environment in it: files are for read, write, edit and bash, public web pages for fetch. "
       "What a service finishes later (a story or post being published, a render, an import) you do not wait for: once the service "
       "has taken it, answer with what it said, a preview link included, and look at its status again when the person asks; "
       "no sleep in between, the person waits for every second of your turn. "
       "With `project`, the code is evaluated by that artifact project's process instead, where its template takes an eval "
       "(a motion design: in the namespace of design.cljs, a redefined function shows on the page at once, until the file is saved)."))

(defn tool
  "The tool over the REPL `codemode`: a program runs in the worker of
   the bot of the call (`rragent.codemode-worker/of`)."
  [codemode]
  (defineTool
   #js {:name "eval"
        :description description
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})
                                       :project (.Optional Type (.String Type #js {:description "An artifact project's name: evaluate in its process"}))})
        :execute (^:async fn [args api call-context]
                   (let [project (not-empty (.-project args))]
                     (work/text (if project
                                  (await (evaluate-in-project api call-context project (.-code args)))
                                  (await (evaluate (codemode-worker/of codemode (await (work/bot-id api call-context)))
                                                   api call-context (.-code args)))))))}))
