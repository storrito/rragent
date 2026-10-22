(ns rragent.client.phone-test
  "What the laptop's panel calls the phone."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.client.phone :as phone]))

(def chrome-android
  "Chrome's user agent on Android today: the model is `K`."
  "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")

(deftest device-name-test
  (testing "the model Chrome's client hints give"
    (is (= "Pixel 8" (phone/device-name chrome-android "Pixel 8"))))
  (testing "the model in the user agent, unless it is Chrome's K"
    (is (= "SM-G960U" (phone/device-name "Mozilla/5.0 (Linux; Android 10; SM-G960U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/22.0 Chrome/111.0 Mobile Safari/537.36" nil)))
    (is (= "Android · Chrome" (phone/device-name chrome-android nil)))
    (is (= "Android · Chrome" (phone/device-name chrome-android ""))))
  (testing "the system and the browser otherwise"
    (is (= "iPhone · Safari" (phone/device-name "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1" nil)))
    (is (= "iPhone · Chrome" (phone/device-name "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/141.0 Mobile/15E148 Safari/604.1" nil)))
    (is (= "Android · Firefox" (phone/device-name "Mozilla/5.0 (Android 14; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0" nil))))
  (testing "a phone the page knows nothing of"
    (is (= "Phone" (phone/device-name "" nil)))))
