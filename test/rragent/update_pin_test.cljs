(ns rragent.update-pin-test
  "A machine pinned to a release (`RRAGENT_UPDATE=<tag>`, see
   `rragent.update`): the pin read from the variable, the lookup at
   the tag's release instead of the newest, taken whatever its version
   when it is not the one in place, the marker file after the swap,
   nothing once it is in place; against a fake of GitHub's API and
   release files on a free port."
  (:require ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.http :as http]
            [rragent.update :as update]))

(deftest pin-test
  (is (nil? (update/pin-of "auto")))
  (is (nil? (update/pin-of "off")))
  (is (nil? (update/pin-of "")))
  (is (nil? (update/pin-of nil)))
  (is (= "v0.1.55" (update/pin-of "v0.1.55")) "a release, for a rollback")
  (is (= "trial-loop-1a2b3c4" (update/pin-of " trial-loop-1a2b3c4\n")) "a trial, trimmed")
  (is (= "https://api.github.com/repos/storrito/rragent/releases/latest" (update/lookup-url nil)))
  (is (= "https://api.github.com/repos/storrito/rragent/releases/tags/trial-loop-1a2b3c4" (update/lookup-url "trial-loop-1a2b3c4")))
  (is (= "trial-loop-1a2b3c4" (:tag (update/release-of {:tag_name "trial-loop-1a2b3c4"
                                                         :assets [{:name "rragent.tar.gz" :browser_download_url "u"}
                                                                  {:name "rragent.tar.gz.sha256" :browser_download_url "s"}]})))
      "the tag stays on the release, the version is the tag without a v")
  (is (= "rragent 0.1.55, pinned to the release trial-loop-1a2b3c4 (RRAGENT_UPDATE): taken once the agent is idle, no other release is"
         (update/report {:current "0.1.55" :auto? true :pin "trial-loop-1a2b3c4"}))))

(deftest installed-tag-test
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-marker-"))]
    (try
      (is (= "v0.1.55" (update/installed-tag dir "0.1.55")) "no marker: the running version")
      (fs/writeFileSync (path/join dir "release") "trial-loop-1a2b3c4\n")
      (is (= "trial-loop-1a2b3c4" (update/installed-tag dir "0.0.0-trial.1a2b3c4")) "the marker, trimmed")
      (is (update/to-take? {:pin "v0.1.50" :current "0.1.55" :install-dir dir} {:tag "v0.1.50" :version "0.1.50"}) "pinned: an older release is taken")
      (is (not (update/to-take? {:pin "trial-loop-1a2b3c4" :current "0.0.0-trial.1a2b3c4" :install-dir dir} {:tag "trial-loop-1a2b3c4" :version "trial-loop-1a2b3c4"})) "pinned: the one in place is not")
      (is (not (update/to-take? {:pin nil :current "0.1.55" :install-dir dir} {:tag "v0.1.50" :version "0.1.50"})) "not pinned: an older release is not")
      (is (update/to-take? {:pin nil :current "0.1.55" :install-dir dir} {:tag "v0.1.56" :version "0.1.56"}) "not pinned: a newer one is")
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(defn- release-json
  [tag]
  (fn [base]
    {:tag_name tag
     :name (str "rragent " tag)
     :body (str "* a trial\n")
     :prerelease true
     :assets [{:name "rragent.tar.gz" :browser_download_url (str base "/download/rragent.tar.gz")}
              {:name "rragent.tar.gz.sha256" :browser_download_url (str base "/download/rragent.tar.gz.sha256")}]}))

(defn- release-files
  "`rragent.tar.gz` of a tiny dist/ that says `label` and its `.sha256`."
  [label]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-trial-release-"))
        dist (path/join dir "dist")
        tarball (path/join dir "rragent.tar.gz")]
    (fs/mkdirSync dist #js {:recursive true})
    (fs/writeFileSync (path/join dist "rragent.js") (str "// rragent " label "\n"))
    (let [result (child/spawnSync "tar" #js ["-czf" tarball "-C" dist "."] #js {:encoding "utf8"})]
      (when-not (= 0 (.-status result))
        (throw (js/Error. (str "tar: " (.-stderr result))))))
    (let [bytes (fs/readFileSync tarball)
          hash (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex"))]
      (fs/rmSync dir #js {:recursive true :force true})
      {"rragent.tar.gz" bytes
       "rragent.tar.gz.sha256" (str hash "  rragent.tar.gz\n")})))

(defn- ^:async fake-github
  "GitHub on a free port: the release of `tag` at the API's path for a
   tag, the newest at the latest path, the files under `/download/`."
  [{:keys [tag newest files]}]
  (let [requests (atom [])
        port (atom nil)
        json (fn [release]
               {:status 200
                :headers {"Content-Type" "application/json"}
                :body (js/JSON.stringify (clj->js ((release-json release) (str "http://127.0.0.1:" @port))))})
        server (await (http/listen (fn [{:keys [uri request-method]}]
                                     (swap! requests conj [request-method uri])
                                     (cond
                                       (= (str "/repos/storrito/rragent/releases/tags/" tag) uri) (json tag)
                                       (= "/repos/storrito/rragent/releases/latest" uri) (json newest)
                                       (and (str/starts-with? uri "/download/") (get files (subs uri (count "/download/"))))
                                       {:status 200 :headers {"Content-Type" "application/octet-stream"} :body (get files (subs uri (count "/download/")))}
                                       :else {:status 404 :headers {"Content-Type" "text/plain"} :body "no"}))
                                   0 "127.0.0.1"))]
    (reset! port (.-port (.address server)))
    {:base (str "http://127.0.0.1:" @port)
     :requests requests
     :stop (fn []
             (.closeAllConnections server)
             (js/Promise. (fn [resolve] (.close server resolve))))}))

(defn- install!
  [label]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "rragent-pinned-install-"))]
    (fs/mkdirSync (path/join dir "dist") #js {:recursive true})
    (fs/writeFileSync (path/join dir "dist" "rragent.js") (str "// rragent " label "\n"))
    dir))

(defn- quiet
  "`f` run with `console.error` silenced."
  [f]
  (let [original (.-error js/console)]
    (set! (.-error js/console) (fn [& _]))
    (-> (f)
        (.finally (fn [] (set! (.-error js/console) original))))))

(deftest pinned-machine-test
  (async done
    (-> ((^:async fn []
           (let [tag "trial-loop-1a2b3c4"
                 {:keys [base requests stop]} (await (fake-github {:tag tag :newest "v0.1.56" :files (release-files tag)}))
                 install (install! "0.1.55")
                 exits (atom [])
                 db (atom {})
                 handle (update/handle {:db db
                                        :current "0.1.55"
                                        :pin tag
                                        :latest-url (str base "/repos/storrito/rragent/releases/tags/" tag)
                                        :install-dir install
                                        :auto? true
                                        :exit! (fn [code] (swap! exits conj code))})]
             (try
               (testing "the pin is on the page from the start"
                 (is (= {:current "0.1.55" :auto? true :pin tag} (:update @db))))
               (testing "a release build pinned to a trial: the trial is looked up, not the newest, and it is one to take"
                 (is (= :found (await (update/check! handle))))
                 (is (= [[:get (str "/repos/storrito/rragent/releases/tags/" tag)]] @requests) "the tag's release, no latest")
                 (is (= tag (get-in @db [:update :tag])))
                 (is (= tag (get-in @db [:update :version])) "a trial's version is its tag"))
               (testing "taken: the trial's bundle is dist/, the marker names the trial"
                 (await (quiet (fn [] (update/apply! handle))))
                 (is (= [75] @exits))
                 (is (= (str "// rragent " tag "\n") (fs/readFileSync (path/join install "dist" "rragent.js") "utf8")))
                 (is (= (str tag "\n") (fs/readFileSync (path/join install "release") "utf8"))))
               (testing "started again on the trial: the pinned release is the one in place, nothing to take"
                 (let [db (atom {})
                       handle (update/handle {:db db
                                              :current "0.0.0-trial.1a2b3c4"
                                              :pin tag
                                              :latest-url (str base "/repos/storrito/rragent/releases/tags/" tag)
                                              :install-dir install
                                              :auto? true})]
                   (is (= :newest (await (update/check! handle))))
                   (is (= {:current "0.0.0-trial.1a2b3c4" :auto? true :pin tag} (:update @db)) "no banner")))
               (finally
                 (fs/rmSync install #js {:recursive true :force true})
                 (await (stop)))))
           (testing "not pinned, the marker is written after a swap too"
             (let [{:keys [base stop]} (await (fake-github {:tag "none" :newest "v0.1.56" :files (release-files "v0.1.56")}))
                   install (install! "0.1.55")
                   exits (atom [])
                   db (atom {})
                   handle (update/handle {:db db
                                          :current "0.1.55"
                                          :pin nil
                                          :latest-url (str base "/repos/storrito/rragent/releases/latest")
                                          :install-dir install
                                          :auto? false
                                          :exit! (fn [code] (swap! exits conj code))})]
               (try
                 (is (= :found (await (update/check! handle))))
                 (await (quiet (fn [] (update/apply! handle))))
                 (is (= [75] @exits))
                 (is (= "v0.1.56\n" (fs/readFileSync (path/join install "release") "utf8")))
                 (finally
                   (fs/rmSync install #js {:recursive true :force true})
                   (await (stop))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
