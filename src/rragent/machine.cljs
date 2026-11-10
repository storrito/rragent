(ns rragent.machine
  "The bots of this process: the one harness of the machine
   (`rragent.harness`) and, in its storage, one conversation per bot
   (docs/bots.md). The bots are the ownerless conversations of the
   storage, listed through pi-durable (`scanConversations`, ascending
   by id, which is the order they were made in; a task's, a run's or a
   subagent's conversation is owned by a task and is none), less the
   ones deleted, which the session document `rragent.bots` names: a
   deleted bot is hidden, its conversation stays, its folder goes to
   the trash. The same document holds the structure of the machine
   (`Structure`, read as data by `structure`, cached in the atom as
   `:structure` for the page): each bot's parent, the bot that made
   it with the `bot` tool, and its project, written in the commit
   that made its conversation (`relate-in!`); the archived bots, whose
   chat the manager of before said was over; and what the
   machine was before every bot was one of equals: the chat it began
   with (`manager`, the manager's conversation and then the one
   conversation's, `first-chat-id`), and what the one conversation
   adopted and the bots got back (`rragent.migration`). The machine
   has one wiki, seeded at the open (`rragent.wiki`), which every bot
   reads and writes. The root conversation is the first bot. Each open
   bot is a map in the one atom (`rragent.db`, under `[:bots id]`):
   the view value of its conversation and the keys of its pane, which
   the page reads, and the handles of its runtime; its artifacts pane
   is followed (`rragent.artifacts`, `rragent.projects`,
   `rragent.render`), its REPL worker made at its first program
   (`rragent.codemode-worker`); its runs (`rragent.runs`) and its
   tasks (`rragent.tasks`), the newest `runs-shown` as `:runs`, read
   again whenever one changes, and the view value of a run a tab shows
   under `:runs/values` (`open-run!`).
   The harness map of the machine is the atom's `:machine`. A bot's
   name is its definition's (`name:` of its `AGENT.md`), as the reload
   last read it; when it was made, the birth time of its folder; when
   it was last used, the time of the newest entry of its conversation.
   A new bot's conversation is made here (`create!`) or by the `bot`
   tool in its own commit (`rragent.tools.bot`), and `adopt!` gives it
   its folder and definition, a file of its name, model and job
   (`rragent.bots/definition-text`); the rules every bot works by are
   the bundle's, read in front of the file (`rragent.reload`)."
  (:require ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.away :as away]
            [rragent.bots :as bots]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.history :as history]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.migration :as migration]
            [rragent.models :as models]
            [rragent.projects :as projects]
            [rragent.reload :as reload]
            [rragent.render :as render]
            [rragent.rframes.sse :as sse]
            [rragent.runs :as runs]
            [rragent.skills :as skills]
            [rragent.tasks :as tasks]
            [rragent.tools.bot :as bot-tool]
            [rragent.transcript :as transcript]
            [rragent.wiki :as wiki]))

(def Structure
  "The structure of the machine, a session document of the storage:
   `hidden`, the ids of the deleted bots; `parents`, child id to
   parent id; `projects`, child id to the name of its project;
   `archived`, the ids of the archived bots. And the machine's past:
   `manager`, the id of the chat it began with, the manager's and then
   the one conversation's (`first-chat-id`); `topics`, `botTopics`
   and `migrated`, what the one conversation adopted, and `restored`
   and `restoredBots`, what the bots got back (`rragent.migration`);
   `advanced`, a switch of the settings of before, read by nothing. A
   document from before the manager has `hidden` alone; the readers
   take a missing key as empty."
  (defineDoc #js {:kind "rragent.bots"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {:hidden #js [] :archived #js [] :parents #js {} :projects #js {} :manager nil})}))

(declare adopt! listed first-chat-id name-of used-at refresh-spend! refresh-structure! dependents delete! transcript)

(defn agent
  "The harness map of the machine."
  []
  (:machine @db/db))

(defn home
  []
  (:home (agent)))

(defn context-window
  "The context a conversation on the model `id` keeps, in tokens: the
   model's window from the models registry of the agent, at most the
   budget past which rragent compacts (`rragent.harness/budget-for`),
   so that the page's ring fills towards the compaction; nil for a model
   it does not know."
  [id]
  (let [{:keys [models provider]} (agent)]
    (when (and models (seq (str id)))
      (try
        (when-let [window (some-> (.getModel ^js models (str (or provider "openrouter")) (str id)) .-contextWindow)]
          (min window (harness/budget-for window)))
        (catch :default _
          nil)))))

(defn model-cost
  "What a million tokens cost on the model `id`, `{:input :output}` in
   dollars, from the models registry of the agent; nil for a model it
   does not know."
  [id]
  (let [{:keys [models provider]} (agent)]
    (when (and models (seq (str id)))
      (try
        (when-let [cost (some-> (.getModel ^js models (str (or provider "openrouter")) (str id)) .-cost)]
          {:input (.-input cost) :output (.-output cost)})
        (catch :default _
          nil)))))

(defn model-choices
  "The models the page offers (`rragent.models/choices`), each with its
   `:cost` when the registry knows the model."
  []
  (mapv #(assoc % :cost (model-cost (:id %))) models/choices))

(defn bot
  "The map of the open bot `id`, nil for none."
  [id]
  (get-in @db/db (db/bot-path id)))

(defn bots
  "The open bots by id."
  []
  (:bots @db/db))

(def runs-shown
  "How many of a bot's runs the page lists, newest first."
  50)

(defn refresh-runs!
  "The newest runs of the bot `id` read into its map, `:runs`, for the
   page, its tasks among them (`rragent.tasks/as-run`), newest first.
   A promise."
  [id]
  (let [id (str id)
        {:keys [harness]} (agent)]
    (if (and harness (bot id))
      (-> (js/Promise.all #js [(runs/listed harness (js/Number id) runs-shown harness/context)
                               (tasks/listed harness id runs-shown harness/context)])
          (.then (fn [both]
                   (let [[runs tasks] (array-seq both)
                         rows (->> (concat runs (map tasks/as-run tasks))
                                   (sort-by #(- (or (:started %) 0)))
                                   (take runs-shown)
                                   (vec))]
                     (when (bot id)
                       (swap! db/db assoc-in (conj (db/bot-path id) :runs) rows)))))
          (.catch (fn [e] (js/console.error "[runs]" (str e)))))
      (js/Promise.resolve nil))))

(defn ^:async open-run!
  "The run `run-id` of the bot `id` followed for the page: the view
   value of its conversation under `[:runs/values run-id]` of the
   bot's map, the text of the definition it works from under
   `[:runs/definitions run-id]`, the handles under `[:runs/open
   run-id]`; nothing new when it is followed already. Answers the
   run, nil when the bot has no such run; a run that waits for its
   turn has no conversation yet and is answered without a view."
  [id run-id]
  (let [id (str id)
        path (db/bot-path id)
        {:keys [harness]} (agent)
        run (when (and harness (bot id))
              (or (await (runs/run harness run-id harness/context))
                  (some-> (await (tasks/task harness run-id harness/context)) tasks/as-run)))]
    (when (and run (= (js/Number id) (js/Number (:bot run))))
      (when (:definition run)
        (swap! db/db assoc-in (conj path :runs/definitions run-id) (:definition run)))
      (when (and (:conversation run) (not (get-in @db/db (conj path :runs/open run-id))))
        (let [handle (await (.conversation harness (:conversation run) harness/context))
              view (await (.viewState handle harness/context))
              unsubscribe (.subscribe view (fn [value] (swap! db/db assoc-in (conj path :runs/values run-id) value)))]
          (swap! db/db update-in path
                 (fn [bot]
                   (-> bot
                       (assoc-in [:runs/values run-id] (.-value view))
                       (assoc-in [:runs/open run-id] {:conversation handle
                                                      :view view
                                                      :stop! (fn []
                                                               (unsubscribe)
                                                               (.dispose view))}))))))
      run)))

(defn run-conversation
  "The conversation handle of the run `run-id` of the bot `id`, when a
   tab follows it."
  [id run-id]
  (get-in (bot id) [:runs/open run-id :conversation]))

(defn- running?
  "Whether a run goes in the view value: the `pi.live` document has
   one."
  [^js value]
  (boolean (some-> value .-docs (aget "pi.live") .-run)))

(defn- ^:async refresh-history!
  "The entries before the bot's active context into its map as
   `:history`, `{:head <the marker's id> :entries [...]}`
   (`rragent.history/before!`), when the context's head marker is
   another than the one the history was read for: after a compaction
   the page shows the summarized messages above the note. Without a
   marker there is no history, and the key goes. One read at a time
   (`:history-loading`, a handle: no frame for it)."
  [path conversation ^js value]
  (let [marker (history/marker value)
        head-id (some-> marker .-id)
        {:keys [history history-loading]} (db/at @db/db path)]
    (cond
      (nil? marker) (when history
                      (swap! db/db db/update-at path dissoc :history))
      (or (= head-id (:head history)) (= head-id history-loading)) nil
      :else (do (swap! db/db db/update-at path assoc :history-loading head-id)
                (try
                  (let [entries (await (history/before! conversation (.-head marker)))]
                    (when (db/at @db/db path)
                      (swap! db/db db/update-at path assoc :history {:head head-id :entries entries})))
                  (catch :default error
                    (js/console.error "[history]" (str "the history of the bot " (last path) " was not read:") error))
                  (finally
                    (swap! db/db db/update-at path dissoc :history-loading)))))))

(defn- value!
  "A new view value of the bot: into its map, with whether a run goes
   (`:working?`); when the run just ended, the bot's cost is read
   again (`refresh-spend!`); the history before the context read when
   its head marker changed (`refresh-history!`)."
  [path conversation ^js value]
  (let [was (:working? (db/at @db/db path))
        now (running? value)]
    (swap! db/db db/update-at path assoc :value value :working? now)
    (when (and was (not now))
      (refresh-spend! (last path)))
    (refresh-history! path conversation value)))

(defn- refresh-spend!
  "The bot's cost so far, its subagents' and runs' included
   (`rragent.harness/spend`), into its map as `:spend`, for the page."
  [id]
  (-> (harness/spend (:harness (agent)) (js/Number id))
      (.then (fn [spend]
               (when (bot id)
                 (swap! db/db update-in (db/bot-path id) assoc :spend spend))))
      (.catch (fn [error]
                (js/console.error "[bots]" (str "the cost of the bot " id " was not read:") error)))))

(defn- start-pane!
  "The bot's map follows the bot: the view value now and every one
   after it, the artifacts folder watched, the projects in it run while
   someone looks at the page, the renders kept, the uploads folder
   known. Answers the handles and what ends the following."
  [id conversation view]
  (let [path (db/bot-path id)
        work (home/work-dir (home/bot-dir (home) id))
        dir (home/artifacts-dir work)
        base (str (bots/url id) artifacts/url-prefix)
        projects (projects/handle {:db db/db
                                   :path path
                                   :dir dir
                                   :base base
                                   :shown? (fn [] (pos? (sse/connections)))})]
    (value! path conversation (.-value view))
    (let [unsubscribe (.subscribe view (fn [value] (value! path conversation value)))
          stop-artifacts (artifacts/watch! db/db path dir)
          stop-projects (projects/watch! projects)]
      {:dir dir
       :work work
       :projects-handle projects
       :render-handle (render/handle {:db db/db :path path :dir dir})
       :uploads (home/uploads-dir work)
       :stop! (fn []
                (unsubscribe)
                (stop-artifacts)
                (stop-projects)
                (.dispose view))})))

(defn ^:async open-bot!
  "The bot of the conversation `id` opened and followed: the
   conversation's handle (the root's for the first bot), its folder
   registered with the reload, its view. One that does not open (no
   definition in its folder) is kept with its reason, said on stderr
   and on its page."
  [id]
  (let [{:keys [harness root] :as agent} (agent)
        id (str id)
        path (db/bot-path id)]
    (try
      (let [conversation (if (= id (str (.-id root)))
                           root
                           (or (await (.conversation harness (js/Number id) harness/context))
                               (throw (js/Error. (str "no conversation " id " in the storage")))))
            definition (await (harness/register-conversation! agent conversation))
            _ (when-not definition
                (throw (js/Error. (str "no definition in " (home/bot-dir (home) id)))))
            view (await (.viewState conversation harness/context))
            handles (start-pane! id conversation view)]
        (swap! db/db update-in path merge {:id id :conversation conversation :view view} handles)
        (refresh-spend! id)
        (await (refresh-runs! id))
        (bot id))
      (catch :default e
        (js/console.error "[bots]" (str "the bot " id " did not open: " (.-message e)))
        (swap! db/db update-in path merge {:id id :failed (.-message e)})
        (bot id)))))

(defn ^:async close-bot!
  "The bot's following ended and its map taken out of the atom, the
   process's hold on its conversation let go; nothing for a bot that
   is not open."
  [id]
  (when-let [{:keys [stop! runs/open]} (bot id)]
    (when stop!
      (stop!))
    (doseq [{:keys [stop!]} (vals open)]
      (stop!))
    (harness/forget-conversation! (js/Number id))
    true))

(defn ^:async conversations
  "The ownerless conversations of the storage, the bots, ascending by
   id: every conversation less the ones a task owns (a task's, a
   run's, a subagent's) and the forks. Read through a commit that changes nothing, where the
   transaction scans them."
  [harness]
  (loop [cursor js/undefined
         found []]
    (let [page (await (.commit harness (fn [tx] (.scanConversations tx #js {} 200 cursor)) harness/context))
          items (array-seq (.-items page))
          found (into found (keep (fn [record]
                                    (when-not (or (.-owner record) (.-parent record))
                                      (str (.-id record))))
                                  items))]
      (if (and (.-next page) (seq items))
        (recur (.-next page) found)
        found))))

(defn ^:async structure
  "The session document as data, ids as strings: `{:hidden #{} :archived
   #{} :parents {child parent} :projects {child name} :manager id
   :bot-topics {bot slug} :restored-bots {bot slug} :migrated version
   :restored version}`; `:manager` the chat the machine began with,
   nil for a machine made since; `:bot-topics` the bots the one
   conversation adopted as topics, `:restored-bots` those of them
   that got their work back; `:migrated` and `:restored` nil before
   the adoption and before the bots came back (`rragent.migration`)."
  [harness]
  (let [doc (await (.snapshot harness Structure harness/context))
        ids (fn [xs] (set (map str (array-seq (or xs #js [])))))
        pairs (fn [o] (into {} (map (fn [[k v]] [(str k) (str v)])) (js/Object.entries (or o #js {}))))
        manager (some-> doc .-manager)]
    {:hidden (ids (some-> doc .-hidden))
     :archived (ids (some-> doc .-archived))
     :parents (pairs (some-> doc .-parents))
     :projects (pairs (some-> doc .-projects))
     :bot-topics (pairs (some-> doc .-botTopics))
     :restored-bots (pairs (some-> doc .-restoredBots))
     :migrated (some-> doc .-migrated)
     :restored (some-> doc .-restored)
     :manager (when (some? manager) (str manager))}))

(defn ^:async refresh-structure!
  "The structure read again into the atom, `:structure`, which the
   page and the tools read without a wait; answers it."
  [harness]
  (let [s (await (structure harness))]
    (swap! db/db assoc :structure s)
    s))

(defn current-structure
  "The structure as last read (`refresh-structure!`)."
  []
  (or (:structure @db/db) {}))

(defn ^:async hidden
  "The ids of the deleted bots, strings."
  [harness]
  (:hidden (await (structure harness))))

(defn- push-id!
  "The id into the array under `key` of the document draft `doc`, made
   when the document is from before the key; nothing when it is there."
  [doc key id]
  (when-not (aget doc key)
    (aset doc key #js []))
  (let [ids (aget doc key)]
    (when-not (some #{(js/Number id)} (array-seq ids))
      (.push ids (js/Number id)))))

(defn ^:async hide!
  "The bot `id` marked deleted in the session document."
  [harness id]
  (await (.commit harness
                  (^:async fn [tx]
                    (push-id! (await (.doc tx Structure)) "hidden" id))
                  harness/context))
  (await (refresh-structure! harness)))

(defn ^:async relate-in!
  "In the transaction `tx` that makes the bot `child`: its `parent`
   (the id of the bot that makes it) and its `project` (a name) into
   the session document, so that both are there with the conversation
   or not at all."
  [tx child {:keys [parent project]}]
  (let [doc (await (.doc tx Structure))]
    (when-not (.-parents doc)
      (aset doc "parents" #js {}))
    (when-not (.-projects doc)
      (aset doc "projects" #js {}))
    (when (some? parent)
      (aset (.-parents doc) (str child) (js/Number parent)))
    (when (seq (str project))
      (aset (.-projects doc) (str child) (str project)))))

(defn first-chat-id
  "The id of the chat the machine began with (the `manager` of the
   structure: the manager's conversation, then the one conversation's),
   as the structure was last read; nil for a machine made since, whose
   bots are all of a kind. It is a bot like the others: only its label
   and the page's links of before know it (`label-of`,
   `rragent.page`)."
  []
  (:manager (current-structure)))

(defn children-of
  "The ids of the bots whose parent is `id`, the deleted ones left out."
  [id]
  (let [{:keys [parents hidden]} (current-structure)]
    (->> parents
         (keep (fn [[child parent]] (when (and (= (str id) parent) (not (contains? hidden child))) child)))
         (sort-by js/Number)
         (vec))))

(defn ^:async open!
  "The machine of the home opened: the harness over its one storage
   (`rragent.harness/open`, which moves a home from before the bots
   into the layout first, and gives the first bot the assistant's file
   when it has none), the machine's wiki in its place (`rragent.wiki`:
   the one a bot kept moved there, a new one seeded), then every bot
   of the storage that is not deleted, in the order they were made,
   and, once, what the one conversation adopted given back to the
   bots (`rragent.migration/restore!`). `opts` as `harness/open` takes
   them: `:home` (RRAGENT_HOME without), `:models`, `:provider`,
   `:storage`, `:gateway`, `:retry`; and `:wiki?`, false for a machine
   without a wiki, which the tests of the other parts open (every real
   machine has one). Answers the bots."
  [opts]
  (let [agent (await (harness/open (assoc opts :extensions [(bot-tool/extension {:adopt! #'adopt!
                                                                                 :list #'listed
                                                                                 :check-skill! (fn [skill] (skills/check! (home) skill))
                                                                                 :relate-in! #'relate-in!
                                                                                 :transcript #'transcript})])))]
    (swap! db/db assoc :machine agent)
    (runs/watch! (fn [bot-id] (refresh-runs! bot-id)))
    (let [{:keys [hidden manager]} (await (refresh-structure! (:harness agent)))
          home (:home agent)
          wiki? (not (false? (:wiki? opts)))
          _ (when wiki?
              (when (and manager (wiki/adopt! home (home/bot-dir home manager)))
                (println (str "the wiki of the bot " manager " is the machine's now, in " (wiki/dir home))))
              (wiki/seed! home (js/Date.now)))
          ids (remove hidden (await (conversations (:harness agent))))]
      (doseq [id ids]
        (await (open-bot! id)))
      ;; what the one conversation adopted goes back to the bots, once (docs/bots.md §8)
      (when-let [{:keys [bots first-chat]} (await (migration/restore! {:harness (:harness agent)
                                                                         :home home
                                                                         :structure-doc Structure
                                                                         :ids ids
                                                                         :names (into {} (map (fn [id] [id (name-of id)])) ids)
                                                                         :context harness/context}))]
        (when first-chat
          (println (str "the chat of the bot " first-chat " is a bot like the others now")))
        (when (seq bots)
          (println (str "restored " (count bots) " bots of before: " (str/join ", " (map :name bots)))))
        (await (refresh-structure! (:harness agent)))
        ;; the first chat's file was written anew: read it now, not at its next turn
        (when-let [conversation (:conversation (bot first-chat))]
          (await (reload/reload! conversation harness/context))))
      (mapv bot ids))))

(defn ^:async close-all!
  []
  (doseq [id (keys (bots))]
    (await (close-bot! id)))
  (when-let [agent (agent)]
    (await (harness/close agent)))
  (swap! db/db dissoc :machine))

(defn codemode
  "The REPL handle of the process."
  []
  (:codemode (agent)))

(defn busy?
  "Whether any bot runs, or renders, or a run of one goes or waits
   (`rragent.runs`), or a bot's conversation is compacted or will be
   once the person is away (`rragent.away`)."
  []
  (boolean (or (runs/active?)
               (away/pending? (fn [id] (some? (bot id))))
               (some (fn [{:keys [view render-handle]}]
                       (or (and view (harness/busy? {:view view}))
                           (and view (harness/compacting? {:view view}))
                           (and render-handle (render/any-running? render-handle))))
                     (vals (bots))))))

(defn away-options
  "What `rragent.away/watch!` takes for the machine's bots: a
   conversation is a bot's when the machine has it open, idle when no
   run of it goes, and compacted through the harness."
  []
  {:known? (fn [id] (some? (bot id)))
   :idle? (fn [id] (not (some-> (bot id) :view (as-> view (harness/busy? {:view view})))))
   :compact! (fn [id] (harness/compact-unless-compacting! (:harness (agent)) id "away"))})

(defn name-of
  "The bot's name: its definition's as the reload last read it
   (`rragent.definition/display-name`), else the file's, else its id."
  [id]
  (or (some-> (bot id) :definition definition/display-name)
      (bots/name-of (home) id)
      (str id)))

(defn- time-of
  "`14:16`, with the day before it when it is not today; the server's
   clock."
  [ms]
  (let [at (js/Date. ms)
        two (fn [n] (.padStart (str n) 2 "0"))
        today (js/Date.)]
    (str (when-not (= (.toDateString at) (.toDateString today))
           (str (.getFullYear at) "-" (two (inc (.getMonth at))) "-" (two (.getDate at)) " "))
         (two (.getHours at)) ":" (two (.getMinutes at)))))

(defn label-of
  "What the page calls the bot: its name, or, while it still has the
   generic one, \"New bot\" with the time it was made (an i18n
   tuple), since it names itself after its first answer. The chat the
   machine began with keeps its name, generic or not: the first bot
   (the root conversation), or, on a machine that had a manager or
   was one conversation, that one (`first-chat-id`), beside which the
   root is a bot nobody talked to."
  [id]
  (let [{:keys [definition]} (bot id)]
    (if (and definition
             (definition/generic? definition)
             (not= (str id) (str (or (first-chat-id) bots/root-id))))
      [:i18n :bot/new-at {:time (time-of (or (bots/created-at (home) id) (js/Date.now)))}]
      (name-of id))))

(defn state-of
  "`:failed` for a bot that did not open, `:running` while a run
   goes, `:asleep` when it idles until a scheduled run, `:idle`
   otherwise."
  [id]
  (let [{:keys [view failed schedules/next]} (bot id)]
    (cond
      (or (nil? view) failed) :failed
      (harness/busy? {:view view}) :running
      next :asleep
      :else :idle)))

(defn used-at
  "When the bot was last used: the time of the newest entry of its
   conversation that carries a message, nil for a conversation with
   none."
  [id]
  (when-let [view (:view (bot id))]
    (->> (array-seq (.-entries (.-value view)))
         (keep (fn [entry] (some-> (.-model entry) (aget 0) .-timestamp)))
         (last))))

(defn newest-entry
  "The id of the newest entry of the bot's conversation, nil for a
   conversation with none."
  [id]
  (when-let [view (:view (bot id))]
    (some-> (last (array-seq (.-entries (.-value view)))) .-id)))

(defn listed
  "The bots in the order they were made, each `{:id :name :label
   :state :created :used :deleting? :renaming? :parent :project
   :archived?}`: the name of the definition, the label the page shows,
   the sidebar's two flags, and what the structure says of the bot
   (`current-structure`)."
  []
  (let [{:keys [parents projects archived]} (current-structure)]
    (->> (vals (bots))
         (sort-by #(js/Number (:id %)))
         (mapv (fn [{:keys [id deleting? renaming?]}]
                 {:id id
                  :name (name-of id)
                  :label (label-of id)
                  :state (state-of id)
                  :created (bots/created-at (home) id)
                  :used (used-at id)
                  :deleting? (boolean deleting?)
                  :renaming? (boolean renaming?)
                  :parent (get parents id)
                  :project (get projects id)
                  :archived? (contains? archived id)})))))

(defn default-id
  "The bot the root of the page goes to: the one used last, else the
   first; nil without any."
  []
  (let [bots (listed)]
    (or (:id (last (sort-by :used (filter :used bots))))
        (:id (first bots)))))

(defn ^:async adopt!
  "The conversation `id`, made ownerless by `create!` or by the `bot`
   tool, given its folder and definition (`rragent.bots/definition-text`
   with `:name`, `:instructions` and `:skill`, the skill's description
   as the bot's; with none of them a new bot's file, and the bot names
   itself after its first answer; `:text`, a definition of its own,
   written as it is), opened and followed. Answers `{:id :name}`, the
   name the definition's."
  [id {:keys [skill text] :as given}]
  (skills/check! (home) skill)
  (let [taken (when (seq (str skill)) (skills/skill (home) skill))]
    (bots/write-definition! (home) id (or text (bots/definition-text (cond-> given
                                                                        taken (assoc :description (:description taken)))))))
  (await (open-bot! id))
  ;; the commit that made the conversation may have related the bot (`relate-in!`)
  (await (refresh-structure! (:harness (agent))))
  {:id (str id) :name (name-of id)})

(defn ^:async create!
  "A new bot: an ownerless conversation of the storage, then
   `adopt!`; `{}` for a new bot's file as it is."
  [{:keys [skill] :as given}]
  (skills/check! (home) skill)
  (let [conversation (await (.createConversation (:harness (agent)) #js {:ownership #js {:kind "ownerless"}} harness/context))]
    (await (adopt! (.-id conversation) given))))

(defn ^:async rename!
  "The `name:` of the bot's definition set (`rragent.bots/rename!`)
   and the conversation configured from the file at once
   (`rragent.reload`), so the page shows the name now."
  [id name]
  (let [name (bots/rename! (home) id name)]
    (when-let [{:keys [conversation]} (bot id)]
      (await (reload/reload! conversation harness/context)))
    name))

(defn ^:async set-metadata!
  "The `key` of the bot's definition metadata set to `value`
   (`rragent.bots/set-metadata!`) and the conversation configured from
   the file at once (`rragent.reload`), so the page shows the pick now."
  [id key value]
  (let [value (bots/set-metadata! (home) id key value)]
    (when-let [{:keys [conversation]} (bot id)]
      (await (reload/reload! conversation harness/context)))
    value))

(defn ^:async dependents
  "What depends on the bot `id`: `{:children [ids] :schedules [names]
   :hooks [names]}`, the bots it made that are not deleted, and its
   schedules and hooks, which would go on without anyone to read their
   runs. Empty vectors for a bot nothing depends on."
  [id]
  (let [harness (:harness (agent))
        names (fn [doc] (vec (sort (js/Object.keys (or doc #js {})))))]
    {:children (children-of id)
     :schedules (names (await (harness/schedules harness (js/Number id))))
     :hooks (names (await (.snapshot harness hooks/Hooks (js/Number id) harness/context)))}))

(defn ^:async delete!
  "The bot hidden: closed, marked deleted in the session document,
   its folder moved to the trash; its conversation stays in the
   storage. The last visible bot stays. Answers `:deleted`, `:last`
   or `:missing`. What depends on the bot (`dependents`) is the
   person's to look at: the page's row asks before it deletes."
  [id]
  (cond
    (nil? (bot id)) :missing
    (= 1 (count (bots))) :last
    :else (do (await (close-bot! id))
              (await (hide! (:harness (agent)) id))
              (bots/trash! (home) id)
              :deleted)))

(defn ^:async transcript
  "The chat of the bot `id` after the entry `since`, folded for a
   model (`rragent.transcript/read!`); throws for a bot the machine
   has not open."
  [id since]
  (let [{:keys [conversation]} (or (bot id) (throw (js/Error. (str "No bot " id " on this machine."))))]
    (await (transcript/read! conversation (or since 0)))))

(defn ^:async hook!
  "A hook's call tried on every open bot (`rragent.hooks/receive!`):
   the answer of the bot whose hook it is, `{:ok false}` when it is
   none's."
  [receive! name secret headers body]
  (loop [[{:keys [conversation]} & more] (vals (bots))]
    (if (nil? conversation)
      (if (seq more) (recur more) {:ok false})
      (let [answer (await (receive! {:harness (:harness (agent)) :conversation conversation} name secret headers body))]
        (if (:ok answer)
          answer
          (if (seq more) (recur more) {:ok false}))))))

(defn report
  "One line for the start: the bots and their names."
  []
  (let [bots (listed)]
    (str (count bots) (if (= 1 (count bots)) " bot: " " bots: ")
         (str/join ", " (map (fn [{:keys [id name state]}] (str name " (" id (when (= :failed state) ", failed") ")")) bots)))))
