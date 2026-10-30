(ns rragent.hyperframes-skills-test
  "The HyperFrames skills at the start, against a fake CLI: a passing
   check installs nothing, a failing one installs, and a machine
   without the CLI is said so."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.test :refer [deftest is testing async]]
            [rragent.hyperframes-skills :as sut]))

(defn- fake-cli
  "A HyperFrames CLI that writes each call into `log`; `skills check`
   ends with `check-status`."
  [dir check-status]
  (let [cli (path/join dir "hyperframes")
        log (path/join dir "calls")]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync cli (str "#!/bin/sh\n"
                               "echo \"$*\" >> " log "\n"
                               "if [ \"$*\" = \"skills check\" ]; then echo 'No HyperFrames skills found'; exit " check-status "; fi\n"
                               "echo 'installed'\n"))
    (fs/chmodSync cli 0755)
    [cli log]))

(defn- calls
  [log]
  (if (fs/existsSync log) (fs/readFileSync log "utf8") ""))

(deftest ensure-test
  (async done
    (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-hf-skills-"))
          env (.-env js/process)
          before (aget env "RRAGENT_HYPERFRAMES")]
      (-> ((^:async fn []
             (testing "the skills are there: nothing installed"
               (let [[cli log] (fake-cli (path/join dir "ok") 0)]
                 (aset env "RRAGENT_HYPERFRAMES" cli)
                 (is (= "hyperframes skills: there" (await (sut/ensure!))))
                 (is (= "skills check\n" (calls log)))))
             (testing "the check fails: they are installed"
               (let [[cli log] (fake-cli (path/join dir "missing") 1)]
                 (aset env "RRAGENT_HYPERFRAMES" cli)
                 (is (= "hyperframes skills: installed, the check had failed" (await (sut/ensure!))))
                 (is (= "skills check\nskills\n" (calls log)))))
             (testing "no CLI on the machine"
               (aset env "RRAGENT_HYPERFRAMES" (path/join dir "nowhere" "hyperframes"))
               (is (re-find #"no HyperFrames CLI|failed" (await (sut/ensure!)))))))
          (.finally (fn []
                      (if before (aset env "RRAGENT_HYPERFRAMES" before) (js-delete env "RRAGENT_HYPERFRAMES"))
                      (fs/rmSync dir #js {:recursive true :force true})
                      (done)))))))
