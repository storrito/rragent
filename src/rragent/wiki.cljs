(ns rragent.wiki
  "The manager's wiki: its compiled knowledge of the person's projects,
   after Karpathy's LLM wiki (gist 442a6bf555914893e9891c11519de94f):
   `wiki/` in the manager's folder, a git repository of Markdown pages
   the manager writes and reads, `index.md` the catalog of the pages,
   `log.md` the append-only record of what it did and when, `raw/`
   what the person brought as it came, `projects/<name>.md` one page
   per project, `workers/<id>.md` one per worker, `decisions.md`,
   `brand.md`. The manager ingests (a report, an approval, a decision
   goes into the pages, one commit with the log line as its message),
   queries (reads the index and the project's page at the start of a
   turn, not its own events) and lints (a weekly look for
   contradictions and stale items), as `deploy/agent/MANAGER.md`
   tells it. One writer, the manager; git keeps the history of every
   page, a bad rewrite is a revert away. `seed!` makes the folder with
   its two files and the repository's first commit when the manager's
   folder has none yet (`rragent.machine/open!`); a machine without
   git gets the files alone and a line on stderr."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn dir
  "The wiki of the bot whose folder is `bot-dir`."
  [bot-dir]
  (path/join bot-dir "wiki"))

(defn- day
  "`2026-11-04` of the time `ms`."
  [ms]
  (subs (.toISOString (js/Date. ms)) 0 10))

(defn index-seed
  "The catalog as it starts: the two pages that are there, the folders
   that will be."
  []
  (str "# Index\n\n"
       "The catalog of this wiki: every page with a line on what it holds. Keep it current when a page comes or goes.\n\n"
       "- [log.md](log.md) — what happened and when, append-only, newest last\n"
       "- projects/ — one page per project of the person: goal, scope now and later, decisions, workers and their versions, open items, next step\n"
       "- workers/ — one page per worker bot: its job, its contract, what it delivered, what it asked\n"
       "- decisions.md — what was decided across projects, dated, with why\n"
       "- brand.md — the person's brand: names, links, colors, fonts, pictures, tone\n"
       "- raw/ — what the person brought, as it came: uploads, pasted mails, reports\n"))

(defn log-seed
  "The log's first line."
  [now]
  (str "# Log\n\n"
       "One line per ingest, query worth noting, lint pass or decision: the date, what, and the page it touched. Append only.\n\n"
       "- " (day now) " · the wiki begins\n"))

(defn- git
  "`git args` in `dir`: `{:status :out :err}`. No signing, no system
   config, a fixed author: the manager."
  [dir & args]
  (let [result (child/spawnSync "git"
                                (clj->js (concat ["-c" "user.name=Manager" "-c" "user.email=manager@rragent"
                                                  "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main"]
                                                 args))
                                #js {:cwd dir :encoding "utf8" :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_CONFIG_NOSYSTEM "1"})})]
    {:status (.-status result)
     :out (str (.-stdout result))
     :err (str (.-stderr result))
     :error (.-error result)}))

(defn seed!
  "The wiki of `bot-dir` made when there is none: the folder,
   `index.md`, `log.md`, and the repository with its first commit.
   Answers `:seeded`, `:present` for a folder that is there already
   (left as it is), or `:no-git` when the files are there but git is
   not, or refused (said on stderr)."
  [bot-dir now]
  (let [wiki (dir bot-dir)]
    (if (fs/existsSync wiki)
      :present
      (do (fs/mkdirSync wiki #js {:recursive true})
          (fs/writeFileSync (path/join wiki "index.md") (index-seed))
          (fs/writeFileSync (path/join wiki "log.md") (log-seed now))
          (let [steps [["init" "-q"] ["add" "-A"] ["commit" "-q" "-m" "The wiki begins"]]
                failed (some (fn [args]
                               (let [{:keys [status error err]} (apply git wiki args)]
                                 (when (or error (not (zero? status)))
                                   (str "git " (str/join " " args) ": " (or (some-> error .-message) (str/trim err))))))
                             steps)]
            (if failed
              (do (js/console.error "[wiki]" (str "the wiki of " bot-dir " is not a git repository: " failed))
                  :no-git)
              :seeded))))))

(defn commits
  "The number of commits of the wiki of `bot-dir`, 0 without a
   repository."
  [bot-dir]
  (let [{:keys [status out]} (git (dir bot-dir) "rev-list" "--count" "HEAD")]
    (if (zero? status)
      (js/parseInt (str/trim out))
      0)))
