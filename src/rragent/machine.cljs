(ns rragent.machine
  "The bots of this process: every bot of the home opened
   (`rragent.harness`), one harness and one SQLite per bot, one REPL
   worker each over the one gateway discovery and the one models
   catalog of the process, their pages' state in the atoms of
   `rragent.db`, the artifacts pane of each followed
   (`rragent.artifacts`, `rragent.projects`, `rragent.render`). A new
   machine, or one from before the bots, gets its first bot here: the
   old agent moved into the layout (`rragent.bots/migrate!`), or the
   assistant made from the generic definition. The `bot` tool of every
   bot is made over the functions below (`rragent.tools.bot`), so a bot
   makes another the way the person does from the sidebar."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.bots :as bots]
            [rragent.codemode :as codemode]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.gateway :as gateway]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.projects :as projects]
            [rragent.render :as render]
            [rragent.rframes.sse :as sse]
            [rragent.tools.bot :as bot-tool]))

(defonce ^{:doc "What every bot opens with: `{:home :codemode :models :provider :storage-fn :extensions}`."}
  machine
  (atom nil))

(defonce ^{:doc "By id: `{:id :agent :pane :dir :projects :render :uploads :stop!}`;
                 a bot that did not open has `:agent` nil and its reason in the pane."}
  bots
  (atom {}))

(declare create! listed)

(defn home
  []
  (:home @machine))

(defn bot
  "The running bot `id`, nil for none."
  [id]
  (get @bots (str id)))

(defn agent
  "The harness map of the bot `id`, nil when it is not open."
  [id]
  (:agent (bot id)))

(defn- start-pane!
  "The page's state follows the bot: the view value now and every one
   after it, the artifacts folder watched, the projects in it run while
   someone looks at the page, the renders kept, the uploads folder
   known. Answers the handles and what ends the following."
  [{:keys [id]} agent pane]
  (let [work (:work agent)
        dir (home/artifacts-dir work)
        base (str (bots/url id) artifacts/url-prefix)
        projects (projects/handle {:db pane
                                   :dir dir
                                   :base base
                                   :shown? (fn [] (pos? (sse/connections)))})]
    (swap! pane assoc :agent agent :value (.-value (:view agent)))
    (sse/watch! pane)
    (let [unsubscribe (.subscribe (:view agent) (fn [value] (swap! pane assoc :value value)))
          stop-artifacts (artifacts/watch! pane dir)
          stop-projects (projects/watch! projects)]
      {:dir dir
       :projects projects
       :render (render/handle {:db pane :dir dir})
       :uploads (home/uploads-dir work)
       :stop! (fn []
                (unsubscribe)
                (stop-artifacts)
                (stop-projects)
                (sse/unwatch! pane))})))

(defn- note-failure!
  "A harness that fails closes itself: said on the page."
  [agent pane]
  (-> (.-closed (:harness agent))
      (.then (fn [end]
               (when (= "failed" (.-reason end))
                 (swap! pane assoc :failed (str (or (some-> (.-error end) .-message) (.-error end)))))))
      (.catch (fn [_] nil))))

(defn ^:async open-bot!
  "The bot of the index `entry` opened and followed; one that does
   not open is kept with its reason, said on stderr and on its page."
  [{:keys [id] :as entry}]
  (let [{:keys [home codemode models provider storage-fn extensions]} @machine
        dir (bots/bot-dir home id)
        pane (db/pane (home/work-dir dir))]
    (try
      (let [agent (await (harness/open {:home dir
                                        :codemode codemode
                                        :models models
                                        :provider provider
                                        :storage (when storage-fn (await (storage-fn dir)))
                                        :extensions extensions}))
            handles (start-pane! entry agent pane)]
        (note-failure! agent pane)
        (swap! bots assoc id (merge {:id id :agent agent :pane pane} handles))
        (bot id))
      (catch :default e
        (js/console.error "[bots]" (str "the bot " id " did not open: " (.-message e)))
        (swap! pane assoc :failed (.-message e))
        (swap! bots assoc id {:id id :pane pane})
        (bot id)))))

(defn ^:async close-bot!
  "The bot's following ended and its harness closed; nothing for a
   bot that is not open."
  [id]
  (when-let [{:keys [agent stop!]} (bot id)]
    (when stop!
      (stop!))
    (when agent
      (await (harness/close agent)))
    (swap! bots dissoc (str id))
    true))

(defn ^:async open-all!
  "Every bot of the home opened: the home made when it is missing, the
   agent of the old layout moved into `bots/` first
   (`rragent.bots/migrate!`), the assistant made when there is no bot
   at all, the gateway discovered once for all of them. `opts`:
   `:home` (RRAGENT_HOME without), `:models`, `:provider`,
   `:storage-fn` (a function of a bot's folder to a promise of its
   storage; SQLite in the folder without), `:gateway` and `:retry`
   as `rragent.harness/open` takes them. Answers the bots in the
   index's order."
  [{:keys [home models provider storage-fn gateway retry]}]
  (let [home (or home (home/home))]
    (fs/mkdirSync home #js {:recursive true})
    (when-let [{:keys [id]} (bots/migrate! home)]
      (println (str "the agent of " home " is the bot " id " now")))
    (when (empty? (bots/read-index home))
      (bots/create! home {}))
    (let [base (gateway/base gateway)
          codemode (codemode/handle base (await (gateway/discover-at-start base retry)))]
      (reset! machine {:home home
                       :codemode codemode
                       :models (or models (harness/openrouter-models codemode))
                       :provider provider
                       :storage-fn storage-fn
                       :extensions [(bot-tool/extension {:create! #'create! :list #'listed})]})
      (let [entries (bots/read-index home)]
        (doseq [entry entries]
          (await (open-bot! entry)))
        (mapv #(bot (:id %)) entries)))))

(defn ^:async close-all!
  []
  (doseq [id (keys @bots)]
    (await (close-bot! id)))
  (reset! machine nil))

(defn codemode
  "The REPL handle of the process."
  []
  (:codemode @machine))

(defn busy?
  "Whether any bot runs, or renders."
  []
  (boolean (some (fn [{:keys [agent render]}]
                   (or (and agent (harness/busy? agent))
                       (and render (render/any-running? render))))
                 (vals @bots))))

(defn name-of
  "The bot's name: the one given, else its definition's
   (`rragent.definition/display-name`), else its id."
  [{:keys [id name]}]
  (or name
      (some-> (bot id) :pane deref (get-in [:agent :definition]) definition/display-name)
      id))

(defn state-of
  "`:failed` for a bot that did not open or whose harness failed,
   `:running` while a run goes, `:asleep` when it idles until a
   scheduled run, `:idle` otherwise."
  [id]
  (let [{:keys [agent pane]} (bot id)
        value (some-> pane deref)]
    (cond
      (or (nil? agent) (:failed value)) :failed
      (harness/busy? agent) :running
      (:schedules/next value) :asleep
      :else :idle)))

(defn listed
  "The bots of the index in its order, each with its name and state."
  []
  (mapv (fn [entry]
          (assoc entry :name (name-of entry) :state (state-of (:id entry))))
        (bots/read-index (home))))

(defn default-id
  "The bot the root of the page goes to: the one last used, else the
   first; nil without any."
  []
  (let [entries (bots/read-index (home))]
    (or (:id (first (sort-by :used > (filter :used entries))))
        (:id (first entries)))))

(defn ^:async create!
  "A new bot made (`rragent.bots/create!`) and opened: `{:name
   :instructions :skill}`, the name required. Answers its entry."
  [{:keys [name] :as given}]
  (when (str/blank? (str name))
    (throw (js/Error. "A bot needs a name.")))
  (let [entry (bots/create! (home) given)]
    (await (open-bot! entry))
    entry))

(defn rename!
  [id name]
  (bots/rename! (home) id name))

(defn touch!
  "The bot `id` was shown now."
  [id]
  (when (bot id)
    (bots/touch! (home) id (js/Date.now))))

(defn ^:async delete!
  "The bot closed and its folder moved to the trash; the last bot of
   the machine stays. Answers `:deleted`, `:last` or `:missing`."
  [id]
  (cond
    (nil? (bot id)) :missing
    (= 1 (count @bots)) :last
    :else (do (await (close-bot! id))
              (bots/delete! (home) id)
              :deleted)))

(defn ^:async hook!
  "A hook's call tried on every open bot (`rragent.hooks/receive!`):
   the answer of the bot whose hook it is, `{:ok false}` when it is
   none's."
  [receive! name secret headers body]
  (loop [[{:keys [agent]} & more] (vals @bots)]
    (if (nil? agent)
      (if (seq more) (recur more) {:ok false})
      (let [answer (await (receive! agent name secret headers body))]
        (if (:ok answer)
          answer
          (if (seq more) (recur more) {:ok false}))))))

(defn report
  "One line for the start: the bots and their names."
  []
  (let [bots (listed)]
    (str (count bots) (if (= 1 (count bots)) " bot: " " bots: ")
         (str/join ", " (map (fn [{:keys [id name state]}] (str name " (" id (when (= :failed state) ", failed") ")")) bots)))))
