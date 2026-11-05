(ns rragent.music-picks
  "The song of a story's music sticker, picked by the person on
   Storrito's music pick page. The bot's `music_pick` tool
   (`rragent.tools.music-pick`) makes a pick: an id and a secret in the
   document `rragent.music-picks` of the bot's conversation, and the
   page's link, `<storrito>/ui/music-pick?callback=<this machine>/music-pick/<id>/<secret>`.
   The person searches the song there like in Storrito's editor and
   drags the part that plays; on confirm Storrito posts the choice to
   the callback (`app.music-pick.confirm` in storrito-server), which
   reaches the bot's chat as a message (`receive!`).

   Unlike a hook (`rragent.hooks`) the callback is a key for one call:
   the link passes through the person's browser, its history and
   Storrito's logs, so a pick is used once and expires after
   `max-age-ms`. Nor does the body reach the model as it came: the
   message is written here from the fields read (`choice`), and so is
   the `<insta-music>` element the bot puts into the story."
  (:require ["node:crypto" :as crypto]
            ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.reload :as reload]
            [rragent.storrito :as storrito]))

(def MusicPicks
  (defineDoc #js {:kind "rragent.music-picks"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "initial"
                  :initial (fn [] #js {})}))

(def kind
  "The kind of the entry a picked song is in the bot's chat."
  "rragent.music-pick")

(def max-age-ms
  "How long a pick's link works."
  (* 24 60 60 1000))

(def max-open
  "The most picks a bot keeps waiting; an older one is dropped."
  20)

(def prefix
  "/music-pick/")

(def default-section-ms
  15000)

(defn- env
  [k]
  (some-> (aget (.-env js/process) k) str str/trim not-empty))

(defn- trim-slash
  [s]
  (str/replace (str s) #"/+$" ""))

(defn callback-url
  "The address Storrito posts the pick to: the machine's page
   (`RRAGENT_URL`); nil when the machine has no public address."
  [id secret]
  (when-let [base (env "RRAGENT_URL")]
    (str (trim-slash base) prefix id "/" secret)))

(defn storrito-host
  "The organization's Storrito, `https://acme.storrito.com`:
   `RRAGENT_STORRITO_URL` when set (a Storrito on a developer's
   machine), else the host of the REPL's Storrito connection
   (`rragent.storrito/api-root`); nil without either."
  [codemode]
  (or (some-> (env "RRAGENT_STORRITO_URL") trim-slash)
      (some-> codemode :storrito deref :connection storrito/api-root :host trim-slash)))

(defn section-ms
  "The length of the part that plays: the story's, 1 to 60 seconds, 15
   (an image story's) without one."
  [ms]
  (if (and (number? ms) (js/isFinite ms))
    (-> (js/Math.round ms) (max 1000) (min 60000))
    default-section-ms))

(defn page-url
  "The music pick page of `host` that posts to `callback`."
  [host callback section]
  (str host "/ui/music-pick?callback=" (js/encodeURIComponent callback)
       "&section-ms=" section))

(defn- open-picks
  "The picks of `doc` still waiting at `now`, the newest `(dec max-open)`
   of them, so a new one fits."
  [doc now]
  (->> (js/Object.entries doc)
       (filter (fn [[_ p]] (and (not (.-usedAt p)) (< (- now (.-createdAt p)) max-age-ms))))
       (sort-by (fn [[_ p]] (- (.-createdAt p))))
       (take (dec max-open))
       (map first)
       set))

(defn ^:async create!
  "A new pick for a section of `section` ms: `{:id :secret :callback}`,
   the callback nil when the machine has no public address (nothing is
   kept then). Expired, used and the oldest open picks are dropped."
  [api section call-context]
  (let [id (.toString (crypto/randomBytes 6) "hex")
        secret (.toString (crypto/randomBytes 16) "hex")
        callback (callback-url id secret)]
    (when callback
      (await (.commit api
                      (^:async fn [tx]
                        (let [doc (await (.doc tx MusicPicks (.-conversationId api)))
                              now (js/Date.now)
                              kept (open-picks doc now)]
                          (doseq [k (js/Object.keys doc)]
                            (when-not (contains? kept k)
                              (js-delete doc k)))
                          (aset doc id #js {:secret secret :createdAt now :sectionMs section})))
                      call-context)))
    {:id id :secret secret :callback callback}))

;;; the callback's body

(defn- text-field
  "A string of the body, without control characters, at most `n` long;
   nil for none."
  [v n]
  (when (string? v)
    (let [s (str/trim (str/replace v #"[\u0000-\u001f\u007f]+" " "))]
      (when-not (str/blank? s)
        (subs s 0 (min n (count s)))))))

(defn- ms-field
  [v]
  (when (and (number? v) (js/isFinite v) (<= 0 v (* 60 60 1000)))
    (js/Math.round v)))

(defn choice
  "The song of the callback's `body` (Storrito's JSON) as data,
   `{:track-id :title :artist :search-term :start-ms :duration-ms
   :song-ms :state}`; nil when it names no track or no start."
  [body]
  (let [parsed (try (js->clj (js/JSON.parse (str body)) :keywordize-keys true)
                    (catch :default _ nil))
        track-id (when (map? parsed)
                   (re-matches #"\d{1,30}" (str (:trackId parsed))))
        start (when (map? parsed) (ms-field (:startMs parsed)))]
    (when (and track-id start)
      (let [title (text-field (:title parsed) 200)
            artist (text-field (:artist parsed) 200)]
        (cond-> {:track-id track-id
                 :title (or title "")
                 :artist (or artist "")
                 :search-term (or (text-field (:searchTerm parsed) 400)
                                  (str/trim (str title " " artist)))
                 :start-ms start
                 :duration-ms (or (ms-field (:durationMs parsed)) default-section-ms)}
          (ms-field (:songDurationMs parsed)) (assoc :song-ms (ms-field (:songDurationMs parsed)))
          (text-field (:state parsed) 500) (assoc :state (text-field (:state parsed) 500)))))))

(defn- attr
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")))

(defn insta-music
  "The Story API's element that plays the picked part of the song."
  [{:keys [track-id search-term start-ms]}]
  (str "<insta-music search-term=\"" (attr search-term) "\" track-id=\"" track-id
       "\" start-ms=\"" start-ms "\"></insta-music>"))

(defn clock
  "`m:ss` of `ms`."
  [ms]
  (let [s (js/Math.round (/ ms 1000))]
    (str (quot s 60) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(defn song-line
  "`\"Beautiful Day\" – U2 · 0:42–0:57`, for the person."
  [{:keys [title artist start-ms duration-ms]}]
  (str "\"" title "\"" (when (seq artist) (str " – " artist))
       " · " (clock start-ms) "–" (clock (+ start-ms duration-ms))))

(defn message
  "What the bot reads about the picked song."
  [{:keys [state] :as choice}]
  (str "The person picked the song for the music sticker on Storrito's music pick page: "
       (song-line choice) " (starts at " (:start-ms choice) " ms, " (/ (:duration-ms choice) 1000) " s).\n"
       "Put this element into the story's HTML, as its only <insta-music>; add design=\"album-art\", \"vinyl\" or \"invisible\" for another look than the simple card:\n"
       (insta-music choice)
       (when state (str "\nThe pick was for: " state))))

(defn entry-data
  "What the page shows of the picked song without reading the message."
  [choice]
  (select-keys choice [:track-id :title :artist :start-ms :duration-ms]))

(defn ^:async receive!
  "Storrito's call of the pick `id` with `secret` on the bot's
   `conversation` (the `root` without one): the song submitted to the
   bot's chat, `{:ok true :submission id}`, when the pick waits for it
   and the body names a song; the pick is used then. `{:ok false}`
   otherwise."
  [{:keys [harness conversation root]} id secret body context]
  (let [conversation (or conversation root)
        picks (await (.snapshot harness MusicPicks (.-id conversation) context))
        pick (some-> picks (aget (str id)))
        chosen (choice body)]
    (if (and pick
             (= secret (.-secret pick))
             (not (.-usedAt pick))
             (< (- (js/Date.now) (.-createdAt pick)) max-age-ms)
             chosen)
      (let [used? (atom false)]
        ;; used in the same transaction it is checked in, so two calls
        ;; at once cannot both reach the chat:
        (await (.commit harness
                        (^:async fn [tx]
                          (let [doc (await (.doc tx MusicPicks (.-id conversation)))
                                p (aget doc (str id))]
                            (when (and p (not (.-usedAt p)))
                              (aset p "usedAt" (js/Date.now))
                              (reset! used? true))))
                        context))
        (if-not @used?
          {:ok false}
          (do (await (reload/reload! conversation context))
              (let [submission (await (.submit conversation #js {:type "input"
                                                                 :content (message chosen)
                                                                 :whenBusy "followUp"
                                                                 :entry #js {:kind kind :data (clj->js (entry-data chosen))}}
                                               context))]
                {:ok true :submission (.-id submission)}))))
      {:ok false})))
