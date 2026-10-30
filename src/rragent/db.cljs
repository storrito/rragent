(ns rragent.db
  "The one atom of the state: `db`, what the page is a function of and
   where the process keeps what it holds, in the Clojure way of one
   place of mutable state. Its keys:

       :machine  the harness map of `rragent.harness/open` (the harness,
                 the home, the models, the REPL handle, the root)
       :updater  the handle of `rragent.update`, whose `:update` key below
                 is the banner's state
       :update   the running version and a newer release, the banner
                 and the Updates section of the settings
       :tabs     {tabid bot-id}, the bot each tab picked in the sidebar
       :settings-open  #{tabid}, the tabs showing the settings in place
                 of the chat
       :expanded {tabid #{item-id}}, the folded rows of the chat (tool
                 calls, hook calls, schedule runs) each tab has open; a
                 frame carries their body only for those
       :bots     {\"<conversation id>\" bot}, every open bot, by its id as a string

   A bot's map holds what the page reads, its value keys, and the
   handles of its runtime, which the page never reads: the value keys
   are `:value` (the view value of its conversation, pi-durable's
   `viewState`), `:history` (the entries before the active context,
   read after a compaction, `rragent.history`), `:definition` (as
   `rragent.reload` last applied it),
   `:schedules/next`, `:failed`, the keys of its pane
   (`:artifacts/list`, `:artifacts/rev`, `:artifacts/current`,
   `:artifacts/folder`, `:projects`, `:renders`) and the sidebar's
   `:deleting?` and `:renaming?`; the handles are `:conversation`,
   `:view`, `:worker` (`rragent.codemode-worker`), `:reload`
   (`{:home :provider :text}`), `:schedule-source`
   (`rragent.schedule`), `:dir`, `:work`, `:uploads`,
   `:projects-handle`, `:render-handle` and `:stop!`. The watch of
   `rragent.page` pushes a frame to the tabs showing a bot whose value
   keys changed, to every tab for the page's own keys, and nothing for
   a handle (`rragent.page/bot-value-keys`).

   The namespaces of the pane write under a bot's path, `[:bots id]`,
   with `at` and `update-at`: `update-in` with an empty path would
   take `nil` as the key, so an empty path means the map itself, for
   the tests that use a plain atom. Its own namespace, so that a tool
   can write into it without depending on the page.")

(defonce db
  (atom {}))

(defn at
  "The value under `path` of `m`; `m` itself for an empty path."
  [m path]
  (if (seq path)
    (get-in m path)
    m))

(defn update-at
  "`m` with `f` applied under `path`, to `m` itself for an empty path."
  [m path f & args]
  (if (seq path)
    (apply update-in m path f args)
    (apply f m args)))

(defn bot-path
  "Where the bot of the conversation `id` is in the atom."
  [id]
  [:bots (str id)])
