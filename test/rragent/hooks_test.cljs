(ns rragent.hooks-test
  "What the page shows of a hook's call: the line of a Storrito
   integration trigger event, the data an entry carries."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.hooks :as hooks]))

(def drive-event
  "What Storrito posts for a Google Drive trigger, cut to what matters
   (seen 2026-10-23)."
  "{\"id\":\"msg_1\",\"type\":\"composio.trigger.message\",\"metadata\":{\"trigger_slug\":\"GOOGLEDRIVE_FILE_CREATED_TRIGGER\",\"trigger_id\":\"ti_1\",\"connected_account_id\":\"ca_1\"},\"data\":{\"event_type\":\"file_created\",\"file\":{\"id\":\"1dIU\",\"name\":\"4.txt\",\"mimeType\":\"text/plain\"}}}")

(deftest event-line-test
  (testing "a Storrito integration trigger event: the service, the event, what it is about"
    (is (= "Google Drive · file created · 4.txt" (hooks/event-line drive-event))))
  (testing "a service without a known name is capitalized, a payload without a subject ends after the event"
    (is (= "Trello · card created" (hooks/event-line "{\"metadata\":{\"trigger_slug\":\"TRELLO_CARD_CREATED_TRIGGER\"},\"data\":{\"board\":\"b\"}}")))
    (is (= "Gmail · new gmail message · Hello" (hooks/event-line "{\"metadata\":{\"trigger_slug\":\"GMAIL_NEW_GMAIL_MESSAGE\"},\"data\":{\"subject\":\"Hello\"}}"))))
  (testing "any other body is no event: a GitHub push, plain text, no body"
    (is (nil? (hooks/event-line "{\"ref\":\"refs/heads/main\",\"commits\":[]}")))
    (is (nil? (hooks/event-line "deploy done")))
    (is (nil? (hooks/event-line nil)))
    (is (nil? (hooks/event-line "{\"metadata\":{\"trigger_slug\":\"\"}}")))))

(deftest entry-data-test
  (testing "the entry of a call carries its name, the body's size and the event's line when there is one"
    (is (= {:name "drive" :bytes (count drive-event) :event "Google Drive · file created · 4.txt"}
           (hooks/entry-data "drive" drive-event)))
    (is (= {:name "deploy" :bytes 4} (hooks/entry-data "deploy" "done")))
    (is (= {:name "ping" :bytes 0} (hooks/entry-data "ping" nil)))))
