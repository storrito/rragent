(ns rragent.page
  "The page of the machine: the bots in a sidebar, the conversation of
   the one a tab shows, served the way pchat does it with rframes. The
   server renders the page as hiccup and pushes it as frames over the
   SSE connection of every open tab (`rragent.rframes`), the browser
   sends commands back. The page is a function of the one atom of
   `rragent.db`: the bot each tab shows, the version and the banner,
   and every bot's map, which follows the bot's conversation
   (pi-durable's `viewState`, see `rragent.machine`) and its
   artifacts; a change of a bot's values pushes a frame to the tabs
   showing it (`affected`), a change of the page's own to every tab.

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
   The commands `:settings/open` and `:settings/close` put the settings
   in place of the chat for a tab and bring the chat back
   (`rragent.view/settings`); `:update/check` is the settings' lookup
   of a newer release, `:update/apply` the Update button's
   (`rragent.update`)."
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
            [rragent.phone :as phone]
            [rragent.programs :as programs]
            [rragent.projects :as projects]
            [rragent.recordings :as recordings]
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

(def bot-value-keys
  "The keys of a bot's map the page is a function of: its
   conversation's view value, its definition, its state and its pane.
   The other keys of the map are the handles of its runtime (the
   conversation, the view, the worker, the reload's text, the schedule
   source, the pane's handles), which change without a difference on
   the page, so the watch (`affected`) leaves them out of its
   comparison: a change of one pushes no frame."
  #{:value :definition :schedules/next :failed
    :artifacts/list :artifacts/rev :artifacts/durations :artifacts/current :artifacts/picked-at :artifacts/folder :projects :renders
    :artifacts/renaming :artifacts/deleting
    :deleting? :renaming? :phone})

(def sidebar-keys
  "The value keys of a bot every tab shows, in the sidebar: its name
   (the definition), its state (asleep, failed) and a rename or delete
   under way in its row. A change of one goes to every tab, as a run
   of the bot starting or ending does (the state running); the rest
   of the value keys, the conversation's view as it streams and the
   pane, go to the tabs showing the bot."
  #{:definition :schedules/next :failed :deleting? :renaming?})

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
  "The bot a connection shows among `bots`, the open ones by id: the
   one its tab picked since (`:bot/show`), else the one of its URL;
   the machine's default when that one is not there, with the id that
   was asked for under `:missing`."
  [bots tabs tabid url-id]
  (let [wanted (or (get tabs tabid) url-id)]
    (if (contains? bots wanted)
      {:id wanted}
      {:id (machine/default-id)
       :missing wanted})))

(defn- url-bot
  "The bot id in the path of a connection's world, `/bot/<id>`."
  [w]
  (first (split-bot (get-in w [:ring/route-params :*]))))

(defn- artifacts-pane
  "The keys of the pane (`rragent.view/artifacts`), with the text of
   the artifact on screen when it is a text file."
  [{:keys [dir] :as bot}]
  (let [pane (select-keys bot [:artifacts/list :artifacts/current :artifacts/folder :artifacts/durations :artifacts/renaming :artifacts/deleting :projects :renders])
        current (some #(when (= (:artifacts/current bot) (:path %)) %) (:artifacts/list bot))]
    (cond-> pane
      current (assoc :artifacts/text (artifacts/text-of dir current)))))

(defn page-view
  "The page in the language of the connection, with the bot of the
   tab. The bot's name and model are its definition's as last read
   (`rragent.reload` replaces the bot's `:definition` in the atom when
   the file changed)."
  [w]
  (let [{:keys [tabs update settings-open]} @db/db
        {:keys [id missing]} (shown-bot (machine/bots) tabs (:tabid w) (url-bot w))
        {:keys [definition] :as bot} (machine/bot id)
        locale (i18n/locale (:ring/request w))]
    (-> (view/page {:bots (machine/listed)
                    :bot {:id id
                          :name (machine/label-of id)
                          :model (:model definition)
                          :context-window (machine/context-window (:model definition))}
                    :conversation (conversation (:value bot))
                    :artifacts (artifacts-pane bot)
                    :phone (phone/status (:phone bot))
                    :locale locale
                    :onboarding (onboarding/onboarding (:metadata definition) locale)
                    :update update
                    :settings? (contains? settings-open (:tabid w))
                    :note (when missing [:i18n :bot/missing])
                    :failed (:failed bot)})
        (i18n/translate locale))))

(defn- running?
  "Whether a run goes in the bot's view value."
  [bot]
  (some? (some-> (:value bot) .-docs (aget "pi.live") .-run)))

(defn- sidebar-of
  "What the sidebar shows of a bot: its `sidebar-keys` and whether it
   runs."
  [bot]
  (assoc (select-keys bot sidebar-keys) :running? (running? bot)))

(defn affected
  "What a change of the atom from `old` to `new` refreshes
   (`rragent.rframes.sse/watch!`): every tab when a key of the page's
   own changed (the version and the banner, the tabs' picks and
   settings, the machine), when a bot came or went, or when what the sidebar shows
   of a bot changed (`sidebar-keys`, a run starting or ending); the
   tabs showing a bot whose other value keys (`bot-value-keys`)
   changed; nil when only handles changed."
  [old new]
  (let [before (:bots old)
        after (:bots new)]
    (cond
      (not= (dissoc old :bots) (dissoc new :bots))
      :all

      (not= (set (keys before)) (set (keys after)))
      :all

      (some (fn [id] (not= (sidebar-of (get before id)) (sidebar-of (get after id)))) (keys after))
      :all

      :else
      (let [ids (set (for [id (keys after)
                           :when (not= (select-keys (get before id) bot-value-keys)
                                       (select-keys (get after id) bot-value-keys))]
                       id))]
        (when (seq ids)
          (fn [w]
            (contains? ids (:id (shown-bot after (:tabs new) (:tabid w) (url-bot w))))))))))

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
      (let [content (await (uploads/content uploads text attachments (await (harness/sees-images? (:models (machine/agent)) conversation))))]
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
  [bot path]
  (some #(when (= path (:path %)) %) (:artifacts/list bot)))

(defn- open-folder!
  "The list browses `folder` of the bot; its README, when it has one,
   takes the screen (`rragent.artifacts/readme`)."
  [bot folder]
  (let [bot-path (db/bot-path (:id bot))]
    (artifacts/browse! db/db bot-path folder)
    (when-let [readme (artifacts/readme (:artifacts/list bot) folder)]
      (artifacts/pick! db/db bot-path readme))))

(defn- open-artifact-command
  "The person picked an artifact in the list or on a card, or went back
   to the list (a nil path); a path that is a folder's, a link in a
   README, opens the folder."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)
        path (some-> path (str/replace #"/+$" ""))]
    (cond
      (nil? bot) (command/rejected w :no-such-bot)
      (or (nil? path) (artifact-of bot path)) (do (artifacts/pick! db/db (db/bot-path (:id bot)) path)
                                                  (command/accepted w))
      (artifacts/folder? (:artifacts/list bot) path) (do (open-folder! bot path)
                                                         (command/accepted w))
      :else (command/rejected w :no-such-artifact))))

(defn- open-folder-command
  "The person opened a folder in the list, or a crumb of the way to it;
   the root for a blank path."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)
        folder (str/replace (str path) #"/+$" "")]
    (cond
      (nil? bot) (command/rejected w :no-such-bot)
      (artifacts/folder? (:artifacts/list bot) folder) (do (open-folder! bot folder)
                                                           (command/accepted w))
      :else (command/rejected w :no-such-folder))))

(defn- clear-artifact-flags
  "The bot's map without a rename or a delete under way in a row of
   the pane."
  [value id]
  (update-in value (db/bot-path id) dissoc :artifacts/renaming :artifacts/deleting))

(defn- busy?
  "Whether the artifact is in use, so that it stays where it is: a
   project whose process runs (`rragent.projects`), one whose render
   runs, or the file a render writes (`rragent.render`)."
  [{:keys [projects-handle render-handle]} {:keys [path kind]}]
  (let [project? (= :project kind)
        render-name (if project? path (artifacts/render-name path))]
    (boolean (or (and project? projects-handle (projects/running? projects-handle path))
                 (and render-name render-handle (render/running? render-handle render-name))))))

(defn- rename-start-artifact-command
  "The field in the row of the artifact, see `:artifacts/rename`."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)]
    (if (and bot (artifact-of bot path))
      (do (swap! db/db #(assoc-in (clear-artifact-flags % (:id bot)) (conj (db/bot-path (:id bot)) :artifacts/renaming) path))
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- rename-artifact-command
  "The artifact renamed to the base name the field says, its extension
   kept (`rragent.artifacts/renamed-path`); a blank name keeps the old.
   The renamed file is the one on screen then. Refused while the
   artifact is in use (`busy?`), for a name that leaves the folder, or
   one that is taken."
  [w]
  (let [{:keys [path name]} (command/command-data w)
        bot (bot-of-command w)
        artifact (artifact-of bot path)]
    (when bot
      (swap! db/db clear-artifact-flags (:id bot)))
    (cond
      (nil? artifact) (command/rejected w :no-such-artifact)
      (str/blank? (str name)) (command/accepted w)
      (busy? bot artifact) (command/rejected w :busy)
      :else (if-let [to (artifacts/renamed-path artifact name)]
              (let [outcome (artifacts/rename! (:dir bot) path to)]
                (if (= :renamed outcome)
                  (do (artifacts/pick! db/db (db/bot-path (:id bot)) to)
                      (artifacts/refresh! db/db (db/bot-path (:id bot)) (:dir bot))
                      (command/accepted w))
                  (command/rejected w outcome)))
              (command/rejected w :bad-name)))))

(defn- delete-artifact-command
  "The first click arms the question in the artifact's row, the second
   moves the file into the bot's trash, `<home>/trash/<bot>/artifacts/`
   (`rragent.artifacts/trash!`); a deleted file on screen goes back to
   the list. Refused while the artifact is in use (`busy?`)."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)
        artifact (artifact-of bot path)
        bot-path (some-> bot :id db/bot-path)]
    (cond
      (nil? artifact)
      (command/rejected w :no-such-artifact)

      (not= path (:artifacts/deleting bot))
      (do (swap! db/db #(assoc-in (clear-artifact-flags % (:id bot)) (conj bot-path :artifacts/deleting) path))
          (command/accepted w))

      :else
      (do (swap! db/db clear-artifact-flags (:id bot))
          (if (busy? bot artifact)
            (command/rejected w :busy)
            (let [trash (path/join (bots/trash-dir (machine/home)) (str (:id bot)) (subs artifacts/url-prefix 1))
                  outcome (artifacts/trash! (:dir bot) trash path)]
              (if (keyword? outcome)
                (command/rejected w outcome)
                (do (when (= path (:artifacts/current bot))
                      (artifacts/pick! db/db bot-path nil))
                    (artifacts/refresh! db/db bot-path (:dir bot))
                    (command/accepted w)))))))))

(defn- keep-artifact-command
  "The question of a delete, or the field of a rename, taken back."
  [w]
  (if-let [bot (bot-of-command w)]
    (do (swap! db/db clear-artifact-flags (:id bot))
        (command/accepted w))
    (command/rejected w :no-such-bot)))

(defn- render-command
  "Make the video of the project on screen; the frames show the progress."
  [w]
  (let [{:keys [path]} (command/command-data w)
        bot (bot-of-command w)]
    (if (and bot (= :project (:kind (artifact-of bot path))))
      (do (render/start! (:render-handle bot) path)
          (command/accepted w))
      (command/rejected w :no-such-artifact))))

(defn- render-stop-command
  [w]
  (if-let [bot (bot-of-command w)]
    (do (render/stop! (:render-handle bot) (:path (command/command-data w)))
        (command/accepted w))
    (command/rejected w :no-such-bot)))

(defn- update-apply-command
  "The Update button: the known release taken (`rragent.update/apply!`
   as the updater's `:apply!`, the atom's `:updater`); the frames show
   the banner's state, the page comes back after the restart."
  [w]
  (if-let [handle (:updater @db/db)]
    (do (-> (js/Promise.resolve ((:apply! handle) handle))
            (.catch (fn [error]
                      (js/console.error "[update]" (str error)))))
        (command/accepted w))
    (command/rejected w :no-updater)))

(defn- update-check-command
  "Check for updates in the settings: the newest release looked up
   now, its answer in the settings."
  [w]
  (if-let [handle (:updater @db/db)]
    (do (-> (update/check-now! handle)
            (.catch (fn [error]
                      (js/console.error "[update]" (str error)))))
        (command/accepted w))
    (command/rejected w :no-updater)))

(defn- chat-in
  "The atom's value with the chat in the tab `tab`: its settings
   closed, when they were open."
  [value tab]
  (update value :settings-open (fnil disj #{}) tab))

(defn- settings-open-command
  "The settings in place of the chat, for the tab `tab` that asked
   (`rragent.view/settings`)."
  [w]
  (if-let [tab (:tab (command/command-data w))]
    (do (swap! db/db update :settings-open (fnil conj #{}) tab)
        (command/accepted w))
    (command/rejected w :no-tab)))

(defn- settings-close-command
  "Back to the chat in the tab `tab`."
  [w]
  (if-let [tab (:tab (command/command-data w))]
    (do (swap! db/db chat-in tab)
        (command/accepted w))
    (command/rejected w :no-tab)))

(defn- show-command
  "The tab `tab` shows the bot `id` from now on, nil whatever its URL
   names; its settings close."
  [w]
  (let [{:keys [id tab]} (command/command-data w)]
    (cond
      (nil? tab) (command/rejected w :no-tab)
      (nil? id) (do (swap! db/db #(-> (update % :tabs dissoc tab) (chat-in tab)))
                    (command/accepted w))
      (machine/bot id) (do (swap! db/db #(-> (assoc-in % [:tabs tab] (str id)) (chat-in tab)))
                           (command/accepted w))
      :else (command/rejected w :no-such-bot))))

(defn- ^:async create-command
  "A bot made by one click in the sidebar, from the assistant's
   definition as it is (`rragent.machine/create!`), shown in the tab
   that made it; it names itself after its first answer."
  [w]
  (let [{:keys [tab]} (command/command-data w)]
    (try
      (let [{:keys [id]} (await (machine/create! {}))]
        (when tab
          (swap! db/db #(-> (assoc-in % [:tabs tab] id) (chat-in tab))))
        (command/accepted w))
      (catch :default e
        (js/console.error "[bots]" (str e))
        (command/rejected w :not-created)))))

(defn- clear-flags
  "The atom's value without a delete or a rename under way in any
   row."
  [value]
  (update value :bots (fn [bots] (into {} (map (fn [[id bot]] [id (dissoc bot :deleting? :renaming?)])) bots))))

(defn- rename-start-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (machine/bot id)
      (do (swap! db/db #(assoc-in (clear-flags %) (conj (db/bot-path id) :renaming?) true))
          (command/accepted w))
      (command/rejected w :no-such-bot))))

(defn- ^:async rename-command
  "The bot's name as the field says, into its definition; a blank one
   keeps the old."
  [w]
  (let [{:keys [id name]} (command/command-data w)]
    (swap! db/db clear-flags)
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

      (not (:deleting? (machine/bot id)))
      (do (swap! db/db #(assoc-in (clear-flags %) (conj (db/bot-path id) :deleting?) true))
          (command/accepted w))

      :else
      (let [outcome (await (machine/delete! id))]
        (swap! db/db (fn [value]
                       (-> (clear-flags value)
                           (update :tabs #(into {} (remove (fn [[_ shown]] (= (str id) shown))) %)))))
        (if (= :deleted outcome)
          (command/accepted w)
          (command/rejected w outcome))))))

(defn- keep-command
  "The question of a delete, or the field of a rename, taken back."
  [w]
  (swap! db/db clear-flags)
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
  [{:keys [projects-handle dir]} w]
  (let [w (await (projects/handler projects-handle w))]
    (if (:ring/response w)
      w
      (artifacts/file-handler dir w))))

(defn- ^:async artifacts-post
  "POST /bot/<id>/artifacts/<name>/...: to the project's process;
   nothing else takes a POST here."
  [{:keys [projects-handle]} w]
  (let [w (await (projects/handler projects-handle w))]
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

      (= rest (subs phone/path-prefix 1))
      (let [locale (i18n/locale (:ring/request w))]
        (assoc w :ring/response {:status 200
                                 :headers {"Content-Type" "text/html; charset=utf-8"
                                           "Cache-Control" "no-cache"}
                                 :body (phone/page-shell {:bot id
                                                          :name (i18n/translate (machine/label-of id) locale)
                                                          :words (i18n/translate (view/phone-words) locale)
                                                          :head (assets/head (assets/client))})}))

      (= rest (str (subs phone/path-prefix 1) "/events"))
      (phone/events-handler id w)

      (= rest (str (subs phone/path-prefix 1) "/frame"))
      (await (phone/frame-handler id w))

      (= rest (str (subs phone/path-prefix 1) "/test"))
      (phone/test-handler {:db db/db :path (db/bot-path id)} w)

      (str/starts-with? rest (str (subs recordings/path-prefix 1) "/"))
      (recordings/get-handler (:dir bot) (with-subpath w rest (subs recordings/path-prefix 1)))

      :else
      (assoc w :ring/response http/not-found))))

(defn- ^:async bot-post
  "POST /bot/<id>: the view stream; /bot/<id>/upload: a file into
   the bot's folder; /bot/<id>/record/<stamp>/...: a screencast as it
   is recorded, into the bot's artifacts (`rragent.recordings`);
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

      (str/starts-with? rest (str (subs recordings/path-prefix 1) "/"))
      (await (recordings/handler (:dir bot)
                                 {:phone-done? (fn [stamp] (phone/done? db/db (db/bot-path id) stamp))}
                                 (with-subpath w rest (subs recordings/path-prefix 1))))

      (str/starts-with? rest (str (subs phone/path-prefix 1) "/"))
      (await (phone/post! {:db db/db :path (db/bot-path id) :bot id}
                          (subs rest (inc (count (subs phone/path-prefix 1))))
                          w))

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
  (loop [[bot & more] (vals (machine/bots))]
    (if (nil? bot)
      w
      (let [answered (if (:projects-handle bot)
                       (await (projects/command-handler (:projects-handle bot) w))
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
     ;; the scrcpy server the page pushes to a phone over USB
     (http/files "/scrcpy/*" (path/join dir "scrcpy"))
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
     {:command/kind :artifacts/open-folder
      :command/fn #'open-folder-command}
     {:command/kind :artifacts/rename-start
      :command/fn #'rename-start-artifact-command}
     {:command/kind :artifacts/rename
      :command/fn #'rename-artifact-command}
     {:command/kind :artifacts/delete
      :command/fn #'delete-artifact-command}
     {:command/kind :artifacts/keep
      :command/fn #'keep-artifact-command}
     {:command/kind :artifacts/render
      :command/fn #'render-command}
     {:command/kind :artifacts/render-stop
      :command/fn #'render-stop-command}
     {:command/kind :update/apply
      :command/fn #'update-apply-command}
     {:command/kind :update/check
      :command/fn #'update-check-command}
     {:command/kind :settings/open
      :command/fn #'settings-open-command}
     {:command/kind :settings/close
      :command/fn #'settings-close-command}
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

(defn open-register
  "The routes outside the token: the hooks, and the pairing of a phone
   (`rragent.phone`), which lets the phone in with the token's cookie."
  [token]
  [{:ring/route [:post "/hook/*"]
    :ring/handler #'hook-handler}
   {:ring/route [:get (str phone/pair-prefix "/*")]
    :ring/handler (fn [w] (phone/pair-handler token w))}])

(defn handler
  "The ring handler: the open routes as they are, everything else behind
   the token."
  [token]
  (let [open-routes (open-register token)
        open (http/ring-handler (fn [] open-routes))
        secured (auth/wrap (http/ring-handler get-register) {:token token})]
    (fn [request]
      (if (some (fn [{:keys [ring/route]}] (http/match route request)) open-routes)
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
    (swap! db/db assoc :updater (update/handle {:db db/db}))
    (sse/watch! db/db affected)
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
                             (:updater @db/db)))
    (println (update/report (:updater @db/db)))
    (println (programs/report (templates/all)))
    (println (codemode/report (machine/codemode)))
    (println (openrouter/report (codemode/discovery (machine/codemode))))
    (println (machine/report))
    (println (str "rragent on "
                  (token-line base from)
                  (when-let [origin (assets/dev-origin)]
                    (str ", the page's scripts from " origin))))))
