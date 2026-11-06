(ns rragent.deploy-test
  "What the Sprite image is built with: the skills repository as a
   build argument of both Dockerfiles, passed through by the composite
   action, what the shared prompt tells every bot, start.sh's pull of
   the clone, run against a bare
   repository in a temporary folder, and start.sh's loop that starts
   rragent again after an update, run with a fake program."
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

(deftest the-skills-repository-is-a-build-argument-test
  (doseq [file ["deploy/Dockerfile" "deploy/Dockerfile.full"]]
    (testing file
      (let [dockerfile (text file)]
        (is (str/includes? dockerfile "ARG SKILLS_REPO=storrito/agentitos\n"))
        (is (str/includes? dockerfile "ARG SKILLS_BRANCH=main\n"))
        (is (str/includes? dockerfile "git clone --depth 1 --branch \"${SKILLS_BRANCH}\" \"https://github.com/${SKILLS_REPO}.git\" /home/sprite/skills")
            "the clone, after USER sprite so that sprite owns it")
        (is (< (str/index-of dockerfile "USER sprite\n") (str/index-of dockerfile "git clone")))
        (is (not (str/includes? dockerfile "deploy/agent/")) "no definition in the image: the shared prompt is the bundle's, a new bot's file rragent's"))))
  (testing "the action passes both through, with the same defaults"
    (let [action (text ".github/actions/sprite-image/action.yml")]
      (is (re-find #"skills-repo:\n(.*\n){1,3}?\s+default: storrito/agentitos\n" action))
      (is (re-find #"skills-branch:\n(.*\n){1,3}?\s+default: main\n" action))
      (is (str/includes? action "SKILLS_REPO: ${{ inputs.skills-repo }}"))
      (is (str/includes? action "SKILLS_BRANCH: ${{ inputs.skills-branch }}"))
      (is (= 1 (count (re-seq #"--build-arg \"SKILLS_REPO=\$SKILLS_REPO\" --build-arg \"SKILLS_BRANCH=\$SKILLS_BRANCH\"" action))) "the one image, deploy/Dockerfile.full")
      (is (not (str/includes? action "-plain")) "no plain image is published"))))

(deftest the-shared-prompt-tells-the-bot-to-name-itself-test
  (let [text (fs/readFileSync "deploy/agent/SYSTEM-PROMPT.md" "utf8")]
    (is (str/includes? text "Name yourself:"))
    (is (str/includes? text "`name:` line"))
    (is (str/includes? text "`renamed: true`") "the person's rename, which the bot leaves alone")))

(deftest the-shared-prompt-holds-the-rules-test
  (let [text (fs/readFileSync "deploy/agent/SYSTEM-PROMPT.md" "utf8")]
    (testing "a text, not a definition: no frontmatter, nothing copied into a bot's file"
      (is (not (str/starts-with? text "---")))
      (is (not (str/includes? text "word for word")))
      (is (str/includes? text "`<agent>/AGENT.md`") "the bot's file, its folder filled in at the read"))
    (testing "the bot's file is the job, changed when the job changes; a file of before is rewritten once"
      (is (str/includes? text "## Your file"))
      (is (str/includes? text "never a log"))
      (is (str/includes? text "costs the\nwhole conversation its cache"))
      (is (str/includes? text "a copy of these rules as they were then")))
    (testing "a new bot offers the skills and takes one by naming it in its frontmatter"
      (is (str/includes? text "## A new bot"))
      (is (str/includes? text "`skill: <the skill's folder name>`"))
      (is (str/includes? text "github.com/SKILLS_REPO") "the placeholder rragent fills from the clone"))
    (testing "the rules of runs, the job, skills, offers and failed steps"
      (is (str/includes? text "## This machine"))
      (is (str/includes? text "start a run of yours"))
      (is (str/includes? text "skill create"))
      (is (str/includes? text "never to learn how to use it"))
      (is (str/includes? text "`write` is for a file that does not exist yet"))
      (is (str/includes? text "Offer what the person may not know exists"))
      (is (str/includes? text "none twice"))
      (is (str/includes? text "When a step of yours did not work")))))

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
  (let [[_ function] (re-find #"(?s)(pull_skills\(\) \{.*?\n\})" (text "deploy/start.sh"))]
    function))

(defn- run-until-updated
  "start.sh's `run_until_updated` function, as it stands in the file."
  []
  (let [[_ function] (re-find #"(?s)(run_until_updated\(\) \{.*?\n\})" (text "deploy/start.sh"))]
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
      (testing "the script runs the serve in the loop, after the one pull, the idle update told"
        (let [script (text "deploy/start.sh")]
          (is (str/ends-with? (str/trim script) "run_until_updated node dist/rragent.js serve"))
          (is (< (str/index-of script "pull_skills /home/sprite/skills") (str/index-of script "run_until_updated node")))
          (is (= 1 (count (re-seq #"pull_skills /home/sprite/skills" script))) "the pull once, not again after an update")
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
          (is (str/includes? err "the clone from the image stands"))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))
