(ns rragent.render-test
  "The render against a fake template: a render script that prints
   progress lines and writes the file, or fails, or hangs; the needs
   of the template checked first."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.programs :as programs]
            [rragent.render :as render]
            [rragent.templates :as templates]
            [rragent.test-templates :as test-templates]))

(defn temp-dir
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-render-")))

(def fake-cli
  "`hyperframes render . --output <out> [--variables-file <f>]`: two
   progress lines the real CLI prints, the file written, the arguments
   noted beside it; a `fail` file beside the script makes it fail."
  (str "#!/bin/sh\n"
       "here=$(dirname \"$0\")\n"
       "out=\"\"\n"
       "echo \"$@\" > \"$here/args\"\n"
       "while [ $# -gt 0 ]; do case \"$1\" in --output) out=\"$2\"; shift;; esac; shift; done\n"
       "echo '@hf-progress {\"code\":\"capture\",\"done\":1,\"total\":4,\"pct\":25}'\n"
       "[ -f \"$here/fail\" ] && { echo 'Error: the composition has no root' >&2; exit 3; }\n"
       "echo '@hf-progress {\"code\":\"encode\",\"done\":4,\"total\":4,\"pct\":90}'\n"
       "echo video > \"$out\"\n"
       "echo 'Rendered'\n"
       "exit 0\n"))

(defn with-tools!
  "The programs the real templates need faked for the body: a
   HyperFrames CLI, a Chrome, an ffmpeg in a temp dir on the PATH.
   Answers the dir; `restore!` puts the environment back."
  []
  (let [tools (temp-dir)
        env (.-env js/process)
        before {:path (aget env "PATH") :chrome (aget env "CHROME") :cli (aget env "RRAGENT_HYPERFRAMES")}]
    (test-templates/executable! tools "ffmpeg" "#!/bin/sh\nexit 0\n")
    (test-templates/executable! tools "google-chrome" "#!/bin/sh\nexit 0\n")
    (aset env "PATH" (str tools ":" (:path before)))
    (aset env "CHROME" (path/join tools "google-chrome"))
    (aset env "RRAGENT_HYPERFRAMES" (test-templates/executable! tools "hyperframes" fake-cli))
    {:tools tools
     :restore! (fn []
                 (aset env "PATH" (:path before))
                 (if (:chrome before) (aset env "CHROME" (:chrome before)) (js-delete env "CHROME"))
                 (if (:cli before) (aset env "RRAGENT_HYPERFRAMES" (:cli before)) (js-delete env "RRAGENT_HYPERFRAMES"))
                 (fs/rmSync tools #js {:recursive true :force true}))}))

(deftest progress-test
  (is (= "30 %" (render/progress-of "progress 54/180")))
  (is (= "25 %" (render/progress-of "progress 25%")))
  (is (= "25 %" (render/progress-of "progress 25 %")))
  (is (nil? (render/progress-of "@hf-progress {\"pct\":30}")) "the CLI's line is the template's to translate")
  (is (nil? (render/progress-of "frame 54/180"))))

(deftest programs-test
  (let [env (.-env js/process)
        before (aget env "PATH")]
    (try
      (aset env "PATH" (temp-dir))
      (js-delete env "CHROME")
      (js-delete env "RRAGENT_HYPERFRAMES")
      (testing "an empty PATH misses every program"
        (is (= ["chrome" "ffmpeg" "hyperframes"] (programs/missing ["chrome" "ffmpeg" "hyperframes"]))))
      (let [{:keys [tools restore!]} (with-tools!)]
        (try
          (testing "a Chrome by any of its names counts, a program from the environment too"
            (is (empty? (programs/missing ["chrome" "ffmpeg" "hyperframes"])))
            (is (= (path/join tools "hyperframes") (programs/locate "hyperframes")))
            (is (= {"CHROME" (path/join tools "google-chrome")
                    "HYPERFRAMES" (path/join tools "hyperframes")}
                   (dissoc (programs/environment ["chrome" "hyperframes"]) "PATH")))
            (is (str/starts-with? (get (programs/environment []) "PATH") (path/dirname (.-execPath js/process))) "the Node that runs rragent first on the PATH")
            (is (str/includes? (programs/report [{:name "x" :needs ["ffmpeg" "nope"]}]) "ffmpeg at"))
            (is (str/includes? (programs/report [{:name "x" :needs ["ffmpeg" "nope"]}]) "nope missing")))
          (finally
            (restore!))))
      (finally
        (aset env "PATH" before)))))

(deftest render-test
  (async done
    (-> ((^:async fn []
           (let [dir (temp-dir)
                 {:keys [tools restore!]} (with-tools!)
                 {templates-dir :dir restore-templates! :restore!} (test-templates/install! [test-templates/fake])
                 fake (path/join templates-dir "fake")
                 db (atom {})
                 handle (render/handle {:db db :dir dir :procs (atom {}) :timeout-ms 500})
                 lines (atom [])]
             (templates/copy! "fake" (path/join dir "card"))
             (try
               (is (= (path/join dir "card.mp4") (render/output-file handle "card")) "the extension of the template's renders")
               (testing "a render runs, its progress in the db, the file written, the lines passed on, the needs in the script's environment"
                 (let [promise (render/start! handle "card" (fn [line] (swap! lines conj line)))]
                   (is (render/running? handle "card"))
                   (is (render/any-running? handle))
                   (is (number? (get-in @db [:renders "card" :started])))
                   (let [render (await promise)]
                     (is (= 0 (:exit render)))
                     (is (nil? (:progress render)) "the progress goes with the end")
                     (is (= "video\n" (fs/readFileSync (path/join dir "card.mp4") "utf8")))
                     (is (= (str (path/join dir "card") " " (path/join dir "card.mp4") "\n") (fs/readFileSync (path/join fake "args") "utf8")))
                     (is (= (str "CHROME=" (path/join tools "google-chrome") " FFMPEG=" (path/join tools "ffmpeg") "\n") (fs/readFileSync (path/join fake "env") "utf8")))
                     (is (some #(= "Rendered" %) @lines))
                     (is (not (render/running? handle "card"))))))
               (testing "a second start while it runs waits for the same render"
                 ;; the first run's complete file goes first: a stop leaves a
                 ;; complete file alone, so only a torso may survive below
                 (fs/rmSync (path/join dir "card.mp4") #js {:force true})
                 (fs/writeFileSync (path/join fake "slow") "")
                 (let [first-run (render/start! handle "card")
                       second-run (render/start! handle "card")]
                   (await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))
                   (is (= "25 %" (get-in @db [:renders "card" :progress])))
                   (testing "stopped: the torso of a file goes, the render is gone from the db"
                     (render/stop! handle "card")
                     (is (nil? (await first-run)))
                     (is (nil? (await second-run)))
                     (is (not (fs/existsSync (path/join dir "card.mp4"))))
                     (is (nil? (get-in @db [:renders "card"]))))))
               (testing "a render that runs past the timeout is ended, with the reason"
                 (let [render (await (render/start! handle "card"))]
                   (is (= 1 (:exit render)))
                   (is (str/includes? (:error render) "took longer than")))
                 (fs/rmSync (path/join fake "slow")))
               (testing "a failed render keeps its stderr as the error"
                 (fs/writeFileSync (path/join fake "fail") "")
                 (let [render (await (render/start! handle "card"))]
                   (is (= 3 (:exit render)))
                   (is (= "Error: the design has no frame" (:error render)))
                   (is (= 3 (get-in @db [:renders "card" :exit]))))
                 (fs/rmSync (path/join fake "fail")))
               (testing "no template: refused with the reason"
                 (fs/mkdirSync (path/join dir "plain"))
                 (fs/writeFileSync (path/join dir "plain" "package.json") "{}")
                 (let [render (await (render/start! handle "plain"))]
                   (is (= 1 (:exit render)))
                   (is (str/includes? (:error render) "not made from a template")))
                 (fs/mkdirSync (path/join dir "folder"))
                 (is (str/includes? (:error (await (render/start! handle "folder"))) "no project")))
               (testing "a template that is gone, one that renders nothing"
                 (fs/writeFileSync (path/join dir "plain" "package.json") "{\"template\": \"gone\"}")
                 (is (str/includes? (:error (await (render/start! handle "plain"))) "not installed"))
                 (test-templates/template! templates-dir (assoc test-templates/static :name "mute" :frontmatter {"name" "mute"}))
                 (templates/copy! "mute" (path/join dir "quiet"))
                 (is (str/includes? (:error (await (render/start! handle "quiet"))) "renders nothing")))
               (testing "without the needs: refused with the reason"
                 (restore!)
                 (let [before (aget (.-env js/process) "PATH")]
                   (aset (.-env js/process) "PATH" (temp-dir))
                   (try
                     (let [render (await (render/start! handle "card"))]
                       (is (= 1 (:exit render)))
                       (is (str/includes? (:error render) "needs chrome, ffmpeg on this machine")))
                     (finally
                       (aset (.-env js/process) "PATH" before)))))
               (finally
                 (render/stop-all! handle)
                 (restore-templates!)
                 (fs/rmSync dir #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
