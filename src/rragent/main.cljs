(ns rragent.main
  "The entry of rragent, the program that runs one agent on one machine:
   `main` is what the bundle exports and runs. `rragent ask <text>` opens
   the agent of RRAGENT_HOME, asks it one thing and prints the answer;
   the page that serves it comes next."
  (:require [clojure.string :as str]
            [rragent.harness :as harness]
            [rragent.version :as version]))

(enable-console-print!)

(defn- usage
  []
  (println (str "rragent " version/version))
  (println "  rragent ask <text>   asks the agent of RRAGENT_HOME (./agent) and prints the answer"))

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
      (usage))))

(-> (main)
    (.catch (fn [error]
              (js/console.error error)
              (set! (.-exitCode js/process) 1))))
