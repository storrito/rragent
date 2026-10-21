(ns rragent.deploy-test
  "What the Sprite image is built with: the skills repository as a
   build argument of both Dockerfiles, passed through by the composite
   action, the placeholder of the generic definition filled by the
   Dockerfile's sed, and start.sh's pull of the clone, run against a
   bare repository in a temporary folder."
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
        (is (re-find #"RUN sed -i .*SKILLS_REPO.* /home/sprite/rragent/agent/AGENT.md" dockerfile) "the placeholder of the definition filled"))))
  (testing "the action passes both through, with the same defaults"
    (let [action (text ".github/actions/sprite-image/action.yml")]
      (is (re-find #"skills-repo:\n(.*\n){1,3}?\s+default: storrito/agentitos\n" action))
      (is (re-find #"skills-branch:\n(.*\n){1,3}?\s+default: main\n" action))
      (is (str/includes? action "SKILLS_REPO: ${{ inputs.skills-repo }}"))
      (is (str/includes? action "SKILLS_BRANCH: ${{ inputs.skills-branch }}"))
      (is (= 1 (count (re-seq #"--build-arg \"SKILLS_REPO=\$SKILLS_REPO\" --build-arg \"SKILLS_BRANCH=\$SKILLS_BRANCH\"" action))) "the one image, deploy/Dockerfile.full")
      (is (not (str/includes? action "-plain")) "no plain image is published"))))

(deftest the-definition-names-the-repository-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-deploy-"))
        copy (path/join dir "AGENT.md")
        [_ sed] (re-find #"RUN (sed -i .* /home/sprite/rragent/agent/AGENT.md)" (text "deploy/Dockerfile"))]
    (try
      (fs/copyFileSync "deploy/agent/AGENT.md" copy)
      (let [{:keys [status]} (sh dir (-> sed
                                         (str/replace "${SKILLS_REPO}" "acme/skills")
                                         (str/replace "/home/sprite/rragent/agent/AGENT.md" copy)))
            filled (text copy)]
        (is (= 0 status))
        (is (str/includes? filled "github.com/acme/skills") "the Dockerfile's sed names the repository of the build argument")
        (is (not (str/includes? filled "SKILLS_REPO"))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(defn- pull-skills
  "start.sh's `pull_skills` function, as it stands in the file."
  []
  (let [[_ function] (re-find #"(?s)(pull_skills\(\) \{.*?\n\})" (text "deploy/start.sh"))]
    function))

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
