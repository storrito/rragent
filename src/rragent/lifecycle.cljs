(ns rragent.lifecycle
  "What a running agent does besides answering: the machine's alarm
   set to its schedules and the daily wake, and the sleep watch that
   stops the machine when nothing goes on. Started with the server."
  (:require [rragent.harness :as harness]
            [rragent.schedule :as schedule]
            [rragent.sleep :as sleep]
            [rragent.wake :as wake]))

(defn ^:async arm!
  "The alarm set to the earliest next run of the agent's schedules."
  [agent]
  (let [now (js/Date.now)]
    (wake/arm! (schedule/next-due (await (harness/schedules agent)) now) now)))

(defn ^:async start!
  "The alarm and the sleep watch; `busy-fns` are the other reasons the
   machine is busy, a connected page among them. Answers a function
   that ends the watch."
  [agent busy-fns]
  (wake/ensure-daily!)
  (await (arm! agent))
  (sleep/watch! (fn [] (boolean (or (harness/busy? agent) (some #(%) busy-fns))))))
