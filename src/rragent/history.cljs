(ns rragent.history
  "The transcript before a bot's active context: what a compaction, or
   a reset, took out of the model's view. pi-durable keeps those entries
   in the storage, the view value carries the active context alone
   (its first entry the head marker, a `pi.compaction` or `pi.reset`,
   with `head` the first entry it keeps). The page shows them above the
   marker's note, so that the person reads the whole conversation while
   the model reads the summary (`rragent.machine/refresh-history!`,
   `rragent.page/conversation`)."
  (:require [rragent.harness :as harness]))

(defn marker
  "The head marker of the view `value`: its first entry, when that one
   heads the context; nil when the context starts at the transcript's
   start, and there is no history."
  [^js value]
  (let [entry (some-> value .-entries (aget 0))]
    (when (some-> entry .-head)
      entry)))

(defn ^:async before!
  "The entries of `conversation` before the entry `head`, the first one
   the active context keeps, oldest first, as the data the view reads
   (`rragent.view/items`): pages of 200 from the storage."
  [conversation head]
  (loop [cursor js/undefined
         found []]
    (let [page (await (.entries conversation #js {:maxEntryId (dec head) :order "ascending"} 200 cursor harness/context))
          items (array-seq (.-items page))
          found (into found (map #(js->clj % :keywordize-keys true)) items)]
      (if (and (.-next page) (seq items))
        (recur (.-next page) found)
        found))))
