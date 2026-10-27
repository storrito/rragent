(ns rragent.fake-oauth
  "An MCP server behind OAuth for the tests, on a real port of
   127.0.0.1 (pi-mcp allows plain http on loopback): the protected
   resource's and the authorization server's metadata, dynamic client
   registration, an authorize endpoint that approves at once (it sends
   the browser back with a code), a token endpoint that checks the PKCE
   verifier and refreshes, and `/mcp`, whose one tool `whoami` answers
   the access token it was called with. Without a valid token `/mcp`
   answers 401 with the resource metadata in WWW-Authenticate, as the
   MCP authorization spec has it."
  (:require ["node:crypto" :as crypto]
            ["node:http" :as http]
            [clojure.string :as str]))

(defn- json!
  [res status data & [headers]]
  (.writeHead res status (clj->js (merge {"content-type" "application/json"} headers)))
  (.end res (js/JSON.stringify (clj->js data))))

(defn- form
  [body]
  (into {} (map (fn [[k v]] [k v])) (js->clj (js/Object.fromEntries (js/URLSearchParams. body)))))

(defn- challenge
  "The S256 code challenge of `verifier`."
  [verifier]
  (-> (crypto/createHash "sha256") (.update verifier) (.digest "base64url")))

(defn- body!
  [req f]
  (let [chunks (atom "")]
    (.on req "data" (fn [c] (swap! chunks str c)))
    (.on req "end" (fn [] (f @chunks)))))

(defn- mcp!
  [{:keys [tokens calls]} req res body]
  (let [auth (str (.. req -headers -authorization))
        token (second (re-find #"^Bearer (.+)$" auth))
        base (str "http://" (.. req -headers -host))]
    (if-not (contains? @tokens token)
      (json! res 401 {:error "invalid_token"}
             {"www-authenticate" (str "Bearer resource_metadata=\"" base "/.well-known/oauth-protected-resource/mcp\"")})
      (let [{:strs [id method params]} (js->clj (js/JSON.parse body))]
        (swap! calls conj method)
        (case method
          "initialize" (json! res 200 {:jsonrpc "2.0" :id id
                                       :result {:protocolVersion (get params "protocolVersion")
                                                :capabilities {:tools {}}
                                                :serverInfo {:name "fake-oauth" :version "1"}}})
          "tools/list" (json! res 200 {:jsonrpc "2.0" :id id
                                       :result {:tools [{:name "whoami" :description "The token of the call."
                                                         :inputSchema {:type "object"}}]}})
          "tools/call" (json! res 200 {:jsonrpc "2.0" :id id
                                       :result {:content [{:type "text" :text (str "you are " token)}]}})
          (do (.writeHead res 202) (.end res)))))))

(defn- handler
  [{:keys [codes tokens refresh clients counter] :as fake}]
  (fn [req res]
    (let [url (js/URL. (.-url req) (str "http://" (.. req -headers -host)))
          base (.-origin url)
          path (.-pathname url)]
      (body! req
             (fn [body]
               (cond
                 (str/starts-with? path "/.well-known/oauth-protected-resource")
                 (json! res 200 {:resource (str base "/mcp") :authorization_servers [base]})

                 (= path "/.well-known/oauth-authorization-server")
                 (json! res 200 {:issuer base
                                 :authorization_endpoint (str base "/authorize")
                                 :token_endpoint (str base "/token")
                                 :registration_endpoint (str base "/register")
                                 :response_types_supported ["code"]
                                 :grant_types_supported ["authorization_code" "refresh_token"]
                                 :code_challenge_methods_supported ["S256"]
                                 :token_endpoint_auth_methods_supported ["none"]})

                 (= path "/register")
                 (let [metadata (js->clj (js/JSON.parse body))
                       client-id (str "client-" (swap! counter inc))]
                   (swap! clients assoc client-id metadata)
                   (json! res 201 (assoc metadata "client_id" client-id)))

                 (= path "/authorize")
                 (let [q (.-searchParams url)
                       code (str "code-" (swap! counter inc))
                       redirect (.get q "redirect_uri")]
                   (if-not (some #{redirect} (get-in @clients [(.get q "client_id") "redirect_uris"]))
                     (json! res 400 {:error "invalid_request" :error_description "unknown redirect_uri"})
                     (do (swap! codes assoc code {:challenge (.get q "code_challenge") :redirect redirect})
                         (.writeHead res 302 #js {"location" (str redirect "?code=" code "&state=" (js/encodeURIComponent (.get q "state")))})
                         (.end res))))

                 (= path "/token")
                 (let [{:strs [grant_type code code_verifier refresh_token]} (form body)
                       issue! (fn []
                                (let [n (swap! counter inc)
                                      access (str "at" n)
                                      refreshing (str "rt" n)]
                                  (swap! tokens conj access)
                                  (swap! refresh conj refreshing)
                                  (json! res 200 {:access_token access :refresh_token refreshing
                                                  :token_type "Bearer" :expires_in 3600})))]
                   (case grant_type
                     "authorization_code" (let [known (get @codes code)]
                                            (swap! codes dissoc code)
                                            (if (and known (= (:challenge known) (challenge (str code_verifier))))
                                              (issue!)
                                              (json! res 400 {:error "invalid_grant"})))
                     "refresh_token" (if (contains? @refresh refresh_token)
                                       (do (swap! refresh disj refresh_token) (issue!))
                                       (json! res 400 {:error "invalid_grant"}))
                     (json! res 400 {:error "unsupported_grant_type"})))

                 (= path "/mcp")
                 (if (= "POST" (.-method req))
                   (mcp! fake req res body)
                   (do (.writeHead res 405) (.end res)))

                 :else
                 (json! res 404 {:error "not found"})))))))

(defn ^:async start!
  "The fake on a free port: `{:base :url :stop! :tokens :calls
   :clients}`, `url` its MCP endpoint; `tokens` the access tokens it
   takes (take one out to make it expire)."
  []
  (let [fake {:codes (atom {}) :tokens (atom #{}) :refresh (atom #{}) :clients (atom {})
              :calls (atom []) :counter (atom 0)}
        server (http/createServer (handler fake))]
    (await (js/Promise. (fn [resolve] (.listen server 0 "127.0.0.1" resolve))))
    (let [base (str "http://127.0.0.1:" (.-port (.address server)))]
      (assoc fake
             :base base
             :url (str base "/mcp")
             :stop! (fn []
                      (.closeAllConnections server)
                      (js/Promise. (fn [resolve] (.close server resolve))))))))
