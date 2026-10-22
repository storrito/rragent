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
      (is (= {:bot nil} (get-in (second (find-tag page :ui/composer)) [:ui/stop 0 1 :command/data])) "Stop is the composer's, with its actions")
      (is (not-any? #(and (string? %) (str/includes? % ":i18n")) text) "every text is translated"))
    (testing "an idle conversation with nothing in it"
      (let [empty (i18n/translate (view/page {:agent {} :conversation nil :locale :de}) :de)
            text (set (strings empty))]
        (is (contains? text "Noch nichts hier. Schreib die erste Nachricht."))
        (is (nil? (:ui/stop (second (find-tag empty :ui/composer)))) "no Stop while nothing runs")))
    (testing "an empty conversation of a skill opens with its welcome and placeholder"
      (let [onboarding {:title "Kinetic Shorts" :ask "Füge den Link ein." :placeholder "YouTube-Link einfügen"}
            page (i18n/translate (view/page {:agent {} :conversation nil :locale :de :onboarding onboarding}) :de)
            text (set (strings page))]
        (is (contains? text "Kinetic Shorts"))
        (is (contains? text "Füge den Link ein."))
        (is (not (contains? text "Noch nichts hier. Schreib die erste Nachricht.")))
        (is (= "YouTube-Link einfügen" (:placeholder (second (find-tag page :ui/composer)))))))
    (testing "the running version in the header, a newer release as a banner above the chat"
      (let [page (i18n/translate (view/page {:agent {} :conversation nil :locale :en
                                             :update {:current "0.1.2" :version "0.1.3" :notes "Subagents" :state :known :auto? true}})
                                 :en)
            text (set (strings page))]
        (is (= "v0.1.2" (last (find-tag page :button.version))))
        (is (= [[:data/command {:command/kind :update/check}]] (get-in (second (find-tag page :button.version)) [:on :click])))
        (is (= "Look for a newer rragent" (:title (second (find-tag page :button.version)))))
        (is (contains? text "rragent 0.1.3 is available."))
        (is (contains? text "Subagents"))
        (is (contains? text "Update"))
        (is (contains? text "It installs itself once the agent is idle; the page comes back by itself."))
        (is (< (.indexOf (vec (nodes page)) (find-tag page :div.banner.update))
               (.indexOf (vec (nodes page)) (find-tag page :section.feed)))
            "the banner stands above the feed")
        (is (= page (transit/read-str (transit/write-str page))))))
    (testing "the header's button switches the theme, its titles translated"
      (let [page (i18n/translate (view/page {:agent {} :conversation nil :locale :de}) :de)]
        (is (= {:ui/light-title "Helles Design" :ui/dark-title "Dunkles Design"}
               (second (find-tag page :ui/theme-toggle))))))
    (testing "without the version nothing of it is on the page"
      (let [page (i18n/translate (view/page {:agent {} :conversation nil :locale :en}) :en)]
        (is (nil? (find-tag page :button.version)))
        (is (nil? (find-tag page :div.banner.update)))))
    (testing "once the conversation has started, the welcome and the skill's placeholder go"
      (let [onboarding {:title "Kinetic Shorts" :ask "Paste the link." :placeholder "Paste a YouTube link"}
            page (i18n/translate (view/page {:agent {} :conversation conversation :locale :en :onboarding onboarding}) :en)
            text (set (strings page))]
        (is (not (contains? text "Paste the link.")))
        (is (not= "Paste a YouTube link" (:placeholder (second (find-tag page :ui/composer)))))))))

(deftest sidebar-test
  (let [bots [{:id "aa11bb22" :name "Assistant" :label [:i18n :bot/new-at {:time "14:16"}] :state :idle}
              {:id "cc33dd44" :name "Counter" :state :running}
              {:id "ee55ff66" :name "Watcher" :state :asleep}]
        page (fn [opts]
               (i18n/translate (view/page (merge {:bots bots
                                                  :bot {:id "cc33dd44" :name "Counter" :model "faux-1"}
                                                  :conversation conversation
                                                  :locale :en}
                                                 opts))
                               :en))
        rows (fn [page] (filter #(and (vector? %) (= :li (first %))) (nodes page)))]
    (testing "the bots in the sidebar, each a link to its page with its state, the one shown marked"
      (let [p (page {})
            text (set (strings p))]
        (is (= ["/bot/aa11bb22" "/bot/cc33dd44" "/bot/ee55ff66"]
               (map #(:href (second (find-tag % :a.row))) (rows p))))
        (is (= ["aa11bb22" "cc33dd44" "ee55ff66"] (map #(:data-bot (second (find-tag % :a.row))) (rows p))))
        (is (= [["idle"] ["running" "current"] ["asleep"]] (map #(:class (second %)) (rows p))))
        (is (contains? text "New bot · 14:16") "a bot without a name of its own, by its label")
        (is (not (contains? text "Assistant")))
        (is (contains? text "running"))
        (is (contains? text "asleep"))
        (is (= {:command/kind :bot/create :command/data {:tab :ui/tabid}}
               (get-in (second (find-tag p :button.new)) [:on :click 0 1]))
            "New bot asks for nothing: one click")
        (is (= "/bot/cc33dd44" (:ui/path (second (find-tag p :ui/url)))))
        (is (= "Counter" (last (find-tag p :h1))) "the header names the bot shown")
        (is (= 3 (count (filter #(and (vector? %) (= :details.actions (first %))) (nodes p)))) "every row has its menu")
        (is (= 3 (count (filter #{"Delete"} (strings p)))) "delete in every row's menu, one per bot")))
    (testing "the bot's files and commands are under its page"
      (let [p (page {:artifacts {:artifacts/list [{:path "card.html" :kind :page :title "Card" :mtime 3}]
                                 :artifacts/current "card.html"}})
            composer (second (find-tag p :ui/composer))]
        (is (= "/bot/cc33dd44/artifacts/card.html" (:ui/src (second (find-tag p :ui/canvas)))))
        (is (= "/bot/cc33dd44/upload" (:ui/upload-url composer)))
        (is (= "/bot/cc33dd44/dictation" (:ui/dictation-url composer)))
        (is (= "/bot/cc33dd44/record" (:ui/record-url composer)) "a screencast's pieces go under the bot too")
        (is (= "Record a screencast" (:title (:ui/recorder (second (find-tag (i18n/translate p :en) :ui/composer))))) "the panel's words are translated")
        (is (= [:draft "cc33dd44"] (:ui/store-key composer)) "the draft is the bot's")
        (is (= {:bot "cc33dd44" :path "card.html"}
               (get-in (second (find-tag p :button.row)) [:on :click 0 1 :command/data])) "a row of the list opens its file under the bot")))
    (testing "on a phone the chat and the artifacts are tabs, once there are artifacts"
      (let [with (page {:artifacts {:artifacts/list [{:path "card.html" :kind :page :title "Card" :mtime 3}]
                                    :artifacts/current "card.html"}})
            without (page {})]
        (is (= ["Chat" "Artifacts"] (map last (filter #(and (vector? %) (= :label (first %))) (nodes (find-tag with :nav.views))))))
        (is (= [:input#view-chat.toggle :input#view-made.toggle]
               (map first (filter #(and (vector? %) (#{:input#view-chat.toggle :input#view-made.toggle} (first %))) (nodes with)))))
        (is (= ["showing"] (:class (second (find-tag with :section.artifacts)))))
        (is (nil? (find-tag without :nav.views)) "no tabs without artifacts")
        (is (= ["empty"] (:class (second (find-tag without :section.artifacts)))) "the pane says it is empty, a phone hides it")))
    (testing "a delete armed asks in the row, a rename is a field, a note says the bot of the URL is gone"
      (let [p (page {:bots [(assoc (nth bots 0) :deleting? true) (nth bots 1) (assoc (nth bots 2) :renaming? true)]
                     :note [:i18n :bot/missing]})
            text (set (strings p))]
        (is (contains? text "Delete this bot? Its folder goes to the trash."))
        (is (= "Watcher" (:value (second (find-tag p :input.name)))) "the field holds the definition's name")
        (is (= {:id "ee55ff66" :name :event/target.value}
               (get-in (second (find-tag p :input.name)) [:on :change 0 1 :command/data])))
        (is (contains? text "That bot is not here any more; this is another one."))))
    (testing "the last bot of the machine cannot be deleted from its row"
      (let [p (page {:bots [(first bots)] :bot {:id "aa11bb22" :name "Assistant"}})]
        (is (not-any? #{"Delete"} (strings p)))
        (is (some #{"Rename"} (strings p)))))
    (testing "the page stays data a frame can carry"
      (let [p (page {})]
        (is (= p (transit/read-str (transit/write-str p))))))))

(deftest artifact-card-test
  (let [made (fn [name arguments result]
               {:entries [(assistant 1 [{:type "toolCall" :id "c1" :name name :arguments arguments}] "toolUse")
                          (tool-result 2 "c1" result false)]})
        card (fn [conversation]
               (find-tag (view/page {:bot {:id "b1"} :conversation conversation :locale :en}) :a.artifact-card))]
    (testing "a file written under artifacts/ is a card that opens it in the pane"
      (let [c (card (made "write" {:path "artifacts/youtube-title_and_description.md" :content "x"} "ok"))]
        (is (= "youtube-title_and_description.md" (:data-artifact (second c))))
        (is (= "b1" (:data-artifact-bot (second c))))
        (is (= "/bot/b1/artifacts/youtube-title_and_description.md" (:href (second c))))
        (is (= "Youtube title and description" (last (find-tag c :span.name))))
        (is (= "md" (last (find-tag c :span.meta))))))
    (testing "an absolute path below artifacts/, a render, a picture, a project"
      (is (= "card/pool.png" (:data-artifact (second (card (made "write" {:path "/home/sprite/rragent/agent/bots/1/work/artifacts/card/pool.png"} "ok"))))))
      (is (= "story.mp4" (:data-artifact (second (card (made "render" {:name "story"} "artifacts/story.mp4 is ready."))))))
      (is (= "card/pool.png" (:data-artifact (second (card (made "image" {:file "card/pool.png"} "artifacts/card/pool.png: 12345 bytes, model x"))))))
      (is (= "clip" (:data-artifact (second (card (made "project" {:name "clip"} "artifacts/clip/ is ready, made from the template motion"))))))
      (is (= [:i18n :artifacts/project] (last (find-tag (card (made "project" {:name "clip"} "artifacts/clip/ is ready")) :span.meta)))))
    (testing "no card for a file elsewhere, a working note, a failed call, or a call still running"
      (is (nil? (card (made "write" {:path "notes/todo.md"} "ok"))))
      (is (nil? (card (made "write" {:path "artifacts/notes/checks.md"} "ok"))) "a note under artifacts/notes/ is no creation")
      (is (nil? (card (made "edit" {:path "/home/sprite/rragent/agent/bots/1/work/artifacts/notes/progress.md"} "ok"))))
      (is (nil? (card {:entries [(assistant 1 [{:type "toolCall" :id "c1" :name "write" :arguments {:path "artifacts/x.md"}}] "toolUse")
                                 (tool-result 2 "c1" "boom" true)]})))
      (is (nil? (card {:entries [(assistant 1 [{:type "toolCall" :id "c1" :name "write" :arguments {:path "artifacts/x.md"}}] "toolUse")]}))))))

(deftest update-banner-test
  (testing "nothing while nothing newer is known"
    (is (nil? (view/update-banner nil)))
    (is (nil? (view/update-banner {:current "0.1.2" :auto? true}))))
  (testing "a newer release: the version, the first line of its notes, the button"
    (let [banner (i18n/translate (view/update-banner {:current "0.1.2" :version "0.1.3" :notes "Subagents" :state :known}) :en)
          text (set (strings banner))]
      (is (= :div.banner.update (first banner)))
      (is (contains? text "rragent 0.1.3 is available."))
      (is (contains? text "Subagents"))
      (is (= [:button.link {:on {:click [[:data/command {:command/kind :update/apply}]]}} "Update"]
             (find-tag banner :button.link)))
      (is (not (contains? text "It installs itself once the agent is idle; the page comes back by itself.")))))
  (testing "with the idle apply the banner says that it goes in by itself"
    (let [banner (i18n/translate (view/update-banner {:current "0.1.2" :version "0.1.3" :state :known :auto? true}) :en)]
      (is (some #{"It installs itself once the agent is idle; the page comes back by itself."} (strings banner)))))
  (testing "what the update is doing, and why it did not work"
    (is (= [:div.banner.update "Updating to rragent 0.1.3 … the page comes back by itself."]
           (i18n/translate (view/update-banner {:version "0.1.3" :state :applying}) :en)))
    (let [banner (i18n/translate (view/update-banner {:version "0.1.3" :state :failed :error "HTTP 404 from x"}) :de)]
      (is (= :div.banner.update.failed (first banner)))
      (is (some #{"Die Aktualisierung auf 0.1.3 hat nicht geklappt: HTTP 404 from x"} (strings banner)))
      (is (= "Noch einmal versuchen" (last (find-tag banner :button.link))))))
  (testing "the answers of a lookup the person asked for"
    (is (= [:div.banner.update "rragent 0.1.2 is the newest."]
           (i18n/translate (view/update-banner {:current "0.1.2" :checked :newest}) :en)))
    (is (= [:div.banner.update.failed "The newest release could not be looked up."]
           (i18n/translate (view/update-banner {:current "0.1.2" :checked :failed}) :en)))))

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
        page (i18n/translate (view/page {:agent {} :conversation c :locale :de}) :de)]
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

(deftest answered-test
  (let [block "```hiccup\n[:button {:send \"A\"} \"A\"]\n```\n"
        answered (fn [c] (into {} (keep (fn [{:keys [kind id answered?]}]
                                          (when (= :assistant kind) [id (boolean answered?)])))
                               (view/items c)))]
    (testing "the answers before the person's last message are answered, the last one is not"
      (is (= {2 true 4 false}
             (answered {:entries [(user 1 "Go") (assistant 2 [{:type "text" :text block}])
                                  (user 3 "A") (assistant 4 [{:type "text" :text block}])]}))))
    (testing "a message waiting in the inbox answers too"
      (is (= {2 true}
             (answered {:entries [(user 1 "Go") (assistant 2 [{:type "text" :text block}])]
                        :inbox {:items [{:id "s1" :mode "followUp" :content "A"}]}}))))
    (testing "a hook's call answers nothing"
      (is (= {2 false}
             (answered {:entries [(user 1 "Go") (assistant 2 [{:type "text" :text block}])
                                  {:id 3 :kind "rragent.hook" :data {:name "deploy"} :model [{:role "user" :content "Hook deploy was called."}]}]}))))
    (testing "on the page, the answered block's button is disabled"
      (let [page (i18n/translate (view/page {:agent {} :locale :en
                                             :conversation {:entries [(user 1 "Go") (assistant 2 [{:type "text" :text block}]) (user 3 "A")]}})
                                 :en)]
        (is (:disabled (second (find-tag page :button))))))))
