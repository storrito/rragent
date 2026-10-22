(ns rragent.machine
  "The bots of this process: the one harness of the machine
   (`rragent.harness`) and, in its storage, one conversation per bot.
   The bots are the ownerless conversations of the storage, listed
   through pi-durable (`scanConversations`, ascending by id, which is
   the order they were made in; a subagent's conversation is owned by
   a task and is none), less the ones deleted, which the session
   document `rragent.bots` names: a deleted bot is hidden, its
   conversation stays, its folder goes to the trash. The root
   conversation is the first bot. Each open bot is a map in the one
   atom (`rragent.db`, under `[:bots id]`): the view value of its
   conversation and the keys of its pane, which the page reads, and
   the handles of its runtime; its artifacts pane is followed
   (`rragent.artifacts`, `rragent.projects`, `rragent.render`), its
   REPL worker made at its first program (`rragent.codemode-worker`).
   The harness map of the machine is the atom's `:machine`. A bot's
   name is its definition's (`name:` of its `AGENT.md`), as the reload
   last read it; when it was made, the birth time of its folder; when
   it was last used, the time of the newest entry of its conversation.
   A new bot's conversation is made here (`create!`) or by the `bot`
   tool in its own commit (`rragent.tools.bot`), and `adopt!` gives it
   its folder and definition, the assistant's text with the
   instructions on top."
  (:require ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.bots :as bots]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.projects :as projects]
            [rragent.reload :as reload]
            [rragent.render :as render]
            [rragent.rframes.sse :as sse]
            [rragent.tools.bot :as bot-tool]))

(def Hidden
  "The ids of the deleted bots, a session document of the storage."
  (defineDoc #js {:kind "rragent.bots"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {:hidden #js []})}))

(declare adopt! listed)

(defn agent
  "The harness map of the machine."
  []
  (:machine @db/db))

(defn home
  []
  (:home (agent)))

(defn bot
  "The map of the open bot `id`, nil for none."
  [id]
  (get-in @db/db (db/bot-path id)))

(defn bots
  "The open bots by id."
  []
  (:bots @db/db))

(defn- running?
  "Whether a run goes in the view value: the `pi.live` document has
   one."
  [^js value]
  (boolean (some-> value .-docs (aget "pi.live") .-run)))

(defn- value!
  "A new view value of the bot: into its map, with whether a run goes;
   when the run just ended, the pane shows what the run made
   (`rragent.artifacts/refollow!`, the screen stays put while a run
   goes)."
  [path ^js value]
  (let [was (:working? (db/at @db/db path))
        now (running? value)]
    (swap! db/db db/update-at path assoc :value value :working? now)
    (when (and was (not now))
      (artifacts/refollow! db/db path))))

(defn- start-pane!
  "The bot's map follows the bot: the view value now and every one
   after it, the artifacts folder watched, the projects in it run while
   someone looks at the page, the renders kept, the uploads folder
   known. Answers the handles and what ends the following."
  [id view]
  (let [path (db/bot-path id)
        work (home/work-dir (home/bot-dir (home) id))
        dir (home/artifacts-dir work)
        base (str (bots/url id) artifacts/url-prefix)
        projects (projects/handle {:db db/db
                                   :path path
                                   :dir dir
                                   :base base
                                   :shown? (fn [] (pos? (sse/connections)))})]
    (swap! db/db db/update-at path assoc :value (.-value view) :working? (running? (.-value view)))
    (let [unsubscribe (.subscribe view (fn [value] (value! path value)))
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
            handles (start-pane! id view)]
        (swap! db/db update-in path merge {:id id :conversation conversation :view view} handles)
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
  (when-let [{:keys [stop!]} (bot id)]
    (when stop!
      (stop!))
    (harness/forget-conversation! (js/Number id))
    true))

(defn ^:async conversations
  "The ownerless conversations of the storage, the bots, ascending by
   id: every conversation less the ones a task owns (a subagent's) and
   the forks. Read through a commit that changes nothing, where the
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

(defn ^:async hidden
  "The ids of the deleted bots, strings."
  [harness]
  (set (map str (array-seq (or (some-> (await (.snapshot harness Hidden harness/context)) .-hidden) #js [])))))

(defn ^:async hide!
  "The bot `id` marked deleted in the session document."
  [harness id]
  (await (.commit harness
                  (^:async fn [tx]
                    (let [doc (await (.doc tx Hidden))]
                      (.push (.-hidden doc) (js/Number id))))
                  harness/context)))

(defn ^:async open!
  "The machine of the home opened: the harness over its one storage
   (`rragent.harness/open`, which moves a home from before the bots
   into the layout and gives the first bot the assistant's definition
   when it has none), then every bot of the storage that is not
   deleted, in the order they were made. `opts` as `harness/open`
   takes them: `:home` (RRAGENT_HOME without), `:models`, `:provider`,
   `:storage`, `:gateway`, `:retry`. Answers the bots."
  [opts]
  (let [agent (await (harness/open (assoc opts :extensions [(bot-tool/extension {:adopt! #'adopt! :list #'listed})])))]
    (when-let [id (:migrated agent)]
      (println (str "the agent of " (:home agent) " is the bot " id " now")))
    (swap! db/db assoc :machine agent)
    (let [hidden (await (hidden (:harness agent)))
          ids (remove hidden (await (conversations (:harness agent))))]
      (doseq [id ids]
        (await (open-bot! id)))
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
  "Whether any bot runs, or renders."
  []
  (boolean (some (fn [{:keys [view render-handle]}]
                   (or (and view (harness/busy? {:view view}))
                       (and render-handle (render/any-running? render-handle))))
                 (vals (bots)))))

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
   generic one and is not the machine's first bot, \"New bot\" with the
   time it was made (an i18n tuple), since it names itself after its
   first answer."
  [id]
  (let [{:keys [definition]} (bot id)]
    (if (and definition
             (definition/generic? definition)
             (not= (str id) (str bots/root-id)))
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

(defn listed
  "The bots in the order they were made, each `{:id :name :label
   :state :created :used :deleting? :renaming?}`: the name of the
   definition, the label the page shows, the last two the sidebar's."
  []
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
                :renaming? (boolean renaming?)}))))

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
   over the assistant's text with `:name`, `:instructions` and
   `:skill`; with none of them the assistant's text as it is, and the
   bot names itself after its first answer), opened and followed.
   Answers `{:id :name}`, the name the definition's."
  [id {:keys [skill] :as given}]
  (bots/check-skill! skill)
  (bots/write-definition! (home) id (bots/definition-text (bots/assistant-text (home)) given))
  (await (open-bot! id))
  {:id (str id) :name (name-of id)})

(defn ^:async create!
  "A new bot: an ownerless conversation of the storage, then
   `adopt!`; `{}` for one from the assistant's definition as it is."
  [{:keys [skill] :as given}]
  (bots/check-skill! skill)
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

(defn ^:async delete!
  "The bot hidden: closed, marked deleted in the session document,
   its folder moved to the trash; its conversation stays in the
   storage. The last visible bot stays. Answers `:deleted`, `:last` or
   `:missing`."
  [id]
  (cond
    (nil? (bot id)) :missing
    (= 1 (count (bots))) :last
    :else (do (await (close-bot! id))
              (await (hide! (:harness (agent)) id))
              (bots/trash! (home) id)
              :deleted)))

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
