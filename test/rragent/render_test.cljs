(ns rragent.render-test
  "The render against a fake HyperFrames CLI: a shell script that prints
   the progress lines the real one prints and writes the file."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.render :as render]))

(defn temp-dir
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-render-")))

(defn- executable!
  [dir name text]
  (let [file (path/join dir name)]
    (fs/writeFileSync file text)
    (fs/chmodSync file "700")
    file))

(def fake-cli
  "`hyperframes render . --output <out> [--variables-file <f>]`: two
   progress lines, the file written, the arguments noted beside it; a
   `fail` file beside the script makes it fail, a `slow` file makes it
   sleep after the first line."
  (str "#!/bin/sh\n"
       "here=$(dirname \"$0\")\n"
       "out=\"\"\n"
       "echo \"$@\" > \"$here/args\"\n"
       "while [ $# -gt 0 ]; do case \"$1\" in --output) out=\"$2\"; shift;; esac; shift; done\n"
       "echo '@hf-progress {\"code\":\"capture\",\"done\":1,\"total\":4,\"pct\":25}'\n"
       "[ -f \"$here/slow\" ] && { echo video > \"$out\"; sleep 30; }\n"
       "[ -f \"$here/fail\" ] && { echo 'Error: the composition has no root' >&2; exit 3; }\n"
       "echo '@hf-progress {\"code\":\"encode\",\"done\":4,\"total\":4,\"pct\":90}'\n"
       "echo video > \"$out\"\n"
       "echo 'Rendered'\n"
       "exit 0\n"))

(defn with-tools!
  "The video tools faked for the body: a CLI, a Chrome, an ffmpeg in a
   temp dir on the PATH. Answers the dir; `restore!` puts the
   environment back."
  []
  (let [tools (temp-dir)
        env (.-env js/process)
        before {:path (aget env "PATH") :chrome (aget env "CHROME") :cli (aget env "RRAGENT_HYPERFRAMES")}]
    (executable! tools "ffmpeg" "#!/bin/sh\nexit 0\n")
    (executable! tools "google-chrome" "#!/bin/sh\nexit 0\n")
    (aset env "PATH" (str tools ":" (:path before)))
    (aset env "CHROME" (path/join tools "google-chrome"))
    (aset env "RRAGENT_HYPERFRAMES" (executable! tools "hyperframes" fake-cli))
    {:tools tools
     :restore! (fn []
                 (aset env "PATH" (:path before))
                 (if (:chrome before) (aset env "CHROME" (:chrome before)) (js-delete env "CHROME"))
                 (if (:cli before) (aset env "RRAGENT_HYPERFRAMES" (:cli before)) (js-delete env "RRAGENT_HYPERFRAMES"))
                 (fs/rmSync tools #js {:recursive true :force true}))}))

(defn project!
  "A HyperFrames project in `dir`, with a variables.json when `variables?`."
  [dir name variables?]
  (let [project-dir (path/join dir name)]
    (fs/mkdirSync (path/join project-dir "composition") #js {:recursive true})
    (fs/writeFileSync (path/join project-dir "package.json") "{}")
    (fs/writeFileSync (path/join project-dir "composition" "index.html") "<html></html>")
    (when variables?
      (fs/writeFileSync (path/join project-dir "variables.json") "{\"title\": \"x\"}"))
    project-dir))

(deftest progress-test
  (is (= "30 %" (render/progress-of "@hf-progress {\"code\":\"capture\",\"done\":16,\"total\":180,\"pct\":30}")))
  (is (= "5 %" (render/progress-of "@hf-progress {\"code\":\"compile\",\"pct\":5}")))
  (is (nil? (render/progress-of "[INFO] [Render] Pipeline started")))
  (is (nil? (render/progress-of "@hf-progress not json"))))

(deftest tools-test
  (let [env (.-env js/process)
        before (aget env "PATH")]
    (try
      (aset env "PATH" (temp-dir))
      (js-delete env "CHROME")
      (js-delete env "RRAGENT_HYPERFRAMES")
      (testing "an empty PATH misses every tool"
        (is (= ["ffmpeg" "google-chrome" "hyperframes"] (render/missing-tools)))
        (is (= "The video tools are not installed on this machine (ffmpeg, google-chrome, hyperframes), so no video: the operator installs them."
               (render/not-ready-message (render/missing-tools))))
        (is (nil? (render/not-ready-message []))))
      (let [{:keys [tools restore!]} (with-tools!)]
        (try
          (testing "a Chrome by any of its names counts, the CLI from the environment"
            (is (empty? (render/missing-tools)))
            (is (= (path/join tools "hyperframes") (render/hyperframes)))
            (is (str/includes? (render/report) "ffmpeg at")))
          (finally
            (restore!))))
      (finally
        (aset env "PATH" before)))))

(deftest command-test
  (let [dir (temp-dir)
        {:keys [tools restore!]} (with-tools!)
        handle (render/handle {:db (atom {}) :dir dir})]
    (try
      (project! dir "card" true)
      (fs/mkdirSync (path/join dir "plain"))
      (let [{:keys [cmd args cwd]} (render/command handle "card")]
        (is (= (path/join tools "hyperframes") cmd))
        (is (= ["render" "." "--output" (path/join dir "card.mp4") "--variables-file" (path/join dir "card" "variables.json")] args))
        (is (= (path/join dir "card" "composition") cwd)))
      (is (nil? (render/command handle "plain")) "no composition, no command")
      (is (= "HYPERFRAMES_BROWSER_PATH" (some #{"HYPERFRAMES_BROWSER_PATH"} (keys (render/environment)))))
      (finally
        (restore!)
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest render-test
  (async done
    (-> ((^:async fn []
           (let [dir (temp-dir)
                 {:keys [tools restore!]} (with-tools!)
                 db (atom {})
                 handle (render/handle {:db db :dir dir :procs (atom {})})
                 lines (atom [])]
             (project! dir "card" false)
             (try
               (testing "a render runs, its progress in the db, the file written, the lines passed on"
                 (let [promise (render/start! handle "card" (fn [line] (swap! lines conj line)))]
                   (is (render/running? handle "card"))
                   (is (render/any-running? handle))
                   (is (number? (get-in @db [:renders "card" :started])))
                   (let [render (await promise)]
                     (is (= 0 (:exit render)))
                     (is (nil? (:progress render)) "the progress goes with the end")
                     (is (= "video\n" (fs/readFileSync (path/join dir "card.mp4") "utf8")))
                     (is (= "render . --output " (subs (fs/readFileSync (path/join tools "args") "utf8") 0 18)))
                     (is (some #(= "Rendered" %) @lines))
                     (is (not (render/running? handle "card"))))))
               (testing "a second start while it runs waits for the same render"
                 (fs/writeFileSync (path/join tools "slow") "")
                 (let [first-run (render/start! handle "card")
                       second-run (render/start! handle "card")]
                   (await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))
                   (is (= "25 %" (get-in @db [:renders "card" :progress])))
                   (testing "stopped: the torso of a file goes, the render is gone from the db"
                     (render/stop! handle "card")
                     (is (nil? (await first-run)))
                     (is (nil? (await second-run)))
                     (is (not (fs/existsSync (path/join dir "card.mp4"))))
                     (is (nil? (get-in @db [:renders "card"])))))
                 (fs/rmSync (path/join tools "slow")))
               (testing "a failed render keeps its last line as the error"
                 (fs/writeFileSync (path/join tools "fail") "")
                 (let [render (await (render/start! handle "card"))]
                   (is (= 3 (:exit render)))
                   (is (= "Error: the composition has no root" (:error render)))
                   (is (= 3 (get-in @db [:renders "card" :exit]))))
                 (fs/rmSync (path/join tools "fail")))
               (testing "no composition: refused with the reason"
                 (fs/mkdirSync (path/join dir "plain"))
                 (let [render (await (render/start! handle "plain"))]
                   (is (= 1 (:exit render)))
                   (is (str/includes? (:error render) "no project that can be rendered"))))
               (testing "without the tools: refused with the reason"
                 (restore!)
                 (let [before (aget (.-env js/process) "PATH")]
                   (aset (.-env js/process) "PATH" (temp-dir))
                   (try
                     (let [render (await (render/start! handle "card"))]
                       (is (= 1 (:exit render)))
                       (is (str/includes? (:error render) "not installed on this machine")))
                     (finally
                       (aset (.-env js/process) "PATH" before)))))
               (finally
                 (render/stop-all! handle)
                 (fs/rmSync dir #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
