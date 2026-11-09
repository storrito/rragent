(ns rragent.migration
  "A machine of before adopted into the one loop (docs/one-loop.md
   §9), once, at the first open of this version. Before the storage
   opens, `backup!`: the database with its sidecars, every bot's
   definition and the loop's wiki copied to `<home>.bak-<version>`,
   next to the home, unless the marker `<home>/.one-loop` says the
   adoption was done. After the bots opened, `adopt!`: every bot but
   the loop becomes a topic (its project's name, else its own) and is
   archived; its artifacts move under the loop's
   `artifacts/<topic-slug>/` (a name taken there: under the bot's
   name within), its schedules are stopped and set again on the loop
   in message mode with the topic, its hooks move to the loop's hook
   document with their secrets, so their addresses keep working; the
   structure document gets the topics, the archived ids, `botTopics`
   (bot id to topic slug, for a link of before,
   `rragent.page/bot-get`) and `migrated`, the version, in one commit.
   Then the handover: the compaction task with the handover's
   instruction (`rragent.compaction/start!`), which has the loop read
   each archived chat and write the topics' pages, and compacts.
   A machine without bots of before is marked and left alone. Rerun
   safe: the marker and the flag stop a second pass, a move finds its
   files gone."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.bots :as bots]
            [rragent.compaction :as compaction]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.schedule :as schedule]
            [rragent.tools.topic :as topic-tool]
            [rragent.version :as version]))

(def marker-name
  ".one-loop")

(defn marker
  [home]
  (path/join home marker-name))

(defn backup-dir
  "`<home>.bak-<version>`, the version of the bundle that migrates."
  [home]
  (str (path/resolve home) ".bak-" version/version))

(defn- copy!
  [from to]
  (when (fs/existsSync from)
    (fs/mkdirSync (path/dirname to) #js {:recursive true})
    (fs/cpSync from to #js {:recursive true})))

(defn backup!
  "The copies, before the storage opens: nothing when the marker is
   there, or the backup folder already. Answers the folder, nil when
   nothing was copied."
  [home]
  (let [bots-dir (path/join home "bots")]
    (when (and (fs/existsSync bots-dir)
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
          (copy! (path/join dir "wiki") (path/join to "bots" id "wiki")))
        to))))

(defn- unique-slug
  [taken name]
  (let [base (topic-tool/slug name)]
    (if (contains? taken base)
      (some (fn [n] (let [c (str base "-" n)] (when-not (contains? taken c) c))) (iterate inc 2))
      base)))

(defn plan
  "What the adoption does with the bots `ids` (strings, the loop among
   them): `[{:id :name :topic :slug}]` for every bot but the loop that
   somebody talked to (`used`, the ids with a message), the topic its
   project's name (`projects`, bot id to name) or its own, the slug
   unique among `topics` (the loop's topics so far, id to `{:name}`)
   and among each other; two bots of one project are one topic. A bot
   never used, the machine's first as it was made, is left as it is."
  [ids loop-id projects topics names used]
  (loop [[id & more] (filter #(and (not= (str %) (str loop-id)) (contains? used (str %))) ids)
         taken (set (keys topics))
         made {}
         out []]
    (if (nil? id)
      out
      (let [topic (or (not-empty (str (get projects (str id)))) (str (get names (str id))))
            topic (if (str/blank? topic) (str "Bot " id) topic)
            lower (str/lower-case topic)
            existing (or (get made lower)
                         (some (fn [[slug {:keys [name]}]] (when (= (str/lower-case name) lower) slug)) topics))
            slug (or existing (unique-slug taken topic))]
        (recur more (conj taken slug) (assoc made lower slug) (conj out {:id (str id) :name (str (get names (str id))) :topic topic :slug slug}))))))

(defn move-artifacts!
  "The bot's `artifacts/` moved under the loop's `artifacts/<slug>/`;
   a name taken there goes under the bot's name within. Answers the
   names moved."
  [home bot-id loop-id slug bot-name]
  (let [from (path/join (home/bot-dir home bot-id) "work" "artifacts")
        to (path/join (home/work-dir (home/bot-dir home loop-id)) "artifacts" slug)]
    (if-not (fs/existsSync from)
      []
      (let [names (vec (array-seq (fs/readdirSync from)))]
        (when (seq names)
          (fs/mkdirSync to #js {:recursive true}))
        (doseq [name names
                :let [source (path/join from name)
                      target (path/join to name)
                      target (if (fs/existsSync target)
                               (let [within (path/join to (topic-tool/slug bot-name))]
                                 (fs/mkdirSync within #js {:recursive true})
                                 (path/join within name))
                               target)]]
          (fs/renameSync source target))
        (when (empty? (array-seq (fs/readdirSync from)))
          (fs/rmdirSync from))
        names))))

(defn handover-instruction
  "What the loop reads once: the topics made from the bots of before,
   and the pages to write."
  [plan]
  (str "This machine was adopted into one conversation: the manager and its workers of before are archived, and every worker is a topic now. "
       "The topics and the chats they came from:\n"
       (str/join "\n" (map (fn [{:keys [id name topic slug]}] (str "- " topic " (" slug "), from the chat of " name " (bot " id ")")) plan))
       "\n\nRead each archived chat with `bot transcript` (its id above, since 0, again with the last entry while there is more) and write the wiki: "
       "one page per topic under topics/<name>.md with its goal, state, decisions, approvals, open items and next step, the artifacts now under artifacts/<slug>/; "
       "people.md; decisions.md; the index and the log; commit. Then answer \"ready\" and nothing else: the summary of this conversation follows."))

(defn ^:async adopt!
  "The adoption, after the bots opened: nothing when the structure says
   `migrated`. `structure-doc` is the machine's document token
   (`rragent.machine/Structure`), `ids` the open bots (strings),
   `loop-id` the loop's, `projects` and `topics` as the structure was
   read, `names` bot id to name, `used` the ids somebody talked to.
   Answers the plan, nil when nothing was done."
  [{:keys [harness home structure-doc ids loop-id projects topics names used context]}]
  (let [^js doc (await (.snapshot harness structure-doc context))]
    (when-not (.-migrated doc)
      (let [plan (plan ids loop-id projects topics names used)
            now (js/Date.now)
            loop-key (js/Number loop-id)
            stopped (atom [])]
        ;; the schedules' tasks of the bots of before end: aborted before
        ;; the commit that sets them again on the loop
        (doseq [{:keys [id]} plan]
          (let [schedules (await (.snapshot harness schedule/Schedules (js/Number id) context))]
            (doseq [[name entry] (js/Object.entries (or schedules #js {}))]
              (when-let [task-id (.-taskId entry)]
                (try
                  (await (.abortTask harness task-id context))
                  (swap! stopped conj name)
                  (catch :default _ nil))))))
        (doseq [{:keys [id slug name]} plan]
          (move-artifacts! home id loop-id slug name))
        (await (.commit harness
                        (^:async fn [tx]
                          (let [^js structure (await (.doc tx structure-doc))
                                ^js loop-schedules (await (.doc tx schedule/Schedules loop-key))
                                ^js loop-hooks (await (.doc tx hooks/Hooks loop-key))]
                            (when-not (.-topics structure) (aset structure "topics" #js {}))
                            (when-not (.-archived structure) (aset structure "archived" #js []))
                            (when-not (.-botTopics structure) (aset structure "botTopics" #js {}))
                            (doseq [{:keys [id topic slug]} plan]
                              (when-not (aget (.-topics structure) slug)
                                (aset (.-topics structure) slug #js {:name topic :created now :archived false}))
                              (when-not (some #{(js/Number id)} (array-seq (.-archived structure)))
                                (.push (.-archived structure) (js/Number id)))
                              (aset (.-botTopics structure) (str id) slug)
                              ;; the schedules: set again on the loop, in message mode, with the topic
                              (let [^js from (await (.doc tx schedule/Schedules (js/Number id)))]
                                (doseq [[name entry] (js/Object.entries (or from #js {}))]
                                  (let [cron (.-cron entry) tz (or (.-tz entry) "UTC") text (.-text entry)
                                        at (schedule/next-run cron tz now)
                                        name (if (aget loop-schedules name) (str name "-" id) name)]
                                    (when at
                                      (let [task-id (await (.createTask tx schedule/Task
                                                                        #js {:name name :cron cron :tz tz :text text :at at}
                                                                        #js {:ownership #js {:kind "conversation"} :conversationId loop-key :background true}))]
                                        (aset loop-schedules name #js {:cron cron :tz tz :text text :mode "message" :topic slug :taskId task-id}))))
                                  (js-delete from name)))
                              ;; the hooks: moved with their secrets, so the addresses keep working
                              (let [^js from (await (.doc tx hooks/Hooks (js/Number id)))]
                                (doseq [[name entry] (js/Object.entries (or from #js {}))]
                                  (let [name (if (aget loop-hooks name) (str name "-" id) name)
                                        moved #js {:secret (.-secret entry) :purpose (or (.-purpose entry) "") :mode "message" :topic slug :createdAt (or (.-createdAt entry) now)}]
                                    (aset loop-hooks name moved))
                                  (js-delete from name))))
                            (aset structure "migrated" version/version))
                          js/undefined)
                        context))
        (fs/writeFileSync (marker home) (str version/version "\n"))
        (when (seq plan)
          (await (schedule/rearm! now))
          (await (compaction/start! harness loop-key "migration" context {:instruction (handover-instruction plan)})))
        plan))))
