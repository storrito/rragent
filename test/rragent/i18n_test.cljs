(ns rragent.i18n-test
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.i18n :as i18n]))

(deftest dictionaries-test
  (is (= (set (keys i18n/en)) (set (keys i18n/de))) "every text of the reference is in German too"))

(deftest translate-test
  (is (= [:button "Send"] (i18n/translate [:button [:i18n :composer/send]] :en)))
  (is (= [:button "Senden"] (i18n/translate [:button [:i18n :composer/send]] :de)))
  (is (= [:p "Used fetch"] (i18n/translate [:p [:i18n :tool/other {:name "fetch"}]] :en)))
  (is (= [:p "fetch benutzt"] (i18n/translate [:p [:i18n :tool/other {:name "fetch"}]] :de)))
  (testing "the sidebar's sections and the advanced switch"
    (is (= [:span "Braucht dich"] (i18n/translate [:span [:i18n :bots/needs-you]] :de)))
    (is (= [:span "Needs you"] (i18n/translate [:span [:i18n :bots/needs-you]] :en)))
    (is (= [:span "archiviert"] (i18n/translate [:span [:i18n :bot/archived]] :de)))
    (is (= [:span "Erweitert"] (i18n/translate [:span [:i18n :settings/advanced]] :de)))
    (is (= [:span "Show every bot and the raw list"] (i18n/translate [:span [:i18n :advanced/show-all]] :en))))
  (testing "a key no dictionary has shows itself"
    (is (= [:p ":chat/nope"] (i18n/translate [:p [:i18n :chat/nope]] :de)))))

(deftest locale-test
  (is (= :de (i18n/locale {:headers {"accept-language" "de-DE,de;q=0.9,en;q=0.8"}})))
  (is (= :en (i18n/locale {:headers {"accept-language" "fr"}})))
  (is (= :en (i18n/locale {:headers {}})))
  (is (= :en (i18n/locale {:query-string "lang=en" :headers {"accept-language" "de"}})))
  (is (= :de (i18n/locale {:headers {"referer" "http://localhost:8080/?lang=de"}})) "a command posted from a page with ?lang="))
