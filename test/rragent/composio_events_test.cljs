(ns rragent.composio-events-test
  "The verification of a delivery, the ids told again, which bot an
   event goes to, what the bot reads."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.composio-events :as events]))

(def secret
  "12964b2a8f4e0c7d5b1a3e9f6c2d8a4b7e1f0c3d5a9b8e2f4c6d1a7b3e5f9c0d")

(def body
  "{\"id\":\"msg_1\",\"timestamp\":\"2026-10-26T13:10:04.229Z\",\"type\":\"composio.trigger.message\",\"metadata\":{\"trigger_slug\":\"GOOGLEDRIVE_FILE_CREATED_TRIGGER\",\"trigger_id\":\"ti_1\",\"connected_account_id\":\"ca_1\",\"user_id\":\"agent-1\"},\"data\":{\"event_type\":\"file_created\",\"file\":{\"name\":\"4.txt\"}}}")

(def now-ms
  1793020204000)

(def timestamp
  (str (quot now-ms 1000)))

(defn- signed
  "The headers of a delivery signed as Composio signs them."
  [secret id timestamp body]
  {"webhook-id" id
   "webhook-timestamp" timestamp
   "webhook-signature" (str "v1," (-> (crypto/createHmac "sha256" secret)
                                       (.update (str id "." timestamp "." body) "utf8")
                                       (.digest "base64")))})

(deftest trigger-of-test
  (is (= {:kind "composio" :name "GOOGLEDRIVE_FILE_CREATED_TRIGGER" :line "Google Drive · file created · 4.txt"}
         (events/trigger-of (events/parse body) body)))
  (is (= {:kind "composio" :name "composio.connected_account.expired"}
         (events/trigger-of {:type "composio.connected_account.expired"} "{\"type\":\"composio.connected_account.expired\"}"))))

(deftest signature-test
  (testing "a delivery signed with the secret verifies; the header may carry several signatures, a bare one too"
    (let [headers (signed secret "msg_1" timestamp body)]
      (is (true? (events/verified? secret headers body now-ms)))
      (is (true? (events/verified? secret (update headers "webhook-signature" #(str "v1,abc " %)) body now-ms)))
      (is (true? (events/verified? secret (update headers "webhook-signature" #(subs % 3)) body now-ms)))))
  (testing "another secret, another body, a missing header or no secret at all: not verified"
    (let [headers (signed secret "msg_1" timestamp body)]
      (is (false? (events/verified? "other" headers body now-ms)))
      (is (false? (events/verified? secret headers (str body " ") now-ms)))
      (is (false? (events/verified? secret (dissoc headers "webhook-signature") body now-ms)))
      (is (false? (events/verified? secret (dissoc headers "webhook-id") body now-ms)))
      (is (false? (events/verified? nil headers body now-ms)))
      (is (false? (events/verified? secret (signed "other" "msg_1" timestamp body) body now-ms)))))
  (testing "a timestamp older than five minutes is refused, seconds, milliseconds and ISO dates alike"
    (is (false? (events/verified? secret (signed secret "msg_1" timestamp body) body (+ now-ms 301000))))
    (is (true? (events/verified? secret (signed secret "msg_1" timestamp body) body (+ now-ms 299000))))
    (is (true? (events/fresh? (str now-ms) now-ms)))
    (is (true? (events/fresh? "2026-10-26T13:10:04.000Z" now-ms)))
    (is (false? (events/fresh? "2026-10-26T12:00:00.000Z" now-ms)))
    (is (false? (events/fresh? "soon" now-ms)))))

(deftest signatures-of-test
  (is (= ["abc"] (events/signatures-of "v1,abc")))
  (is (= ["abc" "def"] (events/signatures-of "v1,abc v1,def")))
  (is (= ["abc"] (events/signatures-of "abc")))
  (is (= [] (events/signatures-of nil))))

(deftest event-test
  (let [event (events/parse body)]
    (testing "the id, the trigger, the type"
      (is (= "msg_1" (events/event-id {"webhook-id" "wh_1"} event)))
      (is (= "wh_1" (events/event-id {"webhook-id" "wh_1"} nil)))
      (is (= "ti_1" (events/trigger-id event)))
      (is (nil? (events/trigger-id {:type "composio.connected_account.activated"})))
      (is (nil? (events/parse "not json"))))
    (testing "what the bot reads: the line of the event, its type, its trigger, the body"
      (let [text (events/message event body)]
        (is (str/starts-with? text "Composio event: Google Drive · file created · 4.txt\nType: composio.trigger.message\nTrigger: GOOGLEDRIVE_FILE_CREATED_TRIGGER (ti_1)\nBody:\n{"))
        (is (str/includes? text "\"file_created\""))))
    (testing "an event that is no trigger's reads with its type alone"
      (is (= "Composio event\nType: composio.connected_account.activated\nBody:\n{\"type\":\"composio.connected_account.activated\"}"
             (events/message {:type "composio.connected_account.activated"} "{\"type\":\"composio.connected_account.activated\"}"))))))

(deftest owner-test
  (let [added {:event "composio-trigger" :id "ti_1" :slug "X" :account "ca_1"}
        deleted {:event "composio-trigger-deleted" :id "ti_1"}]
    (testing "the bot whose newest fact about the trigger is the adding"
      (is (= "7" (events/owner {"3" [] "7" [added]} "ti_1")))
      (is (= "7" (events/owner {"7" [deleted added]} "ti_1"))))
    (testing "none when no bot added it, or the newest says it was deleted"
      (is (nil? (events/owner {"7" [added deleted]} "ti_1")))
      (is (nil? (events/owner {"7" [added]} "ti_2")))
      (is (nil? (events/owner {} "ti_1"))))))
