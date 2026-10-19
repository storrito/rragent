(ns rragent.tools.eval
  "The `eval` tool: the agent's REPL, a Clojure program run by SCI in
   the agent's own process with the connectors of the gateway as
   functions (`rragent.codemode`). One context for the life of the
   process, so a `def` of one call is there for the next: the REPL
   half of the name. The answer is what the program printed and its
   value, cut at ten thousand characters; an error is the tool's
   error. The facts of the conversation are read before the program
   runs, so that it recalls them; what it remembers is committed
   after it. With `project` the code goes to that artifact's process
   instead (`rragent.projects/eval!`), live coding against the pane
   for a template that takes an eval."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.codemode :as codemode]
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
  "`code` run in the REPL `codemode` for the tool's conversation: the
   facts read first, what the program remembered committed after it,
   in order, also when it failed after remembering."
  [codemode api call-context code]
  (codemode/begin! codemode (await (facts/facts (facts/from-tool api call-context))))
  (let [answer (try
                 {:text (codemode/evaluate codemode code)}
                 (catch :default e
                   {:error e}))]
    (loop [[fact & more] (codemode/pending codemode)]
      (when fact
        (await (facts/remember! api (:event fact) (dissoc fact :event :at) (:at fact) call-context))
        (recur more)))
    (if-let [error (:error answer)]
      (throw error)
      (:text answer))))

(defn ^:async evaluate-in-project
  "`code` evaluated by the project's process; its answer, or a throw
   with the error, or with the word that the template takes no eval."
  [api project code]
  (let [handle (projects/handle {:db db/db :dir (work/artifacts-dir api)})
        {:keys [status text]} (await (projects/eval! handle project code))]
    (cond
      (= 200 status) (cut text)
      (#{404 405} status) (throw (js/Error. (str "artifacts/" project " takes no eval: its template has no eval endpoint.")))
      :else (throw (js/Error. text)))))

(def description
  (str "Your REPL: run a Clojure program (SCI) in your own process. Start with (help): it lists the functions that reach "
       "the organization's connectors through the Sprites gateway, (connectors), (call provider path opts), composio/search, "
       "composio/execute, storrito/<procedure>, remember and recall, and (doc f) explains any of them. "
       "Chain calls, loop and filter in one program and print only what matters: the answer is what you printed and the "
       "value of the last form, cut at 10000 characters. What you def stays for the next call. "
       "There is no JavaScript, no file system and no environment in it: files are for read, write, edit and bash, public web pages for fetch. "
       "With `project`, the code is evaluated by that artifact project's process instead, where its template takes an eval "
       "(a motion design: in the namespace of design.cljs, a redefined function shows on the page at once, until the file is saved)."))

(defn tool
  "The tool over the REPL `codemode`."
  [codemode]
  (defineTool
   #js {:name "eval"
        :description description
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})
                                       :project (.Optional Type (.String Type #js {:description "An artifact project's name: evaluate in its process"}))})
        :execute (^:async fn [args api call-context]
                   (let [project (not-empty (.-project args))]
                     (work/text (if project
                                  (await (evaluate-in-project api project (.-code args)))
                                  (await (evaluate codemode api call-context (.-code args)))))))}))
