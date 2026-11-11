(ns rragent.migration
  "The bots given back what the one conversation adopted (docs/bots.md
   §8), once, at the first open of this version. The releases 0.1.56
   to 0.1.58 made one conversation of a machine: every bot but that
   one became a topic and was archived, its artifacts moved under the
   one conversation's `artifacts/<topic>/`, its schedules and hooks
   set again there as messages. `restore!` turns that around for the
   bots that are still there:

   - each is taken off the archived list;
   - a schedule or a hook the adoption moved is the bot's again, a
     run of its own as it was (the adoption wrote `message` and the
     topic into every one it moved; the mode of before is not kept
     anywhere, and a run was what a bot's job was written for). One
     the conversation set itself in such a topic carries the same two
     marks, a reminder for one, and stays where it is: as a run its
     words would reach nobody. The two are told apart by their age
     (`moved?`);
   - its artifacts come back from `artifacts/<topic>/`, with what was
     made there since;
   - a note in its chat says what happened, read by the model at its
     next turn; no turn is started.

   Two bots of one project were adopted as one topic, their artifacts
   in one folder: of such a topic only the schedules and hooks that
   carry a bot's id in their name (the adoption's way out of a name
   taken) go back, the folder stays where it is, and a link of
   before to it still resolves (`rragent.page`).

   The one conversation itself stays, with every topic's turns, as a
   bot like the others (`rragent.bots/first-chat-text`): its other
   schedules and hooks keep reaching it as messages, which they did
   while it was the one conversation whatever their mode said. A
   topic made since the adoption gets no bot: its turns are read
   there. A machine that still has its manager, never adopted, gets
   the file alone. Rerun safe: the structure's `restored` stops a
   second pass, a move finds its files gone.

   Before the storage opens, `backup!` copies what the restore
   changes and no rename brings back, the database with its sidecars
   and every bot's definition and wiki, to `<home>.bak-<version>`
   next to the home, for a home the adoption marked (`.one-loop`) and
   the restore has not yet (`.bots-back`)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.schedule :as schedule]
            [rragent.version :as version]
            [rragent.wiki :as wiki]))

(def restored-kind
  "The entry of the note in a bot's chat."
  "rragent.restored")

(defn adopted-marker
  "The file the adoption left in a home it made one conversation of."
  [home]
  (path/join home ".one-loop"))

(defn marker
  "The file that says the restore ran in the home."
  [home]
  (path/join home ".bots-back"))

(defn backup-dir
  "`<home>.bak-<version>`, the version of the bundle that restores."
  [home]
  (str (path/resolve home) ".bak-" version/version))

(defn- copy!
  [from to]
  (when (fs/existsSync from)
    (fs/mkdirSync (path/dirname to) #js {:recursive true})
    (fs/cpSync from to #js {:recursive true})))

(defn backup!
  "The copies, before the storage opens: the database with its
   sidecars, every bot's definition and the wiki a bot kept. Nothing
   for a home the adoption never marked, one the restore marked, or
   when the backup folder is there already. Answers the folder, nil
   when nothing was copied."
  [home]
  (let [bots-dir (path/join home "bots")]
    (when (and (fs/existsSync bots-dir)
               (fs/existsSync (adopted-marker home))
               (not (fs/existsSync (marker home)))
               (not (fs/existsSync (backup-dir home))))
      (let [to (backup-dir home)
            database (home/database home)]
        (doseq [suffix ["" "-wal" "-shm" "-journal"]]
          (copy! (str database suffix) (path/join to (path/basename (str database suffix)))))
        (doseq [id (array-seq (fs/readdirSync bots-dir))
                :let [dir (path/join bots-dir id)]]
          (doseq [name ["AGENT.md" "SKILL.md"]]
            (copy! (path/join dir name) (path/join to "bots" id name)))
          (copy! (wiki/before dir) (path/join to "bots" id "wiki")))
        to))))

(defn plan
  "What the restore does with the bots the adoption made topics of:
   `[{:id :slug :name :alone?}]` for every one of `bot-topics` (bot id
   to topic slug) that is open (`ids`, strings), `:alone?` when no
   other bot was adopted as its topic, deleted ones counted: then the
   topic's folder holds this bot's artifacts and nobody else's."
  [bot-topics ids names]
  (let [shared (frequencies (vals bot-topics))
        open (set (map str ids))]
    (->> bot-topics
         (filter (fn [[id _]] (contains? open (str id))))
         (sort-by (fn [[id _]] (js/Number id)))
         (mapv (fn [[id slug]]
                 {:id (str id)
                  :slug slug
                  :name (str (get names (str id) (str "Bot " id)))
                  :alone? (= 1 (get shared slug))})))))

(defn owner
  "The bot of `plan` a schedule or a hook of the one conversation goes
   back to, by its `name` and the `topic` and `mode` its entry
   carries: one the adoption moved (it wrote `message` and the topic),
   whose name ends in a bot's id when the name was taken there
   (`<name>-<id>`), else the one bot of the topic; nil for one the
   conversation set itself, and for one of a topic two bots share
   that names neither."
  [plan name topic mode]
  (when (and (= "message" (str mode)) (seq (str topic)))
    (let [of-topic (filter #(= (str topic) (:slug %)) plan)]
      (or (some #(when (str/ends-with? (str name) (str "-" (:id %))) %) of-topic)
          (when (= 1 (count of-topic))
            (when (:alone? (first of-topic))
              (first of-topic)))))))

(defn moved?
  "Whether the adoption moved the schedule or hook `entry` (a draft
   of the document's entry), not the conversation that set it itself
   in an adopted topic since: both carry `message` and the topic. A
   schedule's task (`taskId`) is older than the handover's
   (`:handover`, the id of the compaction task the adoption started
   right after its commit, which made the moved schedules' tasks); a
   hook was made (`createdAt`) no later than the adoption
   (`:adopted-at`, the time it gave its topics). Without the mark to
   compare with, every one counts as moved."
  [^js entry {:keys [handover adopted-at]}]
  (if (some? (.-cron entry))
    (or (nil? handover) (nil? (.-taskId entry)) (< (.-taskId entry) handover))
    (or (nil? adopted-at) (nil? (.-createdAt entry)) (<= (.-createdAt entry) adopted-at))))

(defn name-before
  "The name a schedule or a hook had before the adoption: without the
   `-<id>` the adoption put behind a name taken, when the bot's own
   list (`taken`, a set) has that name free; else as it is."
  [name id taken]
  (let [suffix (str "-" id)
        base (when (str/ends-with? (str name) suffix)
               (subs (str name) 0 (- (count (str name)) (count suffix))))]
    (if (and (seq base) (not (contains? taken base)))
      base
      (str name))))

(defn move-artifacts-back!
  "What lies under the one conversation's `artifacts/<slug>/` moved
   into the bot's `artifacts/`; a name taken there stays where it is.
   Answers the names moved."
  [home bot-id first-chat slug]
  (let [from (path/join (home/work-dir (home/bot-dir home first-chat)) "artifacts" slug)
        to (path/join (home/work-dir (home/bot-dir home bot-id)) "artifacts")]
    (if-not (fs/existsSync from)
      []
      (let [names (vec (array-seq (fs/readdirSync from)))
            moved (atom [])]
        (when (seq names)
          (fs/mkdirSync to #js {:recursive true}))
        (doseq [name names
                :let [target (path/join to name)]]
          (when-not (fs/existsSync target)
            (fs/renameSync (path/join from name) target)
            (swap! moved conj name)))
        (when (empty? (array-seq (fs/readdirSync from)))
          (fs/rmdirSync from))
        @moved))))

(defn note
  "What a restored bot reads at its next turn."
  [{:keys [slug alone?]} topic first-chat {:keys [schedules hooks]} wiki]
  (str "A note from rragent, not from the person: for a while this machine ran as one conversation, and this chat was archived. "
       "Its matter went on there as the topic \"" topic "\". The bots are back now, and so are you: the person talks to you here again. "
       "What was decided and done meanwhile is in the wiki of this machine, " wiki "/topics/ (the page of \"" topic "\"; index.md lists the pages), "
       "and in the chat of that conversation, the bot " first-chat ": `bot transcript` reads it. Read the page before you answer about the matter. "
       (if alone?
         (str "Your artifacts are under artifacts/ again, with what was made for the topic since (they lay under artifacts/" slug "/ of the bot " first-chat "). ")
         (str "Your artifacts stayed with the bot " first-chat ", under its artifacts/" slug "/, since another bot's lie in the same folder. "))
       (when (seq schedules) (str "Yours again, each fire a run of yours: the schedules " (str/join ", " schedules) ". "))
       (when (seq hooks) (str "Yours again, each call a run of yours: the hooks " (str/join ", " hooks) ". "))
       "Nothing to do now; no answer is expected to this note."))

(defn first-chat-note
  "What the chat the machine began with reads at its next turn."
  [plan moved wiki]
  (str "A note from rragent, not from the person: this machine no longer runs as one conversation. The bots are back, each a chat of its own "
       "with the person, and you are one of them now, with a bot's tools and rules; what your instructions say now holds, not what this chat said before. "
       "The topics are no views any more: the person reads this chat whole. "
       (when (seq plan)
         (str "These topics went back to the bots they came from, which the person talks to on their own pages: "
              (str/join "; " (map (fn [{:keys [id name slug alone?]}]
                                    (str slug " to " name " (bot " id ")"
                                         (when alone? (str ", with artifacts/" slug "/"))))
                                  plan))
              ". "))
       (when (seq (:schedules moved)) (str "Their schedules went with them: " (str/join ", " (:schedules moved)) ". "))
       (when (seq (:hooks moved)) (str "Their hooks went with them: " (str/join ", " (:hooks moved)) ". "))
       "Your other schedules and hooks still reach you here as messages. "
       "The wiki is the machine's now, shared by every bot: " wiki " (it lay in your folder; the old path still reads). "
       "Nothing to do now; no answer is expected to this note."))

(defn- ^:async write-note!
  "The note appended to the chat of the bot `id` as an entry the model
   reads at its next turn; no run starts. Once: the request id names
   the bot."
  [harness id text data context]
  (when-let [conversation (await (.conversation harness (js/Number id) context))]
    (await (.submit conversation
                    #js {:type "write"
                         :requestId (str "restored:" id)
                         :entry #js {:kind restored-kind
                                     :data (clj->js data)
                                     :model #js [#js {:role "user" :content text :timestamp (js/Date.now)}]}}
                    context))))

(defn- entries
  "The entries of a schedules or hooks document as `[name entry]`
   pairs."
  [doc]
  (vec (js/Object.entries (or doc #js {}))))

(defn- ^:async handover-task
  "The id of the compaction task the adoption started for its handover
   in the conversation `id` (its reason `migration`); nil without one."
  [harness id context]
  (let [page (await (.commit harness
                             (fn [tx] (.scanTasks tx #js {:conversationId id :kind "rragent.compaction" :order "ascending"} 50))
                             context))]
    (some #(when (= "migration" (some-> (.-input %) .-reason)) (.-id %)) (array-seq (.-items page)))))

(defn ^:async restore!
  "The restore, after the bots opened: nothing when the structure says
   `restored`. `structure-doc` is the machine's document token
   (`rragent.machine/Structure`), `ids` the open bots (strings),
   `names` bot id to name. Answers `{:first-chat id :bots plan}`, the
   chat the machine began with (nil for a machine without one) and the
   bots that got their work back; nil when nothing was done."
  [{:keys [harness home structure-doc ids names context]}]
  (let [^js doc (or (await (.snapshot harness structure-doc context)) #js {})]
    (when-not (.-restored doc)
      (let [open (set (map str ids))
            first-chat (some-> (.-manager doc) str (as-> id (when (contains? open id) id)))
            adopted? (and first-chat (some? (.-migrated doc)))
            bot-topics (into {} (map (fn [[k v]] [(str k) (str v)])) (js/Object.entries (or (.-botTopics doc) #js {})))
            topic-name (fn [slug] (or (some-> (.-topics doc) (aget slug) .-name str not-empty) slug))
            plan (if adopted? (plan bot-topics ids names) [])
            first-key (some-> first-chat js/Number)
            ;; what tells a schedule or a hook the adoption moved from one set since
            marks (when adopted?
                    {:handover (await (handover-task harness first-key context))
                     :adopted-at (some (fn [slug] (some-> (.-topics doc) (aget slug) .-created)) (vals bot-topics))})
            back (fn [name ^js entry]
                   (when (moved? entry marks)
                     (owner plan name (.-topic entry) (.-mode entry))))
            now (js/Date.now)
            moved (atom {:schedules [] :hooks []})
            given (atom {})]
        (when first-chat
          (bots/write-first-chat-definition! home first-chat))
        (when adopted?
          ;; the tasks of the schedules that go back end: aborted before
          ;; the commit that sets them again on their bots
          (let [schedules (await (.snapshot harness schedule/Schedules first-key context))]
            (doseq [[name entry] (entries schedules)]
              (when (back name entry)
                (when-let [task-id (.-taskId entry)]
                  (try
                    (await (.abortTask harness task-id context))
                    (catch :default _ nil))))))
          (await (.commit harness
                          (^:async fn [tx]
                            (let [^js structure (await (.doc tx structure-doc))
                                  ^js first-schedules (await (.doc tx schedule/Schedules first-key))
                                  ^js first-hooks (await (.doc tx hooks/Hooks first-key))]
                              ;; a commit that runs again starts its count again
                              (reset! moved {:schedules [] :hooks []})
                              (reset! given {})
                              (doseq [[name entry] (entries first-schedules)]
                                (if-let [{:keys [id]} (back name entry)]
                                  (let [^js to (await (.doc tx schedule/Schedules (js/Number id)))
                                        cron (.-cron entry) tz (or (.-tz entry) "UTC") text (.-text entry)
                                        at (schedule/next-run cron tz now)
                                        named (name-before name id (set (js/Object.keys to)))]
                                    (when at
                                      (let [task-id (await (.createTask tx schedule/Task
                                                                        #js {:name named :cron cron :tz tz :text text :at at}
                                                                        #js {:ownership #js {:kind "conversation"} :conversationId (js/Number id) :background true}))]
                                        (aset to named #js {:cron cron :tz tz :text text :mode "run" :taskId task-id})
                                        (swap! moved update :schedules conj named)
                                        (swap! given update-in [id :schedules] (fnil conj []) named)))
                                    (js-delete first-schedules name))
                                  ;; the conversation's own: a message, as every fire was
                                  (aset entry "mode" "message")))
                              (doseq [[name entry] (entries first-hooks)]
                                (if-let [{:keys [id]} (back name entry)]
                                  (let [^js to (await (.doc tx hooks/Hooks (js/Number id)))
                                        named (name-before name id (set (js/Object.keys to)))]
                                    (aset to named #js {:secret (.-secret entry) :purpose (or (.-purpose entry) "") :mode "run" :createdAt (or (.-createdAt entry) now)})
                                    (swap! moved update :hooks conj named)
                                    (swap! given update-in [id :hooks] (fnil conj []) named)
                                    (js-delete first-hooks name))
                                  (aset entry "mode" "message")))
                              ;; the bots are no archived chats any more
                              (let [back (set (map #(js/Number (:id %)) plan))]
                                (aset structure "archived" (clj->js (vec (remove back (array-seq (or (.-archived structure) #js [])))))))
                              (aset structure "restoredBots" (clj->js (into {} (map (juxt :id :slug)) (filter :alone? plan)))))
                            js/undefined)
                          context))
          (doseq [{:keys [id slug alone?]} plan]
            (when alone?
              (move-artifacts-back! home id first-chat slug))))
        (await (.commit harness
                        (^:async fn [tx]
                          (aset (await (.doc tx structure-doc)) "restored" version/version)
                          js/undefined)
                        context))
        (when adopted?
          (fs/writeFileSync (marker home) (str version/version "\n"))
          (await (schedule/rearm! now))
          (let [wiki (wiki/dir home)]
            (doseq [{:keys [id slug] :as bot} plan]
              (await (write-note! harness id (note bot (topic-name slug) first-chat (get @given id) wiki) {:topic slug :from first-chat} context)))
            (await (write-note! harness first-chat (first-chat-note plan @moved wiki) {:bots (mapv :id plan)} context))))
        {:first-chat first-chat :bots plan}))))
