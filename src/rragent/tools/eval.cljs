(ns rragent.tools.eval
  "The `eval` tool: Clojure evaluated in the agent's own process, by
   SCI, with the host's JavaScript reachable as `js/...`. One context
   for the life of the process, so a `def` of one call is there for the
   next: the REPL half of the name. The result is printed, cut at ten
   thousand characters; an error is the tool's error. With `project`
   the code goes to that artifact's process instead
   (`rragent.projects/eval!`), live coding against the pane for a
   template that takes an eval."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.db :as db]
            [rragent.projects :as projects]
            [rragent.tools.work :as work]
            [sci.core :as sci]))

(def max-chars
  10000)

(defonce context
  (sci/init {:classes {'js js/globalThis :allow :all}
             :features #{:cljs}}))

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn evaluate
  "`code` evaluated in the shared context; the value printed."
  [code]
  (cut (pr-str (sci/eval-string* context code))))

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

(def tool
  (defineTool
   #js {:name "eval"
        :description (str "Evaluate Clojure in your own process (SCI, ClojureScript semantics, JavaScript as js/...). "
                          "What you def stays for the next call. Answers the printed value. "
                          "With `project`, the code is evaluated by that artifact project's process instead, where its template takes an eval "
                          "(a motion design: in the namespace of design.cljs, a redefined function shows on the page at once, until the file is saved).")
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})
                                       :project (.Optional Type (.String Type #js {:description "An artifact project's name: evaluate in its process"}))})
        :execute (^:async fn [args api _call-context]
                   (let [project (not-empty (.-project args))]
                     (work/text (if project
                                  (await (evaluate-in-project api project (.-code args)))
                                  (evaluate (.-code args))))))}))
