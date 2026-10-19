(ns rragent.sleep
  "The machine put to sleep when there is nothing to do. A Sprite made
   with `auto_idle: false` runs until someone stops it, and the agent
   does, with `sprite-env stop`, once the machine has idled for a
   while: no run going, no page connected. The next request to the
   page, the alarm of a schedule (`rragent.wake`) or a command from
   outside starts the machine again; the process restarts with it and
   pi-durable resumes what it was doing. Without `sprite-env` nothing
   happens here; `RRAGENT_SLEEP=off` switches it off anywhere,
   `RRAGENT_SLEEP_AFTER_MIN` is the idle time, ten minutes by default."
  (:require [rragent.sprite :as sprite]))

(def default-after-min
  10)

(defn after-ms
  []
  (* 60000 (or (some-> (aget (.-env js/process) "RRAGENT_SLEEP_AFTER_MIN") js/parseInt (as-> n (when-not (js/isNaN n) n)))
               default-after-min)))

(defn available?
  []
  (and (not= "off" (aget (.-env js/process) "RRAGENT_SLEEP"))
       (sprite/available?)))

(defn step
  "The idle time kept and the decision, `{:idle-since :stop?}`: work
   resets the idle time, `after-ms` of idling is the moment to stop."
  [idle-since busy? now after-ms]
  (if busy?
    {:idle-since nil :stop? false}
    (let [since (or idle-since now)]
      {:idle-since since :stop? (<= after-ms (- now since))})))

(defn stop!
  "`sprite-env stop`: true when the machine took it."
  []
  (let [{:keys [exit err]} (sprite/sprite-env! "stop")]
    (when-not (zero? exit)
      (js/console.error "[sleep] sprite-env stop failed:" (str err)))
    (zero? exit)))

(defn watch!
  "Every half minute: the machine stopped once it has idled for
   `after-ms` while `busy?` answers false. The process ends with the
   machine and starts afresh with it, so the idle time starts afresh
   after a stop; a stop that was refused is tried again on the next
   round. Answers a function that ends the watch."
  ([busy?]
   (watch! busy? {}))
  ([busy? {:keys [every-ms after-ms stop! available? now]
           :or {every-ms 30000 after-ms (after-ms) stop! stop! available? available? now js/Date.now}}]
   (if-not (available?)
     (fn [])
     (let [idle-since (atom nil)
           timer (js/setInterval (fn []
                                   (try
                                     (let [{:keys [idle-since* stop?]} (let [r (step @idle-since (busy?) (now) after-ms)]
                                                                         {:idle-since* (:idle-since r) :stop? (:stop? r)})]
                                       (if stop?
                                         (do (js/console.error "[sleep] idle for" (quot after-ms 60000) "minutes: the machine stops")
                                             (stop!)
                                             (reset! idle-since nil))
                                         (reset! idle-since idle-since*)))
                                     (catch :default e
                                       (js/console.error "[sleep]" (str e)))))
                                 every-ms)]
       (.unref timer)
       (fn [] (js/clearInterval timer))))))
