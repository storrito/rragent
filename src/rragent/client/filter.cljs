(ns rragent.client.filter
  "The text of the field above the list of the artifacts
   (`:ui/files-filter`, `rragent.client`), told to the server, which
   narrows the list (`:artifacts/filter`, `rragent.page`): a frame
   carries the rows the text names, not every file of the bot for the
   browser to hide.

   The text is the field's, in the store of the page, so that a
   letter typed never waits for a frame; the server follows. Every
   frame says what its rows are narrowed by (`:ui/query`), and the
   field tells the server its text when that is another one: after a
   pause (`pause`), so that a word typed is a command or two and not
   one per letter, and once per text (`:asked`), until the server has
   it. So a server that lost the text, a machine that woke, learns it
   again from the frame it sends itself. A command that fails is not
   sent again, as no click is: the list stays as it was until the
   text changes."
  (:require [rframes.client.state :as state]))

(def pause
  "How many milliseconds a field waits before it tells the server its
   text."
  150)

(defonce fields*
  ;; per key of a field: `:text`, what it holds, `:query`, what the
  ;; rows of the last frame are narrowed by, `:asked`, the text last
  ;; sent to the server, and the `:timer` of a pause that runs
  (atom {}))

(defn seen
  "A field's entry after a render with the `text` it holds and the
   `query` the rows are narrowed by. The text asked for is forgotten
   once the server has it, so that it is asked for again should the
   server lose it."
  [entry text query]
  (cond-> (assoc entry :text text :query query)
    (= text query) (dissoc :asked)))

(defn due?
  "Whether the server is to be told the field's text: the rows are
   narrowed by another one, and this one was not sent yet."
  [{:keys [text query asked]}]
  (and (not= text query) (not= text asked)))

(defn- ask!
  "The pause of the field under `key` is over: its text to the
   server, when that is still due."
  [key bot]
  (swap! fields* update key dissoc :timer)
  (let [{:keys [text] :as entry} (get @fields* key)]
    (when (due? entry)
      (swap! fields* assoc-in [key :asked] text)
      (state/dispatch! [[:data/command {:command/kind :artifacts/filter
                                        :command/data {:bot bot :text text :tab state/tabid}}]]))))

(defn follow!
  "The field under `key`, of the bot `bot`, rendered with the `text`
   it holds while the rows are narrowed by `query`: the server is
   told after the pause when the two differ (`due?`). Called from a
   render, so it sends nothing itself: a command changes the store,
   and the store renders."
  [key bot text query]
  (let [entry (get (swap! fields* update key seen text query) key)]
    (when (and (due? entry) (nil? (:timer entry)))
      (swap! fields* assoc-in [key :timer]
             (js/setTimeout #(ask! key bot) pause)))))
