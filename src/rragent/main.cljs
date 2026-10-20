(ns rragent.main
  "The entry of rragent, the program that runs one agent on one machine:
   `main` is what the bundle exports and runs. `rragent ask <text>` opens
   the agent of RRAGENT_HOME, asks it one thing and prints the answer;
   `rragent serve` serves its page. Under the dev server (which runs
   the entry without a command) the page is served. The templates'
   processes are programs of their own (`rragent.templates`). The
   bundle is also what the REPL's worker thread runs
   (`rragent.codemode-worker`): there the worker's loop runs and `main`
   does not."
  (:require [clojure.string :as str]
            [rragent.assets :as assets]
            [rragent.codemode-worker :as codemode-worker]
            [rragent.harness :as harness]
            [rragent.page :as page]
            [rragent.version :as version]))

(enable-console-print!)

(defn- usage
  []
  (println (str "rragent " version/version))
  (println "  rragent ask <text>   asks the agent of RRAGENT_HOME (./agent) and prints the answer")
  (println "  rragent serve        serves the agent's page on PORT (8080), behind the sprite's token label or RRAGENT_TOKEN"))

(defn ^:async ask
  [text]
  (let [agent (await (harness/open {}))
        answer (await (harness/ask agent text))]
    (if (string? answer)
      (println answer)
      (println "unanswered:" (:unanswered answer)))
    (await (harness/close agent))))

(defn ^:async main
  []
  (let [[command & args] (drop 2 (array-seq (.-argv js/process)))]
    (case command
      "ask" (await (ask (str/join " " args)))
      "serve" (await (page/serve))
      nil (if (assets/dev-origin)
            (await (page/serve))
            (usage))
      (usage))))

(when-not (codemode-worker/worker-thread?)
  (-> (main)
      (.catch (fn [error]
                (js/console.error error)
                (set! (.-exitCode js/process) 1)))))
