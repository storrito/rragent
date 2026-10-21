(ns rragent.lifecycle
  "What a running machine does besides answering: the alarm set to
   the schedules of its bots and the daily wake, the sleep watch that
   stops the machine when nothing goes on, and the update watch that
   looks for a newer release and takes one when nothing goes on
   either. Started with the server."
  (:require [rragent.schedule :as schedule]
            [rragent.sleep :as sleep]
            [rragent.update :as update]
            [rragent.wake :as wake]))

(defn ^:async arm!
  "The alarm set to the earliest next run across the bots' schedules."
  []
  (await (schedule/rearm! (js/Date.now))))

(defn ^:async start!
  "The alarm, the sleep watch and the update watch; `busy-fns` are
   the reasons the machine is busy, a bot's run and a connected page
   among them, `updater` the handle of `rragent.update` (nil for no
   watch). One answer to busy for both watches, so that an update goes
   in exactly when the machine would otherwise idle towards its sleep.
   Answers a function that ends the watches."
  ([busy-fns]
   (start! busy-fns nil))
  ([busy-fns updater]
   (wake/ensure-daily!)
   (await (arm!))
   (let [busy? (fn [] (boolean (some #(%) busy-fns)))
         stop-sleep (sleep/watch! busy?)
         stop-update (if updater
                       (update/watch! updater busy?)
                       (fn []))]
     (fn []
       (stop-sleep)
       (stop-update)))))
