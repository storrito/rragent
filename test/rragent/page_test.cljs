(ns rragent.page-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.client.frames :as frames]
            [rragent.harness :as harness]
            [rragent.page :as page]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]
            [rragent.db :as db]
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
                 (finally
                   (.abort controller)
                   (stop-following)
                   (.closeAllConnections server)
                   (await (js/Promise. (fn [resolve] (.close server resolve))))
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
