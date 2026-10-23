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
            [rragent.db :as db]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
            [rragent.home :as home]
            [rragent.images-test :as images-test]
            [rragent.machine :as machine]
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
              (swap! db/db dissoc :tabs :updater)
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
                         (is (mentions? answer "Noted a fact") "the tool call is on the page")
                         (is (mentions? answer "Noted: asked") "and the fact itself, as a note")
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
                             (is (= (str "/bot/" id "/artifacts/note.html")
                                    (:ui/src (second (find-tag (await (frame! #(find-tag % :ui/canvas))) :ui/canvas))))
                                 "the first bot's tab shows its new artifact")
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
                   (testing "the Update button's command runs the apply of the updater, the version's click the lookup"
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
                         (is (= :failed (get-in @db/db [:update :checked])) "the lookup at a port that refuses fails, said in the banner")
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
