(ns rragent.page-test
  "The page over the machine: the token, the root's redirect to a
   bot's page, the stream with the sidebar, a message and its answer
   as frames, a picture attached under the bot's page, a second bot
   made from the sidebar and shown in the tab with its own chat and
   files, renamed, deleted to the trash, a tab whose bot is gone, the
   hook, the Update button; and the main thread free while a program
   of the REPL blocks."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.client.frames :as frames]
            [rragent.bots :as bots]
            [rragent.artifacts :as artifacts]
            [rragent.db :as db]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.hooks :as hooks]
            [rragent.images-test :as images-test]
            [rragent.machine :as machine]
            [rragent.fake-oauth :as fake-oauth]
            [rragent.mcp :as mcp]
            [rragent.mcp-oauth :as mcp-oauth]
            [rragent.secrets :as secrets]
            [rragent.mcp :as mcp]
            [rragent.secrets :as secrets]
            [rragent.page :as page]
            [rragent.rframes.http :as http]
            [rragent.rframes.sse :as sse]
            [rragent.rframes.transit :as transit]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.uploads-test :as uploads-test]))

(defn- mentions?
  "Whether a string of the hiccup contains `s`."
  [hiccup s]
  (some #(and (string? %) (str/includes? % s)) (tree-seq coll? seq hiccup)))

(defn- nodes
  [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- find-tag
  [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(defn- url-of
  "The page the frame says the tab shows (`[:ui/url]`)."
  [frame]
  (:ui/path (second (find-tag frame :ui/url))))

(defn- ^:async next-frame
  "Reads the stream until a decoded frame satisfies `pred`: `{:view
   :buffer}`, the buffer to go on with; nil when the stream ended."
  [reader decoder buffer pred]
  (let [result (await (.read reader))]
    (when-not (.-done result)
      (let [{:keys [events buffer]} (frames/parse-chunk buffer (.decode decoder (.-value result) #js {:stream true}))
            found (some (fn [{:keys [event data]}]
                          (when (= "frame" event)
                            (let [view (transit/read-str data)]
                              (when (pred view)
                                view))))
                        events)]
        (if found
          {:view found :buffer buffer}
          (await (next-frame reader decoder buffer pred)))))))

(defn- within
  "The promise, or a rejection after `ms`."
  [ms promise]
  (js/Promise.race #js [promise
                        (js/Promise. (fn [_ reject]
                                       (.unref (js/setTimeout #(reject (js/Error. (str "nothing after " ms " ms"))) ms))))]))

(defn- ^:async login
  "The cookie the token's link sets."
  [base token]
  (let [login (await (js/fetch (str base "/?token=" token) #js {:redirect "manual"}))]
    (first (str/split (str (.get (.-headers login) "set-cookie")) #";"))))

(defn- ^:async command!
  "A command posted to the page: its answer."
  [base cookie kind data]
  (let [sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                          :headers #js {:cookie cookie
                                                                        :content-type "application/transit+json"}
                                                          :body (transit/write-str {:command/kind kind
                                                                                    :command/data data})}))]
    (transit/read-str (await (.text sent)))))

(defn- ^:async stream
  "The frame stream of the page at `url` opened for the tab `tab`:
   `{:status :content-type :reader :decoder}`."
  [url cookie controller tab]
  (let [response (await (js/fetch url #js {:method "POST"
                                            :headers #js {:cookie cookie
                                                          :accept "text/event-stream"
                                                          :accept-encoding "identity"}
                                            :body (transit/write-str {:tabid tab})
                                            :signal (.-signal controller)}))]
    {:status (.-status response)
     :content-type (.get (.-headers response) "content-type")
     :reader (.getReader (.-body response))
     :decoder (js/TextDecoder.)}))

(defn- frames!
  "The reader of a stream's frames: `{:frame! :none-within}`. `frame!`
   answers the next frame that satisfies its predicate, within 15 s;
   `none-within` whether no frame came within its milliseconds. A read
   of the stream that is still in flight when a wait ends is kept for
   the next call, so that no frame is lost to it."
  [reader decoder buffer]
  (let [buffer (atom buffer)
        inflight (atom nil)
        read! (^:async fn [ms]
                (let [pending (or @inflight (reset! inflight (next-frame reader decoder @buffer (constantly true))))]
                  (try
                    (let [found (await (within ms pending))]
                      (reset! inflight nil)
                      (reset! buffer (:buffer found))
                      (:view found))
                    (catch :default _
                      :none))))]
    {:frame! (^:async fn [pred]
               (loop []
                 (let [view (await (read! 15000))]
                   (cond
                     (= :none view) (throw (js/Error. "nothing after 15000 ms"))
                     (pred view) view
                     :else (recur)))))
     :none-within (^:async fn [ms]
                    (= :none (await (read! ms))))}))

(defn- ^:async start!
  "The machine of `home`, a home of the old layout, opened with the
   faux `models` and memory storage, its page served behind the token
   `s3cret`: `{:id :base :stop!}`, the id the first bot's."
  [home models & [opts]]
  (let [[bot] (await (machine/open! (merge {:home home
                                            :models models
                                            :provider "faux"
                                            :storage (harness/memory-storage)}
                                           opts)))
        server (await (http/listen (page/handler "s3cret") 0))]
    (sse/watch! db/db page/affected)
    {:id (:id bot)
     :base (str "http://127.0.0.1:" (.-port (.address server)))
     :stop! (^:async fn []
              (sse/unwatch! db/db)
              (swap! db/db dissoc :tabs :updater :settings-open :expanded)
              (.closeAllConnections server)
              (await (js/Promise. (fn [resolve] (.close server resolve))))
              (await (machine/close-all!)))}))

(deftest page-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "remember" {:event "asked" :data {:seen 1}} "c1")
                                      (tools-test/answer "**Paris.**\n\n- the Seine\n- the Louvre\n\n```\n(+ 1 2)\n```")])
             (let [{:keys [id base stop!]} (await (start! home models))
                   controller (js/AbortController.)
                   bot-url (str base "/bot/" id)]
               (try
                 (testing "the page is behind the token"
                   (is (= 403 (.-status (await (js/fetch base)))))
                   (is (= 403 (.-status (await (js/fetch bot-url)))))
                   (is (= 403 (.-status (await (js/fetch (str base "/hook/x/y") #js {:method "GET"})))) "only a POST reaches a hook, a GET is behind the token"))
                 (let [login (await (js/fetch (str base "/?token=s3cret") #js {:redirect "manual"}))
                       cookie (first (str/split (str (.get (.-headers login) "set-cookie")) #";"))]
                   (is (= 302 (.-status login)))
                   (is (= "/" (.get (.-headers login) "location")) "the link's redirect as it was")
                   (is (str/starts-with? cookie "rragent="))
                   (testing "the root goes to the bot's page"
                     (let [root (await (js/fetch base #js {:headers #js {:cookie cookie} :redirect "manual"}))]
                       (is (= 302 (.-status root)))
                       (is (= (str "/bot/" id) (.get (.-headers root) "location")))))
                   (testing "the shim with the cookie"
                     (let [shim (await (js/fetch bot-url #js {:headers #js {:cookie cookie}}))]
                       (is (= 200 (.-status shim)))
                       (is (str/includes? (await (.text shim)) "data-rframes-view"))))
                   (testing "the stream: the empty page with the sidebar first, the question and the answer as frames"
                     (let [{:keys [status content-type reader decoder]} (await (stream bot-url cookie controller "t1"))
                           {first-frame :view buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))
                           {:keys [frame! none-within]} (frames! reader decoder buffer)]
                       (is (= 200 status))
                       (is (= "text/event-stream" content-type))
                       (is (mentions? first-frame "Nothing here yet") "the empty feed, in English")
                       (is (mentions? first-frame "What should the agent do") "the composer")
                       (is (mentions? first-frame "watcher") "the bot in the sidebar")
                       (is (mentions? first-frame "New bot"))
                       (is (= (str "/bot/" id) (url-of first-frame)))
                       (is (= [:draft id] (:ui/store-key (second (find-tag first-frame :ui/composer)))) "the draft is the bot's")
                       (is (= "1" id) "the root conversation")
                       (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Capital of France?"}))))
                       (is (= {:success? false :result {:error :no-such-bot}} (await (command! base cookie :chat/send {:bot "99" :text "x"}))))
                       (let [answer (await (frame! #(mentions? % "Paris.")))]
                         (is (some? answer))
                         (is (number? (:used (first (machine/listed)))) "the bot counts as used by its newest entry")
                         (is (mentions? answer "Capital of France?") "the question is on the page")
                         (is (mentions? answer "Noted an event") "the tool call is on the page; the event itself is in the bot's file, not in the chat")
                         (is (nil? (:ui/stop (second (find-tag answer :ui/composer)))) "the run is over, no Stop in the composer")
                         (testing "the answer's markdown is rendered"
                           (let [tags (set (map first (filter vector? (tree-seq coll? seq answer))))]
                             (is (contains? tags :strong))
                             (is (contains? tags :li))
                             (is (contains? tags :pre))
                             (is (contains? tags :code))
                             (is (not (mentions? answer "**")) "no raw markdown on the page"))))
                       (testing "a picture attached: uploaded under the bot's page, named in the message, seen by the model as a WebP, a thumbnail on the page"
                         (.setResponses faux #js [(tools-test/answer "A dot.")])
                         (await (images-test/with-webp-ffmpeg (^:async fn []
                         (let [uploaded (await (js/fetch (str bot-url "/upload") #js {:method "POST"
                                                                                       :headers #js {:cookie cookie
                                                                                                     :content-type "image/png"}
                                                                                       :body uploads-test/png}))
                               {:keys [name url]} (transit/read-str (await (.text uploaded)))
                               sent (await (command! base cookie :chat/send {:bot id
                                                                             :text "What is this?"
                                                                             :attachments [{:name name :url url :kind :image}]}))]
                           (is (= 200 (.-status uploaded)))
                           (is (= (str "/bot/" id "/uploads/" name) url) "the file's URL is under the bot's page")
                           (is (= {:success? true} sent))
                           (let [answer (await (frame! #(mentions? % "A dot.")))
                                 img (some #(when (and (vector? %) (= :img (first %))) %) (tree-seq coll? seq answer))
                                 message (->> (:entries (page/conversation (:value (machine/bot id))))
                                              (filter #(= "pi.user" (:kind %)))
                                              (last)
                                              (:model)
                                              (first)
                                              (:content))]
                             (is (= url (:src (second img))) "the thumbnail")
                             (is (not (mentions? answer "image: uploads/")) "the line is not shown as text")
                             (is (= (str "What is this?\nimage: uploads/" name) (:text (first message))) "the model reads the line")
                             (is (= "image" (:type (second message))) "and sees the picture")
                             (is (= "image/webp" (:mimeType (second message))) "as rragent.images prepares it")
                             (is (= 200 (.-status (await (js/fetch (str base url) #js {:headers #js {:cookie cookie}})))) "the file is served behind the token")
                             (is (= 403 (.-status (await (js/fetch (str base url))))))))))))
                       (testing "what the pane of the tab shows goes with the message, as a line the model reads and the page shows as a hint"
                         (let [dir (path/join (home/bot-dir home id) "work" "artifacts")
                               last-message #(->> (:entries (page/conversation (:value (machine/bot id))))
                                                  (filter (fn [entry] (= "pi.user" (:kind entry))))
                                                  (last)
                                                  (:model)
                                                  (first)
                                                  (:content))]
                           (fs/mkdirSync (path/join dir "stories") #js {:recursive true})
                           (fs/writeFileSync (path/join dir "stories" "a.html") "<h1>a</h1>")
                           (artifacts/refresh! db/db (db/bot-path id) dir)
                           (try
                             (.setResponses faux #js [(tools-test/answer "Made it pink.") (tools-test/answer "Which one?") (tools-test/answer "Got the long text.")])
                             (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Make it pink" :screen "stories/a.html"}))))
                             (let [answer (await (frame! #(mentions? % "Made it pink.")))]
                               (is (= "Make it pink\non screen beside the chat: artifacts/stories/a.html" (last-message)) "the model reads the line")
                               (is (mentions? answer "Open: stories/a.html") "the page shows it as a hint")
                               (is (not (mentions? answer "on screen beside")) "not as text"))
                             (testing "a step in the pane is a step of the tab's, which its frame carries; a step back restores the pane and the step"
                               (is (= {:success? true} (await (command! base cookie :artifacts/open {:bot id :path "stories/a.html" :tab "t1"}))) "the person opens the file: the list browses its folder")
                               (is (= {:current "stories/a.html" :folder "stories"} (artifacts/place (get-in @db/db [:bots id]))))
                               (let [pane-step #(:ui/step (second (find-tag % :ui/pane-history)))
                                     before (get-in @db/db [:bots id :pane/steps "t1"])]
                                 (is (= {:success? true} (await (command! base cookie :artifacts/open-folder {:bot id :path "" :tab "t1"}))))
                                 (let [step (get-in @db/db [:bots id :pane/steps "t1"])
                                       frame (await (frame! #(= step (pane-step %))))]
                                   (is (string? step))
                                   (is (not= before step))
                                   (is (= "" (:ui/folder (second (find-tag frame :ui/pane-history)))))
                                   (is (= {:success? true} (await (command! base cookie :artifacts/open-folder {:bot id :path "" :tab "t1"}))))
                                   (is (= step (get-in @db/db [:bots id :pane/steps "t1"])) "a click that moves nothing is no step")
                                   (is (= {:success? true} (await (command! base cookie :artifacts/show {:bot id :path nil :folder "stories" :step "1.1" :tab "t1"}))))
                                   (let [frame (await (frame! #(= "1.1" (pane-step %))))]
                                     (is (= {:ui/bot id :ui/folder "stories" :ui/current nil :ui/step "1.1"} (second (find-tag frame :ui/pane-history))))))))
                             (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "And this" :screen "gone.html"}))))
                             (await (frame! #(mentions? % "Which one?")))
                             (is (= "And this" (last-message)) "what the list no longer has names nothing")
                             (let [long-text (str "Notes of this:\n" (apply str (repeat 25000 "z")) "\nThanks")]
                               (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text long-text}))))
                               (let [frame (await (frame! #(mentions? % "Got the long text.")))
                                     message (last-message)
                                     [_ file-name] (re-find #"text: uploads/([0-9a-f]{32}\.txt)" message)]
                                 (is (.startsWith message "Notes of this:") "a long text: the message keeps its start")
                                 (is (some? file-name) "and names the file that holds it")
                                 (is (= long-text (fs/readFileSync (path/join (home/bot-dir home id) "work" "uploads" (str file-name)) "utf8")))
                                 (is (mentions? frame file-name) "the chat links the file")))
                             (finally
                               (fs/rmSync (path/join dir "stories") #js {:recursive true :force true})
                               (artifacts/refresh! db/db (db/bot-path id) dir)
                               (swap! db/db db/update-at (db/bot-path id) #(-> % (artifacts/picked nil) (artifacts/browsed "")))))))
                       (testing "the settings in place of the chat, for this tab alone; a bot picked brings the chat back"
                         (is (= {:success? false :result {:error :no-tab}} (await (command! base cookie :settings/open nil))))
                         (is (= {:success? true} (await (command! base cookie :settings/open {:tab "t1"}))))
                         (let [frame (await (frame! #(mentions? % "Check for updates")))]
                           (is (nil? (find-tag frame :ui/composer)) "no chat")
                           (is (= (str "/bot/" id) (url-of frame)) "the URL stays the bot's")
                           (is (= #{"t1"} (:settings-open @db/db))))
                         (is (= {:success? true} (await (command! base cookie :settings/close {:tab "t1"}))))
                         (await (frame! #(find-tag % :ui/composer)))
                         (is (= {:success? true} (await (command! base cookie :settings/open {:tab "t1"}))))
                         (await (frame! #(mentions? % "Check for updates")))
                         (is (= {:success? true} (await (command! base cookie :bot/show {:id id :tab "t1"}))))
                         (await (frame! #(find-tag % :ui/composer)))
                         (is (= #{} (:settings-open @db/db))))
                       (testing "a folded row's body travels only to the tab that opened it"
                         (is (= {:success? false :result {:error :no-tab}} (await (command! base cookie :chat/fold {:id "c1" :fold :open}))))
                         (is (= {:success? false :result {:error :no-id}} (await (command! base cookie :chat/fold {:tab "t1" :fold :open}))))
                         (is (= {:success? false :result {:error :no-state}} (await (command! base cookie :chat/fold {:tab "t1" :id "c1" :fold :ui/fold}))) "a placeholder the browser did not fill is no state")
                         (is (= {:success? true} (await (command! base cookie :chat/fold {:tab "t1" :id "c1" :fold :open}))))
                         (let [frame (await (frame! #(mentions? % ":event \"asked\"")))]
                           (is (= #{"c1"} (get-in @db/db [:expanded "t1"])))
                           (is (= [[:data/command {:command/kind :chat/fold :command/data {:tab :ui/tabid :id "c1" :fold :ui/fold}}]]
                                  (get-in (second (find-tag frame :details.work)) [:on :toggle]))
                               "the row's toggle, with the tab and the state filled in by the browser"))
                         (is (= {:success? true} (await (command! base cookie :chat/fold {:tab "t1" :id "c1" :fold :closed}))))
                         (await (frame! #(not (mentions? % ":event \"asked\""))))
                         (is (= #{} (get-in @db/db [:expanded "t1"])))
                         (is (= {:success? true} (await (command! base cookie :chat/fold {:tab "t1" :id "c1" :fold :open}))))
                         (await (frame! #(mentions? % ":event \"asked\"")))
                         (is (= {:success? true} (await (command! base cookie :bot/show {:id id :tab "t1"}))) "the chat built anew: every fold closed")
                         (await (frame! #(not (mentions? % ":event \"asked\""))))
                         (is (nil? (get-in @db/db [:expanded "t1"]))))
                       (testing "a second bot by one click: made from the assistant's text, shown in this tab as New bot, its chat and its files its own"
                         (is (= {:success? true} (await (command! base cookie :bot/create {:tab "t1"}))))
                         (let [id2 (:id (second (machine/listed)))
                               frame (await (frame! #(= (str "/bot/" id2) (url-of %))))]
                           (is (and (bots/valid-id? id2) (not= id id2)) "a conversation of the storage of its own")
                           (is (some #(and (string? %) (re-matches #"New bot · \d\d:\d\d" %)) (tree-seq coll? seq frame)) "in the sidebar, by its time")
                           (is (mentions? frame "Nothing here yet") "its chat is empty")
                           (is (not (mentions? frame "Paris.")) "the first bot's chat is not on it")
                           (is (= (fs/readFileSync "deploy/agent/AGENT.md" "utf8") (fs/readFileSync (path/join (home/bot-dir home id2) "AGENT.md") "utf8")) "the assistant's definition as it is")
                           (fs/writeFileSync (path/join (home/bot-dir home id2) "work" "artifacts" "card.html") "<h1>card</h1>")
                           (let [frame (await (frame! #(mentions? % "card.html")))]
                             (is (nil? (find-tag frame :ui/canvas)) "the file is on the list, the screen untouched until the person opens it"))
                           (is (= {:success? true} (await (command! base cookie :artifacts/open {:bot id2 :path "card.html" :tab "t1"}))))
                           (let [frame (await (frame! #(find-tag % :ui/canvas)))]
                             (is (= (str "/bot/" id2 "/artifacts/card.html") (:ui/src (second (find-tag frame :ui/canvas))))))
                           (is (= 200 (.-status (await (js/fetch (str base "/bot/" id2 "/artifacts/card.html") #js {:headers #js {:cookie cookie}})))))
                           (is (= 404 (.-status (await (js/fetch (str bot-url "/artifacts/card.html") #js {:headers #js {:cookie cookie}})))) "not the first bot's")
                           (testing "back to the first bot: its chat, not the card"
                             (is (= {:success? true} (await (command! base cookie :bot/show {:id id :tab "t1"}))))
                             (let [frame (await (frame! #(= (str "/bot/" id) (url-of %))))]
                               (is (mentions? frame "Paris."))
                               (is (nil? (find-tag frame :ui/canvas)))))
                           (testing "renamed from the sidebar: the person's name, marked in the definition"
                             (is (= {:success? true} (await (command! base cookie :bot/rename-start {:id id2}))))
                             (let [frame (await (frame! #(find-tag % :input.name)))]
                               (is (= "Assistant" (:value (second (find-tag frame :input.name))))))
                             (is (= {:success? true} (await (command! base cookie :bot/rename {:id id2 :name "Counting"}))))
                             (await (frame! #(mentions? % "Counting")))
                             (is (= "Counting" (bots/name-of home id2)) "in its definition")
                             (is (= "Counting" (:name (second (machine/listed))) (:label (second (machine/listed)))))
                             (is (str/includes? (fs/readFileSync (path/join (home/bot-dir home id2) "AGENT.md") "utf8") "\nrenamed: true\n")))
                           (testing "delete asks once, then the folder goes to the trash"
                             (is (= {:success? true} (await (command! base cookie :bot/delete {:id id2}))))
                             (await (frame! #(mentions? % "Delete this bot?")))
                             (is (fs/existsSync (home/bot-dir home id2)) "not yet")
                             (is (= {:success? true} (await (command! base cookie :bot/delete {:id id2}))))
                             (await (frame! #(not (mentions? % "Counting"))))
                             (is (nil? (machine/bot id2)))
                             (is (not (fs/existsSync (home/bot-dir home id2))))
                             (is (fs/existsSync (path/join (bots/trash-dir home) id2 "AGENT.md")))
                             (is (= [id] (map :id (machine/listed))))
                             (is (= #{id2} (await (machine/hidden (:harness (machine/agent))))) "hidden, its conversation kept"))
                           (testing "the last bot stays"
                             (is (= {:success? true} (await (command! base cookie :bot/delete {:id id}))) "the question")
                             (is (= {:success? false :result {:error :last}} (await (command! base cookie :bot/delete {:id id}))))
                             (is (= {:success? true} (await (command! base cookie :bot/keep nil)))))
                           (testing "a tab whose bot is gone shows another, with a note"
                             (let [gone (js/AbortController.)
                                   {:keys [reader decoder]} (await (stream (str base "/bot/" id2) cookie gone "t2"))
                                   {frame :view} (await (within 15000 (next-frame reader decoder "" (constantly true))))]
                               (is (= (str "/bot/" id) (url-of frame)))
                               (is (mentions? frame "That bot is not here any more"))
                               (.abort gone)))))
                       (testing "a change of a bot's pane goes to the tabs showing it, to none other; a handle changes nothing; the sidebar's keys go to every tab"
                         (is (= {:success? true} (await (command! base cookie :bot/create {:tab "t3"}))))
                         (let [id3 (:id (last (machine/listed)))
                               other (js/AbortController.)
                               {:keys [reader decoder]} (await (stream (str base "/bot/" id3) cookie other "t3"))
                               {buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))
                               {other-frame! :frame! other-none-within :none-within} (frames! reader decoder buffer)]
                           (try
                             (await (frame! #(mentions? % (str "/bot/" id3))))
                             ;; the create's own refresh of every tab, coalesced, may still come
                             (await (other-none-within 500))
                             (await (none-within 300))
                             (fs/writeFileSync (path/join (home/bot-dir home id) "work" "artifacts" "note.html") "<h1>note</h1>")
                             (let [frame (await (frame! #(mentions? % "note.html")))]
                               (is (nil? (find-tag frame :ui/canvas)) "the first bot's tab lists its new artifact, and shows it only on a click"))
                             (is (true? (await (other-none-within 1500))) "the other bot's tab gets no frame of it")
                             (swap! db/db assoc-in [:bots id :worker] {:a "handle"})
                             (swap! db/db assoc-in [:bots id3 :reload :text] "changed")
                             (is (true? (await (none-within 1500))) "handles changed: no frame to the first bot's tab")
                             (is (true? (await (other-none-within 500))) "nor to the other's")
                             (swap! db/db assoc-in [:bots id3 :failed] "a value")
                             (is (mentions? (await (other-frame! #(mentions? % "a value"))) "This bot did not open: a value") "the other bot's own tab shows it")
                             (is (mentions? (await (frame! #(mentions? % "failed"))) "failed") "and the first bot's tab, whose sidebar shows the state")
                             (swap! db/db update-in [:bots id3] dissoc :failed)
                             (await (other-frame! #(not (mentions? % "a value"))))
                             (finally
                               (.abort other)
                               (swap! db/db update-in [:bots id] dissoc :worker)))))))
                   (testing "a hook with a wrong secret is refused without the cookie"
                     (is (= 403 (.-status (await (js/fetch (str base "/hook/deploy/wrong") #js {:method "POST" :body "x"}))))))
                   (testing "the Update button's command runs the apply of the updater, Check for updates the lookup"
                     (let [applied (atom [])
                           post! (^:async fn [kind]
                                   (let [sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                                                          :headers #js {:cookie cookie
                                                                                                        :content-type "application/transit+json"}
                                                                                          :body (transit/write-str {:command/kind kind})}))]
                                     (transit/read-str (await (.text sent)))))]
                       (is (= {:success? false :result {:error :no-updater}} (await (post! :update/apply))) "no updater, no apply")
                       (swap! db/db assoc :updater {:db db/db
                                                    :current "0.1.2"
                                                    :latest-url "http://127.0.0.1:1/repos/storrito/rragent/releases/latest"
                                                    :apply! (fn [handle] (swap! applied conj (:current handle)) nil)})
                       (try
                         (is (= {:success? true} (await (post! :update/apply))))
                         (is (= ["0.1.2"] @applied))
                         (is (= {:success? true} (await (post! :update/check))))
                         (await (within 5000 (js/Promise. (fn [resolve]
                                                            ((fn poll []
                                                               (if (get-in @db/db [:update :checked])
                                                                 (resolve true)
                                                                 (js/setTimeout poll 20))))))))
                         (is (= :failed (get-in @db/db [:update :checked])) "the lookup at a port that refuses fails, said in the settings")
                         (is (nil? (get-in @db/db [:update :checking?])))
                         (finally
                           (swap! db/db dissoc :updater :update))))))
                 (finally
                   (.abort controller)
                   (await (stop!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- view-value
  "A view value of pi-durable with a run going or not, as the page
   reads it."
  [running?]
  #js {:entries #js [] :docs #js {"pi.live" (if running? #js {:run #js {:taskId "t"}} #js {})}})

(deftest affected-test
  (let [a (view-value false)
        b (view-value false)
        old {:tabs {"t1" "1" "t2" "7"}
             :bots {"1" {:id "1" :value a :definition {:name "A"} :artifacts/list [] :worker {:w 1} :reload {:text "x"}}
                    "7" {:id "7" :value b :definition {:name "B"} :artifacts/list []}}}
        w (fn [tab url-id] {:tabid tab :ring/route-params {:* url-id}})
        shows-1 (w "t1" "1")
        shows-7 (w "t2" "7")]
    (testing "a value of one bot goes to the tabs showing it"
      (let [pred (page/affected old (assoc-in old [:bots "1" :artifacts/list] [{:path "x.html"}]))]
        (is (fn? pred))
        (is (true? (pred shows-1)))
        (is (false? (pred shows-7)))
        (is (true? (pred (w "t9" "1"))) "a tab without a pick shows the bot of its URL")))
    (testing "a new view value with the run as it was: the same, to its tabs"
      (let [pred (page/affected old (assoc-in old [:bots "7" :value] (view-value false)))]
        (is (false? (pred shows-1)))
        (is (true? (pred shows-7)))))
    (testing "a fold opened or closed goes to its tab alone"
      (let [pred (page/affected old (assoc-in old [:expanded "t1"] #{"c1"}))]
        (is (fn? pred))
        (is (true? (pred shows-1)))
        (is (false? (pred shows-7))))
      (let [opened (assoc-in old [:expanded "t1"] #{"c1"})
            pred (page/affected opened (assoc-in opened [:expanded "t1"] #{}))]
        (is (true? (pred shows-1)))
        (is (false? (pred shows-7))))
      (is (nil? (page/affected (assoc-in old [:expanded "t1"] #{"c1"}) (assoc-in old [:expanded "t1"] #{"c1"})))))
    (testing "a handle changes nothing"
      (is (nil? (page/affected old (assoc-in old [:bots "1" :worker] {:w 2}))))
      (is (nil? (page/affected old (assoc-in old [:bots "1" :reload :text] "y"))))
      (is (nil? (page/affected old old))))
    (testing "what the sidebar shows goes to every tab: a name, a run starting, a flag, a bot that comes or goes, and the page's own keys"
      (is (= :all (page/affected old (assoc-in old [:bots "1" :definition :name] "Anna"))))
      (is (= :all (page/affected old (assoc-in old [:bots "1" :value] (view-value true)))))
      (is (= :all (page/affected old (assoc-in old [:bots "7" :deleting?] true))))
      (is (= :all (page/affected old (update old :bots dissoc "7"))))
      (is (= :all (page/affected old (assoc-in old [:bots "9"] {:id "9"}))))
      (is (= :all (page/affected old (assoc-in old [:tabs "t1"] "7"))))
      (is (= :all (page/affected old (assoc old :update {:current "1"})))))))

(deftest stop-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))
                 home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "eval" {:code "(def slow (call \"echo\" \"/slow\" {:query {:delay 3000}})) (:status slow)"} "e1")
                                      (tools-test/answer "Slow done.")
                                      (tools-test/tool-call "eval" {:code "(loop [] (recur))"} "e2")
                                      (tools-test/tool-call "eval" {:code "(def z 1)"} "e3")
                                      (tools-test/answer "Fresh.")])
             (let [{:keys [id base stop!]} (await (start! home models {:gateway url}))
                   bot-url (str base "/bot/" id)
                   controller (js/AbortController.)
                   cookie (await (login base "s3cret"))
                   {:keys [reader decoder]} (await (stream bot-url cookie controller "t2"))
                   {buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))
                   {:keys [frame!]} (frames! reader decoder buffer)
                   agent (machine/agent)]
               (try
                 ;; a fold sends its body only to a tab that opened it
                 ;; (`:chat/fold`): this tab opens the programs' rows first
                 (doseq [call ["e1" "e2" "e3"]]
                   (is (= {:success? true} (await (command! base cookie :chat/fold {:tab "t2" :id call :fold :open})))))
                 (testing "the main thread is free while a program blocks in a call: the page answers meanwhile"
                   (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Call slowly."}))))
                   (let [running (await (frame! #(mentions? % "Evaluated")))
                         at (js/Date.now)]
                     (is (mentions? running "Stop") "the run goes")
                     (is (mentions? running "(:status slow)") "the program is on the page")
                     (is (= 200 (.-status (await (within 1000 (js/fetch bot-url #js {:headers #js {:cookie cookie}}))))) "the shim answers")
                     (let [answer (await (frame! #(mentions? % "Slow done.")))]
                       (is (<= 2000 (- (js/Date.now) at)) "the call was still blocked when the shim answered")
                       (is (mentions? answer "=> 200") "the call's answer, from the worker"))))
                 (testing "Stop ends a program that never would, within a second; the model reads that the call was aborted"
                   (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Loop."}))))
                   (await (frame! #(mentions? % "(loop [] (recur))")))
                   (let [at (js/Date.now)]
                     (is (= {:success? true} (await (command! base cookie :chat/stop {:bot id}))))
                     (let [idle (await (frame! #(nil? (:ui/stop (second (find-tag % :ui/composer))))))]
                       (is (< (- (js/Date.now) at) 1500) "stopped within a second")
                       (is (mentions? idle "did not work") "the call is marked as failed"))
                     (is (str/includes? (tools-test/result-text (get (await (tools-test/tool-results agent)) "e2")) "Tool eval was aborted"))))
                 (testing "the next program starts over, in a fresh context, and says so"
                   (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Again."}))))
                   (let [answer (await (frame! #(mentions? % "Fresh.")))]
                     (is (mentions? answer "The REPL started over since the last program was stopped: what earlier programs defined is gone."))
                     (is (mentions? answer "=> #'user/z"))))
                 (finally
                   (.abort controller)
                   (await (stop!))
                   (test-home/remove! home)
                   (stop)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest editor-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [models]} (tools-test/faux-models)]
             (let [{:keys [id base stop!]} (await (start! home models))
                   cookie (await (login base "s3cret"))
                   dir (path/join (home/bot-dir home id) "work" "artifacts")
                   bot-path (db/bot-path id)]
               (try
                 (fs/mkdirSync (path/join dir "notes") #js {:recursive true})
                 (fs/writeFileSync (path/join dir "notes" "todo.txt") "- milk\n")
                 (fs/writeFileSync (path/join dir "README.md") "# Hello\n")
                 (fs/writeFileSync (path/join dir "card.html") "<h1>card</h1>")
                 (artifacts/refresh! db/db bot-path dir)
                 (testing "the text of the editor is written over the file; the list has the new time at once, so that the frame with the text follows"
                   (let [before (:mtime (some #(when (= "notes/todo.txt" (:path %)) %) (get-in @db/db (conj bot-path :artifacts/list))))]
                     (await (js/Promise. (fn [resolve] (js/setTimeout resolve 5))))
                     (is (= {:success? true} (await (command! base cookie :artifacts/save {:bot id :path "notes/todo.txt" :text "- milk\n- call Anna\n"}))))
                     (is (= "- milk\n- call Anna\n" (fs/readFileSync (path/join dir "notes" "todo.txt") "utf8")))
                     (let [{:keys [mtime size]} (some #(when (= "notes/todo.txt" (:path %)) %) (get-in @db/db (conj bot-path :artifacts/list)))]
                       (is (< before mtime))
                       (is (= 19 size)))

                     (is (= {:success? true} (await (command! base cookie :artifacts/save {:bot id :path "README.md" :text "# Hello\n\nWorld.\n"}))) "a markdown file is a text too")
                     (is (= "# Hello\n\nWorld.\n" (fs/readFileSync (path/join dir "README.md") "utf8")))))
                 (testing "nothing but a text file on the list, and only a text"
                   (is (= {:success? false :result {:error :no-such-artifact}} (await (command! base cookie :artifacts/save {:bot id :path "notes/new.txt" :text "x"}))))
                   (is (not (fs/existsSync (path/join dir "notes" "new.txt"))))
                   (is (= {:success? false :result {:error :no-such-artifact}} (await (command! base cookie :artifacts/save {:bot "99" :path "notes/todo.txt" :text "x"}))))
                   (is (= {:success? false :result {:error :not-text}} (await (command! base cookie :artifacts/save {:bot id :path "card.html" :text "<h1>x</h1>"}))) "a page is played, not edited")
                   (is (= "<h1>card</h1>" (fs/readFileSync (path/join dir "card.html") "utf8")))
                   (is (= {:success? false :result {:error :no-text}} (await (command! base cookie :artifacts/save {:bot id :path "notes/todo.txt" :text 7}))))
                   (is (= "- milk\n- call Anna\n" (fs/readFileSync (path/join dir "notes" "todo.txt") "utf8"))))
                 (testing "Edit puts a markdown file's text in the editor, Preview takes it back"
                   (is (= {:success? true} (await (command! base cookie :artifacts/edit {:bot id :path "README.md" :on? true}))))
                   (is (= "README.md" (get-in @db/db (conj bot-path :artifacts/editing))))
                   (is (= {:success? true} (await (command! base cookie :artifacts/edit {:bot id :path "README.md" :on? false}))))
                   (is (nil? (get-in @db/db (conj bot-path :artifacts/editing))))
                   (is (= {:success? false :result {:error :no-such-artifact}} (await (command! base cookie :artifacts/edit {:bot id :path "gone.md" :on? true}))))
                   (is (= {:success? false :result {:error :no-such-bot}} (await (command! base cookie :artifacts/edit {:bot "99" :path "README.md" :on? true})))))
                 (finally
                   (await (stop!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(defn- ^:async eventually
  "Polls `f` every 20 ms until it answers truthy, at most `ms`."
  [ms f]
  (let [until (+ (js/Date.now) ms)]
    (loop []
      (if-let [v (f)]
        v
        (if (< until (js/Date.now))
          nil
          (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur)))))))

(deftest mcp-settings-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Thanks, testing it now.")])
             (fs/writeFileSync (mcp/file home)
                               (js/JSON.stringify (clj->js {:mcpServers {:shop {:url "http://127.0.0.1:1/mcp"
                                                                                :headers {:Authorization "Bearer ${SHOP_TOKEN}"}}}})))
             (let [{:keys [id base stop!]} (await (start! home models))
                   controller (js/AbortController.)
                   file (secrets/file home)]
               (try
                 (page/follow-mcp!)
                 (let [cookie (await (login base "s3cret"))
                       {:keys [reader decoder]} (await (stream (str base "/bot/" id) cookie controller "t1"))
                       {:keys [frame!]} (frames! reader decoder "")]
                   (testing "the MCP section of the settings: the server waits for its secret, a field takes it"
                     (is (= [{:name "shop" :address "http://127.0.0.1:1/mcp" :tools 0 :needs ["SHOP_TOKEN"]
                              :error "needs the secret SHOP_TOKEN, not saved yet (a field in the chat or the MCP settings takes it)"}]
                            (:mcp @db/db)))
                     (is (= {:success? true} (await (command! base cookie :settings/open {:tab "t1"}))))
                     (is (= {:success? false :result {:error :no-such-section}} (await (command! base cookie :settings/section {:tab "t1" :section :nope}))))
                     (is (= {:success? true} (await (command! base cookie :settings/section {:tab "t1" :section :mcp}))))
                     (let [frame (await (frame! #(mentions? % "Waiting for SHOP_TOKEN")))]
                       (is (= "SHOP_TOKEN" (:ui/name (second (find-tag frame :ui/secret-field)))))))
                   (testing "a secret saved from a bot's field: in the file, the bot told its name, never its value; the server tried"
                     (is (= {:success? false :result {:error :bad-secret-name}} (await (command! base cookie :secret/save {:name "a b" :value "x"}))))
                     (is (= {:success? false :result {:error :empty-secret}} (await (command! base cookie :secret/save {:name "SHOP_TOKEN" :value " "}))))
                     (is (= {:success? true} (await (command! base cookie :secret/save {:bot id :name "SHOP_TOKEN" :value "shop-s3cret-1"}))))
                     (is (= {"SHOP_TOKEN" "shop-s3cret-1"} (secrets/read-all file)))
                     (is (= ["SHOP_TOKEN"] (:secret-names @db/db)))
                     (let [said (await (eventually 5000 #(some (fn [entry] (when (= "pi.user" (:kind entry)) (js/JSON.stringify (clj->js (:model entry)))))
                                                                 (:entries (page/conversation (:value (machine/bot id)))))))]
                       (is (str/includes? (str said) "I saved the secret SHOP_TOKEN"))
                       (is (not (str/includes? (str said) "shop-s3cret-1"))))
                     (is (await (eventually 5000 #(let [[shop] (:mcp @db/db)] (and (empty? (:needs shop)) (:error shop) (not (:connecting shop))))))
                         (str "no longer waiting: tried, and refused at port 1 " (pr-str (:mcp @db/db)))))
                   (testing "Test connection: the row says it runs, then what it found and how long it took"
                     (is (= {:success? true} (await (command! base cookie :mcp/test {:name "shop"}))))
                     (let [result (await (eventually 5000 #(let [t (get-in @db/db [:mcp-tests "shop"])] (when (contains? t :ok?) t))))]
                       (is (false? (:ok? result)) "port 1 refuses")
                       (is (string? (:error result)))
                       (is (number? (:ms result)))))
                   (testing "remove asks first in the row, then takes the server out of the file"
                     (is (= {:success? true} (await (command! base cookie :mcp/remove {:name "shop"}))))
                     (is (= "shop" (:mcp-removing @db/db)))
                     (is (= {:success? true} (await (command! base cookie :mcp/keep {}))))
                     (is (nil? (:mcp-removing @db/db)))
                     (await (command! base cookie :mcp/remove {:name "shop"}))
                     (is (= {:success? true} (await (command! base cookie :mcp/remove {:name "shop"}))))
                     (is (= {} (mcp/read-servers (mcp/file home))))
                     (is (= [] (:mcp @db/db))))
                   (testing "a saved secret removed from the settings"
                     (is (= {:success? true} (await (command! base cookie :secret/remove {:name "SHOP_TOKEN"}))))
                     (is (= {} (secrets/read-all file)))
                     (is (= [] (:secret-names @db/db))))
                   (testing "Connect a service: a bot of its own for it, shown in the tab"
                     (is (= {:success? true} (await (command! base cookie :mcp/setup {:tab "t1"}))))
                     (let [id2 (get-in @db/db [:tabs "t1"])
                           definition (:definition (machine/bot id2))]
                       (is (not= id id2))
                       (is (= "MCP setup" (:name definition)))
                       (is (str/includes? (:instructions definition) "(mcp/add! "))
                       (is (str/starts-with? (get-in definition [:metadata "ask"]) "Which service"))
                       (is (not (contains? (:settings-open @db/db) "t1")) "the settings closed in that tab"))))
                 (finally
                   (.abort controller)
                   (swap! db/db dissoc :settings-section :mcp :mcp-removing :mcp-tests :secret-names)
                   (await (stop!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest oauth-page-test
  (async done
    (-> ((^:async fn []
           (let [service (await (fake-oauth/start!))
                 home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/answer "Connected, good.")])
             (fs/writeFileSync (mcp/file home) (js/JSON.stringify (clj->js {:mcpServers {:linear {:url (:url service) :oauth true}}})))
             (let [{:keys [id base stop!]} (await (start! home models))
                   cookie (await (login base "s3cret"))
                   get! (fn [path] (js/fetch (str base path) #js {:headers #js {:cookie cookie} :redirect "manual"}))]
               (try
                 (testing "behind the token like the rest of the page"
                   (is (= 403 (.-status (await (js/fetch (str base "/mcp/oauth/start/linear") #js {:redirect "manual"})))))
                   (is (= 403 (.-status (await (js/fetch (str base "/mcp/oauth/callback?code=x&state=y") #js {:redirect "manual"}))))))
                 (testing "start: the browser sent to the service, which sends it back to the page's callback"
                   (let [start (await (get! (str "/mcp/oauth/start/linear?bot=" id)))
                         location (.get (.-headers start) "location")
                         _ (is (= 302 (.-status start)))
                         _ (is (str/starts-with? location (str (:base service) "/authorize")))
                         back (js/URL. (.get (.-headers (await (js/fetch location #js {:redirect "manual"}))) "location"))]
                     (is (= (str base "/mcp/oauth/callback") (str (.-origin back) (.-pathname back))))
                     (testing "the callback: signed in, connected, the bot told"
                       (let [page (await (get! (str (.-pathname back) (.-search back))))
                             text (await (.text page))]
                         (is (= 200 (.-status page)))
                         (is (str/includes? text "Signed in to linear"))
                         (is (str/includes? text "with 1 tools"))
                         (is (mcp-oauth/signed-in? (mcp-oauth/file (machine/home)) "linear"))
                         (let [said (await (eventually 5000 #(some (fn [entry] (when (= "pi.user" (:kind entry)) (js/JSON.stringify (clj->js (:model entry)))))
                                                                     (:entries (page/conversation (:value (machine/bot id)))))))]
                           (is (str/includes? (str said) "I signed in to linear"))
                           (is (not (re-find #"at\d|rt\d" (str said))) "no token in the message"))))))
                 (testing "a used or forged answer and the service's refusal say so on a page of their own"
                   (let [page (await (get! "/mcp/oauth/callback?code=code-1&state=forged"))]
                     (is (= 400 (.-status page)))
                     (is (str/includes? (await (.text page)) "No sign-in waits for this answer")))
                   (let [page (await (get! "/mcp/oauth/callback?error=access_denied&error_description=No%20thanks"))]
                     (is (= 400 (.-status page)))
                     (is (str/includes? (await (.text page)) "No thanks"))))
                 (testing "a server that does not exist"
                   (is (= 502 (.-status (await (get! "/mcp/oauth/start/ghost"))))))
                 (testing "sign out from the settings"
                   (is (= {:success? true} (await (command! base cookie :mcp/sign-out {:name "linear"}))))
                   (is (not (mcp-oauth/signed-in? (mcp-oauth/file (machine/home)) "linear"))))
                 (finally
                   (await (stop!))
                   (await ((:stop! service)))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest runs-on-the-page-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "hook" {:action "create" :name "reviews" :purpose "A new review"} "h1")
                                      (tools-test/answer "The hook is made.")
                                      (tools-test/answer "Posted the story.")
                                      (tools-test/answer "Because the cover photo is the apartment's.")
                                      (tools-test/answer "Hello back.")])
             (let [{:keys [id base stop!]} (await (start! home models))
                   controller (js/AbortController.)
                   other (js/AbortController.)
                   bot-url (str base "/bot/" id)]
               (try
                 (let [cookie (await (login base "s3cret"))
                       {:keys [reader decoder]} (await (stream bot-url cookie controller "t1"))
                       {first-frame :view buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))
                       {:keys [frame!]} (frames! reader decoder buffer)]
                   (is (nil? (find-tag first-frame :nav.panes)) "no switch of the pane's lists without runs")
                   (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Make a hook."}))))
                   (await (frame! #(mentions? % "The hook is made.")))
                   (let [doc (await (.snapshot (:harness (machine/agent)) hooks/Hooks (js/Number id) harness/context))
                         secret (.. doc -reviews -secret)
                         posted (await (js/fetch (str base "/hook/reviews/" secret) #js {:method "POST" :body "{\"stars\":5}"}))
                         {:strs [run]} (js->clj (js/JSON.parse (await (.text posted))))]
                     (is (= 202 (.-status posted)))
                     (is (number? run))
                     (testing "the run is in the activity of the pane, with its line once it answered; nothing of it in the chat"
                       (let [frame (await (frame! #(find-tag % :nav.panes)))]
                         (is (nil? (find-tag frame :ul.runs)) "the pane keeps showing the artifacts until the switch"))
                       (is (= {:success? true} (await (command! base cookie :pane/show {:tab "t1" :pane :activity}))))
                       (let [frame (await (frame! #(and (find-tag % :ul.runs) (mentions? % "Posted the story."))))]
                         (is (= (str "/bot/" id) (url-of frame)) "still the bot's page")
                         (is (not (mentions? frame "Started by")))))
                     (testing "the run's page: its transcript, which the person asks"
                       (is (= {:success? true} (await (command! base cookie :run/show {:tab "t1" :bot id :run run}))))
                       (let [frame (await (frame! #(= (str "/bot/" id "/run/" run) (url-of %))))]
                         (is (mentions? frame "Started by A new review") "the hook's purpose")
                         (is (mentions? frame "Posted the story.") "the run's answer in the feed")
                         (is (some? (find-tag frame :div.banner.run)))
                         (is (= [:draft (str id "/run/" run)] (:ui/store-key (second (find-tag frame :ui/composer)))) "the draft is the run's")
                         (testing "the definition the run works from, folded; the bot's unchanged so far"
                           (is (mentions? frame "The definition this run works from"))
                           (is (mentions? frame "You watch a page."))
                           (is (not (mentions? frame "has changed since")))))
                       (testing "the bot's definition changed since the run: the page says so"
                         (fs/writeFileSync (path/join (home/bot-dir home (js/Number id)) "AGENT.md") (str/replace test-home/definition-text "You watch a page." "You watch two pages."))
                         (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :run run :text "Why that photo?"}))))
                         (let [frame (await (frame! #(mentions? % "Because the cover photo")))]
                           (is (mentions? frame "Why that photo?"))
                           ;; the bot's file is read again at its next submission, not the run's:
                           ;; the word comes once the bot ran
                           (is (not (mentions? frame "has changed since"))))))
                     (testing "back to the bot's chat; its file read again at its next message, the run's page then says the definition changed"
                       (is (= {:success? true} (await (command! base cookie :bot/show {:tab "t1" :id id}))))
                       (let [frame (await (frame! #(= (str "/bot/" id) (url-of %))))]
                         (is (mentions? frame "The hook is made."))
                         (is (not (mentions? frame "Because the cover photo"))))
                       (is (= {:success? true} (await (command! base cookie :chat/send {:bot id :text "Hello again."}))))
                       (await (frame! #(mentions? % "Hello back.")))
                       (is (= {:success? true} (await (command! base cookie :run/show {:tab "t1" :bot id :run run}))))
                       (let [frame (await (frame! #(and (= (str "/bot/" id "/run/" run) (url-of %)) (mentions? % "has changed since"))))]
                         (is (mentions? frame "You watch a page.") "the run's own text, not the new one")))
                     (testing "the run's page by its URL, in another tab"
                       (is (= 200 (.-status (await (js/fetch (str bot-url "/run/" run) #js {:headers #js {:cookie cookie}})))))
                       (let [{:keys [reader decoder]} (await (stream (str bot-url "/run/" run) cookie other "t2"))
                             {frame :view} (await (within 15000 (next-frame reader decoder "" (constantly true))))]
                         (is (= (str "/bot/" id "/run/" run) (url-of frame)))
                         (is (mentions? frame "Posted the story."))))
                     (testing "a run the bot does not have"
                       (is (= {:success? false :result {:error :no-such-run}} (await (command! base cookie :run/show {:tab "t1" :bot id :run 999999})))))))
                 (finally
                   (.abort controller)
                   (.abort other)
                   (await (stop!))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
