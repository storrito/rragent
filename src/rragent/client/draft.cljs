(ns rragent.client.draft
  "The draft of the composer (`rragent.client`): what is typed in its
   text, kept by the key the server gives the view (`:ui/store-key`,
   `rragent.view/draft-key`, one per bot and view), so that a switch
   of the view keeps what was typed, and a frame from the server
   resets nothing.

   It lives in an atom of its own, not in the store of the page: a
   change of the store renders the whole page again, the feed of up
   to two hundred items with it, and a letter typed is no change of
   the page. The store learns only whether there is a draft (`key` to
   true, `flagged`), for the Send button, which renders from it: once
   at the first letter of a message and once at the last one deleted,
   not at every letter. So the text of the composer carries no value
   of the frame's: it takes the draft when it mounts (`mount!`), and
   Replicant leaves its value alone from then on; a write from
   elsewhere, the dictation or the recorder ending in the draft, goes
   to the node as well (`write!`). The send carries
   `[:composer/draft key]`, which the enricher of `rragent.client`
   replaces with the text, and `[:composer/clear-draft key]` empties
   the draft once the message is in."
  (:require [clojure.string :as str]
            [rframes.client.state :as state]))

(defonce drafts*
  (atom {}))

(defonce ^:private mounted*
  ;; the text of the composer on the page, `{:key :node}`
  (atom nil))

(defn text
  "The draft under `key`, \"\" without one."
  [key]
  (str (get @drafts* key "")))

(defn mount!
  "The text `node` shows the draft under `key` from now on, and takes
   it."
  [key ^js node]
  (reset! mounted* {:key key :node node})
  (set! (.-value node) (text key)))

(defn unmount!
  "The `node` left the page; a newer text mounted meanwhile stays."
  [^js node]
  (swap! mounted* #(when-not (identical? node (:node %)) %)))

(defn put!
  "`text` as the draft under `key`: in the atom, and in the text of the
   composer when it shows this key (left alone when it has the text
   already: setting a value moves the caret to the end). The store is
   not told, see `write!`."
  [key text]
  (let [text (str text)]
    (if (= "" text)
      (swap! drafts* dissoc key)
      (swap! drafts* assoc key text))
    (let [{mounted-key :key :keys [node]} @mounted*]
      (when (and node (= key mounted-key) (not= text (.-value node)))
        (set! (.-value node) text)))))

(defn flagged
  "`state` told whether there is a draft under `key`: the key to true
   with a `text`, gone with a blank one; the state itself when it says
   so already, so that a caller can see nothing changed."
  [state key text]
  (let [present? (not (str/blank? (str text)))]
    (cond
      (= present? (boolean (get state key))) state
      present? (assoc state key true)
      :else (dissoc state key))))

(defn write!
  "`put!`, and the store told whether there is a draft (`flagged`)
   when that changed: a render then, once at the first letter of a
   message and once at the last one deleted. A writer that changes the
   store anyway, the dictation, puts and flags in its own one change."
  [key text]
  (put! key text)
  (let [state @state/store]
    (when-not (identical? state (flagged state key text))
      (swap! state/store flagged key text))))

(defn clear!
  "The draft under `key` is gone, sent."
  [key]
  (write! key ""))
