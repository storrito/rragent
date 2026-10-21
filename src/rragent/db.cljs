(ns rragent.db
  "The atoms of the page's state: what the page is a function of.
   `db` is the page's own: the running version and a newer release
   (`rragent.update`), the bot each tab shows, a delete or a rename
   under way in the sidebar (`rragent.page`). And one atom per bot, by
   the id of its conversation (`pane`): the view value of the
   conversation (`rragent.machine` keeps it there) and the keys of its
   pane (`rragent.artifacts`, `rragent.projects`, `rragent.render`); a
   tool finds its bot's atom by the conversation of its call
   (`rragent.tools.work`). The watch of `rragent.rframes.sse` on each
   of them pushes a frame to every open tab after a change. Its own
   namespace, so that a tool can write into it without depending on
   the page.")

(defonce db
  (atom {}))

(defonce ^{:doc "The atoms of the bots by the id of their conversation, a string."}
  panes
  (atom {}))

(defn pane
  "The atom of the bot whose conversation is `id`, made on first use."
  [id]
  (let [id (str id)]
    (or (get @panes id)
        (get (swap! panes (fn [m] (if (get m id) m (assoc m id (atom {}))))) id))))

(defn forget-pane!
  [id]
  (swap! panes dissoc (str id)))

(defn forget-panes!
  []
  (reset! panes {}))
