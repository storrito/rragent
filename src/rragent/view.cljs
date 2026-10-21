(ns rragent.view
  "The page as a function of the bots, the conversation of the one
   shown and its artifacts: the sidebar with the bots (name, state, the
   way to make, rename and delete one), the header, the transcript of
   pi-durable's view (`rragent.page/conversation` turns the view value
   into the data this namespace reads) as a feed of items, the
   composer, and the pane of the artifacts (the keys of
   `rragent.artifacts`, `rragent.projects` and `rragent.render` in the
   bot's atom). The texts are m1p reference tuples, `[:i18n :chat/you]`,
   that `rragent.i18n/translate` turns into the language of the person.
   The files attached to a user message are the lines of its text that
   name them (`rragent.uploads`), shown as thumbnails and links. The
   answer of the model is markdown and goes through `rragent.markdown`
   to the page; the person's own words stay plain text. Everything of a
   bot is under its page, `/bot/<id>` (`base`): its artifacts, its
   uploads, where the composer posts. The classes follow rchat's
   stylesheet, which the page ships as app.css."
  (:require [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.bots :as bots]
            [rragent.dictation :as dictation]
            [rragent.markdown :as markdown]
            [rragent.uploads :as uploads]))

(defn cut
  "At most `n` characters of `text`."
  [n text]
  (let [text (str text)]
    (if (< n (count text))
      (str (subs text 0 n) " …")
      text)))

(defn text-of
  "The text of a message content: the string, or the text blocks of a
   list of blocks joined."
  [content]
  (cond
    (string? content) content
    (sequential? content) (str/join "\n" (keep (fn [block]
                                                  (when (= "text" (:type block))
                                                    (:text block)))
                                                content))
    :else ""))

(defn- tool-label
  "The row of a tool call, named for a person."
  [name]
  (case name
    "bash" [:i18n :tool/bash]
    "read" [:i18n :tool/read]
    "write" [:i18n :tool/write]
    "edit" [:i18n :tool/edit]
    "eval" [:i18n :tool/eval]
    "fetch" [:i18n :tool/fetch]
    "remember" [:i18n :tool/remember]
    "recall" [:i18n :tool/recall]
    "schedule" [:i18n :tool/schedule]
    "hook" [:i18n :tool/hook]
    "render" [:i18n :tool/render]
    "project" [:i18n :tool/project]
    "image" [:i18n :tool/image]
    "voice" [:i18n :tool/voice]
    "music" [:i18n :tool/music]
    "subagent" [:i18n :tool/subagent]
    "bot" [:i18n :tool/bot]
    [:i18n :tool/other {:name name}]))

(defn- tool-detail
  [args]
  (or (:command args)
      (:path args)
      (:file_path args)
      (:url args)
      (:code args)
      (:name args)
      (:prompt args)
      (:task args)
      (pr-str args)))

(defn- tool-items
  "The tool calls of an assistant message as items, each with its result
   from the `pi.tool-result` entries (`results`, by call id) or what the
   running call has put out so far (`slots`, the tool slots of `pi.live`)."
  [message results slots]
  (for [{:keys [id name arguments]} (filter #(= "toolCall" (:type %)) (:content message))]
    (let [result (get results id)
          slot (get slots id)]
      {:kind :tool
       :id id
       :name name
       :args arguments
       :done? (boolean (or result (= "done" (:status slot))))
       :error? (boolean (:isError result))
       :result (if result
                 (text-of (:content result))
                 (:output slot))})))

(defn- assistant-item
  [id {:keys [content stopReason errorMessage]}]
  {:kind :assistant
   :id id
   :text (text-of content)
   :error (when (= "error" stopReason)
            [:i18n :chat/model-failed {:error (str errorMessage)}])
   :aborted? (= "aborted" stopReason)})

(defn- with-media
  "The user item with its text, less the lines that name attached
   files, and the files as `:media` (`rragent.uploads/attachments`)."
  [item content]
  (let [text (text-of content)]
    (assoc item
           :text (uploads/without-lines text)
           :media (uploads/attachments text))))

(defn- user-item
  [id label model]
  (with-media {:kind :user
               :id id
               :label label}
              (:content (first model))))

(defn- entry-items
  "An entry of the transcript as the items a person reads about it: a
   user message, an assistant message with its tool calls, a note about
   a reset or a compaction; nothing for the rest (the system prompt, the
   tool results, which the calls show)."
  [{:keys [id kind model data]} results slots]
  (case kind
    "pi.user" [(user-item id nil model)]
    "rragent.hook" [(user-item id [:i18n :chat/hook {:name (:name data)}] model)]
    "rragent.schedule" [(user-item id [:i18n :chat/schedule {:name (:name data)}] model)]
    "pi.assistant" (let [message (first model)]
                     (cons (assistant-item id message)
                           (tool-items message results slots)))
    "rragent.fact" [{:kind :note :id id :text [:i18n :chat/fact {:event (:event data)}]}]
    "pi.reset" [{:kind :note :id id :text [:i18n :chat/reset]}]
    "pi.compaction" [{:kind :note :id id :text [:i18n :chat/compacted]}]
    nil))

(defn- committed?
  "Whether the partial `message` of the running generation is in the
   transcript already: an assistant entry of the same request."
  [entries message]
  (some (fn [{:keys [kind model]}]
          (and (= "pi.assistant" kind)
               (= (:timestamp message) (:timestamp (first model)))))
        entries))

(defn- partial-items
  "The streamed partial of the running generation as an item while the
   answer is not committed yet; during a tool round the tool-calling
   answer is in the transcript and the partial is stale."
  [{:keys [generation tools]} entries results slots]
  (let [message (:message generation)]
    (when (and message (nil? tools) (not (committed? entries message)))
      (cons (assoc (assistant-item "partial" message) :streaming? true)
            (tool-items message results slots)))))

(defn- retry-items
  [{:keys [generation]}]
  (when-let [{:keys [error]} (:retry generation)]
    [{:kind :note :id "retry" :text [:i18n :chat/retry {:error (str error)}]}]))

(defn- queued-items
  "The inputs waiting in the inbox for the run to end, as pending user
   messages at the end of the feed."
  [inbox]
  (for [{:keys [id mode content]} (:items inbox)
        :when (not= "write" mode)]
    (with-media {:kind :user
                 :id id
                 :pending? true}
                content)))

(defn- person-answer?
  "A message of the person's, sent or waiting; a hook's or a schedule's
   carries a label and answers nothing."
  [{:keys [kind label]}]
  (and (= :user kind) (nil? label)))

(defn- answered
  "The answers before the person's last message marked `:answered?`:
   the choices in them are made (`rragent.genui`)."
  [items]
  (let [items (vec items)
        last-answer (last (keep-indexed (fn [i item] (when (person-answer? item) i)) items))]
    (if last-answer
      (into [] (map-indexed (fn [i item]
                              (cond-> item
                                (and (< i last-answer) (= :assistant (:kind item))) (assoc :answered? true))))
            items)
      items)))

(defn items
  "The feed of a conversation `{:entries :live :inbox}`, the answers the
   person has answered since marked (`answered`)."
  [{:keys [entries live inbox]}]
  (let [results (into {}
                      (for [{:keys [kind model]} entries
                            :when (= "pi.tool-result" kind)
                            :let [message (first model)]]
                        [(:toolCallId message) message]))
        slots (into {} (map (juxt :callId identity)) (:tools live))]
    (answered (concat (mapcat #(entry-items % results slots) entries)
                      (partial-items live entries results slots)
                      (retry-items live)
                      (queued-items inbox)))))

(defn status
  "`:working` while a run goes, else `:ready`."
  [live]
  (if (:run live) :working :ready))

(defn- shown?
  "An assistant message that only carried tool calls has no text and is
   not an item on the page."
  [{:keys [kind text error aborted? streaming?]}]
  (not (and (= :assistant kind)
            (str/blank? text)
            (nil? error)
            (not aborted?)
            (not streaming?))))

(defn- tool-view
  [{:keys [name args done? error? result]}]
  [:details.work {:class (when error? "failed")}
   [:summary (tool-label name)
    (cond
      (not done?) [:i18n :tool/running]
      error? [:i18n :tool/failed])]
   [:pre.command (tool-detail args)]
   (when (seq result)
     [:pre.output (cut 2000 result)])])

(defn- media-view
  "The files attached to a message: a picture as a thumbnail, a clip as
   its name, each a link to the file under the bot's page."
  [base media]
  [:div.looks
   (for [{:keys [kind name]} media
         :let [url (str base uploads/url-prefix "/" name)]]
     (if (= :image kind)
       [:a {:replicant/key name
            :href url
            :target "_blank"
            :rel "noopener"}
        [:img {:src url
               :alt [:i18n :chat/picture]
               :loading "lazy"}]]
       [:a.clip {:replicant/key name
                 :href url
                 :target "_blank"
                 :rel "noopener"}
        (if (= :audio kind) "🎵 " "🎬 ")
        name]))])

(defn- item-view
  [{:keys [kind id label text media pending? streaming? answered? error aborted?] :as item} {:keys [base bot]}]
  (case kind
    :user
    [:div.item.user {:replicant/key id
                     :class (when pending? "pending")}
     [:div.label (or label [:i18n :chat/you])]
     (when (seq text)
       [:p.text text])
     (when (seq media)
       (media-view base media))
     (when pending?
       [:p.hint [:i18n :chat/queued]])]

    :assistant
    [:div.item.step {:replicant/key id
                     :class (when streaming? "streaming")}
     (when (seq text)
       (into [:div.markdown] (markdown/hiccup text {:streaming? streaming? :answered? answered? :base base :bot bot})))
     (when aborted?
       [:p.hint [:i18n :chat/aborted]])
     (when error
       [:p.error error])]

    :tool
    [:div.item.step {:replicant/key id}
     (tool-view item)]

    :note
    [:div.item.note {:replicant/key id}
     [:p.text text]]))

(defn welcome
  "A chat with nothing in it, opened by the bot's skill
   (`rragent.onboarding`): its name and what to bring."
  [{:keys [title ask]}]
  [:div.item.step.welcome {:replicant/key "welcome"}
   (when title
     [:div.label title])
   (into [:div.markdown] (markdown/hiccup ask))])

(defn feed
  "The items of the conversation; with none shown yet, the skill's
   welcome, or the plain empty chat for a bot without one. `opts` has
   the `:base` and `:bot` of the files and commands in the items."
  [items status onboarding opts]
  [:section.feed {:data-stick-to-end "true"}
   (let [shown (filter shown? items)]
     (cond
       (seq shown) (map #(item-view % opts) shown)
       onboarding (welcome onboarding)
       :else [:p.hint.empty [:i18n :chat/empty]]))
   (when (= :working status)
     [:div.item.working {:replicant/key "working"}
      [:span.dot]
      [:span [:i18n :chat/working]]])])

(defn header
  "The bot's name and model, the running version of rragent (a click
   on it looks for a newer release, `rragent.update`), whether a run
   goes, and the button that switches between the light and the dark
   theme (`:ui/theme-toggle`, client state, see `rragent.client`). On
   a phone the sidebar is a drawer; the label opens it (the checkbox
   in `page`)."
  [{:keys [name model]} status version]
  [:header.bar
   [:label.menu {:for "nav" :aria-label [:i18n :bots/menu]} "☰"]
   [:h1 name]
   [:span.model model]
   (when version
     [:button.version {:title [:i18n :update/check-title]
                       :on {:click [[:data/command {:command/kind :update/check}]]}}
      (str "v" version)])
   [:span.status {:class (clojure.core/name status)}
    (case status
      :working [:i18n :chat/working]
      [:i18n :chat/ready])]
   [:ui/theme-toggle {:ui/light-title [:i18n :theme/light]
                      :ui/dark-title [:i18n :theme/dark]}]])

(defn- update-button
  [label]
  [:button.link {:on {:click [[:data/command {:command/kind :update/apply}]]}}
   label])

(defn update-banner
  "Above the chat when a newer rragent is known (`rragent.update`, the
   `:update` of the db): the version, the first line of its notes and
   the button, then what the update is doing, or why it did not work.
   After a lookup the person asked for, that there is nothing newer,
   or that the lookup failed. Nothing otherwise."
  [{:keys [current version notes state error auto? checked]}]
  (cond
    (= :applying state)
    [:div.banner.update [:i18n :update/applying {:version version}]]

    (= :failed state)
    [:div.banner.update.failed
     [:i18n :update/failed {:version version :error (str error)}]
     " "
     (update-button [:i18n :update/retry])]

    version
    [:div.banner.update
     [:i18n :update/available {:version version}]
     (when (seq notes)
       [:span.notes " " notes])
     " "
     (update-button [:i18n :update/button])
     (when auto?
       [:span.auto " " [:i18n :update/auto]])]

    (= :newest checked)
    [:div.banner.update [:i18n :update/newest {:version current}]]

    (= :failed checked)
    [:div.banner.update.failed [:i18n :update/lookup-failed]]))

(defn draft-key
  "Where the browser keeps the draft for a bot; one per bot, so that a
   switch of the bot keeps what was typed."
  [id]
  [:draft id])

(defn attachments-key
  [id]
  [:attachments id])

(defn send-actions
  "Sends the draft and the attachments to the bot's conversation, then
   clears both."
  [id]
  [[:data/command
    {:command/kind :chat/send
     :command/data {:bot id
                    :text [:store/deref (draft-key id)]
                    :attachments [:store/deref (attachments-key id)]}}
    {:on-success [[:store/dissoc (draft-key id)]
                  [:store/dissoc (attachments-key id)]]}]])

(defn composer
  "The draft and the attachments are client state (`:ui/composer`, see
   `rragent.client`), per bot: a new frame does not reset them. The
   alias has the microphone button, whose recording goes into the draft
   as text (`rragent.dictation`, under the bot's page), the attach
   button and Send, which waits for the uploads; Stop aborts the run
   while one goes. `placeholder` is the skill's while the chat is
   empty, the page's own otherwise."
  [id base status placeholder]
  [:div.composer
   [:ui/composer {:ui/store-key (draft-key id)
                  :ui/attachments-key (attachments-key id)
                  :ui/upload-url (str base uploads/upload-path)
                  :ui/send (send-actions id)
                  :ui/send-label [:i18n :composer/send]
                  :ui/send-pending-label [:i18n :composer/send-pending]
                  :ui/send-cancel-title [:i18n :composer/send-cancel]
                  :ui/attach-title [:i18n :composer/attach]
                  :ui/remove-title [:i18n :composer/remove]
                  :ui/uploading-label [:i18n :composer/uploading]
                  :ui/failed-label [:i18n :composer/upload-failed]
                  :ui/drop-title [:i18n :composer/drop-title]
                  :ui/drop-hint [:i18n :composer/drop-hint]
                  :ui/dictation-url (str base dictation/path)
                  :ui/dictate-title [:i18n :composer/dictate]
                  :ui/dictate-stop-title [:i18n :composer/dictate-stop]
                  :ui/transcribing-title [:i18n :composer/transcribing]
                  :ui/dictation-failed-title [:i18n :composer/dictation-failed]
                  :ui/send-after-dictation-label [:i18n :composer/send-after-dictation]
                  :rows 3
                  :title [:i18n :composer/keys]
                  :placeholder (or placeholder [:i18n :composer/placeholder])}]
   (when (= :working status)
     [:button.stop {:on {:click [[:data/command {:command/kind :chat/stop
                                                 :command/data {:bot id}}]]}}
      [:i18n :composer/stop]])])

(defn- artifact-url
  [base path]
  (str base artifacts/url-prefix "/" path))

(defn- src
  [base {:keys [path mtime]}]
  (str (artifact-url base path) "?rev=" mtime))

(defn- rendering
  [render]
  [:i18n :artifacts/rendering {:progress (or (:progress render) "")}])

(defn- content
  "The artifact on screen by its kind. `project` is the state of its
   process (`rragent.projects`), `render` the state of its render
   (`rragent.render`): the one of the project, or the one still writing
   a video."
  [base {:keys [path kind mtime title] :as artifact} {:keys [project render]}]
  (case kind
    :page [:ui/canvas {:ui/src (artifact-url base path)
                       :ui/rev mtime
                       :ui/title [:i18n :artifacts/creation]}]
    :image [:div.media
            [:img {:replicant/key path
                   :src (src base artifact)
                   :alt title}]]
    ;; The element stays while a render writes the file (one key, one
    ;; src): a reload per frame would show nothing but a spinner.
    :video [:div.media
            [:video {:replicant/key path
                     :src (if render
                            (str (artifact-url base path) "?rev=" (:started render))
                            (src base artifact))
                     :controls true}]
            (when render
              [:p.rendering (rendering render)])]
    :audio [:div.media
            [:audio {:replicant/key (str path mtime)
                     :src (src base artifact)
                     :controls true}]]
    :project (let [{:keys [ready-at exit]} project]
               (cond
                 exit [:div.placeholder [:i18n :artifacts/stopped {:exit exit}]]
                 ready-at (list
                           (when (:error render)
                             [:p.error [:i18n :artifacts/render-failed {:error (:error render)}]])
                           [:ui/canvas {:ui/src (str (artifact-url base path) "/")
                                        :ui/rev ready-at
                                        :ui/title [:i18n :artifacts/creation]}])
                 :else [:div.placeholder [:i18n :artifacts/starting]]))
    [:div.placeholder [:i18n :artifacts/no-preview]]))

(defn- option
  [{:keys [path title kind]} selected]
  ;; The option carries the choice: a value on the select alone is set
  ;; before the options exist on a fresh page.
  [:option {:value path
            :selected (= path selected)}
   title
   ;; The video of a design shares its name with the design.
   (when (= :video kind) [:i18n :artifacts/video])])

(defn- render-controls
  "The video of the project, made here on a click; its progress and Stop
   while it is made."
  [bot path render]
  (if (and (:started render) (nil? (:exit render)))
    [:span.rendering
     (rendering render)
     " · "
     [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render-stop
                                                 :command/data {:bot bot :path path}}]]}}
      [:i18n :artifacts/render-stop]]]
    [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render
                                                :command/data {:bot bot :path path}}]]}}
     [:i18n :artifacts/render]]))

(defn- actions
  "Open and download links for a file; Open and the render for a project."
  [base bot {:keys [path kind] :as artifact} render]
  (if (= :project kind)
    (list
     [:a.link {:href (str (artifact-url base path) "/")
               :target "_blank"
               :rel "noopener"}
      [:i18n :artifacts/open]]
     (render-controls bot path render))
    (list
     [:a.link {:href (src base artifact)
               :target "_blank"
               :rel "noopener"}
      [:i18n :artifacts/open]]
     [:a.link {:href (src base artifact)
               :download (last (str/split path #"/"))}
      [:i18n :artifacts/download]])))

(defn artifacts
  "The right half of the page: a chooser over the artifacts, the links of
   the one on screen, and the one shown by its kind. `pane` holds the
   keys of `rragent.artifacts` (`:artifacts/list`, `:artifacts/current`),
   `:projects` and `:renders`, and the `:base` of the bot's page with
   its id as `:bot`."
  [{:keys [artifacts/list artifacts/current projects renders base bot] :as pane}]
  (let [artifact (some #(when (= current (:path %)) %) list)
        project? (= :project (:kind artifact))
        render (when artifact
                 (if project?
                   (get renders (:path artifact))
                   (artifacts/writing pane artifact)))]
    [:section.artifacts {:class (when (empty? list) "empty")}
     [:div.bar
      [:span.label [:i18n :page/artifacts]]
      (when (seq list)
        [:select.chooser {:value (:path artifact)
                          :on {:change [[:data/command {:command/kind :artifacts/open
                                                        :command/data {:bot bot :path :event/target.value}}]]}}
         (for [file list]
           (option file (:path artifact)))])
      (when artifact
        (actions base bot artifact render))]
     (if artifact
       (content base artifact {:project (get projects (:path artifact))
                               :render render})
       [:div.placeholder [:i18n :artifacts/empty]])]))

(defn- views
  "On a phone the chat and the artifacts take turns: two radios in
   `page`, these labels are the tabs, the stylesheet shows one of the
   two; nothing of it on a wide screen, where the pane is beside the
   chat."
  []
  [:nav.views
   [:label {:for "view-chat"} [:i18n :page/view-chat]]
   [:label {:for "view-made"} [:i18n :page/artifacts]]])

(defn state-label
  [state]
  (case state
    :running [:i18n :bot/running]
    :asleep [:i18n :bot/asleep]
    :failed [:i18n :bot/failed]
    [:i18n :bot/idle]))

(defn- delete-confirm
  "The row of a bot whose deletion is armed (see `:bot/delete`): the
   question, then delete for good or keep."
  [id]
  [:div.confirm
   [:span [:i18n :bot/delete-sure]]
   [:span.choices
    [:button.link.danger {:on {:click [[:data/command {:command/kind :bot/delete
                                                       :command/data {:id id}}]]}}
     [:i18n :bot/delete-yes]]
    [:button.link {:on {:click [[:data/command {:command/kind :bot/keep}]]}}
     [:i18n :bot/keep]]]])

(defn- rename-field
  "The row of a bot being renamed (see `:bot/rename-start`): a field
   with its name, whose change renames it, and the way to keep it."
  [id name]
  [:div.rename
   [:input.name {:replicant/key (str "rename-" id)
                 :value name
                 :aria-label [:i18n :bot/name]
                 :on {:change [[:data/command {:command/kind :bot/rename
                                               :command/data {:id id
                                                              :name :event/target.value}}]]}}]
   [:button.link {:on {:click [[:data/command {:command/kind :bot/keep}]]}}
    [:i18n :bot/keep]]])

(defn- bot-actions
  "The menu behind the three dots of a sidebar row: rename, and delete
   unless it is the machine's last bot. A <details>, so the browser
   opens and closes it; the runtime (`rragent.client`) closes it on a
   click elsewhere. A delete arms the question in the row."
  [id deletable?]
  [:details.actions
   [:summary {:aria-label [:i18n :bot/actions]
              :title [:i18n :bot/actions]}
    "⋮"]
   [:div.items
    [:button.item {:on {:click [[:data/command {:command/kind :bot/rename-start
                                                :command/data {:id id}}]]}}
     [:i18n :bot/rename]]
    (when deletable?
      [:button.item.danger {:on {:click [[:data/command {:command/kind :bot/delete
                                                         :command/data {:id id}}]]}}
       [:i18n :bot/delete]])]])

(defn- new-bot
  "The way to make a bot: one click, nothing asked. The bot starts
   from the assistant's definition as it is and names itself after its
   first answer; it opens in this tab (`:ui/tabid`, filled in by the
   runtime)."
  []
  [:button.new {:on {:click [[:data/command {:command/kind :bot/create
                                             :command/data {:tab :ui/tabid}}]]}}
   [:i18n :bot/new]])

(defn sidebar
  "The bots of the machine, each a link to its page with its name
   (its `:label` when it has none of its own yet: \"New bot\" and when
   it was made) and state, the one shown marked; the way to make one;
   a row being deleted asks, a row being renamed is a field
   (`:deleting?`, `:renaming?` of the bot)."
  [bots shown version]
  [:aside.sidebar
   [:div.brand "rragent"
    (when version
      [:span.version {:title "rragent version"} version])]
   (new-bot)
   ;; keyed by its order: a list whose rows move makes a new list
   ;; (Replicant 2026.07.1 keeps a stale vdom for a row moved to the front)
   [:ul.bots {:replicant/key (str/join " " (map :id bots))}
    (for [{:keys [id name label state deleting? renaming?]} bots]
      [:li {:replicant/key id
            :class (cond-> [(clojure.core/name state)]
                     (= id shown) (conj "current"))}
       (cond
         deleting? (delete-confirm id)
         renaming? (rename-field id name)
         :else (list
                [:a.row {:href (bots/url id)
                         :data-bot id}
                 [:span.name (or label name)]
                 [:span.state (state-label state)]]
                (bot-actions id (< 1 (count bots)))))])]])

(defn page
  "`opts`: `:bots`, the bots of the sidebar, each `{:id :name :label
   :state :deleting? :renaming?}`; `:bot`, the one shown, its `:id`,
   `:name` (what the header shows, the label of the sidebar) and
   `:model`; `:conversation`, its transcript `{:entries :live
   :inbox}`; `:artifacts`, its pane's keys; `:locale`; `:onboarding`,
   the skill's welcome (`rragent.onboarding`) or nil; `:update`, the
   running version and the newer release when one is known
   (`rragent.update`); `:note`, a line above the chat when the bot is
   not the one the URL named; `:failed`, why the bot did not open,
   when it did not."
  [{:keys [bots bot conversation locale onboarding update note failed] pane :artifacts}]
  (let [id (:id bot)
        base (when id (bots/url id))
        items (items conversation)
        status (status (:live conversation))
        fresh? (not-any? shown? items)
        artifacts? (seq (:artifacts/list pane))]
    [:div.rragent {:lang (some-> locale clojure.core/name)}
     [:ui/url {:ui/path base}]
     ;; the drawer state on a phone, toggled by labels, never rendered
     ;; with :checked so that the person's choice survives a frame
     [:input#nav.toggle {:type "checkbox"}]
     (sidebar bots id (:current update))
     [:label.backdrop {:for "nav"}]
     ;; keyed by the bot: a switch builds the chat anew, the transcripts
     ;; of two bots share no element
     [:div.main {:replicant/key (str "bot-" id)}
      ;; the view on a phone once there are artifacts, chat or artifacts;
      ;; never rendered with :checked either, see `views`
      (when artifacts?
        (list
         [:input#view-chat.toggle {:type "radio" :name "view"}]
         [:input#view-made.toggle {:type "radio" :name "view"}]))
      (header bot status (:current update))
      (when artifacts?
        (views))
      [:div.body
       [:div.chat
        (update-banner update)
        (when note
          [:div.banner.note note])
        (when failed
          [:div.banner.failed [:i18n :bot/not-open {:error (str failed)}]])
        (feed items status onboarding {:base base :bot id})
        (composer id base status (when fresh? (:placeholder onboarding)))]
       (artifacts (assoc pane :base base :bot id))]]]))
