(ns rragent.hyperframes-render-test
  "The HyperFrames template's render script against a fake CLI that, as
   the real one, keeps a work folder beside the file it writes: the
   artifacts folder shows the finished video only, never the work
   folder or a half-written video, and a failed render leaves the last
   video as it was."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def fake-cli
  "A HyperFrames CLI that writes a work folder with a frame beside
   --output, says its progress, then the video, or fails when FAKE_FAIL
   is set (its work folder left behind, as a crash leaves it)."
  (str "#!/usr/bin/env node\n"
       "const fs = require('node:fs'), path = require('node:path');\n"
       "const out = process.argv[process.argv.indexOf('--output') + 1];\n"
       "const work = path.join(path.dirname(out), 'work-1234');\n"
       "fs.mkdirSync(path.join(work, 'captured-frames'), { recursive: true });\n"
       "fs.writeFileSync(path.join(work, 'captured-frames', 'frame_0001.jpg'), 'jpg');\n"
       "console.log('@hf-progress {\"pct\": 50}');\n"
       "if (process.env.FAKE_FAIL) process.exit(1);\n"
       "fs.writeFileSync(out, 'video');\n"
       "fs.rmSync(work, { recursive: true });\n"))

(defn- render
  [work project out env]
  (child/spawnSync (.-execPath js/process)
                   #js [(path/resolve "templates/hyperframes/render") project out]
                   #js {:cwd work
                        :encoding "utf8"
                        :env (js/Object.assign #js {} (.-env js/process) (clj->js env))}))

(deftest render-test
  (let [work (fs/mkdtempSync (path/join (os/tmpdir) "rragent-hf-render-"))
        cli (path/join work "hyperframes")
        artifacts (path/join work "artifacts")
        project (path/join artifacts "card")
        out (path/join artifacts "card.mp4")]
    (try
      (fs/writeFileSync cli fake-cli)
      (fs/chmodSync cli 0755)
      (fs/mkdirSync (path/join project "composition") #js {:recursive true})
      (testing "the video lands at its place, nothing else beside it"
        (let [result (render work project out {:HYPERFRAMES cli})]
          (is (= 0 (.-status result)) (.-stderr result))
          (is (str/includes? (.-stdout result) "progress 50%"))
          (is (= "video" (fs/readFileSync out "utf8")))
          (is (= ["card" "card.mp4"] (sort (array-seq (fs/readdirSync artifacts))))
              "no work folder, no staging folder left")))
      (testing "a failed render: the last video stays, the work folder never was in artifacts/ and is gone"
        (let [result (render work project out {:HYPERFRAMES cli :FAKE_FAIL "1"})]
          (is (= 1 (.-status result)))
          (is (= "video" (fs/readFileSync out "utf8")))
          (is (= ["card" "card.mp4"] (sort (array-seq (fs/readdirSync artifacts)))))))
      (finally
        (fs/rmSync work #js {:recursive true :force true})))))
