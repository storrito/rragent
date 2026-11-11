(ns rragent.roles
  "Which tools the model of a conversation sees, by the conversation's
   role (pi-durable's `modelTools` of the agent's configuration; the
   registry holds every tool for every conversation, the host tools
   among them, which only the `machine` tool calls). A bot, a
   conversation the person talks to, sees every tool but `subagent`:
   it hands work to tasks (`rragent.tasks`), which go on beside it and
   answer into its chat. A run of a bot's, the conversation a hook's
   call or a schedule's fire starts (`rragent.runs`), sees every tool
   but `task`: nobody reads its chat, so it does its work itself and
   hands a long piece to a `subagent`, whose answer it waits for. A
   task has the tools of work alone: the files, the connectors, the
   templates' projects and renders, the media tools; no tasks of its
   own, no schedules, no memory (docs/bots.md §3)."
  (:require [clojure.string :as str]))

(def coding-tools
  "pi-durable's own: files and bash."
  ["read" "write" "edit" "bash"])

(def task-tools
  "The tools of a task."
  (into coding-tools ["fetch" "machine" "project" "render" "image" "voice" "music" "transcribe" "youtube"]))

(def bot-hidden
  "The tools a bot's model does not see."
  ["subagent"])

(def run-hidden
  "The tools the model of a bot's run does not see."
  ["task"])

(defn- named
  "The tools as pi-durable's `modelTools` takes them: a list of
   objects it reads the `name` of."
  [names]
  (clj->js (mapv (fn [name] {:name name}) names)))

(defn model-tools
  "The tools the model of a conversation with `role` sees, as the
   agent's configuration takes them: a task's as the list of them, a
   bot's and a run's as every tool less the ones removed; nil for no
   role, which leaves every tool in sight."
  [role]
  (case role
    :bot #js {:remove (named bot-hidden)}
    :run #js {:remove (named run-hidden)}
    :task (named task-tools)
    nil))

(defn describe
  "The tools of `role` as a line, for a log or a test."
  [role]
  (case role
    :bot (str "every tool but " (str/join " " bot-hidden))
    :run (str "every tool but " (str/join " " run-hidden))
    :task (str/join " " task-tools)
    "every tool"))
