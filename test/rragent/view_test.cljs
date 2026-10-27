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
             {:id 6 :kind "rragent.hook" :data {:name "deploy"} :model [{:role "user" :content "Hook deploy was called."}]}
             {:id 7 :kind "rragent.hook" :data {:name "drive" :bytes 2458 :event "Google Drive · file created · 4.txt"} :model [{:role "user" :content "Hook \"drive\" (files) was called.\nBody:\n{}"}]}
             {:id 8 :kind "rragent.schedule" :data {:name "daily" :at 1} :model [{:role "user" :content "Scheduled run \"daily\" (0 9 * * * UTC), due 2026-10-23T09:00:00.000Z:\nLook."}]}]
   :live {:run {:taskId "t"}
          :generation {:message {:role "assistant" :content [{:type "text" :text "Looking"}] :stopReason "stop" :timestamp 99}}}
   :inbox {:items [{:id "s1" :mode "followUp" :content "And Spain?"}]}})

(deftest items-test
  (let [items (view/items conversation)
        kinds (map (juxt :kind :id) items)]
    (is (= [[:user 2] [:assistant 3] [:tool "c1"] [:assistant 5] [:call 6] [:call 7] [:call 8] [:assistant "partial"] [:user "s1"]] kinds))
    (testing "a tool call carries its result"
      (let [tool (nth items 2)]
        (is (= "fetch" (:name tool)))
        (is (true? (:done? tool)))
        (is (= "HTTP 200" (:result tool)))
        (is (false? (:error? tool)))))
    (testing "a hook's call is a folded step: its hook's name on the line, the whole input behind"
      (is (= [:i18n :chat/hook-called {:name "deploy"}] (:label (nth items 4))))
      (is (= "Hook deploy was called." (:text (nth items 4)))))
    (testing "a call that carried a Storrito integration trigger event says what happened, and how big the body was"
      (is (= [:i18n :chat/hook-event {:event "Google Drive · file created · 4.txt" :name "drive"}] (:label (nth items 5))))
      (is (= 2458 (:bytes (nth items 5)))))
    (testing "a schedule's run is a step of its own too"
      (is (= [:i18n :chat/schedule-ran {:name "daily"}] (:label (nth items 6)))))
    (testing "the partial streams, the queued input is pending"
      (is (true? (:streaming? (nth items 7))))
      (is (= "Looking" (:text (nth items 7))))
      (is (true? (:pending? (nth items 8)))))))

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
      (is (contains? text "Hook deploy was called") "a hook's call, folded")
      (is (contains? text "Google Drive · file created · 4.txt · hook drive") "a trigger event's line")
      (is (contains? text " · 2 KB") "the size of the body it carried")
      (is (contains? text "Schedule daily ran"))
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
        (is (= [[:data/command {:command/kind :settings/open :command/data {:tab :ui/tabid}}]] (get-in (second (find-tag page :button.version)) [:on :click])) "the version opens the settings")
        (is (= "Look for a newer rragent in the settings" (:title (second (find-tag page :button.version)))))
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
        (is (= "Record" (:title (:ui/recorder (second (find-tag (i18n/translate p :en) :ui/composer))))) "the panel's words are translated")
        (is (= "Phone connected: {device}" (:phone-connected (:ui/recorder (second (find-tag (i18n/translate p :en) :ui/composer))))))
        (is (nil? (:ui/phone composer)) "no phone paired")
        (is (= {:device "Pixel 8" :facing "environment"}
               (:ui/phone (second (find-tag (page {:phone {:device "Pixel 8" :facing "environment"}}) :ui/composer)))) "the phone, from the frame")
        (is (= "Camera for a screencast" (:title (i18n/translate (view/phone-words) :en))))
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
        (is (some? (find-tag with :ui/splitter)) "the handle between the chat and the pane")
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

(deftest context-usage-test
  (let [with-usage (fn [id usage]
                     {:id id :kind "pi.assistant" :model [{:role "assistant" :content [{:type "text" :text "ok"}] :stopReason "stop" :timestamp id :usage usage}]})]
    (testing "the newest answer's request is what the context holds; the cost adds up"
      (is (= {:used 775200 :cost 1.75}
             (view/context-usage {:entries [(user 1 "hi")
                                            (with-usage 2 {:input 1000 :output 200 :cacheRead 0 :cacheWrite 0 :cost {:total 0.5}})
                                            (user 3 "more")
                                            (with-usage 4 {:input 5200 :output 1000 :cacheRead 700000 :cacheWrite 69000 :cost {:total 1.25}})]}))))
    (testing "without an answer there is nothing to show; without prices no cost"
      (is (nil? (view/context-usage {:entries [(user 1 "hi")]})))
      (is (= {:used 1200 :cost nil}
             (view/context-usage {:entries [(with-usage 2 {:input 1000 :output 200})]}))))
    (testing "the header carries the ring with the window of the model"
      (let [page (view/page {:bot {:id "b1" :name "Bot" :model "m" :context-window 1000000}
                             :conversation {:entries [(with-usage 2 {:input 1000 :output 200 :cacheRead 0 :cacheWrite 0})]}
                             :locale :en})
            ring (second (find-tag page :ui/context))]
        (is (= 1200 (:ui/used ring)))
        (is (= 1000000 (:ui/window ring)))
        (is (= "Context window" (:title (:ui/labels (second (find-tag (i18n/translate page :en) :ui/context)))))))
      (is (nil? (find-tag (view/page {:bot {:id "b1"} :conversation nil :locale :en}) :ui/context)) "no ring before the first answer"))))

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
  (testing "the answers of a lookup the person asked for are the settings', not the banner's"
    (is (nil? (view/update-banner {:current "0.1.2" :checked :newest})))
    (is (nil? (view/update-banner {:current "0.1.2" :checked :failed})))))

(deftest settings-test
  (let [page (fn [update & [locale]]
               (let [locale (or locale :en)]
                 (i18n/translate (view/page {:agent {} :conversation nil :locale locale :settings? true :update update}) locale)))]
    (testing "in place of the chat: the title, the way back, the one section with the version and the button"
      (let [page (page {:current "0.1.2" :auto? false})
            text (set (strings page))]
        (is (contains? text "Settings"))
        (is (contains? text "Updates"))
        (is (contains? text "You run rragent 0.1.2."))
        (is (contains? text "Check for updates"))
        (is (nil? (find-tag page :ui/composer)) "no chat")
        (is (nil? (find-tag page :section.feed)))
        (is (= [[:data/command {:command/kind :update/check}]] (get-in (second (find-tag page :button.link.check)) [:on :click])))
        (is (false? (:disabled (second (find-tag page :button.link.check)))))
        (is (= [[:data/command {:command/kind :settings/close :command/data {:tab :ui/tabid}}]] (get-in (second (find-tag page :button.link.close)) [:on :click])))
        (is (= "current" (:class (second (find-tag page :button.settings)))) "marked in the sidebar")
        (is (nil? (find-tag page :div.banner.update)))
        (is (not-any? #(and (string? %) (str/includes? % ":i18n")) text) "every text is translated")))
    (testing "the sidebar's way to the settings, unmarked while the chat shows"
      (let [chat (i18n/translate (view/page {:agent {} :conversation nil :locale :en}) :en)
            button (find-tag chat :button.settings)]
        (is (= [[:data/command {:command/kind :settings/open :command/data {:tab :ui/tabid}}]] (get-in (second button) [:on :click])))
        (is (nil? (:class (second button))))
        (is (= "Settings" (last button)))
        (is (some? (find-tag chat :ui/composer)))))
    (testing "what the lookup is doing and what it found"
      (let [checking (page {:current "0.1.2" :checking? true})]
        (is (some #{"Checking …"} (strings checking)))
        (is (true? (:disabled (second (find-tag checking :button.link.check))))))
      (is (some #{"rragent 0.1.2 is the newest."} (strings (page {:current "0.1.2" :checked :newest}))))
      (is (some #{"The newest release could not be looked up."} (strings (page {:current "0.1.2" :checked :failed}))))
      (let [found (page {:current "0.1.2" :version "0.1.3" :notes "Subagents" :state :known :auto? true :checked :found})
            text (set (strings found))]
        (is (contains? text "rragent 0.1.3 is available."))
        (is (contains? text "Subagents"))
        (is (= "Update" (last (find-tag found :button.link))))
        (is (contains? text "It installs itself once the agent is idle; the page comes back by itself.")))
      (is (some #{"Updating to rragent 0.1.3 … the page comes back by itself."} (strings (page {:current "0.1.2" :version "0.1.3" :state :applying}))))
      (let [failed (page {:current "0.1.2" :version "0.1.3" :state :failed :error "HTTP 404 from x"})]
        (is (some #{"The update to 0.1.3 did not work: HTTP 404 from x"} (strings failed)))
        (is (= "Try again" (last (find-tag failed :button.link))))))
    (testing "in German"
      (let [text (set (strings (page {:current "0.1.2"} :de)))]
        (is (contains? text "Einstellungen"))
        (is (contains? text "Du nutzt rragent 0.1.2."))
        (is (contains? text "Nach Updates suchen"))
        (is (contains? text "Zurück zum Chat"))))))

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

(deftest settings-mcp-test
  (let [find-tag (fn [tree tag] (some #(when (and (vector? %) (= tag (first %))) %) (tree-seq coll? seq tree)))
        texts (fn [tree] (str/join " " (filter string? (tree-seq coll? seq tree))))]
    (testing "the updates without a section picked, the MCP servers as a tab"
      (let [page (i18n/translate (view/settings {:current "1.0.0"} nil nil) :en)]
        (is (str/includes? (texts page) "Check for updates"))
        (is (str/includes? (texts page) "MCP servers"))))
    (testing "the MCP section: a row per server with its state, a field per missing secret, the setup button"
      (let [page (i18n/translate (view/settings nil :mcp {:servers [{:name "gh" :address "https://gh.example/mcp" :tools 0 :needs ["GH_TOKEN"]}
                                                                         {:name "files" :address "npx" :tools 12 :connected true}
                                                                         {:name "old" :tools 0 :off true}]
                                                               :secrets ["OTHER_TOKEN"]}) :en)
            text (texts page)]
        (is (str/includes? text "Waiting for GH_TOKEN"))
        (is (str/includes? text "Connected, 12 tools"))
        (is (str/includes? text "Turn on"))
        (is (str/includes? text "Connect a service"))
        (is (str/includes? text "OTHER_TOKEN"))
        (is (= "GH_TOKEN" (:ui/name (second (find-tag page :ui/secret-field)))))
        (is (nil? (:ui/bot (second (find-tag page :ui/secret-field)))) "no bot is told of a secret saved in the settings")))
    (testing "a remove asks in the row first"
      (let [page (i18n/translate (view/settings nil :mcp {:servers [{:name "gh" :tools 0}] :removing "gh"}) :en)]
        (is (str/includes? (texts page) "Remove gh? Your bots lose its tools."))))
    (testing "no servers"
      (is (str/includes? (texts (i18n/translate (view/settings nil :mcp {:servers []}) :en)) "No MCP server is connected yet.")))))

(deftest mcp-section-test
  (let [servers [{:name "github" :address "https://api.githubcopilot.com/mcp/" :tools 40 :connected true}
                 {:name "storrito" :address "https://acme.storrito.com/mcp" :tools 0 :needs ["STORRITO_TOKEN"] :error "needs …"}
                 {:name "old" :tools 0 :off true}]
        section (view/mcp-section {:servers servers :secrets ["GITHUB_TOKEN"]})
        text (str/join " " (filter string? (tree-seq coll? seq (i18n/translate section :en))))]
    (testing "every server with where it is and its state"
      (is (str/includes? text "Connected, 40 tools"))
      (is (str/includes? text "Waiting for STORRITO_TOKEN"))
      (is (str/includes? text "Off"))
      (is (str/includes? text "https://acme.storrito.com/mcp")))
    (testing "a field for the secret a server waits for, the setup button, the saved secrets"
      (is (some #(and (vector? %) (= :ui/secret-field (first %)) (= "STORRITO_TOKEN" (:ui/name (second %)))) (tree-seq coll? seq section)))
      (is (str/includes? text "Connect a service …"))
      (is (str/includes? text "GITHUB_TOKEN"))))
  (testing "what the last test found, or that it runs"
    (let [text (fn [test] (str/join " " (filter string? (tree-seq coll? seq (i18n/translate (view/mcp-section {:servers [{:name "gh" :tools 2 :connected true}] :tests {"gh" test}}) :en)))))]
      (is (str/includes? (text {:running? true}) "Testing …"))
      (is (some #(= [:ui/time {:ui/ms 1700000000000}] %)
                (tree-seq coll? seq (view/mcp-section {:servers [{:name "gh" :tools 2 :connected true}] :tests {"gh" {:ok? true :tools 2 :ms 7 :at 1700000000000}}})))
          "the time of the test, in the browser's time zone")
      (is (str/includes? (text {:ok? true :tools 2 :ms 7}) "Test passed: reconnected, 2 tools, answered in 7 ms."))
      (is (str/includes? (text {:ok? false :error "MCP server requires authentication" :ms 9}) "Test failed after 9 ms: MCP server requires authentication"))))
  (testing "a remove asks in the row first"
    (let [section (view/mcp-section {:servers [{:name "github" :tools 1 :connected true}] :removing "github"})]
      (is (some #(= [:i18n :mcp/remove-confirm {:name "github"}] %) (tree-seq coll? seq section)))))
  (testing "nothing yet"
    (is (some #(= [:i18n :mcp/none] %) (tree-seq coll? seq (view/mcp-section {}))))))

(deftest settings-tabs-test
  (let [tree (view/settings {:current "1.0"} :mcp {:servers []})]
    (is (some #(= [:i18n :mcp/title] %) (tree-seq coll? seq tree)) "the MCP section when the tab picked it")
    (is (not (some #(= [:i18n :updates/title] %) (tree-seq coll? seq tree)))))
  (is (some #(= [:i18n :updates/title] %) (tree-seq coll? seq (view/settings {:current "1.0"} nil nil))) "the updates without a pick"))
