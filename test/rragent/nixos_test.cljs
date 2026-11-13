(ns rragent.nixos-test
  "The NixOS Sprite (deploy/nixos/) and the Ubuntu one (deploy/image/)
   said by the same things: one start.sh, whose PATH line the NixOS base
   replaces by its exact text, the same versions of the HyperFrames CLI
   and nbb, the same NixOS release in the base's flake and in the flake
   a machine starts with. A Nix build would find each of these too, but
   only when somebody builds the NixOS image; the tests run on every
   push, so a change of start.sh or of the Dockerfile says here what it
   means for the other kind of machine."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- text
  [file]
  (fs/readFileSync file "utf8"))

(defn- nix-string
  "The value of the Nix binding `name = \"...\";` in `source`."
  [source name]
  (second (re-find (re-pattern (str "(?m)^\\s*" name "\\s*=\\s*\"([^\"]*)\";")) source)))

(defn- option-default
  "The default of the option `name` of the base, a string."
  [source name]
  (second (re-find (re-pattern (str "(?s)" name " = lib\\.mkOption \\{.*?default = \"([^\"]*)\";")) source)))

(deftest start-sh-is-the-ubuntu-images-test
  (let [base (text "deploy/nixos/sprite.nix")
        script (text "deploy/image/start.sh")
        line (nix-string base "ubuntuPath")]
    (testing "the PATH line the base replaces is in start.sh, once, as a line of its own"
      (is (str/starts-with? (str line) "export PATH="))
      (is (= 1 (count (filter #(= line %) (str/split-lines script))))
          "deploy/nixos/sprite.nix replaces this line by its exact text: change both together"))
    (testing "the shebang the base replaces"
      (is (str/starts-with? script "#!/bin/bash\n"))
      (is (str/starts-with? (text "deploy/nixos/rragent-system.sh") "#!/bin/bash\n")))
    (testing "the base takes start.sh from the Ubuntu image's folder, it has no copy"
      (is (str/includes? base "${../image/start.sh}"))
      (is (not (fs/existsSync "deploy/nixos/start.sh"))))))

(deftest the-same-tools-on-both-machines-test
  (let [base (text "deploy/nixos/sprite.nix")
        dockerfile (text "deploy/image/Dockerfile")
        arg (fn [name] (second (re-find (re-pattern (str "(?m)^ARG " name "=(\\S+)$")) dockerfile)))]
    (testing "the HyperFrames CLI and nbb at the Dockerfile's versions"
      (is (some? (arg "HYPERFRAMES_VERSION")))
      (is (= (arg "HYPERFRAMES_VERSION") (option-default base "hyperframesVersion")))
      (is (= (arg "NBB_VERSION") (option-default base "nbbVersion"))))
    (testing "what the templates need is in the base: Node, ffmpeg, a browser under the prompt's name"
      (doseq [package ["nodejs_26" "ffmpeg" "chromium" "git" "curl"]]
        (is (re-find (re-pattern (str "(?m)^\\s+" package "$")) base) package))
      (is (str/includes? base "writeShellScriptBin \"google-chrome\"")))))

(deftest the-machines-flake-test
  (let [base (text "deploy/nixos/flake.nix")
        machine (text "deploy/nixos/machine/flake.nix")
        release #(second (re-find #"github:NixOS/nixpkgs/(nixos-[0-9.]+)" %))]
    (testing "a new machine starts on the NixOS release the base is built with"
      (is (some? (release base)))
      (is (= (release base) (release machine))))
    (testing "a machine takes the base from this repository and builds from its own local.nix"
      (is (str/includes? machine "github:storrito/rragent?dir=deploy/nixos"))
      (is (str/includes? machine "rragent.inputs.nixpkgs.follows = \"nixpkgs\""))
      (is (str/includes? machine "rragent.nixosModules.sprite"))
      (is (str/includes? machine "./local.nix")))
    (testing "the base's lock pins nixpkgs, which the seed hands on to a new machine"
      (let [lock (js->clj (js/JSON.parse (text "deploy/nixos/flake.lock")))]
        (is (= "NixOS" (get-in lock ["nodes" "nixpkgs" "locked" "owner"])))
        (is (re-matches #"[0-9a-f]{40}" (str (get-in lock ["nodes" "nixpkgs" "locked" "rev"]))))))))

(deftest the-version-test
  (testing "the base's version, the image's tag: x.y.z on a line of its own"
    (is (re-matches #"[0-9]+\.[0-9]+\.[0-9]+\n" (text "deploy/nixos/VERSION"))))
  (testing "the NixOS image is a package of its own, so that a provisioner that takes the highest version of rragent-sprite never takes it"
    (let [action (text ".github/actions/sprite-nixos-image/action.yml")]
      (is (str/includes? action "ghcr.io/storrito/rragent-sprite-nixos:"))
      (is (not (re-find #"ghcr\.io/storrito/rragent-sprite:" action))))
    (is (not (str/includes? (text ".github/actions/sprite-image/action.yml") "nixos"))
        "the Ubuntu image's action knows nothing of it")))

(deftest the-image-and-the-platform-test
  (let [base (text "deploy/nixos/sprite.nix")
        action (text ".github/actions/sprite-nixos-image/action.yml")]
    (testing "the image's config names the command and nothing an exec is run by: the platform refused a sprite of an image with USER, WORKDIR and ENV"
      (is (str/includes? action "--change 'CMD [\"/sbin/init\"]'"))
      (doseq [instruction ["USER" "WORKDIR" "ENV"]]
        (is (not (str/includes? action (str "--change '" instruction " "))) instruction)))
    (testing "an exec's PATH is the platform's: its two directories NixOS leaves empty lead to sudo and to the system's programs"
      (is (str/includes? base "\"L+ /usr/local/sbin - - - - /run/wrappers/bin\""))
      (is (str/includes? base "\"L+ /usr/local/bin - - - - /run/current-system/sw/bin\"")))
    (testing "rragent's own PATH: ~/.local, sudo, the system's programs, the Sprite's"
      (let [path (str (nix-string base "path"))]
        (is (str/starts-with? path "/home/sprite/.local/bin:/run/wrappers/bin:"))
        (is (str/includes? path ":/run/current-system/sw/bin:"))
        (is (str/ends-with? path ":/.sprite/bin"))))
    (testing "/sbin/init is a link to a file named systemd, the only kind a Sprite takes for its init"
      (is (str/includes? base "/sprite-init/lib/systemd/systemd sbin/init") "in the image")
      (is (str/includes? base "\"$1/sprite-init/lib/systemd/systemd\" /sbin/init") "after a switch"))
    (testing "Nix builds without its sandbox, which a Sprite's kernel does not allow"
      (is (str/includes? base "nix.settings.sandbox = false;")))
    (testing "HyperFrames and Puppeteer take the machine's browser"
      (doseq [variable ["HYPERFRAMES_BROWSER_PATH" "PRODUCER_HEADLESS_SHELL_PATH" "PUPPETEER_EXECUTABLE_PATH"]]
        (is (str/includes? base (str variable " = \"/run/current-system/sw/bin/google-chrome\";")) variable)))))

(defn- sh
  [script env]
  (let [result (child/spawnSync "bash" #js ["-c" script]
                                #js {:encoding "utf8" :env (js/Object.assign #js {} (.-env js/process) (clj->js env))})]
    {:status (.-status result) :out (str (.-stdout result)) :err (str (.-stderr result))}))

(deftest rragent-system-test
  (let [script (path/resolve "deploy/nixos/rragent-system.sh")]
    (testing "the script parses"
      (is (zero? (:status (sh (str "bash -n " script) {})))))
    (testing "without a command, and with one it does not know: the usage, exit 64, nothing run"
      (doseq [command ["" "install"]]
        (let [{:keys [status err]} (sh (str "bash " script " " command) {})]
          (is (= 64 status))
          (is (str/includes? err "usage: rragent-system apply")))))
    (testing "the machine's text names the commands the script has"
      (let [notes (text "deploy/nixos/MACHINE.md")
            source (text script)]
        (doseq [command ["apply" "upgrade" "rollback" "status"]]
          (is (str/includes? notes (str "rragent-system " command)) command)
          (is (re-find (re-pattern (str "(?m)^  " command "\\)")) source) command))
        (is (str/includes? notes "/var/lib/rragent-system/rolled-back"))
        (is (str/includes? source "\"$state/rolled-back\""))))))

(deftest the-watch-test
  ;; the watch against a fake machine: curl, nix-env, systemctl and the
  ;; systems are stand-ins on a PATH of their own, the paths the script
  ;; names are pointed at a temporary folder
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-nixos-"))
        bin (path/join dir "bin")
        state (path/join dir "state")
        system (fn [name]
                 (let [folder (path/join dir name "bin")]
                   (fs/mkdirSync folder #js {:recursive true})
                   (fs/writeFileSync (path/join folder "switch-to-configuration")
                                     (str "#!/bin/bash\necho \"switched to " name " $1\" >> " dir "/log\n") #js {:mode 0755})
                   (path/join dir name)))
        good (system "good")
        newer (system "newer")
        script (-> (text "deploy/nixos/rragent-system.sh")
                   (str/replace "state=/var/lib/rragent-system" (str "state=" state))
                   (str/replace "gcroot=/nix/var/nix/gcroots/rragent-system-good" (str "gcroot=" dir "/gcroot"))
                   (str/replace "/run/rragent/started-on" (str dir "/started-on"))
                   (str/replace "readlink -f /run/current-system" (str "cat " dir "/current"))
                   (str/replace "sleep 5" "true"))
        file (path/join dir "rragent-system")
        run (fn [answers?]
              (sh (str "bash " file " watch") {"PATH" (str bin ":" (aget (.-env js/process) "PATH"))
                                                 "ANSWERS" (if answers? "yes" "no")}))
        log #(if (fs/existsSync (path/join dir "log")) (text (path/join dir "log")) "")]
    (try
      (fs/mkdirSync bin)
      (fs/writeFileSync file script)
      (fs/writeFileSync (path/join bin "curl") "#!/bin/bash\n[ \"$ANSWERS\" = yes ]\n" #js {:mode 0755})
      (doseq [program ["nix-env" "systemctl"]]
        (fs/writeFileSync (path/join bin program) (str "#!/bin/bash\necho \"" program " $*\" >> " dir "/log\n") #js {:mode 0755}))
      (testing "rragent never answered on this machine: nothing to go back to, nothing done"
        (fs/writeFileSync (path/join dir "current") good)
        (let [{:keys [status err]} (run false)]
          (is (zero? status))
          (is (str/includes? err "it never did on this machine"))
          (is (= "" (log)))))
      (testing "rragent answers: the system it started on is the one to go back to"
        (fs/writeFileSync (path/join dir "started-on") (str good "\n"))
        (is (zero? (:status (run true))))
        (is (= good (str/trim (text (path/join state "good")))))
        (is (= good (fs/readlinkSync (path/join dir "gcroot"))) "and kept from the garbage collection")
        (is (= "" (log))))
      (testing "it does not answer on the system it answered on before: not the system's doing"
        (let [{:keys [status err]} (run false)]
          (is (zero? status))
          (is (str/includes? err "not the system's doing"))
          (is (= "" (log)))
          (is (not (fs/existsSync (path/join state "rolled-back"))))))
      (testing "a newer system that is switched to but not started on yet is not good: the old process answers"
        (fs/writeFileSync (path/join dir "current") newer)
        (is (zero? (:status (run true))))
        (is (= good (str/trim (text (path/join state "good")))) "still the system rragent started on"))
      (testing "rragent does not answer on the newer system: back to the good one, rragent started again, the note written"
        (let [{:keys [status]} (run false)
              note (text (path/join state "rolled-back"))]
          (is (zero? status))
          (is (= [(str "nix-env -p /nix/var/nix/profiles/system --set " good)
                  "switched to good switch"
                  "systemctl restart rragent"]
                 (str/split-lines (str/trim (log)))))
          (is (str/includes? note (str "left: " newer)))
          (is (str/includes? note (str "back on: " good)))))
      (testing "the good system gone from the store: said, exit 1, nothing switched"
        (fs/rmSync (path/join dir "log"))
        (fs/rmSync good #js {:recursive true})
        (let [{:keys [status err]} (run false)]
          (is (= 1 status))
          (is (str/includes? err "is gone from the store"))
          (is (= "" (log)))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))
