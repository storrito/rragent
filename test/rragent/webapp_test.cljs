(ns rragent.webapp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.webapp :as webapp]))

(deftest manifest-test
  (testing "the start URL is the secret link, the app named and with its icons"
    (let [m (webapp/manifest "s3cret")]
      (is (= "Storrito" (:name m)))
      (is (= "/?token=s3cret" (:start_url m)))
      (is (= "/" (:id m)) "one app, whatever the token")
      (is (= "standalone" (:display m)))
      (is (= #{"192x192" "512x512"} (set (map :sizes (:icons m)))))
      (is (some #(= "maskable" (:purpose %)) (:icons m)))))
  (testing "a token with characters a URL cannot carry is encoded"
    (is (= "/?token=a%2Fb%3Dc" (:start_url (webapp/manifest "a/b=c")))))
  (testing "without a token the page itself"
    (is (= "/" (:start_url (webapp/manifest nil))))))

(deftest response-test
  (let [{:keys [status headers body]} (webapp/response "s3cret")
        parsed (js->clj (js/JSON.parse body))]
    (is (= 200 status))
    (is (str/starts-with? (get headers "Content-Type") "application/manifest+json"))
    (is (= "no-store" (get headers "Cache-Control")) "it carries the token")
    (is (= "/?token=s3cret" (get parsed "start_url")))
    (is (= "/icons/icon-512.png" (get-in parsed ["icons" 1 "src"])))))

(deftest head-test
  (is (str/includes? webapp/head "rel=\"manifest\" href=\"/manifest.webmanifest\" crossorigin=\"use-credentials\"")
      "the manifest is fetched with the cookie")
  (is (str/includes? webapp/head "rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon.png\""))
  (is (str/includes? webapp/head "apple-mobile-web-app-title\" content=\"Storrito\""))
  (is (str/includes? webapp/head "theme-color\" content=\"#111315\" media=\"(prefers-color-scheme: dark)\""))
  (is (not (str/includes? webapp/head "data:,")) "the burrito, not the empty icon"))

(deftest qr-test
  (let [[tag attrs rect path] (webapp/qr "https://example.test/?token=s3cret")
        size (js/parseInt (last (str/split (:viewBox attrs) #" ")) 10)]
    (is (= :svg.qr tag))
    (is (= [:rect {:width size :height size :fill "#ffffff"}] rect) "white under the modules, whatever the theme")
    (is (str/starts-with? (:d (second path)) "M4 4") "the finder pattern begins after the quiet zone")
    (is (< 30 size) "a code with its quiet zone")
    (testing "another text, another code"
      (is (not= (:d (second path))
                (:d (second (nth (webapp/qr "https://example.test/?token=other") 3))))))))
