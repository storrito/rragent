(ns rragent.view
  "The page as a function of the bots, the conversation of the one
   shown and its artifacts: the sidebar with the bots (name, state, the
   way to make, rename and delete one; the settings at its bottom), the
   header, the transcript of
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
            [rragent.recordings :as recordings]
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

(defn- human-size
  [bytes]
  (cond
    (nil? bytes) nil
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (str (js/Math.round (/ bytes 1024)) " KB")
    :else (str (.toFixed (/ bytes 1024 1024) 1) " MB")))

(defn- user-item
  [id model]
  (with-media {:kind :user
               :id id}
              (:content (first model))))

(defn- hook-label
  "What a hook's call was: the event's line when the call carried a
   Storrito integration trigger event (`rragent.hooks/event-line`),
   else the hook's name."
  [{:keys [name event]}]
  (if event
    [:i18n :chat/hook-event {:event event :name name}]
    [:i18n :chat/hook-called {:name name}]))

(defn- call-item
  "A hook's call or a schedule's run: a folded step with `label` on
   its line and the whole input, as the model got it, behind it."
  [id label data model]
  {:kind :call
   :id id
   :label label
   :bytes (:bytes data)
   :text (text-of (:content (first model)))})

(defn- entry-items
  "An entry of the transcript as the items a person reads about it: a
   user message, an assistant message with its tool calls, a note about
   a reset or a compaction; nothing for the rest (the system prompt, the
   tool results, which the calls show)."
  [{:keys [id kind model data]} results slots]
  (case kind
    "pi.user" [(user-item id model)]
    "rragent.hook" [(call-item id (hook-label data) data model)]
    "rragent.schedule" [(call-item id [:i18n :chat/schedule-ran {:name (:name data)}] data model)]
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
  "A message of the person's, sent or waiting; a hook's call or a
   schedule's run is a `:call` and answers nothing."
  [{:keys [kind]}]
  (= :user kind))

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

(defn context-usage
  "How full the model's context is, from the usage pi notes on every
   assistant message: the tokens of the newest request, prompt (cached
   or not) and answer, which is what the next request carries; and
   the cost of the whole conversation so far, when the service prices
   it. Nil for a conversation without an answer yet."
  [{:keys [entries]}]
  (let [usages (for [{:keys [kind model]} entries
                     :when (= "pi.assistant" kind)
                     :let [usage (:usage (first model))]
                     :when (map? usage)]
                 usage)]
    (when-let [{:keys [input output cacheRead cacheWrite]} (last usages)]
      {:used (reduce + 0 (map #(or % 0) [input output cacheRead cacheWrite]))
       :cost (let [costs (keep #(get-in % [:cost :total]) usages)]
               (when (seq costs)
                 (reduce + 0 costs)))})))

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

(defn- artifact-url
  [base path]
  (str base artifacts/url-prefix "/" path))

(defn- src
  [base {:keys [path mtime]}]
  (str (artifact-url base path) "?rev=" mtime))

(def kind-icons
  "The icon of an artifact by its kind, in the pane's list and on a card;
   a folder's and a recording's in the list."
  {:page "🌐" :image "🖼️" :video "🎬" :audio "🎵" :project "🧩" :markdown "📝" :text "📝" :file "📎"
   :folder "📁" :recording "🎥"})

(defn clock
  "Seconds as `m:ss`, `1:02:03` from an hour on."
  [seconds]
  (let [total (js/Math.round (or seconds 0))
        h (quot total 3600)
        m (quot (rem total 3600) 60)
        s (rem total 60)
        two #(if (< % 10) (str "0" %) (str %))]
    (if (pos? h)
      (str h ":" (two m) ":" (two s))
      (str m ":" (two s)))))

(defn- made-path
  "The artifact a tool call made: `artifacts/<path>` in the path it
   wrote, or in the first line of its result (a render, a picture, a
   voice, music, a project); nil for none, for a working note
   (`rragent.artifacts/note?`), or while the call runs, or when it
   failed."
  [{:keys [name args done? error? result]}]
  (when (and done? (not error?))
    (let [candidate (case name
                      ("write" "edit") (or (:path args) (:file_path args))
                      ("render" "image" "voice" "music" "project") (first (str/split-lines (str result)))
                      nil)]
      (when-let [[_ path] (re-find #"(?:^|[\s/])artifacts/([^\s:]+)" (str candidate))]
        (let [path (not-empty (str/replace path #"/+$" ""))]
          (when-not (artifacts/note? path)
            path))))))

(defn- artifact-card
  "What a tool call made, as a card in the chat: a tap opens it in the
   pane (`a[data-artifact]`, `rragent.client`), on a phone the pane
   itself; named and typed the way the pane's list does it."
  [base bot path]
  (let [kind (artifacts/kind path)]
    [:a.artifact-card {:href (artifact-url base path)
                       :class (clojure.core/name kind)
                       :data-artifact path
                       :data-artifact-bot bot}
     [:span.icon (get kind-icons kind "📎")]
     [:span.name (artifacts/title path)]
     [:span.meta (or (artifacts/extension path) [:i18n :artifacts/project])]]))

(defn- item-view
  [{:keys [kind id label text media bytes pending? streaming? answered? error aborted?] :as item} {:keys [base bot]}]
  (case kind
    :user
    [:div.item.user {:replicant/key id
                     :class (when pending? "pending")}
     [:div.label [:i18n :chat/you]]
     (when (seq text)
       [:p.text text])
     (when (seq media)
       (media-view base media))
     (when pending?
       [:p.hint [:i18n :chat/queued]])]

    :call
    [:div.item.step {:replicant/key id}
     [:details.work
      [:summary label (when bytes (str " · " (human-size bytes)))]
      [:pre.output (cut 2000 text)]]]

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
     (tool-view item)
     (when-let [path (made-path item)]
       (artifact-card base bot path))]

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
   on it opens the settings, `settings`, where a newer release is
   looked for), how full the
   model's context is (`:ui/context`, a ring that opens the usage,
   `rragent.client`), whether a run goes, and the button that switches
   between the light and the dark theme (`:ui/theme-toggle`, client
   state, see `rragent.client`). On a phone the sidebar is a drawer;
   the label opens it (the checkbox in `page`)."
  [{:keys [name model context-window]} status version usage]
  [:header.bar
   [:label.menu {:for "nav" :aria-label [:i18n :bots/menu]} "☰"]
   [:h1 name]
   [:span.model model]
   (when version
     [:button.version {:title [:i18n :settings/version-title]
                       :on {:click [[:data/command {:command/kind :settings/open
                                                    :command/data {:tab :ui/tabid}}]]}}
      (str "v" version)])
   (when usage
     [:ui/context {:ui/used (:used usage)
                   :ui/window context-window
                   :ui/cost (:cost usage)
                   :ui/labels {:title [:i18n :context/title]
                               :used [:i18n :context/used]
                               :of [:i18n :context/of]
                               :unknown [:i18n :context/unknown]
                               :cost [:i18n :context/cost]
                               :hint [:i18n :context/hint]
                               :ring [:i18n :context/ring]
                               :close [:i18n :context/close]}}])
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
   Nothing otherwise; what a lookup the person asked for found is the
   settings' (`updates-section`)."
  [{:keys [version notes state error auto?]}]
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
       [:span.auto " " [:i18n :update/auto]])]))

(defn updates-section
  "The Updates section of the settings: the running version, the
   button that looks the newest release up right now
   (`rragent.update/check-now!`; the hourly lookup keeps its rhythm),
   and what the lookup found: the newer release with the Update
   button, what the update is doing or why it did not work, the newest
   already, or that the lookup failed."
  [{:keys [current version notes state error auto? checked checking?]}]
  [:section.updates
   [:h2 [:i18n :updates/title]]
   (when current
     [:p.explain [:i18n :updates/current {:version current}]])
   [:p [:i18n :updates/explain]]
   [:p
    [:button.link.check {:on {:click [[:data/command {:command/kind :update/check}]]}
                         :disabled (boolean checking?)}
     [:i18n :updates/check]]]
   (cond
     checking?
     [:p.result [:i18n :updates/checking]]

     (= :applying state)
     [:p.result [:i18n :update/applying {:version version}]]

     (= :failed state)
     [:p.result.failed
      [:i18n :update/failed {:version version :error (str error)}]
      " "
      (update-button [:i18n :update/retry])]

     version
     [:p.result
      [:i18n :update/available {:version version}]
      (when (seq notes)
        [:span.notes " " notes])
      " "
      (update-button [:i18n :update/button])
      (when auto?
        [:span.auto " " [:i18n :update/auto]])]

     (= :newest checked)
     [:p.result [:i18n :update/newest {:version current}]]

     (= :failed checked)
     [:p.result.failed [:i18n :update/lookup-failed]])])

(defn- mcp-state
  "What a server's row says of it."
  [{:keys [connected connecting off needs error tools]}]
  (cond
    off [:span.state.off [:i18n :mcp/off]]
    connecting [:span.state.connecting [:i18n :mcp/connecting]]
    (seq needs) [:span.state.needs [:i18n :mcp/needs {:names (str/join ", " needs)}]]
    connected [:span.state.connected [:i18n :mcp/connected {:count tools}]]
    error [:span.state.failed [:i18n :mcp/failed {:error (str error)}]]
    :else [:span.state [:i18n :mcp/idle]]))

(defn- mcp-command
  [kind data]
  {:click [[:data/command {:command/kind kind :command/data data}]]})

(defn- secret-field
  "The field that saves the secret `secret-name` from the settings."
  [secret-name]
  [:ui/secret-field {:ui/name secret-name
                     :ui/label secret-name
                     :ui/button [:i18n :secret/save]
                     :ui/placeholder [:i18n :secret/placeholder]}])

(defn- test-line
  "What the last Test connection of a server found, or that it runs."
  [{:keys [running? ok? tools error ms at]}]
  (let [time (when at (list [:ui/time {:ui/ms at}] " · "))]
    (cond
      running? [:p.tested [:i18n :mcp/testing]]
      ok? [:p.tested.ok time [:i18n :mcp/test-ok {:count tools :ms ms}]]
      (some? ok?) [:p.tested.failed time [:i18n :mcp/test-failed {:error (str error) :ms ms}]])))

(defn- mcp-row
  "One server: its name, where it is, its state, what its last test
   found (`test`), a field for each secret it waits for, and its
   buttons; a remove asks first, in the row (`removing`)."
  [{:keys [name address off connecting needs] :as server} removing test]
  [:li.mcp-server {:replicant/key name}
   [:div.head
    [:strong.name name]
    (when address
      [:span.address address])
    (mcp-state server)]
   (when test
     (test-line test))
   (for [secret-name needs]
     [:div.secret {:replicant/key secret-name}
      (secret-field secret-name)])
   (if (= name removing)
     [:div.actions.confirm
      [:span [:i18n :mcp/remove-confirm {:name name}]]
      [:button.link.danger {:on (mcp-command :mcp/remove {:name name})} [:i18n :mcp/remove]]
      [:button.link {:on (mcp-command :mcp/keep {})} [:i18n :mcp/keep]]]
     [:div.actions
      (when-not off
        [:button.link {:on (mcp-command :mcp/test {:name name})
                       :disabled (boolean (or connecting (:running? test)))}
         [:i18n :mcp/test]])
      [:button.link {:on (mcp-command :mcp/enable {:name name :on? (boolean off)})}
       (if off [:i18n :mcp/turn-on] [:i18n :mcp/turn-off])]
      [:button.link.danger {:on (mcp-command :mcp/remove {:name name})} [:i18n :mcp/remove]]])])

(defn mcp-section
  "The MCP servers of the machine (`rragent.mcp/overview`): each with
   its state and the buttons to test, switch and remove it; the button
   that opens a new bot to connect a service (`:mcp/setup`, made in
   this tab); and the saved secrets, which only go from here."
  [{:keys [servers removing tests secrets]}]
  [:section.mcp
   [:h2 [:i18n :mcp/title]]
   [:p.explain [:i18n :mcp/explain]]
   (if (seq servers)
     [:ul.mcp-servers
      (for [server servers]
        (mcp-row server removing (get tests (:name server))))]
     [:p [:i18n :mcp/none]])
   [:p.setup
    [:button.primary {:on {:click [[:data/command {:command/kind :mcp/setup
                                                   :command/data {:tab :ui/tabid}}]]}}
     [:i18n :mcp/setup]]
    [:span.explain " " [:i18n :mcp/setup-explain]]]
   (when (seq secrets)
     (list
      [:h3 [:i18n :mcp/secrets]]
      [:p.explain [:i18n :mcp/secrets-explain]]
      [:ul.secrets
       (for [secret-name secrets]
         [:li {:replicant/key secret-name}
          [:code secret-name]
          " "
          [:button.link.danger {:on (mcp-command :secret/remove {:name secret-name})}
           [:i18n :mcp/secret-remove]]])]))])

(def settings-sections
  [:updates :mcp])

(defn settings
  "In place of the bot's chat and pane, for the tab that opened them
   (`:settings/open`: the button at the bottom of the sidebar, or the
   version in the header): a bar with the title and the way back
   (`:settings/close`), the tabs of the sections (`:settings/section`,
   per tab), and the section the tab picked: the updates
   (`updates-section`) or the MCP servers (`mcp-section`, `mcp` its
   data)."
  [update section mcp]
  (let [section (if (some #{section} settings-sections) section :updates)]
    (list
     [:header.bar
      [:label.menu {:for "nav" :aria-label [:i18n :bots/menu]} "☰"]
      [:h1 [:i18n :settings/title]]
      [:button.link.close {:on {:click [[:data/command {:command/kind :settings/close
                                                        :command/data {:tab :ui/tabid}}]]}}
       [:i18n :settings/close]]]
     [:div.settings
      [:nav.tabs {:role "tablist" :aria-label [:i18n :settings/tabs]}
       (for [k settings-sections]
         [:button.tab {:replicant/key (name k)
                       :role "tab"
                       :aria-selected (= k section)
                       :class (when (= k section) "current")
                       :on {:click [[:data/command {:command/kind :settings/section
                                                    :command/data {:tab :ui/tabid :section k}}]]}}
          [:i18n (keyword "settings" (name k))]])]
      (case section
        :mcp (mcp-section mcp)
        (updates-section update))])))

(defn phone-words
  "The words of the phone's page (`rragent.client.phone`), translated
   by the server into its shell."
  []
  {:title [:i18n :phone/title]
   :connecting [:i18n :phone/connecting]
   :waiting [:i18n :phone/waiting]
   :reconnecting [:i18n :phone/reconnecting]
   :recording [:i18n :phone/recording]
   :testing [:i18n :phone/testing]
   :tested [:i18n :phone/tested]
   :uploading [:i18n :phone/uploading]
   :done [:i18n :phone/done]
   :failed [:i18n :phone/failed]
   :flip [:i18n :phone/flip]
   :keep-open [:i18n :phone/keep-open]})

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
   button and Send, which waits for the uploads; while a run goes, a
   Stop button beside it aborts the run (`:ui/stop`); the screencast
   button records the screen and the webcam into the bot's artifacts
   (`rragent.recordings`, the words of its panel in `:ui/recorder`).
   `placeholder` is the skill's while the chat is empty, the page's own
   otherwise."
  [id base status placeholder phone]
  [:div.composer
   [:ui/composer {:ui/store-key (draft-key id)
                  :ui/phone phone
                  :ui/stop (when (= :working status)
                             [[:data/command {:command/kind :chat/stop
                                              :command/data {:bot id}}]])
                  :ui/stop-label (when (= :working status)
                                   [:i18n :composer/stop])
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
                  :ui/record-url (str base recordings/path-prefix)
                  :ui/recorder {:button [:i18n :record/button]
                                :title [:i18n :record/title]
                                :close [:i18n :record/close]
                                :opening [:i18n :record/opening]
                                :mic [:i18n :record/mic]
                                :camera [:i18n :record/camera]
                                :test [:i18n :record/test]
                                :testing [:i18n :record/testing]
                                :start [:i18n :record/start]
                                :screen-hint [:i18n :record/screen-hint]
                                :recording [:i18n :record/recording]
                                :stop [:i18n :record/stop]
                                :saving [:i18n :record/saving]
                                :failed [:i18n :record/failed]
                                :draft [:i18n :record/draft]
                                :file-screen [:i18n :record/file-screen]
                                :file-webcam [:i18n :record/file-webcam]
                                :file-phone [:i18n :record/file-phone]
                                :camera-choice [:i18n :record/camera-choice]
                                :source-screen [:i18n :record/source-screen]
                                :source-webcam [:i18n :record/source-webcam]
                                :source-phone [:i18n :record/source-phone]
                                :source-phone-screen [:i18n :record/source-phone-screen]
                                :file-phone-screen [:i18n :record/file-phone-screen]
                                :usb-connect [:i18n :record/usb-connect]
                                :usb-picking [:i18n :record/usb-picking]
                                :usb-connecting [:i18n :record/usb-connecting]
                                :usb-none [:i18n :record/usb-none]
                                :usb-busy [:i18n :record/usb-busy]
                                :usb-no-phone [:i18n :record/usb-no-phone]
                                :usb-dark [:i18n :record/usb-dark]
                                :started [:i18n :record/started]
                                :hide-live [:i18n :record/hide-live]
                                :show-live [:i18n :record/show-live]
                                :usb-denied [:i18n :record/usb-denied]
                                :usb-unsupported [:i18n :record/usb-unsupported]
                                :source-mic [:i18n :record/source-mic]
                                :file-audio [:i18n :record/file-audio]
                                :draft-audio [:i18n :record/draft-audio]
                                :mic-hint [:i18n :record/mic-hint]
                                :recording-audio [:i18n :record/recording-audio]
                                :recording-local [:i18n :record/recording-local]
                                :no-screen-hint [:i18n :record/no-screen-hint]
                                :none [:i18n :record/none]
                                :blocked [:i18n :record/blocked]
                                :no-devices [:i18n :record/no-devices]
                                :pair [:i18n :record/pair]
                                :pair-hint [:i18n :record/pair-hint]
                                :phone-connected [:i18n :record/phone-connected]
                                :phone-back [:i18n :record/phone-back]
                                :phone-front [:i18n :record/phone-front]
                                :screen-choose [:i18n :record/screen-choose]
                                :screen-change [:i18n :record/screen-change]
                                :phone-none [:i18n :record/phone-none]
                                :phone-waiting [:i18n :record/phone-waiting]}
                  :ui/dictate-title [:i18n :composer/dictate]
                  :ui/dictate-stop-title [:i18n :composer/dictate-stop]
                  :ui/transcribing-title [:i18n :composer/transcribing]
                  :ui/dictation-failed-title [:i18n :composer/dictation-failed]
                  :ui/send-after-dictation-label [:i18n :composer/send-after-dictation]
                  :rows 3
                  :title [:i18n :composer/keys]
                  :placeholder (or placeholder [:i18n :composer/placeholder])}]])

(defn- rendering
  [render]
  [:i18n :artifacts/rendering {:progress (or (:progress render) "")}])

(defn- text-view
  "A text file on screen: markdown rendered (`rragent.markdown`, the
   links in it to the files and folders beside the file, `:folder`),
   the rest as it is; a note when this is the start of it only
   (`rragent.artifacts/text-of`)."
  [kind {:keys [text truncated?]} base bot path]
  [:div.document
   (if (= :markdown kind)
     (into [:div.markdown] (markdown/hiccup text {:base base :bot bot :folder (artifacts/folder-of path)}))
     [:pre.plain text])
   (when truncated?
     [:p.hint [:i18n :artifacts/truncated]])])

(defn- content
  "The artifact on screen by its kind. `project` is the state of its
   process (`rragent.projects`), `render` the state of its render
   (`rragent.render`): the one of the project, or the one still writing
   a video; `text` the text of a text file, `bot` the bot's id."
  [base {:keys [path kind mtime title] :as artifact} {:keys [project render text bot]}]
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
    (:markdown :text) (if text
                        (text-view kind text base bot path)
                        [:div.placeholder [:i18n :artifacts/no-preview]])
    [:div.placeholder [:i18n :artifacts/no-preview]]))

(defn- file-actions
  "The menu behind the three dots of a row, as the sidebar's
   `bot-actions`: rename, and delete, which arms the question in the
   row."
  [bot path]
  [:details.actions
   [:summary {:aria-label [:i18n :artifacts/actions]
              :title [:i18n :artifacts/actions]}
    "⋮"]
   [:div.items
    [:button.item {:on {:click [[:data/command {:command/kind :artifacts/rename-start
                                                :command/data {:bot bot :path path}}]]}}
     [:i18n :artifacts/rename]]
    [:button.item.danger {:on {:click [[:data/command {:command/kind :artifacts/delete
                                                       :command/data {:bot bot :path path}}]]}}
     [:i18n :artifacts/delete]]]])

(defn- file-confirm
  "The row of an artifact whose deletion is armed (see
   `:artifacts/delete`): the question, then into the trash or keep."
  [bot {:keys [path title]}]
  [:div.confirm
   [:span [:i18n :artifacts/delete-sure {:title title}]]
   [:span.choices
    [:button.link.danger {:on {:click [[:data/command {:command/kind :artifacts/delete
                                                       :command/data {:bot bot :path path}}]]}}
     [:i18n :artifacts/delete-yes]]
    [:button.link {:on {:click [[:data/command {:command/kind :artifacts/keep
                                                :command/data {:bot bot}}]]}}
     [:i18n :artifacts/keep]]]])

(defn- file-rename
  "The row of an artifact being renamed (see `:artifacts/rename-start`):
   a field with its name less the extension, whose change renames it,
   and the way to keep it."
  [bot {:keys [path] :as artifact}]
  [:div.rename
   [:input.name {:replicant/key (str "rename-" path)
                 :value (artifacts/base-name artifact)
                 :aria-label [:i18n :artifacts/name]
                 :on {:change [[:data/command {:command/kind :artifacts/rename
                                               :command/data {:bot bot
                                                              :path path
                                                              :name :event/target.value}}]]}}]
   [:button.link {:on {:click [[:data/command {:command/kind :artifacts/keep
                                               :command/data {:bot bot}}]]}}
    [:i18n :artifacts/keep]]])

(defn- file-row
  "A row of the list: the icon of the kind, the title, and what tells
   two files of one title apart: the extension (a project as such),
   the folder when it is not the one browsed (`folder`), the size; the
   three dots with rename and delete. A click opens the file. The row
   carries its title and path as `data-name` for the filter above the
   list (`:ui/files-filter`, `rragent.client`); a row of a file in
   another folder is `deep?`: there for the filter alone. A row whose
   delete is armed asks, one being renamed is a field (`deleting`,
   `renaming`)."
  [bot {:keys [path kind title ext size] :as artifact} {:keys [current renaming deleting folder deep?]}]
  (let [project? (= :project kind)
        own (artifacts/folder-of path)
        parts (remove nil? [(if project? [:i18n :artifacts/project] ext)
                            (when (and (seq own) (not= own folder)) (str own "/"))
                            (when-not project? (human-size size))])]
    ;; a deep row keyed apart from the row of the level: a node that
    ;; changed its place keeps a stale vdom in Replicant, so none moves
    [:li {:replicant/key (if deep? (str "deep:" path) path)
          :class (cond-> [(clojure.core/name kind)]
                   (= path current) (conj "current"))
          :data-name (str/lower-case (str title " " path))
          :data-deep (when deep? "true")}
     (cond
       (= path deleting) (file-confirm bot artifact)
       (= path renaming) (file-rename bot artifact)
       :else (list
              [:button.row {:on {:click [[:data/command {:command/kind :artifacts/open
                                                         :command/data {:bot bot :path path}}]]}}
               [:span.icon (get kind-icons kind "📎")]
               [:span.name title]
               (into [:span.meta] (interpose " · " parts))]
              (file-actions bot path)))]))

(defn- folder-row
  "A row of a folder of the level: the icon, its name, how many files
   it holds and their size; a recording's folder its time, and its
   length when known. A click opens the folder."
  [bot {:keys [path name title kind count size duration]}]
  [:li.folder {:replicant/key path
               :class [(clojure.core/name kind)]
               :data-name (str/lower-case (str title " " name " " path))}
   [:button.row {:on {:click [[:data/command {:command/kind :artifacts/open-folder
                                              :command/data {:bot bot :path path}}]]}}
    [:span.icon (get kind-icons kind)]
    [:span.name title]
    (into [:span.meta] (interpose " · " (remove nil? [(when duration (clock duration))
                                                     (if (= 1 count) [:i18n :artifacts/one-file] [:i18n :artifacts/files {:n count}])
                                                     (human-size size)])))]])

(defn- open-folder
  [bot path]
  {:on {:click [[:data/command {:command/kind :artifacts/open-folder
                                :command/data {:bot bot :path path}}]]}})

(defn- crumbs
  "The way to the folder browsed, above the rows: the root and every
   folder on the way as a button, the folder itself as the last word,
   each named as its row is (`rragent.artifacts/title`); nothing at
   the root."
  [bot folder]
  (when-let [way (artifacts/crumbs folder)]
    (into [:nav.crumbs
           [:button.crumb (open-folder bot "") [:i18n :page/artifacts]]]
          (mapcat (fn [{:keys [path name]} last?]
                    [[:span.sep "›"]
                     (if last?
                       [:span.here (artifacts/title name)]
                       [:button.crumb (open-folder bot path) (artifacts/title name)])])
                  way
                  (concat (repeat (dec (count way)) false) [true])))))

(def filter-from
  "How many rows the list needs for the filter above it."
  8)

(defn- file-list
  "The level of the folder browsed (`rragent.artifacts/level`): the way
   to it, its folders, then its files, in the filter alias
   (`:ui/files-filter`, `rragent.client`), whose field shows once the
   list is long; the files of the other levels, by path, as rows the
   filter alone shows, so that it finds a file wherever it is."
  [bot list folder durations opts]
  (let [folder (if (artifacts/folder? list folder) folder "")
        {:keys [folders files]} (artifacts/level list folder durations)
        here (set (map :path files))
        opts (assoc opts :folder folder)]
    [:ui/files-filter {:ui/store-key [:artifacts-filter bot]
                       :ui/field? (< filter-from (count list))
                       :ui/placeholder [:i18n :artifacts/filter]
                       :ui/crumbs (crumbs bot folder)}
     (for [f folders]
       (folder-row bot f))
     (for [file files]
       (file-row bot file opts))
     (for [file (sort-by :path artifacts/by-name (remove #(here (:path %)) list))]
       (file-row bot file (assoc opts :deep? true)))]))

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
  "The right half of the page: the list of the artifacts, newest first,
   beside the one on screen, with its Open and Download links in the
   bar; on a phone the list alone, or the one on screen with the way
   back (`.showing`, the stylesheet). `pane` holds the keys of
   `rragent.artifacts` (`:artifacts/list`, `:artifacts/current`, the
   `:artifacts/folder` browsed with the `:artifacts/durations` of the
   recordings, the `:artifacts/text` of a text file on screen, the
   path of a row being renamed or asked about, `:artifacts/renaming`
   and `:artifacts/deleting`), `:projects` and `:renders`, and the
   `:base` of the bot's page with its id as `:bot`."
  [{:keys [artifacts/list artifacts/current artifacts/folder artifacts/durations artifacts/text artifacts/renaming artifacts/deleting projects renders base bot] :as pane}]
  (let [artifact (some #(when (= current (:path %)) %) list)
        project? (= :project (:kind artifact))
        render (when artifact
                 (if project?
                   (get renders (:path artifact))
                   (artifacts/writing pane artifact)))]
    [:section.artifacts {:class (cond-> []
                                  (empty? list) (conj "empty")
                                  artifact (conj "showing"))}
     [:div.bar
      (when artifact
        [:button.link.back {:on {:click [[:data/command {:command/kind :artifacts/open
                                                         :command/data {:bot bot :path nil}}]]}}
         "‹ " [:i18n :artifacts/back]])
      [:span.label [:i18n :page/artifacts]]
      (when artifact
        [:span.title (:title artifact)])
      (when artifact
        (actions base bot artifact render))]
     [:div.split
      (when (seq list)
        (file-list bot list (str folder) durations {:current current :renaming renaming :deleting deleting}))
      [:div.shown
       (cond
         artifact (content base artifact {:project (get projects (:path artifact))
                                          :render render
                                          :text text
                                          :bot bot})
         (seq list) [:div.placeholder [:i18n :artifacts/pick]]
         :else [:div.placeholder [:i18n :artifacts/empty]])]]]))

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
   (`:deleting?`, `:renaming?` of the bot). At the bottom the way to
   the settings, marked while this tab shows them (`settings?`)."
  [bots shown version settings?]
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
                (bot-actions id (< 1 (count bots)))))])]
   [:div.bottom
    [:button.settings {:class (when settings? "current")
                       :on {:click [[:data/command {:command/kind :settings/open
                                                    :command/data {:tab :ui/tabid}}]]}}
     [:i18n :settings/button]]]])

(defn page
  "`opts`: `:bots`, the bots of the sidebar, each `{:id :name :label
   :state :deleting? :renaming?}`; `:bot`, the one shown, its `:id`,
   `:name` (what the header shows, the label of the sidebar), its
   `:model` and the model's `:context-window` in tokens, when known;
   `:conversation`, its transcript `{:entries :live
   :inbox}`; `:artifacts`, its pane's keys; `:locale`; `:onboarding`,
   the skill's welcome (`rragent.onboarding`) or nil; `:update`, the
   running version and the newer release when one is known
   (`rragent.update`); `:note`, a line above the chat when the bot is
   not the one the URL named; `:failed`, why the bot did not open,
   when it did not; `:phone`, the phone paired as a camera while its
   hello is fresh (`rragent.phone/status`), or nil; `:settings?`,
   whether this tab shows the settings in place of the bot's chat and
   pane (`settings`), `:settings-section` which of their sections, and
   `:mcp` what the MCP section shows (`mcp-section`)."
  [{:keys [bots bot conversation locale onboarding update note failed phone settings? settings-section mcp] pane :artifacts}]
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
     (sidebar bots id (:current update) settings?)
     [:label.backdrop {:for "nav"}]
     (if settings?
       ;; keyed apart from the chat: the two share no element
       [:div.main.settings-open {:replicant/key "settings"}
        (settings update settings-section mcp)]
       ;; keyed by the bot: a switch builds the chat anew, the transcripts
       ;; of two bots share no element
       [:div.main {:replicant/key (str "bot-" id)}
        ;; the view on a phone once there are artifacts, chat or artifacts;
        ;; never rendered with :checked either, see `views`
        (when artifacts?
          (list
           [:input#view-chat.toggle {:type "radio" :name "view"}]
           [:input#view-made.toggle {:type "radio" :name "view"}]))
        (header bot status (:current update) (context-usage conversation))
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
          (composer id base status (when fresh? (:placeholder onboarding)) phone)]
         ;; the handle between the chat and the pane, dragged to give one
         ;; more room (`rragent.client/splitter`); a phone has none
         [:ui/splitter]
         (artifacts (assoc pane :base base :bot id))]])]))
