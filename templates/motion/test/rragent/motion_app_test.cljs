(ns rragent.motion-app-test
  "The process of a motion project: served in this process from the
   template, its frame over the stream, a control's command, an eval
   into the design; and the same started by rragent.projects through
   the template's `serve`, the way the page does it."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.client.frames :as frames]
            [rragent.motion.app :as app]
            ;; the entry of the template's program, so that the test build
            ;; has a main.js for the child process of `as-project-test`
            [rragent.motion.main]
            [rragent.projects :as projects]
            [rragent.rframes.transit :as transit]
            [rragent.templates :as templates]))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- mentions?
  [hiccup s]
  (some #(and (string? %) (str/includes? % s)) (tree-seq coll? seq hiccup)))

(defn- ^:async first-frame
  "The first frame of the stream at `url`."
  [url]
  (let [controller (js/AbortController.)
        stream (await (js/fetch url #js {:method "POST"
                                         :headers #js {:accept "text/event-stream" :accept-encoding "identity"}
                                         :body (transit/write-str {:tabid "t1"})
                                         :signal (.-signal controller)}))
        reader (.getReader (.-body stream))
        decoder (js/TextDecoder.)]
    (loop [buffer ""]
      (let [result (await (.read reader))]
        (when-not (.-done result)
          (let [{:keys [events buffer]} (frames/parse-chunk buffer (.decode decoder (.-value result) #js {:stream true}))
                frame (some #(when (= "frame" (:event %)) (transit/read-str (:data %))) events)]
            (if frame
              (do (.abort controller) frame)
              (recur buffer))))))))

(defn- project!
  "A project of the motion template in `dir`."
  [dir]
  (templates/copy! "motion" dir)
  dir)

(defn- ^:async eval!
  [base code]
  (let [response (await (js/fetch (str base "/eval") #js {:method "POST" :body code}))]
    [(.-status response) (await (.text response))]))

(deftest serve-test
  (async done
    (-> ((^:async fn []
           (let [dir (project! (path/join (fs/mkdtempSync (path/join (os/tmpdir) "rragent-motion-")) "card"))
                 {:keys [server state stop]} (await (app/serve! dir 0))
                 base (str "http://127.0.0.1:" (.-port (.address server)))]
             (try
               (testing "the shim with the player's script and the page's scripts"
                 (let [html (await (.text (await (js/fetch base))))]
                   (is (str/includes? html "data-rframes-view"))
                   (is (str/includes? html "--motion-scale"))))
               (testing "the frame: the design of design.edn at time 0, the controls"
                 (let [frame (await (first-frame base))]
                   (is (mentions? frame "Hello"))
                   (is (mentions? frame "What we will make together"))
                   (is (mentions? frame "Play"))
                   (is (not (mentions? frame "design.cljs:")) "no error")))
               (testing "a control's command writes the design, the file follows"
                 (let [sent (await (js/fetch (str base "/command") #js {:method "POST"
                                                                        :headers #js {:content-type "application/transit+json"}
                                                                        :body (transit/write-str {:command/kind :design/set
                                                                                                  :command/data {:path [:title] :value "Kuchen"}})}))]
                   (is (= {:success? true} (transit/read-str (await (.text sent)))))
                   (is (= "Kuchen" (get-in @(:db state) [:design :title])))
                   (is (str/includes? (fs/readFileSync (path/join dir "design.edn") "utf8") "Kuchen"))
                   (is (mentions? (await (first-frame base)) "Kuchen"))))
               (testing "a saved change of the design file is loaded, an error shown"
                 (fs/writeFileSync (path/join dir "design.cljs") "(ns design) (def size [2 2]) (def duration 1) (defn design-view [d t] [:h1 \"Rewritten\"])")
                 (let [later (+ 2 (/ (js/Date.now) 1000))]
                   (fs/utimesSync (path/join dir "design.cljs") later later))
                 (await (sleep (+ app/poll-ms 200)))
                 (is (mentions? (await (first-frame base)) "Rewritten"))
                 (fs/writeFileSync (path/join dir "design.cljs") "(ns design) (def size [2 2])")
                 (let [later (+ 4 (/ (js/Date.now) 1000))]
                   (fs/utimesSync (path/join dir "design.cljs") later later))
                 (await (sleep (+ app/poll-ms 200)))
                 (is (mentions? (await (first-frame base)) "design.cljs: design.cljs defines no design-view")))
               (testing "a media file of the folder, nothing else"
                 (fs/writeFileSync (path/join dir "pool.png") "png")
                 (is (= 200 (.-status (await (js/fetch (str base "/pool.png"))))))
                 (is (= 404 (.-status (await (js/fetch (str base "/design.cljs")))))))
               (testing "an eval into the design's namespace: the value, a redefinition on the page, an error"
                 (fs/writeFileSync (path/join dir "design.cljs") "(ns design) (def size [2 2]) (def duration 1) (defn design-view [d t] [:h1 \"Rewritten\"])")
                 (app/load! state)
                 (is (= [200 "3"] (await (eval! base "(+ 1 2)"))))
                 (is (= [200 "[2 2]"] (await (eval! base "size"))) "the namespace of the file")
                 (is (= [200 "#'design/design-view"] (await (eval! base "(defn design-view [d t] [:h1 \"Evaluated\"])"))))
                 (is (mentions? (await (first-frame base)) "Evaluated"))
                 (let [[status text] (await (eval! base "(undefined-fn)"))]
                   (is (= 400 status))
                   (is (str/includes? text "undefined-fn")))
                 (is (= [200 "#'design/size"] (await (eval! base "(def size nil)"))))
                 (is (mentions? (await (first-frame base)) "design.cljs defines no size") "a broken design shows its error")
                 (is (= [200 "#'design/size"] (await (eval! base "(def size [2 2])"))))
                 (is (mentions? (await (first-frame base)) "Evaluated") "and comes back"))
               (finally
                 (stop)
                 (fs/rmSync (path/dirname dir) #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest as-project-test
  (async done
    (-> ((^:async fn []
           (let [root (fs/mkdtempSync (path/join (os/tmpdir) "rragent-motion-project-"))
                 dir (project! (path/join root "card"))
                 db (atom {:artifacts/current "card"})
                 handle (projects/handle {:db db :dir root :procs (atom {})})
                 env (.-env js/process)
                 before (aget env "RRAGENT_MOTION_JS")]
             ;; the template's program, as the test build has it
             (aset env "RRAGENT_MOTION_JS" (path/resolve "target/test/rragent/motion/main.js"))
             (try
               (is (= [(path/resolve "templates/motion/serve") dir]
                      (let [{:keys [cmd args]} (projects/command dir)] (into [cmd] args))))
               (await (projects/sync! handle))
               (loop [n 0]
                 (when (and (< n 100) (nil? (get-in @db [:projects "card" :ready-at])))
                   (await (sleep 100))
                   (await (projects/sync! handle))
                   (recur (inc n))))
               (is (number? (get-in @db [:projects "card" :ready-at])) (str (fs/readFileSync (path/join dir ".log") "utf8")))
               (testing "its page through the proxy"
                 (let [w {:ring/route-params {:* "card/"} :ring/request {:request-method :get :uri "/artifacts/card/" :headers {}}}
                       {:keys [status body]} (:ring/response (await (projects/handler handle w)))]
                   (is (= 200 status))
                   (is (str/includes? (await (transit/read-body body)) "data-rframes-view"))))
               (testing "an eval through rragent.projects"
                 (is (= {:status 200 :text "6"} (await (projects/eval! handle "card" "duration")))))
               (finally
                 (projects/stop-all! handle)
                 (if before (aset env "RRAGENT_MOTION_JS" before) (js-delete env "RRAGENT_MOTION_JS"))
                 (fs/rmSync root #js {:recursive true :force true}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
