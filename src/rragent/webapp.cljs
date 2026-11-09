(ns rragent.webapp
  "The page as an app on a phone's home screen, and on a desktop: the
   web app manifest with the name and the icons the launcher shows
   (Storrito, the burrito), the tags of the head that name it and the
   `rragent.view/mobile-section` of the settings that gets it onto the
   phone.

   The manifest is served behind the token, so it is the person's, and
   its `start_url` is the page's secret link: an app opened from the
   home screen signs itself in with every start. That is for iOS,
   where a home screen app has a cookie store of its own, apart from
   Safari's (WebKit's choice, bug 181849); Android's and the desktop's
   share the browser's and would not need it. The price is a rotated
   token: the installed app opens the access-required page until it is
   added again, as the link stops working too. The browser fetches the
   manifest without the cookie unless the link tag says
   `crossorigin=use-credentials`, which `head` does, same origin or not.
   The icons are open routes (`rragent.page`): nothing of the person is
   in them, and a browser that fetched them without the cookie would
   install the app without an icon."
  (:require ["qrcode/lib/core/qrcode.js" :as qrcode]
            [clojure.string :as str]
            [rragent.auth :as auth]))

(def app-name
  "What the home screen calls the app."
  "Storrito")

(def colors
  "The page's backgrounds, light and dark (`public/app.css`): the
   splash screen's and the status bar's."
  {:light "#faf9f7" :dark "#111315"})

(def manifest-path
  "/manifest.webmanifest")

(def icons
  "The icons under /icons: the PNGs rendered from burrito.svg on the
   light background, 192 and 512 for a launcher, the one with room
   around the burrito for a launcher that masks its icons to a shape,
   180 for iOS."
  [{:src "/icons/icon-192.png" :sizes "192x192" :type "image/png"}
   {:src "/icons/icon-512.png" :sizes "512x512" :type "image/png"}
   {:src "/icons/maskable-512.png" :sizes "512x512" :type "image/png" :purpose "maskable"}])

(defn manifest
  "The web app manifest for the page behind `token`, nil for a page
   without one: the start URL is the secret link (see the namespace),
   `id` keeps the app one app when the token changes."
  [token]
  {:name app-name
   :short_name app-name
   :id "/"
   :start_url (if token (auth/link "" token) "/")
   :scope "/"
   :display "standalone"
   :background_color (:light colors)
   :theme_color (:light colors)
   :icons icons})

(defn response
  "The manifest as the browser asks for it, never cached: it carries
   the token."
  [token]
  {:status 200
   :headers {"Content-Type" "application/manifest+json; charset=utf-8"
             "Cache-Control" "no-store"}
   :body (js/JSON.stringify (clj->js (manifest token)))})

(def head
  "The tags of the head that make the page an app: the manifest, fetched
   with the cookie; the burrito as the tab's icon and as the home
   screen's on iOS; the name iOS before 26 shows under it and that it
   opens without the browser's bars; the color of the bars around the
   page, light or dark with the system."
  (str "<link rel=\"manifest\" href=\"" manifest-path "\" crossorigin=\"use-credentials\">"
       "<link rel=\"icon\" href=\"/icons/burrito.svg\" type=\"image/svg+xml\">"
       "<link rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon.png\">"
       "<meta name=\"mobile-web-app-capable\" content=\"yes\">"
       "<meta name=\"apple-mobile-web-app-capable\" content=\"yes\">"
       "<meta name=\"apple-mobile-web-app-title\" content=\"" app-name "\">"
       "<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"default\">"
       "<meta name=\"theme-color\" content=\"" (:light colors) "\" media=\"(prefers-color-scheme: light)\">"
       "<meta name=\"theme-color\" content=\"" (:dark colors) "\" media=\"(prefers-color-scheme: dark)\">"))

(def quiet-zone
  "The modules of white around a QR code that a scanner needs."
  4)

(defn qr
  "`text` as a QR code, hiccup of an SVG: one path for the dark
   modules on a white square, always white, so that a phone reads it
   off a dark page too. Scales with the CSS size of the element."
  [text]
  (let [modules (.-modules (qrcode/create text #js {:errorCorrectionLevel "M"}))
        size (.-size modules)
        whole (+ size (* 2 quiet-zone))
        path (str/join " " (for [row (range size)
                                 col (range size)
                                 :when (pos? (.get modules row col))]
                             (str "M" (+ col quiet-zone) " " (+ row quiet-zone) "h1v1h-1z")))]
    [:svg.qr {:viewBox (str "0 0 " whole " " whole)
              :shape-rendering "crispEdges"
              :role "img"}
     [:rect {:width whole :height whole :fill "#ffffff"}]
     [:path {:d path :fill "#000000"}]]))
