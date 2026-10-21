(ns rragent.update-test
  "The update of rragent against a fake of GitHub's API and release
   files on a free port: the version comparison, the lookup into the
   db, the apply with its checksum, the swap of dist/ and the exit,
   the idle watch."
  (:require ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.http :as http]
            [rragent.update :as update]))

(deftest versions-test
  (is (= [0 1 3] (update/parse-version "v0.1.3")))
  (is (= [0 1 3] (update/parse-version "0.1.3")))
  (is (= [0 1 0] (update/parse-version "0.1.x")))
  (is (update/newer? "v0.1.3" "0.1.2") "the tag's v is no difference")
  (is (update/newer? "0.2.0" "0.1.10"))
  (is (update/newer? "1.0.0" "0.9.9"))
  (is (update/newer? "0.1.3" "0.0.0") "a checkout takes any release")
  (is (not (update/newer? "0.1.2" "0.1.2")) "the same is no update")
  (is (not (update/newer? "v0.1.2" "0.1.2")))
  (is (not (update/newer? "0.1.1" "0.1.2")) "an older one is no update")
  (is (not (update/newer? "0.1" "0.1.0"))))

(deftest release-of-test
  (let [json {:tag_name "v0.1.3"
              :body "## What's Changed\n* Subagents by @max in https://github.com/storrito/rragent/pull/34\n* Dictation\n"
              :assets [{:name "rragent.tar.gz" :browser_download_url "https://github.com/storrito/rragent/releases/download/v0.1.3/rragent.tar.gz"}
                       {:name "rragent.tar.gz.sha256" :browser_download_url "https://github.com/storrito/rragent/releases/download/v0.1.3/rragent.tar.gz.sha256"}]}]
    (is (= {:version "0.1.3"
            :tarball-url "https://github.com/storrito/rragent/releases/download/v0.1.3/rragent.tar.gz"
            :sha256-url "https://github.com/storrito/rragent/releases/download/v0.1.3/rragent.tar.gz.sha256"
            :notes "Subagents by @max in https://github.com/storrito/rragent/pull/34"}
           (update/release-of json)))
    (is (nil? (update/release-of (assoc json :assets []))) "a release without its tarball is none to take yet")
    (is (nil? (update/release-of {})))
    (is (nil? (:notes (update/release-of (assoc json :body "")))))
    (is (= "Dictation" (update/first-line "\n\n# Title\n- Dictation\n")))
    (is (str/ends-with? (update/first-line (apply str (repeat 200 "x"))) " …"))
    (is (= "abc" (update/expected-sha256 "abc  rragent.tar.gz\n")) "the hash as sha256sum writes it")))

(defn- ^:async fake-github
  "A GitHub for the tests on a free port: the API's latest release at
   the API's path (`:latest`, a function of the base answering the
   JSON as data, or a status to answer) and the release's files under
   `/download/` (`:files`, name → content). Answers `{:base :requests
   :stop}`, the requests as `[method uri authorization?]`."
  [{:keys [latest files]}]
  (let [requests (atom [])
        port (atom nil)
        server (await (http/listen (fn [{:keys [uri request-method headers]}]
                                     (swap! requests conj [request-method uri (contains? headers "authorization")])
                                     (let [file (when (str/starts-with? uri "/download/")
                                                  (get files (subs uri (count "/download/"))))]
                                       (cond
                                         (= "/repos/storrito/rragent/releases/latest" uri)
                                         (if (number? latest)
                                           {:status latest :headers {"Content-Type" "text/plain"} :body "no"}
                                           {:status 200
                                            :headers {"Content-Type" "application/json"}
                                            :body (js/JSON.stringify (clj->js (latest (str "http://127.0.0.1:" @port))))})

                                         file
                                         {:status 200 :headers {"Content-Type" "application/octet-stream"} :body file}

                                         :else
                                         {:status 404 :headers {"Content-Type" "text/plain"} :body "no"})))
                                   0 "127.0.0.1"))]
    (reset! port (.-port (.address server)))
    {:base (str "http://127.0.0.1:" @port)
     :requests requests
     :stop (fn []
             (.closeAllConnections server)
             (js/Promise. (fn [resolve] (.close server resolve))))}))

(defn- latest-json
  "GitHub's JSON of a release `version` whose files are at the fake."
  [version]
  (fn [base]
    {:tag_name (str "v" version)
     :name (str "rragent v" version)
     :body (str "## What's Changed\n* rragent " version " by @max\n")
     :assets [{:name "rragent.tar.gz" :browser_download_url (str base "/download/rragent.tar.gz")}
              {:name "rragent.tar.gz.sha256" :browser_download_url (str base "/download/rragent.tar.gz.sha256")}]}))

(defn- latest-path
  [base]
  (str base "/repos/storrito/rragent/releases/latest"))

(defn- ^:async with-stderr
  "`f` run while `console.error` records its lines instead of
   printing; answers the lines."
  [f]
  (let [lines (atom [])
        original (.-error js/console)]
    (set! (.-error js/console) (fn [& args] (swap! lines conj (str/join " " (map str args)))))
    (try
      (await (f))
      (finally
        (set! (.-error js/console) original)))
    @lines))

(defn- install!
  "A fresh install folder with a `dist/` that says `version`."
  [version]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-install-"))]
    (fs/mkdirSync (path/join dir "dist" "public") #js {:recursive true})
    (fs/writeFileSync (path/join dir "dist" "rragent.js") (str "// rragent " version "\n"))
    (fs/writeFileSync (path/join dir "dist" "public" "app.css") "old")
    dir))

(defn- bundle-of
  [install]
  (fs/readFileSync (path/join install "dist" "rragent.js") "utf8"))

(defn- release-files
  "`rragent.tar.gz` of a tiny dist/ that says `version`, packed as
   `npm run release` packs it (the content of dist/ at the root), and
   its `.sha256` as sha256sum writes it; a `:sha256` given stands in
   for the real one, for a checksum that does not match."
  ([version]
   (release-files version {}))
  ([version {:keys [sha256]}]
   (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-release-"))
         dist (path/join dir "dist")
         tarball (path/join dir "rragent.tar.gz")]
     (fs/mkdirSync (path/join dist "templates" "motion") #js {:recursive true})
     (fs/writeFileSync (path/join dist "rragent.js") (str "// rragent " version "\n"))
     (fs/writeFileSync (path/join dist "templates" "motion" "TEMPLATE.md") "new")
     (let [result (child/spawnSync "tar" #js ["-czf" tarball "-C" dist "."] #js {:encoding "utf8"})]
       (when-not (= 0 (.-status result))
         (throw (js/Error. (str "tar: " (.-stderr result))))))
     (let [bytes (fs/readFileSync tarball)
           hash (or sha256 (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex")))]
       (fs/rmSync dir #js {:recursive true :force true})
       {"rragent.tar.gz" bytes
        "rragent.tar.gz.sha256" (str hash "  rragent.tar.gz\n")}))))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- until
  "Resolves once `pred` answers true, polled every 10 ms; rejects
   after 5 s."
  [pred]
  (js/Promise. (fn [resolve reject]
                 (let [started (js/Date.now)]
                   ((fn poll []
                      (cond
                        (pred) (resolve true)
                        (< 5000 (- (js/Date.now) started)) (reject (js/Error. "nothing after 5 s"))
                        :else (js/setTimeout poll 10))))))))

(deftest check-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [base requests stop]} (await (fake-github {:latest (latest-json "0.1.3")}))]
             (try
               (testing "a newer release: the banner's data in the db"
                 (let [db (atom {})
                       handle (update/handle {:db db :current "0.1.2" :latest-url (latest-path base) :auto? false})]
                   (is (= {:current "0.1.2" :auto? false} (:update @db)) "the running version from the start")
                   (is (= :found (await (update/check! handle))))
                   (is (= {:current "0.1.2"
                           :auto? false
                           :version "0.1.3"
                           :notes "rragent 0.1.3 by @max"
                           :tarball-url (str base "/download/rragent.tar.gz")
                           :sha256-url (str base "/download/rragent.tar.gz.sha256")
                           :state :known}
                          (:update @db)))
                   (is (= [[:get "/repos/storrito/rragent/releases/latest" false]] @requests) "one request, without a token")))
               (testing "the same version: nothing"
                 (let [db (atom {})
                       handle (update/handle {:db db :current "0.1.3" :latest-url (latest-path base) :auto? true})]
                   (is (= :newest (await (update/check! handle))))
                   (is (= {:current "0.1.3" :auto? true} (:update @db)))))
               (testing "a newer running version than the release: nothing either"
                 (let [db (atom {})
                       handle (update/handle {:db db :current "0.2.0" :latest-url (latest-path base) :auto? false})]
                   (is (= :newest (await (update/check! handle))))
                   (is (= {:current "0.2.0" :auto? false} (:update @db)))))
               (testing "the person asks: the answer kept for the banner"
                 (let [db (atom {})
                       handle (update/handle {:db db :current "0.1.3" :latest-url (latest-path base) :auto? false})]
                   (is (= :newest (await (update/check-now! handle))))
                   (is (= :newest (get-in @db [:update :checked])))
                   (is (= :newest (await (update/check! handle))) "the next lookup on its own")
                   (is (nil? (get-in @db [:update :checked])) "clears the answer")))
               (testing "an API that is down: nothing in the db, one line"
                 (let [db (atom {})
                       handle (update/handle {:db db :current "0.1.2" :latest-url "http://127.0.0.1:1/repos/storrito/rragent/releases/latest" :auto? false})
                       lines (await (with-stderr (fn [] (update/check! handle))))]
                   (is (= {:current "0.1.2" :auto? false} (:update @db)))
                   (is (= 1 (count lines)) (pr-str lines))
                   (is (str/starts-with? (first lines) "[update] no lookup of the newest release:"))
                   (is (= 1 (count (await (with-stderr (fn [] (update/check-now! handle)))))) "asked for: one line again")
                   (is (= :failed (get-in @db [:update :checked])) "and the failure is said in the banner")))
               (finally
                 (await (stop)))))
           (testing "an API that answers an error: nothing, one line"
             (let [{:keys [base stop]} (await (fake-github {:latest 403}))
                   db (atom {})
                   handle (update/handle {:db db :current "0.1.2" :latest-url (latest-path base) :auto? false})
                   lines (await (with-stderr (fn [] (update/check! handle))))]
               (is (= {:current "0.1.2" :auto? false} (:update @db)))
               (is (= 1 (count lines)) (pr-str lines))
               (is (str/starts-with? (first lines) "[update] no lookup of the newest release: HTTP 403 from "))
               (await (stop))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest apply-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [base requests stop]} (await (fake-github {:latest (latest-json "0.1.3") :files (release-files "0.1.3")}))
                 install (install! "0.1.2")
                 exits (atom [])
                 db (atom {})
                 handle (update/handle {:db db
                                        :current "0.1.2"
                                        :install-dir install
                                        :latest-url (latest-path base)
                                        :auto? false
                                        :exit! (fn [code] (swap! exits conj code))})]
             (try
               (testing "nothing to take yet: nothing happens"
                 (is (nil? (await (update/apply! handle))))
                 (is (empty? @exits))
                 (is (empty? @requests)))
               (is (= :found (await (update/check! handle))))
               (testing "the click: downloaded, verified, swapped in, the process ended with 75"
                 (let [first-click (update/apply! handle)
                       second-click (update/apply! handle)]
                   (is (= :applying (get-in @db [:update :state])) "the db says so at once")
                   (is (nil? (await second-click)) "a second click while that runs does nothing")
                   (await (with-stderr (fn [] first-click))))
                 (is (= [75] @exits))
                 (is (= "// rragent 0.1.3\n" (bundle-of install)) "the new bundle is dist/")
                 (is (= "new" (fs/readFileSync (path/join install "dist" "templates" "motion" "TEMPLATE.md") "utf8")))
                 (is (not (fs/existsSync (path/join install "dist" "public" "app.css"))) "nothing of the old dist/ remains")
                 (is (= ["dist"] (vec (fs/readdirSync install))) "no dist.old, no dist.new, no temp folder")
                 (is (= :applying (get-in @db [:update :state])))
                 (is (= [[:get "/download/rragent.tar.gz" false] [:get "/download/rragent.tar.gz.sha256" false]]
                        (rest @requests))))
               (finally
                 (fs/rmSync install #js {:recursive true :force true})
                 (await (stop)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest bad-release-test
  (async done
    (-> ((^:async fn []
           (testing "a checksum that does not match: dist/ untouched, the error in the banner"
             (let [{:keys [base stop]} (await (fake-github {:latest (latest-json "0.1.3")
                                                            :files (release-files "0.1.3" {:sha256 (apply str (repeat 64 "0"))})}))
                   install (install! "0.1.2")
                   exits (atom [])
                   db (atom {})
                   handle (update/handle {:db db
                                          :current "0.1.2"
                                          :install-dir install
                                          :latest-url (latest-path base)
                                          :auto? false
                                          :exit! (fn [code] (swap! exits conj code))})]
               (try
                 (await (update/check! handle))
                 (let [lines (await (with-stderr (fn [] (update/apply! handle))))]
                   (is (empty? @exits))
                   (is (= :failed (get-in @db [:update :state])))
                   (is (str/includes? (str (get-in @db [:update :error])) "checksum of rragent.tar.gz does not match"))
                   (is (= "// rragent 0.1.2\n" (bundle-of install)) "dist/ as it was")
                   (is (= ["dist"] (vec (fs/readdirSync install))) "nothing left behind")
                   (is (= 1 (count lines)) (pr-str lines)))
                 (testing "the banner's Try again"
                   (await (with-stderr (fn [] (update/apply! handle))))
                   (is (= :failed (get-in @db [:update :state])))
                   (is (empty? @exits)))
                 (finally
                   (fs/rmSync install #js {:recursive true :force true})
                   (await (stop))))))
           (testing "a download that fails: the same"
             (let [{:keys [base stop]} (await (fake-github {:latest (latest-json "0.1.3") :files {}}))
                   install (install! "0.1.2")
                   exits (atom [])
                   db (atom {})
                   handle (update/handle {:db db
                                          :current "0.1.2"
                                          :install-dir install
                                          :latest-url (latest-path base)
                                          :auto? false
                                          :exit! (fn [code] (swap! exits conj code))})]
               (try
                 (await (update/check! handle))
                 (await (with-stderr (fn [] (update/apply! handle))))
                 (is (empty? @exits))
                 (is (= :failed (get-in @db [:update :state])))
                 (is (= (str "HTTP 404 from " base "/download/rragent.tar.gz") (get-in @db [:update :error])))
                 (is (= "// rragent 0.1.2\n" (bundle-of install)))
                 (is (= ["dist"] (vec (fs/readdirSync install))))
                 (finally
                   (fs/rmSync install #js {:recursive true :force true})
                   (await (stop))))))
           (testing "no dist/ to replace here: said, nothing downloaded"
             (let [{:keys [base requests stop]} (await (fake-github {:latest (latest-json "0.1.3") :files (release-files "0.1.3")}))
                   install (fs/mkdtempSync (path/join (os/tmpdir) "rragent-checkout-"))
                   exits (atom [])
                   db (atom {})
                   handle (update/handle {:db db
                                          :current "0.0.0"
                                          :install-dir install
                                          :latest-url (latest-path base)
                                          :auto? false
                                          :exit! (fn [code] (swap! exits conj code))})]
               (try
                 (await (update/check! handle))
                 (await (update/apply! handle))
                 (is (empty? @exits))
                 (is (= :failed (get-in @db [:update :state])))
                 (is (str/includes? (str (get-in @db [:update :error])) "no dist/ to replace"))
                 (is (= 1 (count @requests)) "the lookup alone")
                 (finally
                   (fs/rmSync install #js {:recursive true :force true})
                   (await (stop))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest watch-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [base requests stop]} (await (fake-github {:latest (latest-json "0.1.3") :files (release-files "0.1.3")}))]
             (try
               (testing "the idle watch: a known update goes in once the agent is idle"
                 (let [install (install! "0.1.2")
                       exits (atom [])
                       busy (atom true)
                       db (atom {})
                       handle (update/handle {:db db
                                              :current "0.1.2"
                                              :install-dir install
                                              :latest-url (latest-path base)
                                              :auto? true
                                              :exit! (fn [code] (swap! exits conj code))})
                       end! (update/watch! handle (fn [] @busy) {:check-every-ms 100000 :idle-every-ms 10 :off? (constantly false)})]
                   (try
                     (await (until #(= :known (get-in @db [:update :state]))))
                     (await (sleep 60))
                     (is (empty? @exits) "busy: the update waits")
                     (is (= "// rragent 0.1.2\n" (bundle-of install)))
                     (reset! busy false)
                     (await (with-stderr (fn [] (until #(seq @exits)))))
                     (is (= [75] @exits))
                     (is (= "// rragent 0.1.3\n" (bundle-of install)))
                     (finally
                       (end!)
                       (fs/rmSync install #js {:recursive true :force true})))))
               (testing "without auto, the update waits for the click"
                 (let [install (install! "0.1.2")
                       exits (atom [])
                       db (atom {})
                       handle (update/handle {:db db
                                              :current "0.1.2"
                                              :install-dir install
                                              :latest-url (latest-path base)
                                              :auto? false
                                              :exit! (fn [code] (swap! exits conj code))})
                       end! (update/watch! handle (constantly false) {:check-every-ms 100000 :idle-every-ms 10 :off? (constantly false)})]
                   (try
                     (await (until #(= :known (get-in @db [:update :state]))))
                     (await (sleep 60))
                     (is (empty? @exits))
                     (is (= "// rragent 0.1.2\n" (bundle-of install)))
                     (finally
                       (end!)
                       (fs/rmSync install #js {:recursive true :force true})))))
               (testing "switched off: no lookup at all"
                 (let [before (count @requests)
                       db (atom {})
                       handle (update/handle {:db db :current "0.1.2" :latest-url (latest-path base) :auto? true})
                       end! (update/watch! handle (constantly false) {:check-every-ms 10 :idle-every-ms 10 :off? (constantly true)})]
                   (await (sleep 60))
                   (end!)
                   (is (= before (count @requests)))
                   (is (= {:current "0.1.2" :auto? true} (:update @db)))))
               (finally
                 (await (stop)))))
           (testing "what the start says"
             (is (= "rragent 0.1.2, a newer release is looked up hourly and taken once the agent is idle"
                    (update/report {:current "0.1.2" :auto? true})))
             (is (= "rragent 0.1.2, a newer release is looked up hourly and offered on the page"
                    (update/report {:current "0.1.2" :auto? false}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
