(ns rragent.mcp-oauth
  "OAuth for the MCP servers whose entry says `\"oauth\": true`: the
   person signs in at the service in their browser, the machine keeps
   the tokens and refreshes them. pi-mcp (`@earendil-works/pi-mcp/oauth`)
   does the protocol: discovery of the authorization server, dynamic
   client registration, PKCE, the code exchange, refresh. This holds
   what it needs to keep: per server, its client registration, tokens,
   code verifier, state and discovery, in `mcp-oauth.json` of the
   machine's folder, readable by its owner only, as `secrets.json`.

   The browser goes to `/mcp/oauth/start/<server>` (`rragent.page`), is
   sent on to the service, and comes back to `/mcp/oauth/callback` with
   the code; both behind the page's token, whose cookie (SameSite=Lax)
   the redirect back carries. `rragent.mcp` makes the provider for a
   connection (`provider`) and runs the sign-in (`sign-in!`,
   `signed-in!`)."
  (:require ["@earendil-works/pi-mcp/oauth" :refer [McpOAuthProvider]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.secrets :as secrets]))

(def file-name
  "mcp-oauth.json")

(def callback-path
  "/mcp/oauth/callback")

(def start-prefix
  "/mcp/oauth/start")

(defn file
  "The OAuth state of the machine's folder `home`."
  [home]
  (path/join home file-name))

(defn redirect-url
  "Where the service sends the browser back: the callback under `base`,
   the page's address."
  [base]
  (str (str/replace (str base) #"/+$" "") callback-path))

(defn start-path
  "The page's address that starts the sign-in of `server-name`; `bot`,
   the bot to tell once it is done."
  [server-name & [bot]]
  (str start-prefix "/" (js/encodeURIComponent server-name)
       (when bot (str "?bot=" (js/encodeURIComponent (str bot))))))

(defn read-all
  "`{server-name state}` of the file, the states as pi-mcp keeps them
   (`McpOAuthState`, string keys); `{}` without the file."
  [file]
  (try
    (let [data (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))]
      (if (map? data) data {}))
    (catch :default _
      {})))

(defn state-of
  [file server-name]
  (get (read-all file) server-name))

(defn- write!
  [file all]
  (secrets/write! file all))

(defn update-state!
  "The state of `server-name` through `f`; a nil result removes it."
  [file server-name f]
  (let [all (read-all file)
        next (f (get all server-name))]
    (write! file (if (nil? next) (dissoc all server-name) (assoc all server-name next)))))

(defn forget!
  "Everything kept for `server-name`: a sign-out, a removed server."
  [file server-name]
  (when (contains? (read-all file) server-name)
    (update-state! file server-name (constantly nil))))

(defn signed-in?
  "Whether the server has tokens."
  [file server-name]
  (boolean (get-in (read-all file) [server-name "tokens" "access_token"])))

(defn token-values
  "`{label value}` of every access and refresh token kept, for the
   redaction of tool results (`rragent.secrets`)."
  [file]
  (into {}
        (for [[server-name state] (read-all file)
              [k label] [["access_token" "access token"] ["refresh_token" "refresh token"]]
              :let [v (get-in state ["tokens" k])]
              :when (string? v)]
          [(str server-name " " label) v])))

(defn- store
  "pi-mcp's `McpOAuthStateStore` over the file, for one server."
  [file server-name]
  #js {:load (fn [] (some-> (state-of file server-name) clj->js))
       :save (fn [state] (update-state! file server-name (constantly (js->clj state))))})

(defn fresh-attempt!
  "Before a sign-in: no state or code verifier of an earlier attempt,
   so that every sign-in has a state of its own; a client registered
   for another redirect address forgotten, the service would refuse
   it."
  [file server-name redirect]
  (update-state! file server-name
                 (fn [state]
                   (let [uris (get-in state ["clientInformation" "redirect_uris"])
                         state (dissoc state "oauthState" "codeVerifier")]
                     (if (and (seq uris) (not (some #{redirect} uris)))
                       (dissoc state "clientInformation")
                       state)))))

(defn server-by-state
  "The server whose sign-in waits for the callback with `oauth-state`."
  [file oauth-state]
  (when (seq (str oauth-state))
    (some (fn [[server-name state]]
            (when (= oauth-state (get state "oauthState")) server-name))
          (read-all file))))

(defn provider
  "pi-mcp's provider for `server-name` at `server-url`: its state in
   `file`, the service sending the browser back to `redirect`;
   `on-redirect` gets the service's sign-in address when the person has
   to sign in. `client-id`, `client-secret` and `scope` from the entry
   for a service that does not register clients itself."
  [{:keys [file server-name server-url redirect on-redirect client-id client-secret scope]}]
  (McpOAuthProvider.
   (clj->js (cond-> {:serverUrl server-url
                     :redirectUrl redirect
                     :clientMetadata (cond-> {:client_name "rragent"
                                              :grant_types ["authorization_code" "refresh_token"]
                                              :response_types ["code"]
                                              :token_endpoint_auth_method (if client-secret "client_secret_post" "none")}
                                       scope (assoc :scope scope))
                     :store (store file server-name)
                     :onRedirect (fn [url] (when on-redirect (on-redirect (str url))))}
              client-id (assoc :clientId client-id)
              client-secret (assoc :clientSecret client-secret)))))
