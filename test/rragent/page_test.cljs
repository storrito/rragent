(ns rragent.page-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.client.frames :as frames]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.harness :as harness]
            [rragent.page :as page]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]
            [rragent.db :as db]
            [rragent.sync-fetch :as sync-fetch]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]
            [rragent.uploads-test :as uploads-test]))

(defn- mentions?
  "Whether a string of the hiccup contains `s`."
  [hiccup s]
  (some #(and (string? %) (str/includes? % s)) (tree-seq coll? seq hiccup)))

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

(deftest page-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)]
             (.setResponses faux #js [(tools-test/tool-call "remember" {:event "asked" :data {:seen 1}} "c1")
                                      (tools-test/answer "**Paris.**\n\n- the Seine\n- the Louvre\n\n```\n(+ 1 2)\n```")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                   stop-following (page/start! agent)
                   server (await (http/listen (page/handler "s3cret") 0))
                   base (str "http://127.0.0.1:" (.-port (.address server)))
                   controller (js/AbortController.)]
               (try
                 (testing "the page is behind the token"
                   (is (= 403 (.-status (await (js/fetch base)))))
                   (is (= 403 (.-status (await (js/fetch (str base "/hook/x/y") #js {:method "GET"})))) "only a POST reaches a hook, a GET is behind the token"))
                 (let [login (await (js/fetch (str base "/?token=s3cret") #js {:redirect "manual"}))
                       cookie (first (str/split (str (.get (.-headers login) "set-cookie")) #";"))]
                   (is (= 302 (.-status login)))
                   (is (= "/" (.get (.-headers login) "location")))
                   (is (str/starts-with? cookie "rragent="))
                   (testing "the shim with the cookie"
                     (let [shim (await (js/fetch base #js {:headers #js {:cookie cookie}}))]
                       (is (= 200 (.-status shim)))
                       (is (str/includes? (await (.text shim)) "data-rframes-view"))))
                   (testing "the stream: the empty page first, the question and the answer as frames"
                     (let [stream (await (js/fetch base #js {:method "POST"
                                                             :headers #js {:cookie cookie
                                                                           :accept "text/event-stream"
                                                                           :accept-encoding "identity"}
                                                             :body (transit/write-str {:tabid "t1"})
                                                             :signal (.-signal controller)}))
                           reader (.getReader (.-body stream))
                           decoder (js/TextDecoder.)
                           {first-frame :view buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))]
                       (is (= 200 (.-status stream)))
                       (is (= "text/event-stream" (.get (.-headers stream) "content-type")))
                       (is (mentions? first-frame "Nothing here yet") "the empty feed, in English")
                       (is (mentions? first-frame "What should the agent do") "the composer")
                       (let [sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                                              :headers #js {:cookie cookie
                                                                                            :content-type "application/transit+json"}
                                                                              :body (transit/write-str {:command/kind :chat/send
                                                                                                        :command/data {:text "Capital of France?"}})}))]
                         (is (= {:success? true} (transit/read-str (await (.text sent))))))
                       (let [{answer :view buffer :buffer} (await (within 15000 (next-frame reader decoder buffer #(mentions? % "Paris."))))]
                         (is (some? answer))
                         (is (mentions? answer "Capital of France?") "the question is on the page")
                         (is (mentions? answer "Noted a fact") "the tool call is on the page")
                         (is (mentions? answer "Noted: asked") "and the fact itself, as a note")
                         (is (not (mentions? answer "Stop")) "the run is over")
                         (testing "the answer's markdown is rendered"
                           (let [tags (set (map first (filter vector? (tree-seq coll? seq answer))))]
                             (is (contains? tags :strong))
                             (is (contains? tags :li))
                             (is (contains? tags :pre))
                             (is (contains? tags :code))
                             (is (not (mentions? answer "**")) "no raw markdown on the page")))
                         (testing "a picture attached: uploaded, named in the message, seen by the model, a thumbnail on the page"
                           (.setResponses faux #js [(tools-test/answer "A dot.")])
                           (let [uploaded (await (js/fetch (str base "/upload") #js {:method "POST"
                                                                                      :headers #js {:cookie cookie
                                                                                                    :content-type "image/png"}
                                                                                      :body uploads-test/png}))
                                 {:keys [name url]} (transit/read-str (await (.text uploaded)))
                                 sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                                                   :headers #js {:cookie cookie
                                                                                                 :content-type "application/transit+json"}
                                                                                   :body (transit/write-str {:command/kind :chat/send
                                                                                                             :command/data {:text "What is this?"
                                                                                                                            :attachments [{:name name :url url :kind :image}]}})}))]
                             (is (= 200 (.-status uploaded)))
                             (is (= {:success? true} (transit/read-str (await (.text sent)))))
                             (let [{answer :view} (await (within 15000 (next-frame reader decoder buffer #(mentions? % "A dot."))))
                                   img (some #(when (and (vector? %) (= :img (first %))) %) (tree-seq coll? seq answer))
                                   message (->> (:entries (page/conversation (:value @db/db)))
                                                (filter #(= "pi.user" (:kind %)))
                                                (last)
                                                (:model)
                                                (first)
                                                (:content))]
                               (is (= (str "/uploads/" name) (:src (second img))) "the thumbnail")
                               (is (not (mentions? answer "image: uploads/")) "the line is not shown as text")
                               (is (= (str "What is this?\nimage: uploads/" name) (:text (first message))) "the model reads the line")
                               (is (= "image" (:type (second message))) "and sees the picture")
                               (is (= "image/png" (:mimeType (second message))))
                               (is (= 200 (.-status (await (js/fetch (str base url) #js {:headers #js {:cookie cookie}})))) "the file is served behind the token")
                               (is (= 403 (.-status (await (js/fetch (str base url)))))))))))))
                 (testing "a hook with a wrong secret is refused without the cookie"
                   (is (= 403 (.-status (await (js/fetch (str base "/hook/deploy/wrong") #js {:method "POST" :body "x"}))))))
                 (testing "the Update button's command runs the apply of the updater, the version's click the lookup"
                   (let [applied (atom [])
                         login (await (js/fetch (str base "/?token=s3cret") #js {:redirect "manual"}))
                         cookie (first (str/split (str (.get (.-headers login) "set-cookie")) #";"))
                         post! (^:async fn [kind]
                                 (let [sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                                                        :headers #js {:cookie cookie
                                                                                                      :content-type "application/transit+json"}
                                                                                        :body (transit/write-str {:command/kind kind})}))]
                                   (transit/read-str (await (.text sent)))))]
                     (is (= {:success? false :result {:error :no-updater}} (await (post! :update/apply))) "no updater, no apply")
                     (reset! page/updater {:db db/db
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
                         (reset! page/updater nil)
                         (swap! db/db dissoc :update)))))
                 (finally
                   (.abort controller)
                   (stop-following)
                   (.closeAllConnections server)
                   (await (js/Promise. (fn [resolve] (.close server resolve))))
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

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
  "The page's frame stream opened: `{:reader :decoder}`."
  [base cookie controller]
  (let [response (await (js/fetch base #js {:method "POST"
                                             :headers #js {:cookie cookie
                                                           :accept "text/event-stream"
                                                           :accept-encoding "identity"}
                                             :body (transit/write-str {:tabid "t2"})
                                             :signal (.-signal controller)}))]
    {:reader (.getReader (.-body response))
     :decoder (js/TextDecoder.)}))

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
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux" :gateway url}))
                   stop-following (page/start! agent)
                   server (await (http/listen (page/handler "s3cret") 0))
                   base (str "http://127.0.0.1:" (.-port (.address server)))
                   controller (js/AbortController.)
                   cookie (await (login base "s3cret"))
                   {:keys [reader decoder]} (await (stream base cookie controller))
                   {buffer :buffer} (await (within 15000 (next-frame reader decoder "" (constantly true))))]
               (try
                 (let [buffer (atom buffer)
                       frame! (^:async fn [pred]
                                (let [found (await (within 15000 (next-frame reader decoder @buffer pred)))]
                                  (reset! buffer (:buffer found))
                                  (:view found)))]
                   (testing "the main thread is free while a program blocks in a call: the page answers meanwhile"
                     (is (= {:success? true} (await (command! base cookie :chat/send {:text "Call slowly."}))))
                     (let [running (await (frame! #(mentions? % "Evaluated")))
                           at (js/Date.now)]
                       (is (mentions? running "Stop") "the run goes")
                       (is (mentions? running "(:status slow)") "the program is on the page")
                       (is (= 200 (.-status (await (within 1000 (js/fetch base #js {:headers #js {:cookie cookie}}))))) "the shim answers")
                       (let [answer (await (frame! #(mentions? % "Slow done.")))]
                         (is (<= 2000 (- (js/Date.now) at)) "the call was still blocked when the shim answered")
                         (is (mentions? answer "=> 200") "the call's answer, from the worker"))))
                   (testing "Stop ends a program that never would, within a second; the model reads that the call was aborted"
                     (is (= {:success? true} (await (command! base cookie :chat/send {:text "Loop."}))))
                     (await (frame! #(mentions? % "(loop [] (recur))")))
                     (let [at (js/Date.now)]
                       (is (= {:success? true} (await (command! base cookie :chat/stop {}))))
                       (let [idle (await (frame! #(not (mentions? % "Stop"))))]
                         (is (< (- (js/Date.now) at) 1500) "stopped within a second")
                         (is (mentions? idle "did not work") "the call is marked as failed"))
                       (is (str/includes? (tools-test/result-text (get (await (tools-test/tool-results agent)) "e2")) "Tool eval was aborted"))))
                   (testing "the next program starts over, in a fresh context, and says so"
                     (is (= {:success? true} (await (command! base cookie :chat/send {:text "Again."}))))
                     (let [answer (await (frame! #(mentions? % "Fresh.")))]
                       (is (mentions? answer "The REPL started over since the last program was stopped: what earlier programs defined is gone."))
                       (is (mentions? answer "=> #'user/z")))))
                 (finally
                   (.abort controller)
                   (stop-following)
                   (.closeAllConnections server)
                   (await (js/Promise. (fn [resolve] (.close server resolve))))
                   (await (harness/close agent))
                   (test-home/remove! home)
                   (stop)
                   (sync-fetch/stop!)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
