(ns rragent.motion.client
  "The browser bundle of a motion project's page: the rframes runtime,
   which opens the frame stream of the page it is on and renders every
   frame with Replicant, nothing of its own. Built by the template's
   vite.client.config.mjs into its dist/public, served by the project's
   process (`rragent.motion.app`)."
  (:require [rframes.client :as rframes]))

(defn init!
  []
  (rframes/init! {:register []}))

(init!)
