(ns rragent.page
  "The page of the agent: the one conversation, served the way pchat
   does it with rframes. The server renders the page as hiccup and
   pushes it as frames over the SSE connection of every open tab
   (`rragent.rframes`), the browser sends commands back. The page is a
   function of `rragent.db/db`, which follows the conversation's view (pi-durable's
   `viewState`, see `rragent.harness/open`): every commit sets the new
   view value, the watch of `rragent.rframes.sse` pushes a new frame.

   The pane beside the chat follows `work/artifacts/` of the agent's
   folder (`rragent.artifacts`), runs the projects in it
   (`rragent.projects`) and shows the renders (`rragent.render`); their
   keys are in the same db.

   Routes: the page at `/`, its stream (POST /), `/command` (a command
   posted from a project's page goes to the project), the built client
   under `/app.css` and `/assets/*`, the artifacts under `/artifacts/*`
   (a running project's page through the proxy, else the file), the
   files attached to a message, `POST /upload` and `/uploads/*`
   (`rragent.uploads`), all behind the token (`rragent.auth`); `POST /hook/<name>/<secret>`
   outside it, the hooks' own secrets are their keys (`rragent.hooks`)."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.assets :as assets]
            [rragent.auth :as auth]
            [rragent.codemode :as codemode]
            [rragent.db :as db]
            [rragent.definition :as definition]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.i18n :as i18n]
            [rragent.lifecycle :as lifecycle]
            [rragent.onboarding :as onboarding]
            [rragent.programs :as programs]
            [rragent.projects :as projects]
            [rragent.render :as render]
            [rragent.rframes.command :as command]
            [rragent.rframes.http :as http]
            [rragent.rframes.replicant :as replicant]
            [rragent.rframes.sse :as sse]
            [rragent.rframes.transit :as transit]
            [rragent.starting :as starting]
            [rragent.templates :as templates]
            [rragent.uploads :as uploads]
            [rragent.view :as view]))

(defonce ^{:doc "The handles of the pane while the page runs: `{:dir :projects :render :uploads}`,
                 the artifacts folder, the handles of `rragent.projects` and
                 `rragent.render`, and the uploads folder (`rragent.uploads`)."}
  pane
  (atom nil))

(defn conversation
  "The view value of pi-durable as the data the view reads:
   `{:entries :live :inbox}`, the active transcript, the `pi.live`
   document (the running generation and its tool calls) and the
   `pi.inbox` document (the queued inputs)."
  [value]
  (when value
    (let [docs (.-docs value)]
      {:entries (js->clj (.-entries value) :keywordize-keys true)
       :live (js->clj (aget docs "pi.live") :keywordize-keys true)
       :inbox (js->clj (aget docs "pi.inbox") :keywordize-keys true)})))

(defn page-view
  "The page in the language of the connection. The agent's name and
   model are the definition's as last read (`rragent.reload` replaces
   the db's copy when the file changed), the name shown as
   `rragent.definition/display-name` has it."
  [w]
  (let [{:keys [agent value]} @db/db
        locale (i18n/locale (:ring/request w))]
    (-> (view/page {:agent {:name (definition/display-name (:definition agent))
                            :model (get-in agent [:definition :model])}
                    :conversation (conversation value)
                    :artifacts (select-keys @db/db [:artifacts/list :artifacts/current :projects :renders])
                    :locale locale
                    :onboarding (onboarding/onboarding (get-in agent [:definition :metadata]) locale)})
        (i18n/translate locale))))

(defn- ^:async send-command
  "The text of the composer and the files attached to it submitted as
   an input (`rragent.uploads/content`: a line per file, the pictures
   as image blocks when the model takes them); the answer arrives as
   frames."
  [w]
  (let [{:keys [text attachments]} (command/command-data w)
        text (str/trim (str text))
        agent (:agent @db/db)
        dir (:uploads @pane)
        attachments (uploads/named dir (when (sequential? attachments) (keep :name attachments)))]
    (if (and (str/blank? text) (empty? attachments))
      (command/rejected w :empty-message)
      (let [content (uploads/content dir text attachments (await (harness/sees-images? agent)))]
        (await (harness/submit agent #js {:type "input" :content (clj->js content)}))
        (command/accepted w)))))

(defn- stop-command
  "The run aborted; the abort resolves once the conversation is idle,
   which the frames show, so the command does not wait for it."
  [w]
  (-> (.abort (get-in @db/db [:agent :root]) harness/context)
      (.catch (fn [error]
                (js/console.error "[stop]" (str error)))))
  (command/accepted w))

(defn- artifact-of
  [path]
  (some #(when (= path (:path %)) %) (:artifacts/list @db/db)))

(defn- open-artifact-command
  "The person picked an artifact in the chooser."
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (artifact-of path)
      (do (artifacts/pick! db/db path)
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- render-command
  "Make the video of the project on screen; the frames show the progress."
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (= :project (:kind (artifact-of path)))
      (do (render/start! (:render @pane) path)
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- render-stop-command
  [w]
  (render/stop! (:render @pane) (:path (command/command-data w)))
  (command/accepted w))

(defn- ^:async artifacts-get
  "GET /artifacts/<path>: a running project's page through the proxy,
   else the file."
  [w]
  (let [w (await (projects/handler (:projects @pane) w))]
    (if (:ring/response w)
      w
      (artifacts/file-handler (:dir @pane) w))))

(defn- ^:async artifacts-post
  "POST /artifacts/<name>/...: to the project's process; nothing else
   takes a POST here."
  [w]
  (let [w (await (projects/handler (:projects @pane) w))]
    (if (:ring/response w)
      w
      (assoc w :ring/response http/not-found))))

(defn- ^:async project-command
  "POST /command from a project's page: to the project's process."
  [w]
  (await (projects/command-handler (:projects @pane) w)))

(defn- ^:async hook-handler
  "`POST /hook/<name>/<secret>`: the body as text to `rragent.hooks`,
   202 with the submission when the secret is the hook's, 403 otherwise."
  [w]
  (let [[name secret] (str/split (str (get-in w [:ring/route-params :*])) #"/" 2)
        request (:ring/request w)
        body (await (transit/read-body (:body request)))
        {:keys [ok submission]} (await (hooks/receive! (:agent @db/db) name secret (:headers request) body harness/context))]
    (assoc w
           :ring/response
           (if ok
             {:status 202
              :headers {"Content-Type" "application/json"}
              :body (js/JSON.stringify #js {:submission submission})}
             {:status 403
              :headers {"Content-Type" "text/plain"}
              :body "forbidden"}))))

(defn register
  []
  (let [dir (assets/public-dir)]
    (concat
     [(http/files "/app.css" (path/join dir "app.css"))
      (http/files "/assets/*" (path/join dir "assets"))
      {:replicant/shim "/"
       :head (assets/head (assets/client))}
      {:replicant/render "/"
       :render/fn #'page-view}
      {:command/kind :chat/send
       :command/fn #'send-command}
      {:command/kind :chat/stop
       :command/fn #'stop-command}
      ;; before the page's own command route: a command from a project's page
      {:ring/route [:post "/command"]
       :ring/handler #'project-command}
      {:ring/route [:get "/artifacts/*"]
       :ring/handler #'artifacts-get}
      {:ring/route [:post "/artifacts/*"]
       :ring/handler #'artifacts-post}]
     ;; the files attached to a message, posted and served
     (uploads/routes (:uploads @pane))
     [{:command/kind :artifacts/open
       :command/fn #'open-artifact-command}
      {:command/kind :artifacts/render
       :command/fn #'render-command}
      {:command/kind :artifacts/render-stop
       :command/fn #'render-stop-command}])))

(defn get-register
  []
  (replicant/expand (concat (register) command/register)))

(def open-register
  "The routes outside the token."
  [{:ring/route [:post "/hook/*"]
    :ring/handler #'hook-handler}])

(defn handler
  "The ring handler: the open routes as they are, everything else behind
   the token."
  [token]
  (let [open (http/ring-handler (fn [] open-register))
        secured (auth/wrap (http/ring-handler get-register) {:token token})]
    (fn [request]
      (if (some (fn [{:keys [ring/route]}] (http/match route request)) open-register)
        (open request)
        (secured request)))))

(defn start!
  "The page's state follows the conversation of `agent`: the view value
   now, and every one after it; and the artifacts folder of its `work/`:
   the files watched, the projects in it run while someone looks at the
   page (`rragent.projects`), the renders kept, and the uploads folder
   of its `work/` known (`rragent.uploads`). Answers a function that
   ends the following, the watches and the projects."
  [agent]
  (let [work (home/work-dir (:home agent))
        dir (home/artifacts-dir work)
        projects (projects/handle {:db db/db
                                   :dir dir
                                   :shown? (fn [] (pos? (sse/connections)))})]
    (reset! pane {:dir dir
                  :projects projects
                  :render (render/handle {:db db/db :dir dir})
                  :uploads (home/uploads-dir work)})
    (swap! db/db assoc :agent agent :value (.-value (:view agent)))
    (sse/watch! db/db)
    (let [unsubscribe (.subscribe (:view agent) (fn [value] (swap! db/db assoc :value value)))
          stop-artifacts (artifacts/watch! db/db dir)
          stop-projects (projects/watch! projects)]
      (fn []
        (unsubscribe)
        (stop-artifacts)
        (stop-projects)))))

(defn- env
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn ^:async serve
  "`rragent serve`: the agent of RRAGENT_HOME opened, its page served on
   PORT (8080) behind RRAGENT_TOKEN, generated and printed in the link
   when absent; the machine's alarm and sleep watch started
   (`rragent.lifecycle`), a connected page keeping it awake. The port is
   taken before the agent opens, `rragent.starting` answers until then."
  []
  (let [given (env "RRAGENT_TOKEN")
        token (or given (auth/generate-token))
        port (js/parseInt (or (env "PORT") "8080") 10)
        opened! (atom nil)
        opened (js/Promise. (fn [resolve] (reset! opened! resolve)))
        handler* (atom (partial starting/response opened))
        server (await (http/listen (fn [request] (@handler* request)) port))
        base (str "http://localhost:" (.-port (.address server)))]
    (println (str "rragent on " base ", the agent opens"))
    ;; an agent that does not open ends the process, as before: the server
    ;; would keep it alive, answering "starting" for ever
    (let [agent (try
                  (let [agent (await (harness/open {}))
                        page-handler (handler token)]
                    (start! agent)
                    (reset! handler* page-handler)
                    (@opened! page-handler)
                    agent)
                  (catch :default error
                    (.close server)
                    (throw error)))]
      ;; a connected page and a render on its way keep the machine awake
      (await (lifecycle/start! agent [(fn [] (pos? (sse/connections)))
                                      (fn [] (render/any-running? (:render @pane)))]))
      (println (programs/report (templates/all)))
      (println (codemode/report (:codemode agent)))
      (println (harness/model-route (codemode/discovery (:codemode agent))))
      (println (str "rragent on "
                    (if given
                      (str base " (the token is in the environment)")
                      (auth/link base token))
                    ", the agent " (definition/display-name (:definition agent))
                    " on " (get-in agent [:definition :model])
                    (when-let [origin (assets/dev-origin)]
                      (str ", the page's scripts from " origin)))))))
