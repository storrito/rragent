(ns rragent.db
  "The one atom of the page's state: what the page is a function of.
   `rragent.page` keeps the conversation's view value in it, the pane's
   namespaces (`rragent.artifacts`, `rragent.projects`, `rragent.render`)
   their keys, and the watch of `rragent.rframes.sse` pushes a frame to
   every open tab after a change. Its own namespace, so that a tool can
   write into it without depending on the page.")

(defonce db
  (atom {}))
