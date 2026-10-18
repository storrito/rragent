(ns rragent.tools.render
  "The `render` tool: an artifact project as a video, `artifacts/<name>.mp4`,
   through `rragent.render`. The lines of the render are the running
   output of the call, the pane shows the progress, the result names the
   file. The counterpart of pchat's bin/render."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.db :as db]
            [rragent.render :as render]
            [rragent.tools.work :as work]))

(def tool
  (defineTool
   #js {:name "render"
        :description (str "Makes the video of an artifact project, artifacts/<name>.mp4, which the person sees beside the chat. "
                          "A motion design (design.cljs) renders thirty frames a second at its size with a headless Chrome and ffmpeg, the :audio of design.edn mixed in; "
                          "a HyperFrames project (composition/index.html) renders with hyperframes, with the values of its variables.json. "
                          "It takes a while; the preview beside the chat plays the design live, so render when the file itself is needed, not after every change.")
        :parameters (.Object Type #js {:name (.String Type #js {:description "The project's name, its folder under artifacts/"})})
        :execute (^:async fn [args api _call-context]
                   (let [name (.-name args)
                         handle (render/handle {:db db/db :dir (work/artifacts-dir api)})
                         render (await (render/start! handle name (fn [line] (.output api (str line "\n")))))]
                     (cond
                       (nil? render)
                       (throw (js/Error. "The render was stopped."))

                       (not (zero? (:exit render)))
                       (throw (js/Error. (str "The render failed: " (:error render))))

                       :else
                       (work/text (str (work/relative api (render/output-file handle name)) " is ready.")))))}))
