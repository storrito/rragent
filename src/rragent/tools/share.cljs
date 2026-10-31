(ns rragent.tools.share
  "The `share` tool: a folder of the bot's artifacts behind a public
   link (`rragent.shares`), for the people the person wants to show
   it to; stopped and listed by the bot too. A run shares as the bot
   would: the folder is the bot's, and so is the share."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [clojure.string :as str]
            [rragent.shares :as shares]
            [rragent.tools.work :as work]))

(def description
  (str "A public link to a folder under artifacts/, for a customer or anyone the person wants to show something to. "
       "`create` with the folder answers the link; give it to the person to pass on. Anyone with the link sees every file in the folder, "
       "as it is now and as it changes later: the link opens the folder's index.html (nothing without one), each file by its path below it; "
       "hidden files and a notes/ folder stay private. Put only what is meant for the reader into a shared folder; drafts and notes go elsewhere. "
       "`until`, an ISO date or date-time, is the moment the link stops working; without it the link works until `stop`. "
       "Creating a share of a folder that has one answers the same link. `stop` with the folder ends it at once (what was downloaded stays downloaded); "
       "`list` shows your shares."))

(defn- parse-until
  "The moment `until` names, ms; a throw for a string that is no date."
  [until]
  (when (seq until)
    (let [ms (js/Date.parse until)]
      (when (js/isNaN ms)
        (throw (js/Error. (str "until is no date: " until))))
      ms)))

(def tool
  (defineTool
   #js {:name "share"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "stop") (.Literal Type "list")])
                                       :folder (.Optional Type (.String Type #js {:description "The folder under artifacts/, such as artifacts/gallery"}))
                                       :until (.Optional Type (.String Type #js {:description "When the link stops working, an ISO date or date-time; none for until stopped"}))})
        :execute (^:async fn [args api call-context]
                   (let [bot (await (work/bot-id api call-context))
                         folder (shares/folder-name (.-folder args))]
                     (case (.-action args)
                       "create" (do (when (str/blank? folder)
                                      (throw (js/Error. "create needs the folder")))
                                    (when-not (shares/shareable? (work/artifacts-dir api) folder)
                                      (throw (js/Error. (str "artifacts/" folder " is no folder you can share: a folder below artifacts/, not hidden, not notes/"))))
                                    (let [share (await (shares/create! api bot folder (parse-until (.-until args)) call-context))]
                                      (work/text (str "Shared " (shares/line share) ". Anyone with the link sees every file in the folder."))))
                       "stop" (do (when (str/blank? folder)
                                    (throw (js/Error. "stop needs the folder")))
                                  (work/text (if (await (shares/stop! api bot folder call-context))
                                               (str "artifacts/" folder "/ is no longer shared.")
                                               (str "artifacts/" folder "/ was not shared."))))
                       "list" (let [listed (shares/listed (await (shares/read api call-context)) bot)]
                                (work/text (if (empty? listed)
                                             "No shares"
                                             (str/join "\n" (map shares/line listed))))))))}))
