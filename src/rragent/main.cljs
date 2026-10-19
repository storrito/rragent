(ns rragent.main
  "The entry of rragent, the program that runs one agent on one machine:
   `main` is what the bundle exports and runs. The pieces come one by one
   (`rragent.harness`, the page, the tools); this is the door."
  (:require [rragent.version :as version]))

(enable-console-print!)

(defn ^:async main
  "Starts rragent. For now: says which version this is."
  []
  (println (str "rragent " version/version)))

(-> (main)
    (.catch (fn [error]
              (js/console.error error)
              (set! (.-exitCode js/process) 1))))
