(ns rragent.genui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
    (is (= [:div {}] (genui/sanitize [:div {:onclick "x" :on {:click [[:x]]} :replicant/key "k" :innerHTML "<b>"}]))))
  (testing "a tag with a namespace is no alias of the client"
    (is (= [:span {} "x"] (genui/sanitize [:ui/composer {:ui/send "x"} "x"])))
    (is (= [:span {} "x"] (genui/sanitize [:ui/div "x"])))
    (is (= [:span {} "x"] (genui/sanitize [:ui/button {:send "On: {checked}"} "x"]))))
  (testing "no id and no name outside an input: they would replace globals of the page"
    (is (= [:div.card {} "x"] (genui/sanitize [:div#process.card "x"])))
    (is (= [:div {}] (genui/sanitize [:div#main])))
    (is (= [:img {}] (genui/sanitize [:img {:name "querySelector"}])))
    (is (= [:input {:type "checkbox" :name "pick"}] (genui/sanitize [:input {:type "checkbox" :name "pick"}]))))
  (testing "links and files point at artifacts, uploads, https or mailto"
    (is (= [:a {:href "https://youtu.be/x?t=4" :target "_blank" :rel "noopener noreferrer"} "watch"]
           (genui/sanitize [:a {:href "https://youtu.be/x?t=4"} "watch"])))
    (is (= [:img {:src "/artifacts/card/a.png"}] (genui/sanitize [:img {:src "artifacts/card/a.png"}])))
    (is (= [:video {:src "/uploads/c.mp4" :controls true}] (genui/sanitize [:video {:src "uploads/c.mp4" :controls true}])))
    (is (= [:a {}] (genui/sanitize [:a {:href "javascript:alert(1)"}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "http://x.test/a.png"}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "/etc/passwd"}]))))
  (testing "a style keeps layout, spacing, borders, colors and type"
    (is (= {:display "flex" :flex-wrap "wrap" :gap "10px" :background "#ffb36b" :color "rgb(1, 2, 3)" :width "calc(100% - 4px)"}
           (:style (second (genui/sanitize [:div {:style {:display "flex" :flex-wrap "wrap" :gap "10px" :background "#ffb36b"
                                                          :color "rgb(1, 2, 3)" :width "calc(100% - 4px)"}}]))))))
  (testing "nothing that lies over the page or loads a file"
    (is (= [:div {:style {:opacity 0}}]
           (genui/sanitize [:div {:style {:position "fixed" :inset 0 :z-index 99999 :opacity 0 :transform "scale(9)"}}])))
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "URL(https://x.test/t.gif)"}}])))
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "image-set(\"https://x.test/t.gif\" 1x)"}}])))
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "\\75 rl(https://x.test/t.gif)"}}]))))
  (testing "an input is a checkbox or a radio button, nothing else"
    (is (= [:input {:type "checkbox" :value "a" :checked true}]
           (genui/sanitize [:input {:type "checkbox" :value "a" :checked true}])))
    (is (= [:input {:type "radio" :name "n" :value "b"}]
           (genui/sanitize [:input {:type "radio" :name "n" :value "b"}])))
    (is (= [:label {} nil " name"] (genui/sanitize [:label [:input {:type "text"}] " name"])))
    (is (nil? (genui/sanitize [:input {:type "password"}])))
    (is (nil? (genui/sanitize [:input]))))
  (testing "what is nested too deep goes"
    (let [deep (reduce (fn [inner _] [:div inner]) "bottom" (range 100))]
      (is (not (str/includes? (pr-str (genui/sanitize deep)) "bottom")))))
  (testing "a value nested a thousand deep is printed a few levels deep"
    (let [deep (reduce (fn [inner _] #{inner}) 1 (range 1000))
          [_ _ text] (genui/sanitize [:p deep])]
      (is (string? text))
      (is (< (count text) 100))))
  (testing "texts, numbers and keywords stay, anything else is printed"
    (is (= [:p {} "a" 1 "b" "{:x 1}"] (genui/sanitize [:p "a" 1 :b {:x 1}])))))

(deftest actions-test
  (testing ":send on a button sends its text, which is its title, whatever title the model gave"
    (is (= [:button {:on (send-of "Take 4 shorts") :title "Take 4 shorts"} "4"]
           (genui/sanitize [:button {:send "Take 4 shorts"} "4"])))
    (is (= "Take 4" (:title (second (genui/sanitize [:button {:send "Take 4" :title "Show more"} "4"])))))
    (is (= "On: {checked}" (:title (second (genui/sanitize [:button {:send "On: {checked}" :title "Show more"} "4"])))))
    (is (= "Mine" (:title (second (genui/sanitize [:button {:title "Mine"} "4"])))))
    (testing ", a title is text, never data the page would walk"
      (is (= [:p {} "x"] (genui/sanitize [:p {:title (reduce (fn [inner _] [1 inner]) 1 (range 1500))} "x"])))
      (is (= [:p {} "x"] (genui/sanitize [:p {:title [:i18n :genui/rejected {:error "x"}]} "x"])))
      (is (= [:p {} "x"] (genui/sanitize [:p {:title {:a 1}} "x"])))))
  (testing "a button in a label is a span: the whole label would click it"
    (is (= [:label {:style {:display "block" :height "100vh"}} [:span {} "x"]]
           (genui/sanitize [:label {:style {:display "block" :height "100vh"}} [:button {:send "Do it"} "x"]])))
    (is (= [:label {} [:span {} [:span {} "x"]]]
           (genui/sanitize [:label [:span [:button {:send "On: {checked}"} "x"]]]))))
  (testing ":send and :open only on a button"
    (is (= [:div {}] (genui/sanitize [:div {:send "Do this" :open "artifacts/x"}])))
    (is (= [:span {}] (genui/sanitize [:script {:send "Do this"}]))))
  (testing "{checked} in it makes it the client's button, which fills them in"
    (is (= [:ui/send-checked {:ui/send "Post on: {checked}" :class "go" :title "Post on: {checked}"} "Take these"]
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
  (testing "the names of its inputs are its own: radio buttons of two answers are two groups"
    (let [name-in (fn [text] (get-in (genui/block text) [1 1 :name]))
          a (name-in "[:input {:type \"radio\" :name \"choice\" :value \"a\"}]")
          b (name-in "[:input {:type \"radio\" :name \"choice\" :value \"b\"}]")]
      (is (str/ends-with? a "-choice"))
      (is (str/ends-with? b "-choice"))
      (is (not= a b))))
  (testing "one that does not, its code with the reason"
    (let [[tag [_ [_ k {:keys [error]}]] code] (genui/block "[:p")]
      (is (= :div.genui.genui-error tag))
      (is (= :genui/rejected k))
      (is (string? error))
      (is (= [:pre [:code "[:p"]] code)))))
