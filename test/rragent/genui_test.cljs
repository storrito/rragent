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
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "\\75 rl(https://x.test/t.gif)"}}])))
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "linear-gradient(#000, #fff), url(https://x.test/t.gif)"}}]))
        "a gradient does not carry a file in")
    (is (= [:div {}] (genui/sanitize [:div {:style {:background "linear-gradient(var(--x), red)"}}]))
        "nor a function of its own inside"))
  (testing "a background may be a gradient, a skill's backgrounds are"
    (is (= {:background "radial-gradient(circle at 25% 25%, #ffcf8a 0 8%, transparent 9%), linear-gradient(#2a1208, #120806)"
            :color "#fff"}
           (:style (second (genui/sanitize [:div {:style {:background "radial-gradient(circle at 25% 25%, #ffcf8a 0 8%, transparent 9%), linear-gradient(#2a1208, #120806)"
                                                          :color "#fff"}}])))))
    (is (= {:background "repeating-linear-gradient(90deg, rgba(0,240,255,.35) 0 1px, transparent 1px 22px)"}
           (:style (second (genui/sanitize [:div {:style {:background "repeating-linear-gradient(90deg, rgba(0,240,255,.35) 0 1px, transparent 1px 22px)"}}]))))))
  (testing "tiles of the same width in a grid"
    (is (= {:display "grid" :grid-template-columns "repeat(auto-fill, minmax(180px, 1fr))" :gap "8px"}
           (:style (second (genui/sanitize [:div {:style {:display "grid" :grid-template-columns "repeat(auto-fill, minmax(180px, 1fr))" :gap "8px"}}]))))))
  (testing "a color goes with a background that did not get through: dark text was meant for a light card"
    (is (= {:padding "12px"}
           (:style (second (genui/sanitize [:div {:style {:background "url(https://x.test/paper.png)" :color "#222" :padding "12px"}}])))))
    (is (= {:padding "12px"}
           (:style (second (genui/sanitize [:div {:style {:background-image "linear-gradient(#111, #222)" :color "#fff" :padding "12px"}}]))))
        "a background-image is a background too"))
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
  (testing ":send and :open take text only, also in a list: what is lazy would run after the try"
    (let [deep (reduce (fn [inner _] [1 inner]) 1 (range 1500))]
      (is (= [:button {} "x"] (genui/sanitize [:button {:send deep} "x"])))
      (is (= [:button {} "x"] (genui/sanitize [:button {:open {:a deep}} "x"])))
      (let [[_ _ children] (genui/sanitize [:p (list [:button {:send deep} "x"])])]
        (is (realized? children))
        (is (= [[:button {} "x"]] children)))))
  (testing "a button's style keeps its colors, corners, type and size, no padding or border width"
    (is (= {:background "#ffb36b" :width "34px" :height "34px" :border-radius "50%"}
           (:style (second (genui/sanitize [:button {:send "x" :style {:background "#ffb36b" :width "34px" :height "34px" :border-radius "50%"
                                                                       :padding "40vh 0" :border "40vh solid transparent" :min-height "100vh"
                                                                       :box-sizing "content-box"}} "x"]))))))
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
                                [1 :on :click 0 1 :command/data :path]))))
  (testing "under a bot's page its files are served there and its commands name it"
    (let [opts {:base "/bot/ab12cd34" :bot "ab12cd34"}]
      (is (= [:img {:src "/bot/ab12cd34/artifacts/card/a.png"}] (genui/sanitize [:img {:src "artifacts/card/a.png"}] opts)))
      (is (= [:video {:src "/bot/ab12cd34/uploads/c.mp4" :controls true}] (genui/sanitize [:video {:src "/uploads/c.mp4" :controls true}] opts)))
      (is (= {:bot "ab12cd34" :text "Do this"}
             (get-in (genui/sanitize [:button {:send "Do this"} "x"] opts) [1 :on :click 0 1 :command/data])))
      (is (= {:bot "ab12cd34" :path "shorts"}
             (get-in (genui/sanitize [:button {:open "artifacts/shorts/"} "x"] opts) [1 :on :click 0 1 :command/data])
             (get-in (genui/sanitize [:button {:open "/bot/ab12cd34/artifacts/shorts"} "x"] opts) [1 :on :click 0 1 :command/data])))
      (is (= "ab12cd34" (get-in (genui/sanitize [:button {:send "On: {checked}"} "x"] opts) [1 :ui/bot])))
      (is (= [:img {}] (genui/sanitize [:img {:src "/bot/other123/artifacts/a.png"}] opts)) "another bot's page is no file of this one"))))

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

(deftest answered-test
  (let [hiccup [:div
                [:label [:input {:type "checkbox" :value "Reel" :checked true}] " Reel"]
                [:button {:send "Post on: {checked}"} "Take these"]
                [:button {:send "Not now"} "Later"]
                [:button {:open "artifacts/shorts"} "Show the shorts"]]
        [_ _ [_ _ input] checked later open] (genui/sanitize hiccup {:answered? true})]
    (testing "once the person has answered, the choices are made: buttons that send and inputs are disabled"
      (is (= [:input {:type "checkbox" :value "Reel" :checked true :disabled true}] input))
      (is (= [:button {:title "Post on: {checked}" :disabled true} "Take these"] checked)
          "no alias that sends the ticked values")
      (is (= [:button {:title "Not now" :disabled true} "Later"] later)))
    (testing "a button that opens an artifact still does"
      (is (some? (:on (second open))))
      (is (not (:disabled (second open))))))
  (testing "the block says so"
    (is (= :div.genui.answered (first (genui/block "[:button {:send \"A\"} \"A\"]" {:answered? true}))))
    (is (= :div.genui (first (genui/block "[:button {:send \"A\"} \"A\"]"))))))

(deftest secret-test
  (testing "[:secret …] is the client's password field, for the bot of the block"
    (let [[_ part] (genui/block "[:div [:p \"Your token:\"] [:secret {:name \"GH_TOKEN\" :label \"GitHub token\"}]]" {:bot "7"})
          field (some #(when (and (vector? %) (= :ui/secret-field (first %))) %) (tree-seq coll? seq part))]
      (is (= {:ui/name "GH_TOKEN" :ui/label "GitHub token" :ui/bot "7"}
             (select-keys (second field) [:ui/name :ui/label :ui/bot])))))
  (testing "a name ${NAME} could not name is no field"
    (is (not (str/includes? (pr-str (genui/block "[:secret {:name \"a b\"}]")) ":ui/secret-field"))))
  (testing "it stays usable once the message is answered, to replace the secret"
    (is (str/includes? (pr-str (genui/block "[:secret {:name \"GH_TOKEN\"}]" {:answered? true})) ":ui/secret-field"))))
