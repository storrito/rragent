(ns rragent.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.i18n :as i18n]
            [rragent.rframes.transit :as transit]
            [rragent.view :as view]))

(defn- user [id text]
  {:id id :kind "pi.user" :model [{:role "user" :content text :timestamp 1}]})

(defn- assistant [id content & [stop]]
  {:id id :kind "pi.assistant" :model [{:role "assistant" :content content :stopReason (or stop "stop") :timestamp id}]})

(defn- tool-result [id call-id text error?]
  {:id id :kind "pi.tool-result" :model [{:role "toolResult" :toolCallId call-id :content [{:type "text" :text text}] :isError error?}]})

(def conversation
  {:entries [{:id 1 :kind "pi.system" :model [{:role "system" :content ""}]}
             (user 2 "Capital of France?")
             (assistant 3 [{:type "toolCall" :id "c1" :name "fetch" :arguments {:url "https://x.test"}}] "toolUse")
             (tool-result 4 "c1" "HTTP 200" false)
             (assistant 5 [{:type "text" :text "Paris."}])
             {:id 6 :kind "rragent.hook" :data {:name "deploy"} :model [{:role "user" :content "Hook deploy was called."}]}]
   :live {:run {:taskId "t"}
          :generation {:message {:role "assistant" :content [{:type "text" :text "Looking"}] :stopReason "stop" :timestamp 99}}}
   :inbox {:items [{:id "s1" :mode "followUp" :content "And Spain?"}]}})

(deftest items-test
  (let [items (view/items conversation)
        kinds (map (juxt :kind :id) items)]
    (is (= [[:user 2] [:assistant 3] [:tool "c1"] [:assistant 5] [:user 6] [:assistant "partial"] [:user "s1"]] kinds))
    (testing "a tool call carries its result"
      (let [tool (nth items 2)]
        (is (= "fetch" (:name tool)))
        (is (true? (:done? tool)))
        (is (= "HTTP 200" (:result tool)))
        (is (false? (:error? tool)))))
    (testing "a hook's input is labelled by its hook"
      (is (= [:i18n :chat/hook {:name "deploy"}] (:label (nth items 4)))))
    (testing "the partial streams, the queued input is pending"
      (is (true? (:streaming? (nth items 5))))
      (is (= "Looking" (:text (nth items 5))))
      (is (true? (:pending? (nth items 6)))))))

(deftest partial-test
  (testing "a committed answer is no partial any more"
    (let [c (-> conversation
                (assoc-in [:live :generation :message :timestamp] 5))]
      (is (not-any? #(= "partial" (:id %)) (view/items c)))))
  (testing "a running tool call shows what it put out so far"
    (let [c (-> conversation
                (update :entries #(vec (remove (comp #{4 5} :id) %)))
                (assoc :live {:run {:taskId "t"}
                              :tools [{:callId "c1" :name "fetch" :status "running" :output "HTTP 2"}]}))
          tool (some #(when (= :tool (:kind %)) %) (view/items c))]
      (is (false? (:done? tool)))
      (is (= "HTTP 2" (:result tool))))))

(defn- strings
  [hiccup]
  (filter string? (tree-seq coll? seq hiccup)))

(defn- nodes
  [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- find-tag
  [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(deftest page-test
  (let [page (view/page {:agent {:name "watcher" :model "faux-1"}
                         :conversation conversation
                         :locale :en})
        translated (i18n/translate page :en)
        text (set (strings translated))]
    (testing "the page is data a frame can carry: no functions, and transit reads it back"
      (is (not-any? fn? (tree-seq coll? seq translated)))
      (is (= translated (transit/read-str (transit/write-str translated)))))
    (testing "what is on it"
      (is (contains? text "Capital of France?"))
      (is (contains? text "Paris."))
      (is (contains? text "Fetched a page"))
      (is (contains? text "Hook deploy"))
      (is (contains? text "What the agent makes for you appears here."))
      (is (contains? text "Stop") "a run goes")
      (is (not-any? #(and (string? %) (str/includes? % ":i18n")) text) "every text is translated"))
    (testing "an idle conversation with nothing in it"
      (let [empty (i18n/translate (view/page {:agent {} :conversation nil :locale :de}) :de)
            text (set (strings empty))]
        (is (contains? text "Noch nichts hier. Schreib die erste Nachricht."))
        (is (not (contains? text "Stopp")))))
    (testing "an empty conversation of a skill opens with its welcome and placeholder"
      (let [onboarding {:title "Kinetic Shorts" :ask "Füge den Link ein." :placeholder "YouTube-Link einfügen"}
            page (i18n/translate (view/page {:agent {} :conversation nil :locale :de :onboarding onboarding}) :de)
            text (set (strings page))]
        (is (contains? text "Kinetic Shorts"))
        (is (contains? text "Füge den Link ein."))
        (is (not (contains? text "Noch nichts hier. Schreib die erste Nachricht.")))
        (is (= "YouTube-Link einfügen" (:placeholder (second (find-tag page :ui/composer)))))))
    (testing "once the conversation has started, the welcome and the skill's placeholder go"
      (let [onboarding {:title "Kinetic Shorts" :ask "Paste the link." :placeholder "Paste a YouTube link"}
            page (i18n/translate (view/page {:agent {} :conversation conversation :locale :en :onboarding onboarding}) :en)
            text (set (strings page))]
        (is (not (contains? text "Paste the link.")))
        (is (not= "Paste a YouTube link" (:placeholder (second (find-tag page :ui/composer)))))))))

(deftest attachments-test
  (let [text "What is in it?\nimage: uploads/0a0b.png\nvideo: uploads/ab12.mp4\naudio: uploads/ff00.wav"
        c {:entries [(user 2 [{:type "text" :text text} {:type "image" :data "iVBOR" :mimeType "image/png"}])]
           :inbox {:items [{:id "s1" :mode "followUp" :content "audio: uploads/ee11.mp3"}]}}
        items (view/items c)]
    (testing "the item carries the text without the lines and the files as media"
      (is (= "What is in it?" (:text (first items))))
      (is (= [{:kind :image :name "0a0b.png"} {:kind :video :name "ab12.mp4"} {:kind :audio :name "ff00.wav"}]
             (:media (first items))))
      (is (= "" (:text (second items))) "a queued input too")
      (is (= [{:kind :audio :name "ee11.mp3"}] (:media (second items)))))
    (let [page (i18n/translate (view/page {:agent {} :conversation c :locale :en}) :en)
          img (find-tag page :img)
          clip (find-tag page :a.clip)]
      (testing "a picture is a thumbnail, a clip its name, each a link to the file"
        (is (= "/uploads/0a0b.png" (:src (second img))))
        (is (= "Attached picture" (:alt (second img))))
        (is (= "/uploads/ab12.mp4" (:href (second clip))))
        (is (some #{"ab12.mp4"} (strings clip))))
      (testing "the base64 of the image never reaches the page"
        (is (not-any? #(and (string? %) (str/includes? % "iVBOR")) (strings page))))
      (testing "the composer's labels are translated like the rest"
        (let [composer (find-tag page :ui/composer)]
          (is (= "Remove" (:ui/remove-title (second composer))))
          (is (= "Add files" (:ui/drop-title (second composer))))
          (is (= "/upload" (:ui/upload-url (second composer))))
          (is (not-any? #(and (vector? %) (= :i18n (first %))) (nodes page)) "no reference is left anywhere, attributes included"))))))

(deftest streaming-hiccup-test
  (let [c {:entries [(user 2 "Where?")]
           :live {:run {:taskId "t"}
                  :generation {:message {:role "assistant" :content [{:type "text" :text "Pick one:\n\n```hiccup\n[:div [:button {:send \"A\"}"}] :timestamp 99}}}}
        page (i18n/translate (view/page {:agent {} :conversation c :locale :de}) :de)
        text (set (strings page))]
    (testing "a ```hiccup block the model is still writing shows a placeholder, not its code"
      (is (some #(and (vector? %) (= :div.genui.genui-pending (first %))) (nodes page)))
      (is (not-any? #(and (string? %) (str/includes? % "[:button")) (strings page))))))

(deftest markdown-test
  (let [c {:entries [(user 2 "Keep **this** as it is")
                     (assistant 3 [{:type "text" :text "**Bold** and\n\n- one\n- two\n\n```js\nlet x = 1;\n```"}])]
           :live {:run {:taskId "t"}
                  :generation {:message {:role "assistant" :content [{:type "text" :text "Looking *hard*"}] :stopReason "stop" :timestamp 99}}}}
        page (i18n/translate (view/page {:agent {} :conversation c :locale :en}) :en)
        answers (filter #(and (vector? %) (= :div.markdown (first %))) (nodes page))
        answer (first answers)]
    (testing "the person's message stays plain text"
      (is (= "Keep **this** as it is" (last (find-tag page :p.text)))))
    (testing "the answer is rendered from its markdown"
      (is (= [:strong "Bold"] (find-tag answer :strong)))
      (is (= 2 (count (filter #(and (vector? %) (= :li (first %))) (nodes answer)))))
      (is (= "language-js" (:class (second (find-tag answer :code)))))
      (is (some? (find-tag answer :pre)))
      (is (not-any? #(str/includes? % "**") (strings answer))))
    (testing "the streaming partial too"
      (is (= [:em "hard"] (find-tag (second answers) :em))))
    (testing "the page stays data a frame can carry"
      (is (= page (transit/read-str (transit/write-str page)))))))
