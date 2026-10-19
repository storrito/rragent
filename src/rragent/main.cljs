(ns rragent.main
  "The entry of rragent, the program that runs one agent on one machine:
   `main` is what the bundle exports and runs. `rragent ask <text>` opens
   the agent of RRAGENT_HOME, asks it one thing and prints the answer;
   `rragent serve` serves its page; `rragent motion <dir>` and
   `rragent motion-render <dir> <out>` are the process and the render of
   a motion project, which the page starts (`rragent.motion.app`). Under
   the dev server (which runs the entry without a command) the page is
   served."
  (:require [clojure.string :as str]
            [rragent.assets :as assets]
            [rragent.harness :as harness]
            [rragent.motion.app :as motion]
            [rragent.page :as page]
            [rragent.version :as version]))

(enable-console-print!)

(defn- usage
  []
  (println (str "rragent " version/version))
  (println "  rragent ask <text>   asks the agent of RRAGENT_HOME (./agent) and prints the answer")
  (println "  rragent serve        serves the agent's page on PORT (8080), behind RRAGENT_TOKEN")
  (println "  rragent motion <dir>              serves a motion design on PORT, for the page")
  (println "  rragent motion-render <dir> <out> renders a motion design to the mp4"))

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
      "motion" (await (motion/serve-main (first args)))
      "motion-render" (await (motion/render-main (first args) (second args)))
      nil (if (assets/dev-origin)
            (await (page/serve))
            (usage))
      (usage))))

(-> (main)
    (.catch (fn [error]
              (js/console.error error)
              (set! (.-exitCode js/process) 1))))
