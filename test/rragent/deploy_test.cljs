(ns rragent.deploy-test
  "What the Sprite image is built with and what start.sh does around
   it: no skills and no bundle in the image, what the shared prompt
   tells every bot, start.sh's clone and pull of the skills, run
   against a bare repository in a temporary folder, its download of
   the bundle, run against a release under a file:// URL, and its loop
   that starts rragent again after an update, run with a fake program."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- text
  [file]
  (fs/readFileSync file "utf8"))

(defn- sh
  "`script` run by bash in `dir`: `{:status :out :err}`."
  [dir script]
  (let [result (child/spawnSync "bash" #js ["-c" script] #js {:cwd dir :encoding "utf8"})]
    {:status (.-status result) :out (str (.-stdout result)) :err (str (.-stderr result))}))

(deftest the-skills-are-not-in-the-image-test
  (testing "the Dockerfile clones nothing: start.sh does, at the first start"
    (let [dockerfile (text "deploy/image/Dockerfile")]
      (is (not (str/includes? dockerfile "git clone")))
      (is (not (str/includes? dockerfile "SKILLS_REPO=")) "no build argument for it either")
      (is (not (str/includes? dockerfile "deploy/agent/")) "no definition in the image: the shared prompt is the bundle's, a new bot's file rragent's")))
  (testing "start.sh clones from SKILLS_REPO at SKILLS_BRANCH, the agentitos defaults"
    (let [script (text "deploy/image/start.sh")]
      (is (str/includes? script "https://github.com/${SKILLS_REPO:-storrito/agentitos}.git"))
      (is (str/includes? script "--branch \"${SKILLS_BRANCH:-main}\""))
      (is (str/includes? script "--depth 1") "one commit deep, as the image's clone was")))
  (testing "the action passes no skills arguments any more"
    (let [action (text ".github/actions/sprite-image/action.yml")]
      (is (not (str/includes? action "skills-repo")))
      (is (not (str/includes? action "--build-arg")))
      (is (not (str/includes? action "-plain")) "no plain image is published"))))

(deftest the-shared-prompt-is-for-every-conversation-test
  (let [text (fs/readFileSync "deploy/agent/SYSTEM-PROMPT.md" "utf8")]
    (testing "a text, not a definition: no frontmatter, for the bots and the tasks alike"
      (is (not (str/starts-with? text "---")))
      (is (str/starts-with? text "# Every conversation of this machine"))
      (is (str/includes? text "the\nbots the person talks to and the tasks they start"))
      (is (not (str/includes? text "## Your file")) "what is a bot's alone is the bot's text: a task has no file")
      (is (not (str/includes? text "bot create")))
      (is (not (str/includes? text "`remember`")) "a task has no events tools")
      (is (not (str/includes? text "Name yourself")))
      (is (not (str/includes? text "start a run of yours")))
      (is (not (str/includes? text "topic")) "no topics any more"))
    (testing "the skills: where they are, a skill a job names read and done"
      (is (str/includes? text "## Skills"))
      (is (str/includes? text "github.com/SKILLS_REPO") "the placeholder rragent fills from the clone")
      (is (str/includes? text "read that skill's `SKILL.md`"))
      (is (str/includes? text "never to learn how to use it")))
    (testing "the rules of files, programs, offers and failed steps"
      (is (str/includes? text "## Files and programs"))
      (is (str/includes? text "`write` is for a file that does not exist yet"))
      (is (str/includes? text "[:remember ...]") "programs keep their events through the machine's effects")
      (is (str/includes? text "Offer what the person may not know exists"))
      (is (str/includes? text "none twice"))
      (is (str/includes? text "When a step of yours did not work")))))

(deftest the-bot-and-task-texts-test
  (let [bot-text (fs/readFileSync "deploy/agent/BOT.md" "utf8")
        task-text (fs/readFileSync "deploy/agent/TASK.md" "utf8")]
    (testing "a bot's text: no frontmatter, what a bot is, after the shared prompt"
      (is (str/starts-with? bot-text "# A bot of this machine"))
      (is (str/includes? bot-text "## Your file"))
      (is (str/includes? bot-text "## A new bot"))
      (is (str/includes? bot-text "skill: <the skill's folder name>") "a bot takes a skill into its file")
      (is (str/includes? bot-text "Name yourself"))
      (is (str/includes? bot-text "skill create"))
      (is (str/includes? bot-text "## Tasks"))
      (is (str/includes? bot-text "`wait: true`"))
      (is (str/includes? bot-text "## Runs"))
      (is (str/includes? bot-text "A hook's call and a schedule's fire start a run of yours"))
      (is (str/includes? bot-text "`mode: message`"))
      (is (str/includes? bot-text "## The other bots"))
      (is (str/includes? bot-text "`bot create`"))
      (is (str/includes? bot-text "`bot transcript`"))
      (is (str/includes? bot-text "## The wiki"))
      (is (str/includes? bot-text "`<wiki>/`") "the placeholder the definition fills with the machine's wiki")
      (is (str/includes? bot-text "`topics/<name>.md`"))
      (is (str/includes? bot-text "never `git add -A`") "the bots share the repository")
      (is (str/includes? bot-text "`<wiki>/preferences.md`"))
      (is (str/includes? bot-text "## When your memory is about to shrink"))
      (is (str/includes? bot-text "answer \"ready\" and nothing else"))
      (is (str/includes? bot-text "## Asking the person"))
      (is (str/includes? bot-text "is opened by its project,\n`artifacts/<project>`") "a card plays a design, it does not browse its source")
      (is (not (str/includes? bot-text "`topic create`")) "no topics")
      (is (not (str/includes? bot-text "manager"))))
    (testing "a task's text: no frontmatter, the result rules"
      (is (str/starts-with? task-text "# A task"))
      (is (str/includes? task-text "Your last message is your result"))
      (is (str/includes? task-text "You answer a bot, not a person"))
      (is (str/includes? task-text "a design made with `project` by its\nproject, `artifacts/<project>`"))
      (is (str/includes? task-text "set none, write\nnone, start none")))))

(deftest the-shared-prompt-offers-the-connection-test
  (testing "a service the person names is connected in the same answer, through a machine"
    (let [text (fs/readFileSync "deploy/agent/SYSTEM-PROMPT.md" "utf8")]
      (is (str/includes? text "offer the connection in the same answer"))
      (is (str/includes? text "(composio/connections [\"googlesheets\"])"))
      (is (str/includes? text "in your `machine` tool"))
      (is (str/includes? text "good for ten minutes")))))

(defn- pull-skills
  "start.sh's `pull_skills` function, as it stands in the file."
  []
  (let [[_ function] (re-find #"(?s)(pull_skills\(\) \{.*?\n\})" (text "deploy/image/start.sh"))]
    function))

(defn- run-until-updated
  "start.sh's `run_until_updated` function, as it stands in the file."
  []
  (let [[_ function] (re-find #"(?s)(run_until_updated\(\) \{.*?\n\})" (text "deploy/image/start.sh"))]
    function))

(defn- fake-program!
  "A program that notes each run in `runs` of `dir` and ends with
   `first-code` on its first run, 0 after."
  [dir first-code]
  (let [program (path/join dir "rragent")]
    (fs/writeFileSync program (str "#!/bin/sh\n"
                                   "echo run >> \"" dir "/runs\"\n"
                                   "[ \"$(wc -l < \"" dir "/runs\")\" -eq 1 ] && exit " first-code "\n"
                                   "exit 0\n"))
    (fs/chmodSync program 448)
    program))

(defn- runs
  [dir]
  (if (fs/existsSync (path/join dir "runs"))
    (count (str/split-lines (str/trim (text (path/join dir "runs")))))
    0))

(deftest start-sh-starts-again-after-an-update-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-start-"))]
    (try
      (is (string? (run-until-updated)) "the function is in start.sh")
      (testing "75 starts the program again; its next exit ends the loop with that code"
        (let [program (fake-program! dir 75)
              {:keys [status out err]} (sh dir (str (run-until-updated) "\nrun_until_updated " program " && echo ended"))]
          (is (= 0 status))
          (is (= 2 (runs dir)) "run twice")
          (is (= "ended" (str/trim out)))
          (is (= 1 (count (str/split-lines (str/trim err)))) err)
          (is (str/includes? err "starting it again"))))
      (testing "any other code ends the loop with it, at once and quietly"
        (fs/rmSync (path/join dir "runs") #js {:force true})
        (let [program (fake-program! dir 3)
              {:keys [status err]} (sh dir (str (run-until-updated) "\nrun_until_updated " program))]
          (is (= 3 status))
          (is (= 1 (runs dir)))
          (is (= "" err))))
      (testing "the script runs the serve in the loop, after the skills, the idle update told"
        (let [script (text "deploy/image/start.sh")]
          (is (str/ends-with? (str/trim script) "run_until_updated node dist/rragent.js serve"))
          (is (< (str/index-of script "skills /home/sprite/skills") (str/index-of script "run_until_updated node")))
          (is (= 1 (count (re-seq #"\nskills /home/sprite/skills" script))) "the clone or pull once, not again after an update")
          (is (str/includes? script "export RRAGENT_UPDATE=\"${RRAGENT_UPDATE:-auto}\""))
          (is (not (str/includes? script "exec node")))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest start-sh-pulls-the-skills-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-skills-"))
        git "git -c user.name=t -c user.email=t@example.com -c init.defaultBranch=main"
        {:keys [status err]} (sh dir (str git " init -q --bare origin.git"
                                          " && " git " clone -q origin.git author"
                                          " && cd author && echo one > SKILL.md && " git " add . && " git " commit -q -m first && " git " push -q origin main"
                                          " && cd .. && " git " clone -q --depth 1 --branch main origin.git machine"
                                          " && cd author && echo two > SKILL.md && " git " commit -q -am second && " git " push -q origin main"))]
    (try
      (is (= 0 status) err)
      (is (string? (pull-skills)) "the function is in start.sh")
      (testing "a machine that wakes has the new commit"
        (let [{:keys [status out err]} (sh dir (str (pull-skills) "\npull_skills machine && git -C machine log -1 --format=%s"))]
          (is (= 0 status))
          (is (= "second" (str/trim out)))
          (is (= "" err) "nothing said when the pull works")))
      (testing "a pull that fails is said in one line and does not stop the start"
        (fs/rmSync (path/join dir "origin.git") #js {:recursive true :force true})
        (let [{:keys [status err]} (sh dir (str (pull-skills) "\npull_skills machine && echo started"))]
          (is (= 0 status))
          (is (= 1 (count (str/split-lines (str/trim err)))) err)
          (is (str/includes? err "the skills in machine were not pulled"))
          (is (str/includes? err "the clone that stands stays"))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest the-image-has-a-version-of-its-own-test
  (testing "the Dockerfile carries the version label and no bundle"
    (let [dockerfile (text "deploy/image/Dockerfile")]
      (is (re-find #"\nLABEL org\.opencontainers\.image\.version=\"\d+\.\d+\.\d+\"\n" dockerfile) "the version the image is tagged with, bumped with every change in deploy/image/")
      (is (not (re-find #"COPY[^\n]*dist" dockerfile)) "no bundle in the image: start.sh downloads the release")
      (is (str/includes? dockerfile "COPY --chown=sprite:sprite --chmod=700 start.sh /home/sprite/rragent/start.sh") "start.sh from the build context deploy/image")))
  (testing "the Image workflow tags with the label's version, refuses one that exists on main, a trial version on a branch"
    (let [workflow (text ".github/workflows/image.yml")]
      (is (str/includes? workflow "org.opencontainers.image.version=") "the version read from the Dockerfile")
      (is (str/includes? workflow "- deploy/image/**") "a push to main that touches the folder")
      (is (str/includes? workflow "-trial.") "a branch's build is a pre-release version")
      (is (str/includes? workflow "when-there: ${{ steps.tag.outputs.when-there }}") "refuse on main, skip for a trial")))
  (testing "the action builds from deploy/image and refuses an existing tag unless told to skip"
    (let [action (text ".github/actions/sprite-image/action.yml")]
      (is (str/includes? action "-f deploy/image/Dockerfile"))
      (is (str/ends-with? (str/trim (re-find #"docker build[^\n]*" action)) "deploy/image") "the build context")
      (is (str/includes? action "default: refuse"))
      (is (not (str/includes? action "dist/rragent.js")) "no bundle needed for the build"))))

(defn- function-of
  "A function of start.sh, as it stands in the file."
  [name]
  (let [[_ function] (re-find (re-pattern (str "(?s)\\n(" name "\\(\\) \\{.*?\\n\\})")) (text "deploy/image/start.sh"))]
    function))

(defn- release!
  "A release's files under `dir`: rragent.tar.gz holding `files` (name
   to text) and its checksum, as `npm run release` packs them; the
   checksum spoiled with `:broken? true`."
  [dir files & {:keys [broken?]}]
  (fs/mkdirSync (path/join dir "dist") #js {:recursive true})
  (doseq [[name content] files]
    (fs/writeFileSync (path/join dir "dist" name) content))
  (let [{:keys [status err]} (sh dir (str "tar -czf rragent.tar.gz -C dist . && rm -r dist && sha256sum rragent.tar.gz > rragent.tar.gz.sha256"
                                          (when broken? " && sed -i 's/^./x/' rragent.tar.gz.sha256")))]
    (when-not (= 0 status)
      (throw (ex-info "no release made" {:err err})))))

(deftest start-sh-takes-the-bundle-from-the-release-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-bootstrap-"))
        releases (path/join dir "releases")
        install (path/join dir "install")
        functions (str (function-of "release_url") "\n" (function-of "bootstrap"))
        run (fn [env]
              (sh dir (str "export " env "\n" functions "\nbootstrap " install " && echo in place")))]
    (fs/mkdirSync install)
    (try
      (is (string? (function-of "release_url")) "the function is in start.sh")
      (is (string? (function-of "bootstrap")) "the function is in start.sh")
      (testing "the newest release's tarball, verified and unpacked into dist/"
        (release! (path/join releases "latest/download") {"rragent.js" "console.log('newest')" "system-prompt.md" "rules"})
        (let [{:keys [status out err]} (run (str "RRAGENT_RELEASES=file://" releases " RRAGENT_UPDATE=auto"))]
          (is (= 0 status) err)
          (is (= "in place" (str/trim out)))
          (is (= "console.log('newest')" (text (path/join install "dist/rragent.js"))))
          (is (= "rules" (text (path/join install "dist/system-prompt.md"))))
          (is (str/includes? err "taking the bundle from file://") "said on stderr")
          (is (= ["dist"] (vec (fs/readdirSync install))) "no temp folder left")))
      (testing "a dist/ that is there is left alone, nothing downloaded"
        (fs/rmSync releases #js {:recursive true :force true})
        (let [{:keys [status out err]} (run (str "RRAGENT_RELEASES=file://" releases))]
          (is (= 0 status))
          (is (= "in place" (str/trim out)))
          (is (= "" err))
          (is (= "console.log('newest')" (text (path/join install "dist/rragent.js"))))))
      (testing "RRAGENT_UPDATE names a release: its tarball, not the newest"
        (fs/rmSync (path/join install "dist") #js {:recursive true :force true})
        (release! (path/join releases "download/trial-loop-1a2b3c4") {"rragent.js" "console.log('trial')"})
        (let [{:keys [status err]} (run (str "RRAGENT_RELEASES=file://" releases " RRAGENT_UPDATE=trial-loop-1a2b3c4"))]
          (is (= 0 status) err)
          (is (= "console.log('trial')" (text (path/join install "dist/rragent.js"))))
          (is (= "trial-loop-1a2b3c4\n" (text (path/join install "release"))) "the marker names the release in place, for rragent.update")))
      (testing "a checksum that does not match: nothing in place, said, 1"
        (fs/rmSync (path/join install "dist") #js {:recursive true :force true})
        (fs/rmSync (path/join install "release") #js {:force true})
        (fs/rmSync releases #js {:recursive true :force true})
        (release! (path/join releases "latest/download") {"rragent.js" "console.log('tampered')"} :broken? true)
        (let [{:keys [status out err]} (run (str "RRAGENT_RELEASES=file://" releases " RRAGENT_UPDATE=auto"))]
          (is (= 1 status))
          (is (= "" (str/trim out)))
          (is (str/includes? err "could not be taken") err)
          (is (not (fs/existsSync (path/join install "dist"))))
          (is (= [] (vec (fs/readdirSync install))) "no temp folder left")))
      (testing "no release there: nothing in place, 1"
        (fs/rmSync releases #js {:recursive true :force true})
        (let [{:keys [status err]} (run (str "RRAGENT_RELEASES=file://" releases " RRAGENT_UPDATE=auto"))]
          (is (= 1 status))
          (is (str/includes? err "could not be taken") err)
          (is (not (fs/existsSync (path/join install "dist"))))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest the-trial-workflow-publishes-a-pre-release-test
  (let [workflow (text ".github/workflows/trial.yml")]
    (is (str/includes? workflow "workflow_dispatch:") "by hand on a branch")
    (is (str/includes? workflow "tag=trial-$branch-$sha") "the tag names the branch and the commit")
    (is (str/includes? workflow "0.0.0-trial.$SHA") "the bundle says it is a trial")
    (is (str/includes? workflow "--prerelease") "invisible to releases/latest")
    (is (str/includes? workflow "when-there: skip") "the branch's image only when it is not there yet")
    (is (str/includes? workflow "image=$version-trial.$sha") "a pre-release version of the Dockerfile's label, which no provisioner takes")
    (is (not (str/includes? workflow "npm test")) "no tests here, the PR's workflow runs them")))

(deftest start-sh-clones-the-skills-at-the-first-start-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-clone-"))
        git "git -c user.name=t -c user.email=t@example.com -c init.defaultBranch=main"
        {:keys [status err]} (sh dir (str git " init -q --bare origin.git"
                                          " && " git " clone -q origin.git author"
                                          " && cd author && echo one > SKILL.md && " git " add . && " git " commit -q -m first && " git " push -q origin main"
                                          " && echo two > SKILL.md && " git " commit -q -am second && " git " push -q origin main"))
        functions (str (function-of "pull_skills") "\n" (function-of "clone_skills") "\n" (function-of "skills"))]
    (try
      (is (= 0 status) err)
      (is (string? (function-of "clone_skills")) "the function is in start.sh")
      (is (string? (function-of "skills")) "the function is in start.sh")
      (testing "no clone yet: made, one commit deep, from the branch"
        (let [{:keys [status out err]} (sh dir (str "export SKILLS_URL=file://" dir "/origin.git SKILLS_BRANCH=main\n" functions "\nskills machine && cat machine/SKILL.md && git -C machine rev-list --count HEAD"))]
          (is (= 0 status) err)
          (is (= ["two" "1"] (str/split-lines (str/trim out))) "the newest commit alone")
          (is (= "" err) "nothing said when the clone works")))
      (testing "a clone that is there is pulled, not made again"
        (let [{:keys [status out err]} (sh dir (str "cd author && echo three > SKILL.md && " git " commit -q -am third && " git " push -q origin main && cd .. && export SKILLS_URL=file://" dir "/origin.git\n" functions "\nskills machine && cat machine/SKILL.md"))]
          (is (= 0 status) err)
          (is (= "three" (str/trim out)))
          (is (= "" err))))
      (testing "a clone that fails is said in one line and does not stop the start"
        (let [{:keys [status err]} (sh dir (str "export SKILLS_URL=file://" dir "/nowhere.git\n" functions "\nskills other && echo started"))]
          (is (= 0 status))
          (is (= 1 (count (str/split-lines (str/trim err)))) err)
          (is (str/includes? err "the skills were not cloned into other"))
          (is (str/includes? err "the machine starts without them"))
          (is (not (fs/existsSync (path/join dir "other"))) "nothing half made")))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))
