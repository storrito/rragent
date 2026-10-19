(ns rragent.tools.eval
  "The `eval` tool: Clojure evaluated in the agent's own process, by
   SCI, with the host's JavaScript reachable as `js/...`. One context
   for the life of the process, so a `def` of one call is there for the
   next: the REPL half of the name. The result is printed, cut at ten
   thousand characters; an error is the tool's error."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [sci.core :as sci]))

(def max-chars
  10000)

(defonce context
  (sci/init {:classes {'js js/globalThis :allow :all}
             :features #{:cljs}}))

(defn evaluate
  "`code` evaluated in the shared context; the value printed."
  [code]
  (let [value (sci/eval-string* context code)
        printed (pr-str value)]
    (if (< max-chars (count printed))
      (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
      printed)))

(def tool
  (defineTool
   #js {:name "eval"
        :description (str "Evaluate Clojure in your own process (SCI, ClojureScript semantics, JavaScript as js/...). "
                          "What you def stays for the next call. Answers the printed value.")
        :parameters (.Object Type #js {:code (.String Type #js {:description "Clojure code"})})
        :execute (fn [args _api _call-context]
                   #js {:output #js [#js {:type "text" :text (evaluate (.-code args))}]})}))
