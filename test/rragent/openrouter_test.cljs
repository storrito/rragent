(ns rragent.openrouter-test
  "Where a call to OpenRouter goes (`rragent.openrouter/route`): the
   fake gateway's `openrouter` connection, else OpenRouter itself with
   the key of the environment, else nowhere; and the gateway asked
   again, at most every interval, until it lists one."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.codemode :as codemode]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.openrouter :as openrouter]
            [rragent.sync-fetch :as sync-fetch]))

(def none
  "No gateway: a port that refuses at once."
  "http://127.0.0.1:1")

(defn- ^:async with-env
  "`thunk` awaited with the environment variables of `vars` set (a nil
   value unset), every one restored after."
  [vars thunk]
  (let [env (.-env js/process)
        set-var! (fn [k v] (if (nil? v) (js-delete env k) (aset env k v)))
        before (into {} (map (fn [[k _]] [k (aget env k)])) vars)]
    (try
      (doseq [[k v] vars] (set-var! k v))
      (await (thunk))
      (finally
        (doseq [[k v] before] (set-var! k v))))))

(defn- ^:async sleep
  [ms]
  (await (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

(defn- gateway-route
  [url]
  {:base-url (str url "/v1/gateway/openrouter/conn_openrouter/v1") :headers {} :source :gateway})

(deftest route-test
  (async done
    (-> ((^:async fn []
           (let [{:keys [url stop]} (await (fake-gateway/start!))]
             (try
               (testing "an openrouter connection: the gateway's base and /v1, no header, whatever the environment holds"
                 (let [codemode (codemode/handle url)]
                   (is (= (gateway-route url)
                          (await (with-env {"OPENROUTER_API_KEY" "k3y" "OPENROUTER_BASE_URL" "http://elsewhere/v1"}
                                   #(openrouter/route codemode)))))
                   (is (= (gateway-route url)
                          (await (with-env {"OPENROUTER_API_KEY" nil} #(openrouter/route! codemode "dictation")))))
                   (is (str/starts-with? (openrouter/report (codemode/discovery codemode)) "OpenRouter through the Sprites gateway ("))))
               (testing "no connection, a key: OpenRouter itself with the bearer, or what OPENROUTER_BASE_URL says without its trailing slash"
                 (let [codemode (codemode/handle none)]
                   (is (= {:base-url "https://openrouter.ai/api/v1" :headers {"Authorization" "Bearer sk-or-test"} :source :environment}
                          (await (with-env {"OPENROUTER_API_KEY" "sk-or-test" "OPENROUTER_BASE_URL" nil} #(openrouter/route codemode)))))
                   (is (= "http://127.0.0.1:9/v1"
                          (:base-url (await (with-env {"OPENROUTER_API_KEY" "sk-or-test" "OPENROUTER_BASE_URL" "http://127.0.0.1:9/v1/"} #(openrouter/route codemode))))))
                   (is (str/starts-with? (openrouter/report (codemode/discovery codemode)) "OpenRouter with OPENROUTER_API_KEY"))))
               (testing "neither: nothing, and the throw says what there is not, with what the caller wanted"
                 (let [codemode (codemode/handle none)]
                   (is (nil? (await (with-env {"OPENROUTER_API_KEY" nil} #(openrouter/route codemode)))))
                   (is (= "no OpenRouter: no gateway connection and OPENROUTER_API_KEY is not set, so no dictation."
                          (await (with-env {"OPENROUTER_API_KEY" nil}
                                   #(try (openrouter/route! codemode "dictation") (catch :default e (.-message e)))))))))
               (finally
                 (stop)
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest gateway-up-later-test
  (async done
    (-> ((^:async fn []
           (let [port (await (fake-gateway/free-port))
                 url (str "http://127.0.0.1:" port)
                 codemode (codemode/handle url)
                 fake (atom nil)]
             (try
               (await (with-env {"OPENROUTER_API_KEY" nil}
                        (^:async fn []
                          (testing "nothing listens when the REPL opens: no route, the gateway asked once more"
                            (is (zero? (codemode/asked-at codemode)) "the reading at start is not an asking again")
                            (is (nil? (openrouter/route codemode 500)))
                            (is (pos? (codemode/asked-at codemode))))
                          (testing "the gateway comes up: not asked again within the interval, then the route is the gateway's"
                            (reset! fake (await (fake-gateway/start! nil {:port port})))
                            (is (nil? (openrouter/route codemode 500)) "asked less than the interval ago")
                            (await (sleep 500))
                            (is (= (gateway-route url) (openrouter/route codemode 500)))
                            (is (= 5 (count (:connections (codemode/discovery codemode)))) "the REPL's discovery is the refreshed one")
                            (let [asked (codemode/asked-at codemode)]
                              (is (= (gateway-route url) (openrouter/route codemode 500)))
                              (is (= asked (codemode/asked-at codemode)) "known from then on, not asked again"))))))
               (finally
                 (when-let [{:keys [stop]} @fake] (stop))
                 (sync-fetch/stop!))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
