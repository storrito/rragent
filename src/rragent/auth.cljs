(ns rragent.auth
  "A secret link as the whole login, for a page that one person uses.

   `?token=<secret>` on any path sets a cookie holding an HMAC of the
   token, and that cookie is the proof on every later request. Nothing
   about auth is stored on the server, so the cookie survives restarts by
   itself; rotating the token logs every browser out at once, which is
   the only revocation there is. Anyone without the cookie sees an
   \"access required\" page. The port of rchat.auth to node:crypto."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]))

(def cookie-name
  "rragent")

(defn cookie-value
  "What the cookie holds for `token`: an HMAC, never the token itself."
  [token]
  (-> (crypto/createHmac "sha256" (str token))
      (.update "rragent-cookie")
      (.digest "hex")))

(defn- timing-safe=
  [a b]
  (let [a (js/Buffer.from (str a) "utf8")
        b (js/Buffer.from (str b) "utf8")]
    (and (= (.-length a) (.-length b))
         (crypto/timingSafeEqual a b))))

(defn- url-decode
  [s]
  (try
    (js/decodeURIComponent (str/replace (str s) "+" " "))
    (catch :default _
      nil)))

(defn query-params
  [{:keys [query-string]}]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [(url-decode k) (url-decode (or v ""))]))))
        (str/split (str query-string) #"&")))

(defn- cookies
  [request]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split (str/trim pair) #"=" 2)]
                  (when (seq k)
                    [k (or v "")]))))
        (str/split (str (get-in request [:headers "cookie"])) #";")))

(defn authorized?
  [token request]
  (timing-safe= (get (cookies request) cookie-name "") (cookie-value token)))

(def access-required
  {:status 403
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
              "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
              "<title>Access required</title></head>"
              "<body style=\"font: 16px system-ui, sans-serif; padding: 2rem\">"
              "<h1>Access required</h1><p>Open this page with the link you were given.</p>"
              "</body></html>")})

(defn- secure?
  [request]
  (or (= :https (:scheme request))
      (= "https" (get-in request [:headers "x-forwarded-proto"]))))

(defn- set-cookie
  [token request]
  (str cookie-name "=" (cookie-value token)
       "; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000"
       (when (secure? request)
         "; Secure")))

(defn wrap
  "The handler behind the token. With a nil `:token` everything passes. A
   request with `?token=` equal to the token gets the cookie and a
   redirect to the same path without the query; a request with the cookie
   passes; anything else sees the access-required page."
  [handler {:keys [token]}]
  (fn [request]
    (cond
      (nil? token)
      (handler request)

      (some-> (get (query-params request) "token") (timing-safe= token))
      {:status 302
       :headers {"Location" (:uri request)
                 "Set-Cookie" (set-cookie token request)
                 "Cache-Control" "no-store"}
       :body ""}

      (authorized? token request)
      (handler request)

      :else
      access-required)))

(defn link
  "The link that sets the cookie."
  [base-url token]
  (str base-url "/?token=" (js/encodeURIComponent (str token))))

(defn generate-token
  "A token for a server started without RRAGENT_TOKEN."
  []
  (.toString (crypto/randomBytes 18) "base64url"))

(comment
  (link "http://localhost:8080" "s3cret")
  )
