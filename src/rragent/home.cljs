(ns rragent.home
  "The machine's folder, `RRAGENT_HOME` (`./agent` by default): in it
   `agent.sqlite`, the one storage of every bot's conversation
   (pi-durable), and a folder per bot, `bots/<id>/`, named by the id
   of its conversation (`rragent.bots` lays them out). In a bot's
   folder: `AGENT.md` (or a `SKILL.md` of agentitos), the definition,
   and `work/`, where the bot's tools read and write, with
   `work/artifacts/`, what the pane shows, and `work/uploads/`, what
   the person attached to a message. `home`, `database` and `bot-dir`
   are the machine's; the other functions take a bot's folder."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn home
  "The machine's folder, absolute."
  []
  (path/resolve (or (not-empty (aget (.-env js/process) "RRAGENT_HOME")) "agent")))

(defn database
  "The SQLite file of the machine."
  [home]
  (path/join home "agent.sqlite"))

(defn bot-dir
  "The folder of the bot whose conversation is `id`."
  [home id]
  (path/join home "bots" (str id)))

(defn definition-file
  "`AGENT.md` of the folder, or its `SKILL.md`; nil without either."
  [dir]
  (some (fn [name]
          (let [file (path/join dir name)]
            (when (fs/existsSync file) file)))
        ["AGENT.md" "SKILL.md"]))

(defn work-dir
  "Where the bot's tools read and write; made on first use."
  [dir]
  (let [work (path/join dir "work")]
    (fs/mkdirSync work #js {:recursive true})
    work))

(defn artifacts-dir
  "What the bot makes for the person, under `work/`: the files the
   pane shows (`rragent.artifacts`); made on first use."
  [work]
  (let [dir (path/join work "artifacts")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn uploads-dir
  "What the person attaches to a message, under `work/`: the files
   `rragent.uploads` stores, which the bot reaches as `uploads/<name>`;
   made on first use."
  [work]
  (let [dir (path/join work "uploads")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))
