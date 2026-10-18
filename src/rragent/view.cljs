(ns rragent.view
  "The page as a function of the conversation: the transcript of
   pi-durable's view (`rragent.page/conversation` turns the view value
   into the data this namespace reads) as a feed of items, the header,
   the composer, and the pane of the artifacts, empty for now. The texts
   are m1p reference tuples, `[:i18n :chat/you]`, that
   `rragent.i18n/translate` turns into the language of the person. The
   classes follow rchat's stylesheet, which the page ships as app.css."
  (:require [clojure.string :as str]))

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
    "state" [:i18n :tool/state]
    "schedule" [:i18n :tool/schedule]
    "hook" [:i18n :tool/hook]
    [:i18n :tool/other {:name name}]))

(defn- tool-detail
  [args]
  (or (:command args)
      (:path args)
      (:file_path args)
      (:url args)
      (:code args)
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

(defn- user-item
  [id label model]
  {:kind :user
   :id id
   :label label
   :text (text-of (:content (first model)))})

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
    {:kind :user
     :id id
     :pending? true
     :text (text-of content)}))

(defn items
  "The feed of a conversation `{:entries :live :inbox}`."
  [{:keys [entries live inbox]}]
  (let [results (into {}
                      (for [{:keys [kind model]} entries
                            :when (= "pi.tool-result" kind)
                            :let [message (first model)]]
                        [(:toolCallId message) message]))
        slots (into {} (map (juxt :callId identity)) (:tools live))]
    (concat (mapcat #(entry-items % results slots) entries)
            (partial-items live entries results slots)
            (retry-items live)
            (queued-items inbox))))

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

(defn- item-view
  [{:keys [kind id label text pending? streaming? error aborted?] :as item}]
  (case kind
    :user
    [:div.item.user {:replicant/key id
                     :class (when pending? "pending")}
     [:div.label (or label [:i18n :chat/you])]
     [:p.text text]
     (when pending?
       [:p.hint [:i18n :chat/queued]])]

    :assistant
    [:div.item.step {:replicant/key id
                     :class (when streaming? "streaming")}
     (when (seq text)
       [:p.text text])
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

(defn feed
  [items status]
  [:section.feed {:data-stick-to-end "true"}
   (if (empty? items)
     [:p.hint.empty [:i18n :chat/empty]]
     (map item-view (filter shown? items)))
   (when (= :working status)
     [:div.item.working {:replicant/key "working"}
      [:span.dot]
      [:span [:i18n :chat/working]]])])

(defn header
  "The agent's name and model, and whether a run goes."
  [{:keys [name model]} status]
  [:header.bar
   [:h1 name]
   [:span.model model]
   [:span.status {:class (clojure.core/name status)}
    (case status
      :working [:i18n :chat/working]
      [:i18n :chat/ready])]])

(defn send-actions
  "Sends the draft to the conversation, then clears it."
  []
  [[:data/command
    {:command/kind :chat/send
     :command/data {:text [:store/deref :draft]}}
    {:on-success [[:store/dissoc :draft]]}]])

(defn composer
  "The draft is client state (`:ui/composer`, see `rragent.client`): a
   new frame does not reset it. Stop aborts the run while one goes."
  [status]
  [:div.composer
   [:ui/composer {:ui/store-key :draft
                  :ui/send (send-actions)
                  :rows 3
                  :placeholder [:i18n :composer/placeholder]}]
   [:button.primary {:on {:click (send-actions)}}
    [:i18n :composer/send]]
   (when (= :working status)
     [:button.stop {:on {:click [[:data/command {:command/kind :chat/stop}]]}}
      [:i18n :composer/stop]])])

(defn artifacts
  "The right half of the page, where the live artifacts come."
  []
  [:section.artifacts
   [:div.label [:i18n :page/artifacts]]
   [:div.placeholder [:i18n :page/artifacts-placeholder]]])

(defn page
  "`opts`: `:agent`, its `:name` and `:model`; `:conversation`, the
   transcript `{:entries :live :inbox}`; `:locale`."
  [{:keys [agent conversation locale]}]
  (let [items (items conversation)
        status (status (:live conversation))]
    [:div.rragent {:lang (some-> locale clojure.core/name)}
     (header agent status)
     [:div.body
      [:div.chat
       (feed items status)
       (composer status)]
      (artifacts)]]))
