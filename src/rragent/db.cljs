(ns rragent.db
  "The atoms of the page's state: what the page is a function of.
   `db` is the page's own: the running version and a newer release
   (`rragent.update`), the bot each tab shows, a delete or a rename
   under way in the sidebar (`rragent.page`). And one atom per bot, by
   its `work` folder (`pane`): the view value of its conversation
   (`rragent.machine` keeps it there) and the keys of its pane
   (`rragent.artifacts`, `rragent.projects`, `rragent.render`); a tool
   finds its bot's atom by the folder its call works in. The watch of
   `rragent.rframes.sse` on each of them pushes a frame to every open
   tab after a change. Its own namespace, so that a tool can write
   into it without depending on the page."
  (:require ["node:path" :as path]))

(defonce db
  (atom {}))

(defonce ^{:doc "The atoms of the bots by their work folder."}
  panes
  (atom {}))

(defn pane
  "The atom of the bot whose tools work in `work`, made on first use."
  [work]
  (let [work (path/resolve (str work))]
    (or (get @panes work)
        (get (swap! panes (fn [m] (if (get m work) m (assoc m work (atom {}))))) work))))

(defn forget-pane!
  [work]
  (swap! panes dissoc (path/resolve (str work))))
