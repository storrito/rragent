(ns rragent.status-test
  "The status of a bot of before: the document `rragent.status`,
   initial for a bot nothing was read for, stale when the chat has
   entries past it, the parse of a status the model wrote and the
   prompt that asks for one. The manager's `bot status` that wrote it
   is gone with the manager (docs/one-loop.md); the documents of
   before stay readable."
  (:require [clojure.test :refer [deftest is]]
            [rragent.status :as status]))

(deftest initial-and-stale-test
  (is (= {:summary "" :needs [] :version nil :as-of-entry 0 :at 0} status/initial))
  (is (false? (status/stale? status/initial nil)) "a chat without entries")
  (is (true? (status/stale? status/initial 1)))
  (is (false? (status/stale? {:as-of-entry 12} 12)) "read up to the newest entry")
  (is (true? (status/stale? {:as-of-entry 12} 13))))

(deftest parse-test
  (is (= {:summary "Made v1." :needs ["Approve v1"] :version "v1"}
         (status/parse "{\"summary\": \"Made v1.\", \"needs\": [\"Approve v1\"], \"version\": \"v1\"}")))
  (is (= {:summary "Made v1." :needs [] :version nil}
         (status/parse "```json\n{\"summary\": \"Made v1.\", \"needs\": [], \"version\": null}\n```"))
      "a code fence around it")
  (is (= {:summary "x" :needs [] :version nil} (status/parse "Here it is: {\"summary\": \"x\"} done.")) "prose around the object")
  (is (= {:summary "x" :needs ["a" "3"] :version nil} (status/parse "{\"summary\": \"x\", \"needs\": [\"a\", \"\", 3]}")) "an empty need dropped, a number kept as text")
  (is (nil? (status/parse "I cannot tell.")))
  (is (nil? (status/parse "{\"needs\": []}")) "without a summary"))

(deftest prompt-test
  (is (= "The status as it was:\n(none yet)\n\nThe chat since then:\n[3] Person: Hi" (status/prompt status/initial "[3] Person: Hi")))
  (is (= "The status as it was:\nv1 is built.\nWaiting for the person: Approve v1; Pick a color\nVersion: v1\n\nThe chat since then:\n(nothing)"
         (status/prompt {:summary "v1 is built." :needs ["Approve v1" "Pick a color"] :version "v1"} ""))))
