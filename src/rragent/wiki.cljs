(ns rragent.wiki
  "The wiki of the machine: what its bots know, compiled by them,
   after Karpathy's LLM wiki (gist 442a6bf555914893e9891c11519de94f):
   `shared/wiki/` of the home, one git repository of Markdown pages
   that every bot reads, searches and writes (docs/bots.md §4),
   `index.md` the catalog of the pages by category with a summary and
   a date each, `log.md` the append-only timeline with a parseable
   prefix per entry, `README.md` the schema of the pages the bots and
   the person settle, `raw/` what the person brought as it came, never
   changed, `topics/<name>.md` one page per matter, `sources/<name>.md`
   one summary per raw file, `notes/<topic>.md` answers filed back,
   `decisions.md`, `brand.md`, `people.md`, and `preferences.md`, what
   the person asked of their bots (`preferences-file`). A bot ingests
   (a result, an approval, a decision goes into the pages, one commit
   with the log line as its message), queries (reads the index and its
   matter's page, not its own transcript) and writes what must survive
   before its conversation is compacted (`rragent.compaction`), as
   `deploy/agent/BOT.md` tells it. Git keeps the history of every
   page, a bad rewrite is a revert away. `seed!` makes the folder with
   its three files and the repository's first commit when the machine
   has none yet (`rragent.machine/open!`); a machine without git gets
   the files alone and a line on stderr. The wiki of before, the one
   conversation's in its bot's folder, is moved here once (`adopt!`)."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn dir
  "The wiki of the machine whose folder is `home`."
  [home]
  (path/join home "shared" "wiki"))

(defn of-bot
  "The wiki of the machine as a bot's folder finds it, `bots/<id>/`
   of the home."
  [bot-dir]
  (dir (path/dirname (path/dirname bot-dir))))

(defn before
  "Where the wiki of one bot lay before the bots shared one: `wiki/`
   in the folder of the manager, then of the one conversation."
  [bot-dir]
  (path/join bot-dir "wiki"))

(defn preferences-file
  "What the person asked of their bots, `preferences.md` of the wiki,
   the person's page: appended to every bot's definition at every
   read (`rragent.reload`), so that it shapes every turn and every
   run, and in the wiki, so that git keeps its history. Made by a bot
   at the first preference."
  [home]
  (path/join (dir home) "preferences.md"))

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
       "- preferences.md — what the person asked of their bots, one line each; appended to every bot's instructions at every turn\n"
       "- people.md — who talks to the bots: first name, role, what they decide, what they prefer\n"
       "- brand.md — the person's brand: names, links, colors, fonts, pictures, tone\n"
       "- decisions.md — what was decided, dated, with the why\n\n"
       "## Topics\n\n(none yet: topics/<name>.md, one per matter)\n\n"
       "## Sources\n\n(none yet: sources/<name>.md, one summary per file under raw/)\n\n"
       "## Notes\n\n(none yet: notes/<topic>.md, answers worth keeping)\n"))

(defn log-seed
  "The log's first entry, in the prefix every entry starts with:
   `## [date] kind | what`, so that `grep \"^## \\[\" log.md | tail -5`
   lists the newest."
  [now]
  (str "# Log\n\n"
       "The timeline of this wiki, append only, newest last. Every entry starts with `## [YYYY-MM-DD] kind | what` "
       "(kinds: ingest, query, lint, decision, approval), with a line on the pages it touched and the bot that wrote it under it.\n\n"
       "## [" (day now) "] seed | the wiki begins\n\n"
       "index.md, log.md and README.md written by rragent.\n"))

(defn readme-seed
  "The schema of the wiki as it starts: the layers, the page
   conventions, what the person and the bots may change here."
  []
  (str "# This wiki\n\n"
       "What the bots of this machine know of the person's matters, compiled by them, after Karpathy's LLM wiki: `raw/` holds what the person brought, "
       "as it came and never changed (pictures under `raw/assets/`); the pages are the bots'; this file holds the conventions "
       "the bots and the person settle together. Every bot's instructions say how the wiki is run; this file says how its pages look.\n\n"
       "## Pages\n\n"
       "- Every page starts with a frontmatter: `updated: YYYY-MM-DD` and `sources: [...]`, the raw files and pages it rests on, and links the pages it mentions.\n"
       "- `topics/<name>.md`: goal · scope now · later · decisions (dated) · what was approved · open items · next step · the bot that works on it.\n"
       "- `sources/<name>.md`: the summary of one file under `raw/`, with the takeaways and what it changed in other pages.\n"
       "- `notes/<topic>.md`: an answer worth keeping, a comparison, an analysis, a plan.\n"
       "- `decisions.md`, `brand.md`, `preferences.md`, `people.md`: one each.\n\n"
       "## Changing this file\n\n"
       "When a convention proves wrong, a bot changes it here with the person's word, in a commit of its own, logged as a decision.\n"))

(defn- git
  "`git args` in `dir`: `{:status :out :err}`. No signing, no system
   config, a fixed author: rragent."
  [dir & args]
  (let [result (child/spawnSync "git"
                                (clj->js (concat ["-c" "user.name=rragent" "-c" "user.email=rragent@rragent"
                                                  "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main"]
                                                 args))
                                #js {:cwd dir :encoding "utf8" :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_CONFIG_NOSYSTEM "1"})})]
    {:status (.-status result)
     :out (str (.-stdout result))
     :err (str (.-stderr result))
     :error (.-error result)}))

(defn seed!
  "The wiki of the machine in `home` made when there is none: the
   folder, `index.md`, `log.md`, `README.md`, and the repository with
   its first commit. Answers `:seeded`, `:present` for a folder that
   is there already (left as it is), or `:no-git` when the files are
   there but git is not, or refused (said on stderr)."
  [home now]
  (let [wiki (dir home)]
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
              (do (js/console.error "[wiki]" (str "the wiki of " home " is not a git repository: " failed))
                  :no-git)
              :seeded))))))

(defn adopt!
  "The wiki one bot kept in its folder `bot-dir` (`before`: the
   manager's, then the one conversation's) moved to the machine's
   place, `shared/wiki/` of `home`, with its git history, one rename;
   a link stays where it lay, so that a path the bot's chat names
   still reads. Nothing when the machine has a wiki already or the
   bot has none. Answers `:moved`, else nil."
  [home bot-dir]
  (let [from (before bot-dir)
        to (dir home)]
    (when (and (not (fs/existsSync to))
               (fs/existsSync from)
               (not (.isSymbolicLink (fs/lstatSync from))))
      (fs/mkdirSync (path/dirname to) #js {:recursive true})
      (fs/renameSync from to)
      (try
        (fs/symlinkSync (path/relative bot-dir to) from "dir")
        (catch :default error
          (js/console.error "[wiki]" (str "no link from " from " to the machine's wiki: " (.-message error)))))
      :moved)))

(defn commits
  "The number of commits of the wiki of the machine in `home`, 0
   without a repository."
  [home]
  (let [{:keys [status out]} (git (dir home) "rev-list" "--count" "HEAD")]
    (if (zero? status)
      (js/parseInt (str/trim out))
      0)))
