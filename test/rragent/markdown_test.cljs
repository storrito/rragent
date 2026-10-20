(ns rragent.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.markdown :as markdown]
            [rragent.rframes.transit :as transit]))

(defn- nodes
  "The elements and texts of the hiccup, the outer ones first; the
   attributes of an element are not walked into."
  [hiccup]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            (seq hiccup)))

(defn- elements
  [hiccup]
  (filter vector? (nodes hiccup)))

(defn- tags
  [hiccup]
  (set (map first (elements hiccup))))

(defn- strings
  [hiccup]
  (filter string? (nodes hiccup)))

(defn- find-tag
  [hiccup tag]
  (some #(when (= tag (first %)) %) (elements hiccup)))

(defn- attribute
  [hiccup tag k]
  (get (second (find-tag hiccup tag)) k))

(deftest blocks-test
  (testing "paragraphs"
    (is (= [[:p "One"] [:p "Two"]] (markdown/parse "One\n\nTwo"))))
  (testing "headings"
    (is (= [[:h1 "Title"] [:h3 "Three"] [:h2 "Two"]] (markdown/parse "# Title\n\n### Three\n\nTwo\n---"))))
  (testing "a horizontal rule"
    (is (= [[:p "a"] [:hr] [:p "b"]] (markdown/parse "a\n\n***\n\nb"))))
  (testing "a blockquote, with a paragraph of its own"
    (is (= [[:blockquote [:p "quoted" [:br] "twice"]]] (markdown/parse "> quoted\n> twice"))))
  (testing "nothing"
    (is (= [] (markdown/parse "")))
    (is (= [] (markdown/parse nil)))))

(deftest inline-test
  (testing "emphasis, strong, strikethrough and code"
    (is (= [[:p [:em "a"] " " [:strong "b"] " " [:s "c"] " " [:code "d"]]]
           (markdown/parse "*a* **b** ~~c~~ `d`")))
    (is (= [[:p [:em [:strong "both"]]]] (markdown/parse "***both***"))))
  (testing "a hard line break, and a single newline as one too"
    (is (= [[:p "a" [:br] "b"]] (markdown/parse "a  \nb")))
    (is (= [[:p "a" [:br] "b"]] (markdown/parse "a\\\nb")))
    (is (= [[:p "a" [:br] "b"]] (markdown/parse "a\nb"))))
  (testing "an escaped character and an entity are the character"
    (is (= [[:p "*not emphasis* & <"]] (markdown/parse "\\*not emphasis\\* &amp; &lt;"))))
  (testing "the typographer is off, the text stays the model's"
    (is (= [[:p "\"quoted\" -- (c)"]] (markdown/parse "\"quoted\" -- (c)")))))

(deftest code-test
  (testing "a fence with its language as a class"
    (is (= [[:pre [:code {:class "language-clojure"} "(+ 1 2)\n"]]]
           (markdown/parse "```clojure\n(+ 1 2)\n```"))))
  (testing "a fence without one, and a tilde fence"
    (is (= [[:pre [:code "x\n"]]] (markdown/parse "```\nx\n```")))
    (is (= [[:pre [:code "y\n"]]] (markdown/parse "~~~\ny\n~~~"))))
  (testing "an indented block"
    (is (= [[:pre [:code "code\n  more\n"]]] (markdown/parse "    code\n      more"))))
  (testing "code is no markdown and no html"
    (is (= [[:pre [:code "**x** <b>y</b>\n"]]] (markdown/parse "```\n**x** <b>y</b>\n```")))))

(deftest lists-test
  (testing "unordered and ordered"
    (is (= [[:ul [:li "a"] [:li "b"]]] (markdown/parse "- a\n- b")))
    (is (= [[:ol [:li "a"] [:li "b"]]] (markdown/parse "1. a\n2. b"))))
  (testing "an ordered list that starts later keeps its start"
    (is (= [[:ol {:start 3} [:li "a"] [:li "b"]]] (markdown/parse "3. a\n4. b"))))
  (testing "nested"
    (is (= [[:ul [:li "a" [:ul [:li "b"] [:li "c"]]] [:li "d"]]]
           (markdown/parse "- a\n  - b\n  - c\n- d"))))
  (testing "loose items keep their paragraphs"
    (is (= [[:ul [:li [:p "a"]] [:li [:p "b"]]]] (markdown/parse "- a\n\n- b"))))
  (testing "task items get a checkbox that cannot be changed"
    (is (= [[:ul
             [:li {:class "task"} [:input {:type "checkbox" :disabled true}] "todo"]
             [:li {:class "task"} [:input {:type "checkbox" :disabled true :checked true}] "done " [:em "now"]]]]
           (markdown/parse "- [ ] todo\n- [x] done *now*"))))
  (testing "a mark elsewhere is text"
    (is (= [[:p "[ ] not a task"]] (markdown/parse "[ ] not a task")))))

(deftest links-test
  (testing "a link opens in a new tab without a referrer"
    (is (= [[:p [:a {:href "https://x.test/" :target "_blank" :rel "noopener noreferrer"} "x"]]]
           (markdown/parse "[x](https://x.test/)"))))
  (testing "its title and its markup"
    (let [hiccup (markdown/parse "[**b**](https://x.test/ \"T\")")]
      (is (= "T" (attribute hiccup :a :title)))
      (is (= [:strong "b"] (last (find-tag hiccup :a))))))
  (testing "relative links stay as they are"
    (is (= "/uploads/x.mp4" (attribute (markdown/parse "[clip](/uploads/x.mp4)") :a :href)))
    (is (= "uploads/x.mp4" (attribute (markdown/parse "[clip](uploads/x.mp4)") :a :href))))
  (testing "a bare URL is a link"
    (is (= [[:p "see " [:a {:href "https://x.test/y" :target "_blank" :rel "noopener noreferrer"} "https://x.test/y"]]]
           (markdown/parse "see https://x.test/y"))))
  (testing "an unsafe scheme is no link, the text is kept"
    (let [hiccup (markdown/parse "[x](javascript:alert(1))")]
      (is (not (contains? (tags hiccup) :a)))
      (is (some #(str/includes? % "javascript:alert(1)") (strings hiccup))))))

(deftest images-test
  (testing "an upload the agent names is served from /uploads"
    (is (= [[:p [:img {:src "/uploads/a.png" :alt "a dot"}]]] (markdown/parse "![a dot](uploads/a.png)")))
    (is (= "/uploads/b.png" (attribute (markdown/parse "![b](/uploads/b.png)") :img :src))))
  (testing "any other src stays, with the title"
    (is (= [[:p [:img {:src "https://x.test/a.png" :alt "" :title "T"}]]]
           (markdown/parse "![](https://x.test/a.png \"T\")"))))
  (testing "the alt is the text of its markup"
    (is (= "a b" (attribute (markdown/parse "![a *b*](x.png)") :img :alt)))))

(deftest tables-test
  (is (= [[:table
           [:thead [:tr [:th "a"] [:th {:style {:text-align "right"}} "b"]]]
           [:tbody
            [:tr [:td "1"] [:td {:style {:text-align "right"}} "2"]]
            [:tr [:td [:code "3"]] [:td {:style {:text-align "right"}} "4"]]]]]
         (markdown/parse "| a | b |\n|---|--:|\n| 1 | 2 |\n| `3` | 4 |"))))

(deftest html-test
  (testing "a tag in the text is text"
    (is (= [[:p "<b>bold</b>"]] (markdown/parse "<b>bold</b>"))))
  (testing "a script or an image tag is text, no element of the page"
    (let [hiccup (markdown/parse "<script>alert(1)</script>\n\ntext <img src=x onerror=alert(1)>")]
      (is (not-any? #{:script :img} (tags hiccup)))
      (is (some #(str/includes? % "<script>alert(1)</script>") (strings hiccup)))
      (is (some #(str/includes? % "<img src=x onerror=alert(1)>") (strings hiccup)))))
  (testing "a block of html is text as well"
    (let [hiccup (markdown/parse "<div class=\"x\">\nin a div\n</div>")]
      (is (= #{:p :br} (tags hiccup)))
      (is (some #(= "<div class=\"x\">" %) (strings hiccup))))))

(deftest unknown-token-test
  (testing "a token the walk does not know is its text"
    (is (= ["kept"] (markdown/from-tokens #js [#js {:type "weird" :nesting 0 :content "kept"}]))))
  (testing "an unknown element keeps its children under its tag"
    (is (= [[:kbd "k"]]
           (markdown/from-tokens #js [#js {:type "kbd_open" :nesting 1 :tag "kbd"}
                                      #js {:type "text" :nesting 0 :content "k"}
                                      #js {:type "kbd_close" :nesting -1 :tag "kbd"}]))))
  (testing "an element left open is closed at the end"
    (is (= [[:em "x"]]
           (markdown/from-tokens #js [#js {:type "em_open" :nesting 1 :tag "em"}
                                      #js {:type "text" :nesting 0 :content "x"}])))))

(def sample
  "# Title\n\nSome **bold** text with a [link](https://x.test/) and `code`.\n\n- one\n- two\n  - [x] nested task\n\n> a quote\n\n```js\nlet x = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n![pic](uploads/p.png)\n\n---\n\nhttps://x.test/bare")

(deftest hiccup-test
  (testing "the hiccup is data a frame carries: no functions, and transit reads it back"
    (let [hiccup (markdown/hiccup sample)]
      (is (not-any? fn? (tree-seq coll? seq hiccup)))
      (is (= hiccup (transit/read-str (transit/write-str hiccup))))
      (is (= #{:h1 :p :strong :a :code :ul :li :input :blockquote :pre :table :thead :tbody :tr :th :td :img :hr}
             (tags hiccup)))))
  (testing "the same text comes from the cache"
    (is (identical? (markdown/hiccup sample) (markdown/hiccup sample)))
    (is (= (markdown/parse sample) (markdown/hiccup sample)))))

(deftest hiccup-block-test
  (testing "a closed ```hiccup fence is a part of the page"
    (let [hiccup (markdown/parse "Which?\n\n```hiccup\n[:button {:send \"This\"} \"This one\"]\n```\n\nOr type.")]
      (is (= [:p "Which?"] (first hiccup)))
      (is (= [:div.genui [:button {:on {:click [[:data/command {:command/kind :chat/send
                                                                 :command/data {:text "This"}}]]}
                                   :title "This"}
                          "This one"]]
             (second hiccup)))
      (is (= [:p "Or type."] (nth hiccup 2)))))
  (testing "one the model is still writing is a placeholder until its fence closes"
    (is (= [[:div.genui.genui-pending {:aria-busy "true"} [:span.bar] [:span.bar]]]
           (markdown/parse "```hiccup\n[:button {:send \"Th\n" {:streaming? true})))
    (is (= :div.genui (ffirst (markdown/parse "```hiccup\n[:p \"a\"]\n```" {:streaming? true}))) "closed, it is the block")
    (is (= :pre (ffirst (markdown/parse "```clojure\n(+ 1\n" {:streaming? true}))) "other code stays code"))
  (testing "in an answer that ended without the closing fence it stays code"
    (is (= [[:pre [:code {:class "language-hiccup"} "[:button {:send \"Th\n"]]]
           (markdown/parse "```hiccup\n[:button {:send \"Th\n"))))
  (testing "an open one without a newline at its end is still being written"
    (is (= :pre (ffirst (markdown/parse "```hiccup\n[:p \"a\"]")))))
  (testing "a longer closing fence closes it"
    (is (= :div.genui (ffirst (markdown/parse "```hiccup\n[:p \"a\"]\n````")))))
  (testing "where markdown-it closes a fence: in a list, a blockquote, after CR line ends"
    (is (= [:li [:p "a"] [:div.genui [:p {} "x"]]]
           (second (first (markdown/parse "- a\n\n  ```hiccup\n  [:p \"x\"]\n  ```"))))
        "in a list")
    (is (= [:blockquote [:div.genui [:p {} "x"]]]
           (first (markdown/parse "> ```hiccup\n> [:p \"x\"]\n> ```"))))
    (is (= [[:div.genui [:p {} "x"]]] (markdown/parse "```hiccup\r\n[:p \"x\"]\r\n```")))
    (is (= [[:div.genui [:p {} "x"]]] (markdown/parse "```hiccup\r[:p \"x\"]\r```"))))
  (testing "a block that does not read is shown as code with what went wrong"
    (let [[block] (markdown/parse "```hiccup\n[:p \"a\"\n```")]
      (is (= :div.genui.genui-error (first block)))
      (is (= :genui/rejected (second (second (second block)))))
      (is (= [:pre [:code "[:p \"a\"\n"]] (nth block 2)))))
  (testing "the part of the page is data a frame carries"
    (let [hiccup (markdown/parse "```hiccup\n[:div [:input {:type \"checkbox\" :value \"a\"}] [:button {:send \"On: {checked}\"} \"Go\"]]\n```")]
      (is (not-any? fn? (tree-seq coll? seq hiccup)))
      (is (= hiccup (transit/read-str (transit/write-str hiccup)))))))
