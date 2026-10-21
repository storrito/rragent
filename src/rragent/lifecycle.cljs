(ns rragent.lifecycle
  "What a running agent does besides answering: the machine's alarm
   set to its schedules and the daily wake, the sleep watch that
   stops the machine when nothing goes on, and the update watch that
   looks for a newer release and takes one when nothing goes on
   either. Started with the server."
  (:require [rragent.harness :as harness]
            [rragent.schedule :as schedule]
            [rragent.sleep :as sleep]
            [rragent.update :as update]
            [rragent.wake :as wake]))

(defn ^:async arm!
  "The alarm set to the earliest next run of the agent's schedules."
  [agent]
  (let [now (js/Date.now)]
    (wake/arm! (schedule/next-due (await (harness/schedules agent)) now) now)))

(defn ^:async start!
  "The alarm, the sleep watch and the update watch; `busy-fns` are
   the other reasons the machine is busy, a connected page among
   them, `updater` the handle of `rragent.update` (nil for no watch).
   One answer to busy for both watches, so that an update goes in
   exactly when the machine would otherwise idle towards its sleep.
   Answers a function that ends the watches."
  ([agent busy-fns]
   (start! agent busy-fns nil))
  ([agent busy-fns updater]
   (wake/ensure-daily!)
   (await (arm! agent))
   (let [busy? (fn [] (boolean (or (harness/busy? agent) (some #(%) busy-fns))))
         stop-sleep (sleep/watch! busy?)
         stop-update (if updater
                       (update/watch! updater busy?)
                       (fn []))]
     (fn []
       (stop-sleep)
       (stop-update)))))
