(ns rragent.composio-setup
  "What the agent sets up at Composio for itself when it starts,
   through the gateway's connection to Composio
   (`rragent.composio`), awaited in the background of the page: its
   tool-router session, for its user id (the machine's `agent:` label),
   and the webhook subscription of its Composio project, pointed at
   its own page (`/composio/event`, `rragent.composio-events`). Both
   are kept in the machine's storage, the session document
   `rragent.composio` (`Setup`): the session id, the subscription with
   its signing secret, and the ids of the events seen. A run finds
   what an earlier run made: the session from the document (or from
   `COMPOSIO_SESSION_ID`, when the environment names one made
   elsewhere), the subscription from Composio's own list, one per
   project, its URL set to this page's when it is another's. Nothing
   here throws past `setup!`: an agent without a Composio connection,
   or without a public URL, says so on stderr and runs without."
  (:require ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.composio :as composio]
            [rragent.gateway :as gateway]))

(def Setup
  "The agent's Composio setup, a session document of the storage:
   `session` (the id), `subscription` (`{id url secret}`) and `seen`
   (the ids of the events taken, newest last)."
  (defineDoc #js {:kind "rragent.composio"
                  :version 1
                  :scope "session"
                  :history "latest"
                  :initial (fn [] #js {})}))

(def event-path
  "Where the page takes Composio's events."
  "/composio/event")

(def event-types
  "The events the subscription carries: a trigger's message, and the
   state changes an agent should hear of."
  ["composio.trigger.message"
   "composio.connected_account.activated"
   "composio.connected_account.expired"
   "composio.trigger.disabled"])

(def subscriptions-path
  "/api/v3.1/webhook_subscriptions")

(defn event-url
  "The page's event address: `base` (`RRAGENT_URL`) and `event-path`."
  [base]
  (str (str/replace (str base) #"/+$" "") event-path))

(defn ^:async snapshot
  "The document as data, `{}` before anything was written."
  [harness context]
  (let [doc (await (.snapshot harness Setup context))]
    (or (some-> doc (js->clj :keywordize-keys true)) {})))

(defn ^:async write!
  "`f` applied to the document in a commit."
  [harness context f]
  (await (.commit harness
                  (^:async fn [tx]
                    (let [doc (await (.doc tx Setup))]
                      (f doc)))
                  context)))

(defn- ok?
  [status]
  (<= 200 status 299))

(defn- said
  [body]
  (or (get-in body [:error :message])
      (if (string? body) body (js/JSON.stringify (clj->js body)))))

(defn ^:async ensure-session!
  "The agent's session: the environment's (`COMPOSIO_SESSION_ID`, one
   made elsewhere), else `stored` (an earlier run's), else one made
   now for `user-id` through `fetch` (`rragent.gateway/fetch!` bound
   to the discovery) on the connection `which`. `{:session :source}`,
   the source `:environment`, `:stored` or `:made`."
  [fetch which user-id stored]
  (cond
    (composio/session-id) {:session (composio/session-id) :source :environment}
    (seq (str stored)) {:session stored :source :stored}
    :else (let [{:keys [status body]} (await (fetch which composio/session-path {:method "POST" :body (composio/session-body user-id)}))]
            (if (and (ok? status) (seq (str (:session_id body))))
              {:session (:session_id body) :source :made}
              (throw (js/Error. (str "Composio answered HTTP " status " to the session: " (said body))))))))

(defn- subscription-of
  [{:keys [id webhook_url secret]} source]
  {:id id :url webhook_url :secret secret :source source})

(defn ^:async ensure-subscription!
  "The project's webhook subscription, pointed at `url`: Composio
   allows one per project, so the one listed is kept when it points
   here, set to `url` when it points elsewhere, made when there is
   none. `{:id :url :secret :source}`, the source `:kept`, `:updated`
   or `:made`; the secret is what the signatures of the events are
   checked with."
  [fetch which url]
  (let [{:keys [status body]} (await (fetch which subscriptions-path {:method "GET"}))
        _ (when-not (ok? status)
            (throw (js/Error. (str "Composio answered HTTP " status " to the subscriptions: " (said body)))))
        existing (first (or (:items body) (when (sequential? body) body)))
        wanted {:webhook_url url :enabled_events event-types}]
    (cond
      (and existing (= url (:webhook_url existing)) (= (set event-types) (set (:enabled_events existing))))
      (subscription-of existing :kept)

      existing
      (let [{:keys [status body]} (await (fetch which (str subscriptions-path "/" (:id existing)) {:method "PATCH" :body wanted}))]
        (when-not (ok? status)
          (throw (js/Error. (str "Composio answered HTTP " status " to the subscription's update: " (said body)))))
        (subscription-of (merge existing body wanted) :updated))

      :else
      (let [{:keys [status body]} (await (fetch which subscriptions-path {:method "POST" :body (assoc wanted :version "V3")}))]
        (when-not (ok? status)
          (throw (js/Error. (str "Composio answered HTTP " status " to the subscription: " (said body)))))
        (subscription-of (merge wanted body) :made)))))

(defn report
  "One line for the start."
  [{:keys [session subscription skipped]}]
  (cond
    skipped (str "composio: " skipped)
    :else (str "composio: session " (:session session) " (" (name (:source session)) ")"
               (if subscription
                 (str ", events to " (:url subscription) " (" (name (:source subscription)) ")")
                 ", no events (RRAGENT_URL is not set)"))))

(defn ^:async setup!
  "The session and the subscription ensured and kept, the session
   and the user id put into the environment for the REPL; one line on
   stdout, a failure on stderr, nothing thrown. `discovery` is the
   gateway's as the REPL found it, `url` the page's public address
   (`RRAGENT_URL`), `user-id` the agent's, `fetch`
   `rragent.gateway/fetch!` unless a test gives another."
  [{:keys [harness context discovery url user-id fetch]}]
  (try
    (let [connection (composio/connection discovery)
          fetch (or fetch (partial gateway/fetch! discovery))]
      (if-not connection
        (println (report {:skipped "no connection to Composio, the composio/ functions will say so"}))
        (let [stored (await (snapshot harness context))
              session (await (ensure-session! fetch (:id connection) user-id (:session stored)))]
          (composio/set-session! (:session session))
          (composio/set-user-id! user-id)
          (let [subscription (when (seq (str url))
                               (await (ensure-subscription! fetch (:id connection) (event-url url))))]
            (await (write! harness context
                           (fn [doc]
                             (aset doc "session" (:session session))
                             (when subscription
                               (aset doc "subscription" #js {:id (:id subscription) :url (:url subscription) :secret (:secret subscription)})))))
            (println (report {:session session :subscription subscription}))
            {:session session :subscription subscription}))))
    (catch :default e
      (js/console.error "[composio] the setup failed:" (or (.-message e) e))
      nil)))
