(ns rragent.roles
  "Which tools the model of a conversation sees, by the conversation's
   role (pi-durable's `modelTools` of the agent's configuration; the
   registry holds every tool for every conversation, the host tools
   among them, which only the `machine` tool calls). The loop, the one
   conversation the person talks to, has the tools of talk and
   judgment: the files (its wiki above all), a quick read or call
   through a connector, schedules and hooks, skills, shares, the tasks
   it starts and the topics it keeps, and `bot` to read the chats of
   before. A task has the tools of work: the files, the connectors,
   the templates' projects and renders, the media tools; no tasks of
   its own, no topics, no schedules. Neither has `remember` or
   `recall`: the wiki is the loop's memory, a task has none (the
   machine's `[:remember]` effect stays for programs). A conversation
   without a role, a bot of before, sees every tool as it did."
  (:require [clojure.string :as str]))

(def coding-tools
  "pi-durable's own: files and bash."
  ["read" "write" "edit" "bash"])

(def loop-tools
  "The tools of the loop."
  (into coding-tools ["fetch" "machine" "schedule" "hook" "skill" "share" "task" "topic" "bot"]))

(def task-tools
  "The tools of a task."
  (into coding-tools ["fetch" "machine" "project" "render" "image" "voice" "music" "transcribe" "youtube"]))

(defn- named
  "The tools as pi-durable's `modelTools` takes them: a list of
   objects it reads the `name` of."
  [names]
  (clj->js (mapv (fn [name] {:name name}) names)))

(defn model-tools
  "The tools the model of a conversation with `role` sees, as the
   list the agent's configuration takes; nil for no role, which leaves
   every tool in sight."
  [role]
  (case role
    :loop (named loop-tools)
    :task (named task-tools)
    nil))

(defn describe
  "The tools of `role` as a line, for a log or a test."
  [role]
  (str/join " " (case role :loop loop-tools :task task-tools ["every tool"])))
