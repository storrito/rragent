(ns rragent.home
  "The agent's folder, the one thing on the machine that is the agent:
   `RRAGENT_HOME`, `./agent` by default. In it: `AGENT.md` (or a
   `SKILL.md` of agentitos), the definition; `agent.sqlite`, the
   transcript and the state; `work/`, where the tools read and write."
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
