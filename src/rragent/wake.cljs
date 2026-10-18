(ns rragent.wake
  "The alarm of the schedules on a machine that sleeps. The agent keeps
   its schedules (`rragent.schedule`) and knows the next minute it has
   to be awake for; this tells the machine. On a Sprite the machine's
   own automations do it: `rragent-wake` is one automation whose cron
   line is that minute in UTC and whose action wakes the sprite; the
   process starts again with the machine and pi-durable resumes the
   sleeping schedule, which fires. `rragent-daily` wakes it once a day
   besides, so that an alarm lost to a crash is set again within a day.
   Both are set from inside the sprite with `sprite-env`. Without
   `sprite-env` nothing happens here: the process runs on and its own
   timers fire. `RRAGENT_WAKE=off` switches it off anywhere."
  (:require [clojure.string :as str]
            [rragent.sprite :as sprite]))

(def wake-name
  "rragent-wake")

(def daily-name
  "rragent-daily")

(def daily-cron
  "17 3 * * *")

(def awake-s
  "How long a wake keeps the machine up at least: the process starts,
   the schedule fires, the run begins; `rragent.sleep` holds it while
   the run goes."
  300)

(defn available?
  []
  (and (not= "off" (aget (.-env js/process) "RRAGENT_WAKE"))
       (sprite/available?)))

(defn cron-of
  "The minute of `ms` as a five-field cron line in UTC: `30 7 16 10 *`.
   It names a minute of a day of a month; the alarm is set to the next
   one long before the year is round."
  [ms]
  (let [t (js/Date. ms)]
    (str (.getUTCMinutes t) " " (.getUTCHours t) " " (.getUTCDate t) " " (inc (.getUTCMonth t)) " *")))

(defn next-minute
  [now]
  (* 60000 (inc (quot now 60000))))

(defn- say
  [& words]
  (js/console.error "[wake]" (str/join " " words)))

(defn- automation!
  "The automation set to the cron line with a wake: updated when it is
   there, made otherwise, enabled either way."
  [name cron]
  (let [args ["--cron" cron "--wake" "--timeout" (str awake-s)]
        updated (apply sprite/sprite-env! "automation" "update" name args)
        result (if (sprite/ok? updated)
                 updated
                 (apply sprite/sprite-env! "automation" "create" name args))]
    (if (sprite/ok? result)
      (do (sprite/sprite-env! "automation" "resume" name)
          true)
      (do (say "could not set" name "to" cron (str/trim (str (:err result) " " (:out result))))
          false))))

(defn arm!
  "The machine wakes at `at` (milliseconds; the next minute when it is
   past); without one (`nil`: no schedule) the wake automation goes."
  [at now]
  (when (available?)
    (if at
      (automation! wake-name (cron-of (max at (next-minute now))))
      (do (sprite/sprite-env! "automation" "delete" wake-name)
          true))))

(defn ensure-daily!
  "The daily wake, made when it is missing."
  []
  (when (available?)
    (or (sprite/ok? (sprite/sprite-env! "automation" "get" daily-name))
        (automation! daily-name daily-cron))))
