(ns rragent.starting
  "What the page answers while the agent opens. The port is taken
   first, before the agent: on a Sprite that wakes up, opening it takes
   some seconds (the gateway's connections among them), longer than the
   Sprite's proxy waits for the port before it answers `internal_error`.
   So a browser that asks for the page gets one that says the agent is
   starting and asks again every second, until the page itself comes;
   anything else (a command, the frame stream, an upload, a hook that
   woke the machine) waits for the agent, as it waited at the proxy
   before, and goes to the page's handler once it is there; after
   `wait-ms` it gets a 503 with Retry-After."
  (:require [clojure.string :as str]
            [rragent.assets :as assets]
            [rragent.i18n :as i18n]))

(def poll-ms
  "How often the page asks whether the agent is there."
  1000)

(def wait-ms
  "How long a request other than the page waits for the agent."
  30000)

(defn- text
  [k locale]
  (i18n/translate [:i18n k] locale))

(defn html
  "The page in `locale`: the texts are the dictionary's, nothing of the
   request goes into it."
  [locale]
  (str "<!doctype html>"
       "<html lang=\"" (name locale) "\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>" (text :starting/title locale) "</title>"
       assets/theme-script
       "<style>"
       ":root{color-scheme:light;--bg:#faf9f7;--text:#1f2328;--muted:#6b7079;--accent:#e0394b}"
       ;; the theme the person chose with the page's button, else the system's
       "@media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;--bg:#111315;--text:#e8eaed;--muted:#8d949e;--accent:#ff4d5e}}"
       ":root[data-theme=dark]{color-scheme:dark;--bg:#111315;--text:#e8eaed;--muted:#8d949e;--accent:#ff4d5e}"
       "html,body{height:100%;margin:0}"
       "body{display:flex;align-items:center;justify-content:center;padding:16px;background:var(--bg);color:var(--text);"
       "font:16px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif;text-align:center}"
       ".spinner{width:28px;height:28px;margin:0 auto 16px;border:3px solid color-mix(in srgb,var(--muted) 30%,transparent);"
       "border-top-color:var(--accent);border-radius:50%;animation:spin .9s linear infinite}"
       "@keyframes spin{to{transform:rotate(360deg)}}"
       "@media (prefers-reduced-motion:reduce){.spinner{animation:none}}"
       "p{margin:0}.hint{color:var(--muted);font-size:14px;margin-top:4px}"
       "</style></head><body><main role=\"status\">"
       "<div class=\"spinner\" aria-hidden=\"true\"></div>"
       "<p>" (text :starting/title locale) "</p>"
       "<p class=\"hint\">" (text :starting/hint locale) "</p>"
       "</main><script>"
       ;; the same address, the token in it included, until it is no longer this page
       "(function poll(){setTimeout(function(){"
       "fetch(location.href,{cache:'no-store',headers:{Accept:'text/html'}})"
       ".then(function(r){if(r.status!==503){location.reload()}else{poll()}},poll)"
       "}," poll-ms ")})()"
       "</script><noscript><meta http-equiv=\"refresh\" content=\"2\"></noscript>"
       "</body></html>"))

(defn- page-request?
  [request]
  (and (= :get (:request-method request))
       (str/includes? (str (get-in request [:headers "accept"])) "text/html")))

(def unavailable
  {:status 503
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"
             "Retry-After" "1"}
   :body "starting"})

(defn response
  "The answer to `request` while the agent opens, see the namespace.
   `opened` is a promise of the page's handler."
  [opened request]
  (if (page-request? request)
    {:status 503
     :headers {"Content-Type" "text/html; charset=utf-8"
               "Cache-Control" "no-store"
               "Retry-After" "1"}
     :body (html (i18n/locale request))}
    (js/Promise. (fn [resolve reject]
                   (let [timer (js/setTimeout #(resolve unavailable) wait-ms)]
                     (-> opened
                         (.then (fn [handler]
                                  (js/clearTimeout timer)
                                  (handler request)))
                         (.then resolve reject)))))))

(comment
  (response (js/Promise. (fn [_])) {:request-method :get :headers {"accept" "text/html" "accept-language" "de"}})
  (response (js/Promise.resolve (fn [_] {:status 200})) {:request-method :post :uri "/command" :headers {}})
  )
