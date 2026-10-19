(ns rragent.genui-test
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.genui :as genui]))

(defn- send-of
  [text]
  {:click [[:data/command {:command/kind :chat/send
                           :command/data {:text text}}]]})

(deftest parse-test
  (testing "a hiccup vector"
    (is (= {:hiccup [:p "a"]} (genui/parse "[:p \"a\"]"))))
  (testing "anything else is refused with a reason"
    (is (:error (genui/parse "{:a 1}")))
    (is (:error (genui/parse "[:p")))
    (is (:error (genui/parse "")))
    (is (:error (genui/parse "\"text\""))))
  (testing "a tagged literal is its value"
    (is (= {:hiccup [:p "x"]} (genui/parse "[:p #my/tag \"x\"]")))))

(deftest sanitize-test
  (testing "the allowed tags and attributes pass, a class on the tag too"
    (is (= [:div.card {:class "x" :style {:border "1px solid #ddd" :padding "10px"}} [:strong {} "a"]]
           (genui/sanitize [:div.card {:class "x" :style {:border "1px solid #ddd" :padding "10px"}} [:strong "a"]]))))
  (testing "an unknown tag is a span, an unknown attribute goes"
    (is (= [:span {} "alert(1)"] (genui/sanitize [:script "alert(1)"])))
    (is (= [:div {}] (genui/sanitize [:div {:onclick "x" :on {:click [[:x]]} :replicant/key "k"}]))))
  (testing "a tag with a namespace is no alias of the client"
    (is (= [:span {} "x"] (genui/sanitize [:ui/composer {:ui/send "x"} "x"])))
    (is (= [:span {} "x"] (genui/sanitize [:ui/div "x"])))
    (is (= [:span {} "x"] (genui/sanitize [:ui/button {:send "On: {checked}"} "x"]))))
  (testing "links and files point at artifacts, uploads, https or mailto"
    (is (= [:a {:href "https://youtu.be/x?t=4" :target "_blank" :rel "noopener noreferrer"} "watch"]
           (genui/sanitize [:a {:href "https://youtu.be/x?t=4"} "watch"])))
    (is (= [:img {:src "/artifacts/card/a.png"}] (genui/sanitize [:img {:src "artifacts/card/a.png"}])))
    (is (= [:video {:src "/uploads/c.mp4" :controls true}] (genui/sanitize [:video {:src "uploads/c.mp4" :controls true}])))
    (is (= [:a {}] (genui/sanitize [:a {:href "javascript:alert(1)"}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "http://x.test/a.png"}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "/etc/passwd"}]))))
  (testing "a style value that loads something goes"
    (is (= [:div {:style {:color "red"}}]
           (genui/sanitize [:div {:style {:color "red" :background "URL(https://x.test/t.gif)"}}]))))
  (testing "an input is a checkbox or a radio button, nothing else"
    (is (= [:input {:type "checkbox" :value "a" :checked true}]
           (genui/sanitize [:input {:type "checkbox" :value "a" :checked true}])))
    (is (= [:input {:type "radio" :name "n" :value "b"}]
           (genui/sanitize [:input {:type "radio" :name "n" :value "b"}])))
    (is (= [:label {} nil " name"] (genui/sanitize [:label [:input {:type "text"}] " name"])))
    (is (nil? (genui/sanitize [:input {:type "password"}])))
    (is (nil? (genui/sanitize [:input]))))
  (testing "texts, numbers and keywords stay, anything else is printed"
    (is (= [:p {} "a" 1 "b" "{:x 1}"] (genui/sanitize [:p "a" 1 :b {:x 1}])))))

(deftest actions-test
  (testing ":send on a button sends its text"
    (is (= [:button {:on (send-of "Take 4 shorts")} "4"]
           (genui/sanitize [:button {:send "Take 4 shorts"} "4"]))))
  (testing "{checked} in it makes it the client's button, which fills them in"
    (is (= [:ui/send-checked {:ui/send "Post on: {checked}" :class "go"} "Take these"]
           (genui/sanitize [:button {:send "Post on: {checked}" :class "go" :open "artifacts/x"} "Take these"]))))
  (testing ":open shows the artifact, a project's folder with or without its slash"
    (is (= {:click [[:data/command {:command/kind :artifacts/open
                                    :command/data {:path "shorts"}}]]}
           (:on (second (genui/sanitize [:button {:open "artifacts/shorts"} "Show"])))
           (:on (second (genui/sanitize [:button {:open "/artifacts/shorts/"} "Show"])))))
    (is (= "card/a.mp4" (get-in (genui/sanitize [:button {:open "artifacts/card/a.mp4"} "x"])
                                [1 :on :click 0 1 :command/data :path])))))

(deftest block-test
  (testing "a block that reads is a part of the page"
    (is (= [:div.genui [:p {} "a"]] (genui/block "[:p \"a\"]"))))
  (testing "one that does not, its code with the reason"
    (let [[tag [_ [_ k {:keys [error]}]] code] (genui/block "[:p")]
      (is (= :div.genui.genui-error tag))
      (is (= :genui/rejected k))
      (is (string? error))
      (is (= [:pre [:code "[:p"]] code)))))
