(ns rragent.wiki
  "The manager's wiki: its compiled knowledge of the person's projects,
   after Karpathy's LLM wiki (gist 442a6bf555914893e9891c11519de94f):
   `wiki/` in the manager's folder, a git repository of Markdown pages
   the manager writes and reads, `index.md` the catalog of the pages
   by category with a summary and a date each, `log.md` the
   append-only timeline with a parseable prefix per entry, `README.md`
   the schema of the pages the manager and the person settle, `raw/`
   what the person brought as it came, never changed, `projects/<name>.md`
   one page per project, `workers/<id>.md` one per worker,
   `sources/<name>.md` one summary per raw file, `notes/<topic>.md`
   answers filed back, `decisions.md`, `brand.md`, `preferences.md`
   what the person asked of the manager (`preferences-file`). The manager ingests (a report, an approval, a decision
   goes into the pages, one commit with the log line as its message),
   queries (reads the index and the project's page at the start of a
   turn, not its own events) and lints (a weekly look for
   contradictions and stale items), as `deploy/agent/MANAGER.md`
   tells it. One writer, the manager; git keeps the history of every
   page, a bad rewrite is a revert away. `seed!` makes the folder with
   its three files and the repository's first commit when the manager's
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

(defn preferences-file
  "What the person asked of the manager, `preferences.md` of the wiki,
   the person's page: appended to the manager's definition at every
   read (`rragent.reload`), so that it shapes every turn and every run,
   and in the wiki, so that git keeps its history and the lint sees
   it. Made by the manager at the first preference."
  [bot-dir]
  (path/join (dir bot-dir) "preferences.md"))

(defn- day
  "`2026-11-04` of the time `ms`."
  [ms]
  (subs (.toISOString (js/Date. ms)) 0 10))

(defn index-seed
  "The catalog as it starts: by category, each page with its link, a
   line on what it holds and its date, the categories empty but for
   the files that are there."
  [now]
  (str "# Index\n\n"
       "The catalog of this wiki, by category: every page with its link, a one-line summary and the date it was last updated. "
       "Updated at every ingest, read first at every query.\n\n"
       "## Wiki\n\n"
       "- [README.md](README.md) — the conventions of this wiki, settled with the person · " (day now) "\n"
       "- [log.md](log.md) — the timeline: what happened and when, append-only · " (day now) "\n"
       "- preferences.md — what the person asked of the manager, one line each; appended to the manager's definition at every turn\n"
       "- brand.md — the person's brand: names, links, colors, fonts, pictures, tone\n"
       "- decisions.md — what was decided across projects, dated, with the why\n\n"
       "## Projects\n\n(none yet: projects/<name>.md, one per project)\n\n"
       "## Workers\n\n(none yet: workers/<id>.md, one per worker bot)\n\n"
       "## Sources\n\n(none yet: sources/<name>.md, one summary per file under raw/)\n\n"
       "## Notes\n\n(none yet: notes/<topic>.md, answers worth keeping)\n"))

(defn log-seed
  "The log's first entry, in the prefix every entry starts with:
   `## [date] kind | what`, so that `grep \"^## \\[\" log.md | tail -5`
   lists the newest."
  [now]
  (str "# Log\n\n"
       "The timeline of this wiki, append only, newest last. Every entry starts with `## [YYYY-MM-DD] kind | what` "
       "(kinds: ingest, query, lint, decision, approval), with a line on the pages it touched under it.\n\n"
       "## [" (day now) "] seed | the wiki begins\n\n"
       "index.md, log.md and README.md written by rragent.\n"))

(defn readme-seed
  "The schema of the wiki as it starts: the layers, the page
   conventions, what the person and the manager may change here."
  []
  (str "# This wiki\n\n"
       "The manager's compiled knowledge of the person's projects, after Karpathy's LLM wiki: `raw/` holds what the person brought, "
       "as it came and never changed (pictures under `raw/assets/`); the pages are the manager's; this file holds the conventions "
       "the manager and the person settle together. The manager's definition says how the wiki is run; this file says how its pages look.\n\n"
       "## Pages\n\n"
       "- Every page starts with a frontmatter: `updated: YYYY-MM-DD` and `sources: [...]`, the raw files and pages it rests on, and links the pages it mentions.\n"
       "- `projects/<name>.md`: goal · scope now · later · decisions (dated) · workers with their versions · open items · next step.\n"
       "- `workers/<id>.md`: its job · its contract (what it must deliver, in which form) · what it delivered, by version · what it asked.\n"
       "- `sources/<name>.md`: the summary of one file under `raw/`, with the takeaways and what it changed in other pages.\n"
       "- `notes/<topic>.md`: an answer worth keeping, a comparison, an analysis, a plan.\n"
       "- `decisions.md`, `brand.md`, `preferences.md`: one each.\n\n"
       "## Changing this file\n\n"
       "When a convention proves wrong, the manager changes it here with the person's word, in a commit of its own, logged as a decision.\n"))

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
          (fs/writeFileSync (path/join wiki "index.md") (index-seed now))
          (fs/writeFileSync (path/join wiki "log.md") (log-seed now))
          (fs/writeFileSync (path/join wiki "README.md") (readme-seed))
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
