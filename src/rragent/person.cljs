(ns rragent.person
  "Who is talking: several people may talk to one bot, a customer and
   the Storrito side of a project, a team, and the bot answers better
   when it knows which of them is writing. A browser tells its person's
   first name once, in a form the page shows in place of the composer
   (`rragent.view/person-form`), with a word on why, and the name goes
   into the cookie `cookie-name` (`POST /person`, `rragent.page`); a
   person who wants it changed opens `GET /person`, which forgets the
   cookie, and the form is back. Every message the person sends then
   goes into the conversation as an entry of the kind `kind` with the
   name in its data, the way pi-durable places an input under a kind
   of one's own, and the model's content starts with `Name: `, so that
   the model reads who speaks (`draft`); the page shows the name over
   the message and the text without the prefix (`strip-name`). Only a
   first name, kept in the browser; it is what the person says they
   are, not a proof: everyone with the token's link may type any name."
  (:require [clojure.string :as str]
            [rragent.auth :as auth]))

(def cookie-name
  "rragent.person")

(def kind
  "The entry of a message with its person's name, `{:name}` in its
   data."
  "rragent.person")

(def max-chars
  40)

(defn clean
  "The name as kept: one line, trimmed, at most `max-chars`; nil for
   nothing."
  [s]
  (let [s (-> (str s) (str/replace #"[\r\n\t]+" " ") (str/replace #"\s+" " ") str/trim)]
    (when (seq s)
      (subs s 0 (min max-chars (count s))))))

(defn name-of
  "The person's name from the request's cookie, nil without one."
  [request]
  (when-let [raw (get (auth/cookies request) cookie-name)]
    (try
      (clean (js/decodeURIComponent raw))
      (catch :default _
        nil))))

(defn set-cookie
  "The header that keeps `name` in the browser for a year."
  [name request]
  (str cookie-name "=" (js/encodeURIComponent (str name))
       "; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000"
       (when (auth/secure? request)
         "; Secure")))

(defn clear-cookie
  "The header that forgets the name."
  [request]
  (str cookie-name "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
       (when (auth/secure? request)
         "; Secure")))

(defn back-path
  "Where the form sends the browser afterwards: `back` when it is a
   path of this page, `/` else."
  [back]
  (let [back (str back)]
    (if (and (str/starts-with? back "/") (not (str/starts-with? back "//")))
      back
      "/")))

(defn label
  "What stands in front of a message for the model: the name, the
   topic's name in brackets, or both, `Lydia (Content week)`; nil
   without either."
  [name topic]
  (let [name (not-empty (str/trim (str name)))
        topic (not-empty (str/trim (str topic)))]
    (cond
      (and name topic) (str name " (" topic ")")
      name name
      topic (str "(" topic ")"))))

(defn prefix
  [label]
  (str label ": "))

(defn strip-prefix
  "`text` without the label and the colon in front of it, for the
   page: what a message of our kind carries for the model."
  [text]
  (str/replace (str text) #"^[^\n:]{1,160}: " ""))

(defn with-name
  "The model's content with the label (`label`) in front: a string
   prefixed, a list of blocks with its first text block prefixed, or a
   text block of the label put first."
  [content name]
  (cond
    (string? content) (str (prefix name) content)
    (sequential? content) (let [[block & more] content]
                            (if (= "text" (:type block))
                              (into [(update block :text #(str (prefix name) %))] more)
                              (into [{:type "text" :text (str/trimr (prefix name))}] content)))
    :else content))

(defn strip-name
  "`text` without the prefix of `name`, for the page."
  [text name]
  (let [p (prefix name)]
    (if (and (seq (str name)) (str/starts-with? (str text) p))
      (subs text (count p))
      text)))

(def new-topic
  "What stands in the brackets of a message from the \"New topic\" view
   (docs/one-loop.md §3): the model reads from it that the message
   opens a matter of its own."
  "new topic")

(defn draft
  "The submission of the person's message: the input with `content`
   (`rragent.uploads/content`), steering a run that goes; with a `name`
   or a `topic` (`{:id :name}`, the view the message came from,
   docs/one-loop.md §3), the content prefixed with the label and the
   entry of our kind with the name and the topic's id. A `topic` of
   `{:fresh token}` is the \"New topic\" view's: the label says
   `new-topic` and the entry carries the token, by which the page
   finds the topic the model then makes."
  [content name & [topic]]
  (let [fresh (not-empty (str (:fresh topic)))
        label (label name (if fresh new-topic (:name topic)))
        draft #js {:type "input" :content (clj->js (if label (with-name content label) content)) :whenBusy "steer"}]
    (when label
      (let [data #js {:kind kind :data #js {}}]
        (when (seq (str name))
          (aset (.-data data) "name" (str name)))
        (when (and (:id topic) (not fresh))
          (aset (.-data data) "topic" (str (:id topic))))
        (when fresh
          (aset (.-data data) "fresh" fresh))
        (aset draft "entry" data)))
    draft))
