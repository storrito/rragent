(ns rragent.phone
  "A phone as a camera of a screencast (`rragent.recordings`): paired
   by a QR code, it records its own file at full quality beside the
   screen and the webcam, with its own microphone, so that the files
   align by the waveform like the others. Nothing streams between the
   two browsers: the phone's pieces go to the machine as the laptop's
   do, and the two only need a word from the machine to start and stop
   together.

   `POST /bot/<id>/phone/code` makes a pairing code that lives ten
   minutes, answered with the link the QR carries; `GET /pair/<code>`,
   outside the token, takes the code once, sets the cookie the token
   sets and sends the phone to `GET /bot/<id>/phone`, its page
   (`rragent.client.phone`). There the phone says hello every few
   seconds (`POST .../phone/hello`, its device and camera, kept in
   the bot's map under `:phone` so that the panel on the laptop shows
   it), and listens on `GET .../phone/events`, server-sent events: the
   laptop's `POST .../phone/start` with the stamp of the recording and
   `POST .../phone/stop` go to every phone listening, and
   `POST .../phone/test` asks for a clip of a few seconds, which the
   phone records, sends up and finishes itself; `GET .../phone/test`
   names that clip for the laptop's panel. When the phone's last piece
   is up it says so (`POST .../phone/done`), which the recording's
   finish waits for."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [rragent.auth :as auth]
            [rragent.db :as db]
            [rragent.rframes.transit :as transit]
            [rragent.uploads :as uploads]))

(def path-prefix
  "The phone's routes under the bot's page."
  "/phone")

(def pair-prefix
  "Where a pairing code is taken, outside the token."
  "/pair")

(def code-ttl-ms
  (* 10 60 1000))

(def fresh-ms
  "How long after its last hello a phone counts as connected."
  20000)

(defonce ^:private codes
  ;; code -> {:bot :expires}
  (atom {}))

(defonce ^:private listeners
  ;; bot id -> conn id -> send!
  (atom {}))

(defonce ^:private next-conn-id
  (atom 0))

(defn new-code!
  "A pairing code for the bot, good for ten minutes."
  [bot-id]
  (let [code (.toString (crypto/randomBytes 6) "hex")]
    (swap! codes (fn [all]
                   (let [now (js/Date.now)]
                     (-> (into {} (remove (fn [[_ {:keys [expires]}]] (< expires now)) all))
                         (assoc code {:bot (str bot-id) :expires (+ now code-ttl-ms)})))))
    code))

(defn take-code!
  "The bot of the code, once; nil for an unknown or an old one."
  [code]
  (let [{:keys [bot expires]} (get @codes code)]
    (swap! codes dissoc code)
    (when (and bot (< (js/Date.now) expires))
      bot)))

(defn origin
  "Where the phone reaches the machine: what the request was sent to."
  [request]
  (let [headers (:headers request)
        proto (or (get headers "x-forwarded-proto")
                  (if (get headers "x-forwarded-host") "https" "http"))
        host (or (get headers "x-forwarded-host") (get headers "host"))]
    (str proto "://" host)))

(defn pair-handler
  "`GET /pair/<code>`: the phone is in, as the token lets a browser in,
   and goes to its page."
  [token w]
  (let [request (:ring/request w)
        code (str (get-in w [:ring/route-params :*]))
        bot (take-code! code)]
    (assoc w :ring/response
           (if bot
             {:status 302
              :headers (cond-> {"Location" (str "/bot/" bot path-prefix)
                                "Cache-Control" "no-store"}
                         token (assoc "Set-Cookie" (auth/set-cookie token request)))
              :body ""}
             {:status 404
              :headers {"Content-Type" "text/plain; charset=utf-8"}
              :body "This pairing code is unknown or too old. Show the QR code on the laptop again."}))))

(defn page-shell
  "The phone's page: the client bundle mounts `rragent.client.phone` on
   `#phone`, which carries the bot and the words of the page."
  [{:keys [bot name words head]}]
  (str "<!DOCTYPE html>"
       "<html lang=\"en\"><head><meta charset=\"UTF-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, viewport-fit=cover\">"
       head
       "</head><body>"
       "<div id=\"phone\" data-bot=\"" bot "\""
       " data-name=\"" (str/replace (str name) "\"" "&quot;") "\""
       " data-words=\"" (str/replace (js/JSON.stringify (clj->js words)) "\"" "&quot;") "\"></div>"
       "</body></html>"))

(defn events-handler
  "`GET /bot/<id>/phone/events`: the stream the phone listens on, held
   open; a comment every twenty seconds keeps it alive."
  [bot-id w]
  (let [conn-id (swap! next-conn-id inc)
        timer (atom nil)]
    (assoc w :ring/response
           {:status 200
            :headers {"Content-Type" "text/event-stream"
                      "Cache-Control" "no-store"}
            :on-open (fn [{:keys [send!]}]
                       (swap! listeners assoc-in [(str bot-id) conn-id] send!)
                       (send! ": hello\n\n")
                       (reset! timer (js/setInterval (fn [] (send! ": alive\n\n")) 20000)))
            :on-close (fn []
                        (some-> @timer js/clearInterval)
                        (swap! listeners update (str bot-id) dissoc conn-id))})))

(defn send-phones!
  "`event` with `data` to every phone of the bot; how many there were."
  [bot-id event data]
  (let [sends (vals (get @listeners (str bot-id)))
        frame (str "event: " (name event) "\ndata: " (js/JSON.stringify (clj->js data)) "\n\n")]
    (doseq [send! sends]
      (try
        (send! frame)
        (catch :default _
          nil)))
    (count sends)))

(defn listening
  "How many phones listen for the bot now."
  [bot-id]
  (count (get @listeners (str bot-id))))

(defn hello!
  "The phone's hello in the bot's map: its device, its camera, when."
  [db path {:keys [device facing]}]
  (swap! db db/update-at path update :phone merge {:device (str device)
                                                    :facing (str facing)
                                                    :seen (js/Date.now)}))

(defn done!
  "The phone's file of the recording `stamp` is up; for the test, the
   file's name and when, for the laptop's panel (`test-of`)."
  [db path stamp file]
  (if (= "test" (str stamp))
    (swap! db db/update-at path assoc-in [:phone :test] {:file (str file) :at (js/Date.now)})
    (swap! db db/update-at path update-in [:phone :done] (fnil conj #{}) (str stamp))))

(defn test-of
  "The phone's last test clip, `{:file :at}`, or nil."
  [db path]
  (get-in (db/at @db path) [:phone :test]))

(defn done?
  [db path stamp]
  (contains? (get-in (db/at @db path) [:phone :done]) (str stamp)))

(defn status
  "What the panel shows of the phone: its device while its hello is
   fresh, nil otherwise."
  [{:keys [device facing seen]}]
  (when (and seen (< (- (js/Date.now) seen) fresh-ms))
    {:device device
     :facing facing}))

(defn test-handler
  "`GET /bot/<id>/phone/test`: the phone's last test clip."
  [{:keys [db path]} w]
  (uploads/respond w 200 (or (test-of db path) {})))

(defn- ^:async body
  [w]
  (try
    (await (transit/read-stream (:body (:ring/request w))))
    (catch :default _
      nil)))

(defn ^:async post!
  "`POST /bot/<id>/phone/<action>`: `code` makes a pairing code (the
   link for the QR), `hello` is the phone's presence, `start` and
   `stop` go to the phones, `done` marks the phone's file of a
   recording as up."
  [{:keys [db path bot]} action w]
  (case action
    "code" (let [code (new-code! bot)]
             (uploads/respond w 200 {:code code
                                     :url (str (origin (:ring/request w)) pair-prefix "/" code)}))
    "hello" (let [facts (await (body w))]
              (hello! db path (if (map? facts) facts {}))
              (uploads/respond w 200 {:ok true}))
    "start" (let [{:keys [stamp]} (await (body w))]
              (if (seq (str stamp))
                (uploads/respond w 200 {:phones (send-phones! bot :start {:stamp (str stamp)})})
                (uploads/respond w 400 {:error :no-stamp})))
    "stop" (let [{:keys [stamp]} (await (body w))]
             (uploads/respond w 200 {:phones (send-phones! bot :stop {:stamp (str stamp)})}))
    "test" (let [{:keys [seconds]} (await (body w))]
             (uploads/respond w 200 {:phones (send-phones! bot :test {:stamp "test" :seconds (or seconds 5)})}))
    "done" (let [{:keys [stamp file]} (await (body w))]
             (if (seq (str stamp))
               (do (done! db path stamp file)
                   (uploads/respond w 200 {:ok true}))
               (uploads/respond w 400 {:error :no-stamp})))
    (uploads/respond w 404 {:error :no-such-action})))
