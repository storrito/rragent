(ns store
  "What the program has seen, in a SQLite file through `node:sqlite`,
   which comes with Node: an item's id with a hash of its content, so
   that a later run tells a new item from a known one, and an edited
   one from a repeat. One table, `seen`, which `open` makes."
  (:require ["node:crypto" :as crypto]
            ["node:sqlite" :refer [DatabaseSync]]))

(defn open
  "The store in `file`, made when it is missing; `\":memory:\"` is one
   that lives as long as the process."
  [file]
  (doto (DatabaseSync. file)
    (.exec "CREATE TABLE IF NOT EXISTS seen (id TEXT PRIMARY KEY, hash TEXT NOT NULL, at INTEGER NOT NULL)")))

(defn hash-of
  "A short hash of `item`'s content, its keys in a fixed order so that
   the order they came in does not count."
  [item]
  (-> (crypto/createHash "sha256")
      (.update (pr-str (into (sorted-map) item)))
      (.digest "hex")
      (subs 0 16)))

(defn status
  "`:new` when `item` (a map with an `:id`) was never seen, `:changed`
   when it was seen with other content, `:known` otherwise."
  [db item]
  (let [row (.get (.prepare db "SELECT hash FROM seen WHERE id = ?") (str (:id item)))]
    (cond
      (nil? row) :new
      (not= (.-hash row) (hash-of item)) :changed
      :else :known)))

(defn remember!
  "Keeps `item` as seen, with its content as it is now."
  [db item]
  (.run (.prepare db "INSERT INTO seen (id, hash, at) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET hash = excluded.hash, at = excluded.at")
        (str (:id item)) (hash-of item) (js/Date.now))
  nil)

(defn ids
  "The ids seen, the oldest first."
  [db]
  (mapv #(.-id %) (.all (.prepare db "SELECT id FROM seen ORDER BY at, id"))))
