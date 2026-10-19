(ns rragent.home
  "The agent's folder, the one thing on the machine that is the agent:
   `RRAGENT_HOME`, `./agent` by default. In it: `AGENT.md` (or a
   `SKILL.md` of agentitos), the definition; `agent.sqlite`, the
   transcript and the state; `work/`, where the tools read and write,
   with `work/artifacts/`, what the pane shows, and `work/uploads/`, what
   the person attached to a message."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn home
  "The agent's folder, absolute."
  []
  (path/resolve (or (not-empty (aget (.-env js/process) "RRAGENT_HOME")) "agent")))

(defn definition-file
  "`AGENT.md` of the folder, or its `SKILL.md`; nil without either."
  [home]
  (some (fn [name]
          (let [file (path/join home name)]
            (when (fs/existsSync file) file)))
        ["AGENT.md" "SKILL.md"]))

(defn database
  [home]
  (path/join home "agent.sqlite"))

(defn work-dir
  "Where the agent's tools read and write; made on first use."
  [home]
  (let [dir (path/join home "work")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn artifacts-dir
  "What the agent makes for the person, under `work/`: the files the
   pane shows (`rragent.artifacts`); made on first use."
  [work]
  (let [dir (path/join work "artifacts")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn uploads-dir
  "What the person attaches to a message, under `work/`: the files
   `rragent.uploads` stores, which the agent reaches as `uploads/<name>`;
   made on first use."
  [work]
  (let [dir (path/join work "uploads")]
    (fs/mkdirSync dir #js {:recursive true})
    dir))
