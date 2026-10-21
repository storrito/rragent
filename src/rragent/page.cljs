(ns rragent.page
  "The page of the machine: the bots in a sidebar, the conversation of
   the one a tab shows, served the way pchat does it with rframes. The
   server renders the page as hiccup and pushes it as frames over the
   SSE connection of every open tab (`rragent.rframes`), the browser
   sends commands back. The page is a function of the atoms of
   `rragent.db`: the page's own, with the bot each tab shows, and one
   per bot, which follows the bot's conversation (pi-durable's
   `viewState`, see `rragent.machine`) and its artifacts; every change
   pushes a new frame.

   Which bot a tab shows is the tab's: its URL, `/bot/<id>`, the id of
   the bot's conversation, until a click in the sidebar names another
   (`:bot/show`, with the tab's id); the root redirects to the bot
   last used. A tab whose bot is gone shows another with a note. The pane beside the chat follows
   `work/artifacts/` of the bot's folder (`rragent.artifacts`), runs
   the projects in it (`rragent.projects`) and shows the renders
   (`rragent.render`).

   Routes: `/` redirects to a bot's page; `GET /bot/<id>` the page,
   `POST /bot/<id>` its stream, `/bot/<id>/artifacts/*` the bot's
   artifacts (a running project's page through the proxy, else the
   file), `POST /bot/<id>/upload` and `/bot/<id>/uploads/*` the files
   attached to a message (`rragent.uploads`), `POST /bot/<id>/dictation`
   a dictated message (`rragent.dictation`) and
   `POST /bot/<id>/dictation/correct` its correction with the bot's
   context (`rragent.correction`); `/command` (a command posted from a
   project's page goes to the project, any other to the page), the
   built client under `/app.css` and `/assets/*`; all behind the token
   (`rragent.auth`); `POST /hook/<name>/<secret>` outside it, tried on
   every bot, the hooks' own secrets are their keys (`rragent.hooks`).
   The commands `:update/apply` and `:update/check` are the banner's
   and the header's (`rragent.update`)."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.assets :as assets]
            [rragent.auth :as auth]
            [rragent.bots :as bots]
            [rragent.codemode :as codemode]
            [rragent.correction :as correction]
            [rragent.db :as db]
            [rragent.dictation :as dictation]
            [rragent.harness :as harness]
            [rragent.hooks :as hooks]
            [rragent.i18n :as i18n]
            [rragent.lifecycle :as lifecycle]
            [rragent.machine :as machine]
            [rragent.onboarding :as onboarding]
            [rragent.openrouter :as openrouter]
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
            [rragent.update :as update]
            [rragent.uploads :as uploads]
            [rragent.view :as view]))

(defonce ^{:doc "The handle of `rragent.update` while the page runs, set by `serve`;
                 its `:apply!` is what the Update button runs, a test gives
                 another."}
  updater
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

(defn split-bot
  "`<id>/rest/of/it` of the wildcard into [id \"rest/of/it\"]."
  [subpath]
  (let [[id & rest] (str/split (str subpath) #"/" 2)]
    [id (or (first rest) "")]))

(defn shown-bot
  "The bot a connection shows: the one its tab picked since
   (`:bot/show`), else the one of its URL; the machine's default when
   that one is not there, with the id that was asked for under
   `:missing`."
  [tabs tabid url-id]
  (let [wanted (or (get tabs tabid) url-id)]
    (if (machine/bot wanted)
      {:id wanted}
      {:id (machine/default-id)
       :missing wanted})))

(defn page-view
  "The page in the language of the connection, with the bot of the
   tab. The bot's name and model are its definition's as last read
   (`rragent.reload` replaces the copy in the bot's atom when the file
   changed)."
  [w]
  (let [{:keys [tabs deleting renaming update]} @db/db
        {:keys [id missing]} (shown-bot tabs (:tabid w) (first (split-bot (get-in w [:ring/route-params :*]))))
        {:keys [pane]} (machine/bot id)
        value (some-> pane deref)
        definition (get-in value [:agent :definition])
        locale (i18n/locale (:ring/request w))]
    (-> (view/page {:bots (machine/listed)
                    :bot {:id id
                          :name (machine/name-of id)
                          :model (:model definition)}
                    :conversation (conversation (:value value))
                    :artifacts (select-keys value [:artifacts/list :artifacts/current :projects :renders])
                    :locale locale
                    :onboarding (onboarding/onboarding (:metadata definition) locale)
                    :update update
                    :sidebar {:deleting deleting :renaming renaming}
                    :note (when missing [:i18n :bot/missing])
                    :failed (:failed value)})
        (i18n/translate locale))))

(def ^:private stream
  "The view stream of a bot's page, POST /bot/<id>: the bot id of the
   path is in the route params of the connection."
  (:ring/handler (replicant/expand-entry {:replicant/render "/bot/*"
                                          :render/fn #'page-view})))

(defn- bot-of-command
  "The running bot a command names, or nil."
  [w]
  (machine/bot (:bot (command/command-data w))))

(defn- ^:async send-command
  "The text of the composer and the files attached to it submitted as
   an input of the bot's conversation (`rragent.uploads/content`: a
   line per file, the pictures as image blocks when the model takes
   them); the answer arrives as frames."
  [w]
  (let [{:keys [text attachments]} (command/command-data w)
        text (str/trim (str text))
        {:keys [conversation uploads]} (bot-of-command w)
        attachments (when conversation (uploads/named uploads (when (sequential? attachments) (keep :name attachments))))]
    (cond
      (nil? conversation)
      (command/rejected w :no-such-bot)

      (and (str/blank? text) (empty? attachments))
      (command/rejected w :empty-message)

      :else
      (let [content (uploads/content uploads text attachments (await (harness/sees-images? (:models (machine/agent)) conversation)))]
        (await (harness/submit conversation #js {:type "input" :content (clj->js content)}))
        (command/accepted w)))))

(defn- stop-command
  "The run aborted; the abort resolves once the conversation is idle,
   which the frames show, so the command does not wait for it."
  [w]
  (if-let [{:keys [conversation]} (bot-of-command w)]
    (do (-> (.abort conversation harness/context)
            (.catch (fn [error]
                      (js/console.error "[stop]" (str error)))))
        (command/accepted w))
    (command/rejected w :no-such-bot)))

(defn- artifact-of
  [{:keys [pane]} path]
  (some #(when (= path (:path %)) %) (:artifacts/list @pane)))

(defn- open-artifact-command
  "The person picked an artifact in the chooser."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)]
    (if (and bot (artifact-of bot path))
      (do (artifacts/pick! (:pane bot) path)
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- render-command
  "Make the video of the project on screen; the frames show the progress."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)]
    (if (and bot (= :project (:kind (artifact-of bot path))))
      (do (render/start! (:render bot) path)
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- render-stop-command
  [w]
  (if-let [bot (bot-of-command w)]
    (do (render/stop! (:render bot) (:path (command/command-data w)))
        (command/accepted w))
    (command/rejected w :no-such-bot)))

(defn- update-apply-command
  "The Update button: the known release taken (`rragent.update/apply!`
   as the updater's `:apply!`); the frames show the banner's state, the
   page comes back after the restart."
  [w]
  (if-let [handle @updater]
    (do (-> (js/Promise.resolve ((:apply! handle) handle))
            (.catch (fn [error]
                      (js/console.error "[update]" (str error)))))
        (command/accepted w))
    (command/rejected w :no-updater)))

(defn- update-check-command
  "The version in the header: the newest release looked up now, its
   answer in the banner."
  [w]
  (if-let [handle @updater]
    (do (-> (update/check-now! handle)
            (.catch (fn [error]
                      (js/console.error "[update]" (str error)))))
        (command/accepted w))
    (command/rejected w :no-updater)))

(defn- show-command
  "The tab `tab` shows the bot `id` from now on, nil whatever its URL
   names."
  [w]
  (let [{:keys [id tab]} (command/command-data w)]
    (cond
      (nil? tab) (command/rejected w :no-tab)
      (nil? id) (do (swap! db/db update :tabs dissoc tab)
                    (command/accepted w))
      (machine/bot id) (do (swap! db/db assoc-in [:tabs tab] (str id))
                           (command/accepted w))
      :else (command/rejected w :no-such-bot))))

(defn- ^:async create-command
  "A bot made from the sidebar's name and purpose
   (`rragent.machine/create!`), shown in the tab that made it."
  [w]
  (let [{:keys [name purpose tab]} (command/command-data w)]
    (if (str/blank? (str name))
      (command/rejected w :no-name)
      (try
        (let [{:keys [id]} (await (machine/create! {:name name :instructions purpose}))]
          (when tab
            (swap! db/db assoc-in [:tabs tab] id))
          (command/accepted w))
        (catch :default e
          (js/console.error "[bots]" (str e))
          (command/rejected w :not-created))))))

(defn- rename-start-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (machine/bot id)
      (do (swap! db/db assoc :renaming id)
          (command/accepted w))
      (command/rejected w :no-such-bot))))

(defn- ^:async rename-command
  "The bot's name as the field says, into its definition; a blank one
   keeps the old."
  [w]
  (let [{:keys [id name]} (command/command-data w)]
    (swap! db/db dissoc :renaming)
    (cond
      (nil? (machine/bot id)) (command/rejected w :no-such-bot)
      (str/blank? (str name)) (command/accepted w)
      :else (do (await (machine/rename! id name))
                (command/accepted w)))))

(defn- ^:async delete-command
  "The first click arms the question in the bot's row, the second
   deletes: the bot closed and hidden, its folder in the trash
   (`rragent.machine/delete!`), the tabs that showed it on another."
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (cond
      (nil? (machine/bot id))
      (command/rejected w :no-such-bot)

      (not= id (:deleting @db/db))
      (do (swap! db/db assoc :deleting id)
          (command/accepted w))

      :else
      (let [outcome (await (machine/delete! id))]
        (swap! db/db (fn [value]
                       (-> value
                           (dissoc :deleting)
                           (update :tabs #(into {} (remove (fn [[_ shown]] (= id shown))) %)))))
        (if (= :deleted outcome)
          (command/accepted w)
          (command/rejected w outcome))))))

(defn- keep-command
  "The question of a delete, or the field of a rename, taken back."
  [w]
  (swap! db/db dissoc :deleting :renaming)
  (command/accepted w))

(defn- with-subpath
  "`w` with the rest of the path as its route params, for a handler of
   `/<prefix>/*`."
  [w rest prefix]
  (assoc w :ring/route-params {:* (if (str/starts-with? rest (str prefix "/"))
                                    (subs rest (inc (count prefix)))
                                    "")}))

(defn- ^:async artifacts-get
  "GET /bot/<id>/artifacts/<path>: a running project's page through the
   proxy, else the file."
  [{:keys [projects dir]} w]
  (let [w (await (projects/handler projects w))]
    (if (:ring/response w)
      w
      (artifacts/file-handler dir w))))

(defn- ^:async artifacts-post
  "POST /bot/<id>/artifacts/<name>/...: to the project's process;
   nothing else takes a POST here."
  [{:keys [projects]} w]
  (let [w (await (projects/handler projects w))]
    (if (:ring/response w)
      w
      (assoc w :ring/response http/not-found))))

(defn- shim
  [w]
  (assoc w :ring/response {:status 200
                           :headers {"Content-Type" "text/html; charset=utf-8"
                                     "Cache-Control" "no-cache"}
                           :body (replicant/shim-html (assets/head (assets/client)))}))

(defn- ^:async bot-get
  "GET /bot/<id>: the page, for any id (the stream shows another bot
   with a note when the id is gone); /bot/<id>/artifacts/<path>: a
   running project's page, else the file; /bot/<id>/uploads/<name>: the
   file."
  [w]
  (let [[id rest] (split-bot (get-in w [:ring/route-params :*]))
        bot (machine/bot id)]
    (cond
      (str/blank? rest)
      (shim w)

      (nil? bot)
      (assoc w :ring/response http/not-found)

      (str/starts-with? rest (str (subs artifacts/url-prefix 1) "/"))
      (await (artifacts-get bot (with-subpath w rest (subs artifacts/url-prefix 1))))

      (str/starts-with? rest (str (subs uploads/url-prefix 1) "/"))
      (uploads/file-handler (:uploads bot) (with-subpath w rest (subs uploads/url-prefix 1)))

      :else
      (assoc w :ring/response http/not-found))))

(defn- ^:async bot-post
  "POST /bot/<id>: the view stream; /bot/<id>/upload: a file into
   the bot's folder;
   /bot/<id>/artifacts/<name>/...: to the project's process;
   /bot/<id>/dictation and /bot/<id>/dictation/correct: a recording
   written down by the OpenRouter the machine knows, and the dictation
   corrected with the bot's context."
  [w]
  (let [[id rest] (split-bot (get-in w [:ring/route-params :*]))
        bot (machine/bot id)]
    (cond
      (str/blank? rest)
      (await (stream w))

      (nil? bot)
      (assoc w :ring/response http/not-found)

      (= rest (subs uploads/upload-path 1))
      (await (uploads/handler (:uploads bot) (bots/url id) w))

      (str/starts-with? rest (str (subs artifacts/url-prefix 1) "/"))
      (await (artifacts-post bot (with-subpath w rest (subs artifacts/url-prefix 1))))

      (= rest (subs dictation/path 1))
      (await (dictation/handler (machine/codemode) w))

      (= rest (subs correction/path 1))
      (if (:conversation bot)
        (await (correction/handler {:harness (:harness (machine/agent)) :conversation (:conversation bot) :models (:models (machine/agent))} w))
        (assoc w :ring/response http/not-found))

      :else
      (assoc w :ring/response http/not-found))))

(defn- root-handler
  "GET /: to the page of the bot last used."
  [w]
  (assoc w :ring/response (if-let [id (machine/default-id)]
                            {:status 302
                             :headers {"Location" (bots/url id)
                                       "Cache-Control" "no-store"}
                             :body ""}
                            http/not-found)))

(defn- ^:async project-command
  "POST /command from a project's page: to the project's process of
   the bot whose page it is (its Referer names it); any other command
   is left to the page's handler."
  [w]
  (loop [[bot & more] (vals @machine/bots)]
    (if (nil? bot)
      w
      (let [answered (if (:projects bot)
                       (await (projects/command-handler (:projects bot) w))
                       w)]
        (if (:ring/response answered)
          answered
          (recur more))))))

(defn- ^:async hook-handler
  "`POST /hook/<name>/<secret>`: the body as text to the bot whose hook
   it is (`rragent.hooks`, every bot asked), 202 with the submission
   when the secret is a hook's, 403 otherwise."
  [w]
  (let [[name secret] (str/split (str (get-in w [:ring/route-params :*])) #"/" 2)
        request (:ring/request w)
        body (await (transit/read-body (:body request)))
        {:keys [ok submission]} (await (machine/hook! (fn [bot name secret headers body]
                                                        (hooks/receive! bot name secret headers body harness/context))
                                                      name secret (:headers request) body))]
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
    [(http/files "/app.css" (path/join dir "app.css"))
     (http/files "/assets/*" (path/join dir "assets"))
     {:ring/route [:get "/"]
      :ring/handler #'root-handler}
     {:ring/route [:get "/bot/*"]
      :ring/handler #'bot-get}
     {:ring/route [:post "/bot/*"]
      :ring/handler #'bot-post}
     ;; before the page's own command route: a command from a project's page
     {:ring/route [:post "/command"]
      :ring/handler #'project-command}
     {:command/kind :chat/send
      :command/fn #'send-command}
     {:command/kind :chat/stop
      :command/fn #'stop-command}
     {:command/kind :artifacts/open
      :command/fn #'open-artifact-command}
     {:command/kind :artifacts/render
      :command/fn #'render-command}
     {:command/kind :artifacts/render-stop
      :command/fn #'render-stop-command}
     {:command/kind :update/apply
      :command/fn #'update-apply-command}
     {:command/kind :update/check
      :command/fn #'update-check-command}
     {:command/kind :bot/show
      :command/fn #'show-command}
     {:command/kind :bot/create
      :command/fn #'create-command}
     {:command/kind :bot/rename-start
      :command/fn #'rename-start-command}
     {:command/kind :bot/rename
      :command/fn #'rename-command}
     {:command/kind :bot/delete
      :command/fn #'delete-command}
     {:command/kind :bot/keep
      :command/fn #'keep-command}]))

(defn get-register
  []
  (concat (register) command/register))

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

(defn- env
  [name]
  (not-empty (aget (.-env js/process) name)))

(defn- token-line
  "Where the page is and where its token came from, never the token
   itself; the link with the token only when it was generated for this
   run, the one way anyone could learn it then."
  [base {:keys [token source]}]
  (case source
    :label (str base " (the token from the sprite's label)")
    :environment (str base " (the token from the environment)")
    (auth/link base token)))

(defn ^:async serve
  "`rragent serve`: the bots of RRAGENT_HOME opened (`rragent.machine`),
   their page served on PORT (8080) behind the token of
   `rragent.auth/token` (the sprite's label, else RRAGENT_TOKEN, else
   one generated and printed in the link); the machine's alarm, sleep
   watch and update watch started (`rragent.lifecycle`), a connected
   page keeping it awake. The port is taken before the bots open,
   `rragent.starting` answers until then."
  []
  (let [{:keys [token] :as from} (auth/token)
        port (js/parseInt (or (env "PORT") "8080") 10)
        opened! (atom nil)
        opened (js/Promise. (fn [resolve] (reset! opened! resolve)))
        handler* (atom (partial starting/response opened))
        server (await (http/listen (fn [request] (@handler* request)) port))
        base (str "http://localhost:" (.-port (.address server)))]
    (println (str "rragent on " base ", the bots open"))
    ;; the running version is on the page from the first frame on
    (reset! updater (update/handle {:db db/db}))
    (sse/watch! db/db)
    ;; a home that does not open ends the process, as before: the server
    ;; would keep it alive, answering "starting" for ever
    (try
      (await (machine/open! {}))
      (let [page-handler (handler token)]
        (reset! handler* page-handler)
        (@opened! page-handler))
      (catch :default error
        (.close server)
        (throw error)))
    ;; a bot's run, a connected page and a render on its way keep the
    ;; machine awake, and hold a known update back
    (await (lifecycle/start! [(fn [] (machine/busy?))
                              (fn [] (pos? (sse/connections)))]
                             @updater))
    (println (update/report @updater))
    (println (programs/report (templates/all)))
    (println (codemode/report (machine/codemode)))
    (println (openrouter/report (codemode/discovery (machine/codemode))))
    (println (machine/report))
    (println (str "rragent on "
                  (token-line base from)
                  (when-let [origin (assets/dev-origin)]
                    (str ", the page's scripts from " origin))))))
