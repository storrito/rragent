(ns rragent.motion.main
  "The entry of the motion template's program, `dist/motion.js`, which
   the template's `serve` and `render` executables run: `main` is what
   the bundle exports. `serve <dir>` is the process of a motion
   project, its page on PORT; `render <dir> <out>` its video
   (`rragent.motion.app`). An error is one line on stderr and the exit
   status, the way rragent reads a template's scripts."
  (:require [rragent.motion.app :as app]))

(enable-console-print!)

(defn ^:async main
  [command dir out]
  (try
    (case command
      "serve" (await (app/serve-main dir))
      "render" (await (app/render-main dir out))
      (do (js/console.error "motion serve <dir> | motion render <dir> <out.mp4>")
          (set! (.-exitCode js/process) 2)))
    (catch :default error
      (js/console.error (or (.-message error) (str error)))
      (set! (.-exitCode js/process) 1))))
