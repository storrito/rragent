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
      (is (true? (:pending? (nth items 8)))))
    (testing "the person's message steers the run and says so; a follow-up waits for the run to end"
      (let [[steer follow-up] (take-last 2 (view/items (assoc-in conversation [:inbox :items] [{:id "s2" :mode "steer" :content "Faster."}
                                                                                                {:id "s1" :mode "followUp" :content "And Spain?"}])))]
        (is (= {:kind :user :id "s2" :text "Faster." :pending? true :steer? true} (select-keys steer [:kind :id :text :pending? :steer?])))
        (is (= {:kind :user :id "s1" :text "And Spain?" :pending? true} (select-keys follow-up [:kind :id :text :pending? :steer?])))))))

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
    (testing "a newer release as a banner above the chat; the version is the settings', not the header's"
      (let [page (i18n/translate (view/page {:agent {} :conversation nil :locale :en
                                             :update {:current "0.1.2" :version "0.1.3" :notes "Subagents" :state :known :auto? true}})
                                 :en)
            text (set (strings page))]
        (is (nil? (find-tag page :button.version)))
        (is (not (contains? text "v0.1.2")))
        (is (contains? text "A new version of rragent is ready."))
        (is (not (contains? text "Subagents")) "the notes are the settings'")
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

(deftest switch-test
  (let [models [{:id "openai/gpt-6.1-sol" :name "GPT-6.1 Sol" :cost {:input 2 :output 10}}
                {:id "anthropic/claude-opus-5.5" :name "Claude Opus 5.5" :cost {:input 4 :output 20}}]
        page (fn [bot]
               (i18n/translate (view/page {:bot bot :conversation conversation :locale :en}) :en))
        switch (fn [page] (find-tag page :details.actions.switch))
        buttons (fn [switch] (filter #(and (vector? %) (= :button.item (first %))) (nodes switch)))
        command (fn [button] (get-in (second button) [:on :click 0 1]))]
    (testing "the pill names the model and the level; the menu offers the models with their prices and the levels, the chosen ones marked"
      (let [s (switch (page {:id "cc33dd44" :name "Counter" :model "anthropic/claude-opus-5.5" :thinking "high" :models models}))
            text (set (strings s))]
        (is (some? s))
        (is (contains? text "Claude Opus 5.5"))
        (is (contains? text "High"))
        (is (contains? text "$4 in · $20 out per million tokens"))
        (is (contains? text "2× the price of GPT-6.1 Sol") "the dearer model says how much dearer")
        (is (contains? text "$2 in · $10 out per million tokens"))
        (is (contains? text "Extra"))
        (is (contains? text "More effort means more thorough answers, but each takes longer and costs more."))
        (is (= [{:command/kind :bot/model :command/data {:id "cc33dd44" :model "openai/gpt-6.1-sol"}}
                {:command/kind :bot/model :command/data {:id "cc33dd44" :model "anthropic/claude-opus-5.5"}}
                {:command/kind :bot/thinking :command/data {:id "cc33dd44" :thinking "low"}}
                {:command/kind :bot/thinking :command/data {:id "cc33dd44" :thinking "medium"}}
                {:command/kind :bot/thinking :command/data {:id "cc33dd44" :thinking "high"}}
                {:command/kind :bot/thinking :command/data {:id "cc33dd44" :thinking "xhigh"}}
                {:command/kind :bot/thinking :command/data {:id "cc33dd44" :thinking "max"}}]
               (map command (buttons s))))
        (is (= ["anthropic/claude-opus-5.5" "high"]
               (keep #(when (= "chosen" (:class (second %)))
                        (let [data (:command/data (command %))]
                          (or (:model data) (:thinking data))))
                     (buttons s))))))
    (testing "the pill says high while the bot has no level of its own; a page without a bot has no switch"
      (is (contains? (set (strings (switch (page {:id "cc33dd44" :name "Counter" :model "openai/gpt-6.1-sol" :models models})))) "High"))
      (is (nil? (switch (page {})))))
    (testing "the switch is every bot's, under its composer; a run's page has none"
      (let [bot {:id "cc33dd44" :name "Counter" :model "anthropic/claude-opus-5.5" :thinking "high" :models models}
            run (i18n/translate (view/page {:bot bot :run {:id 5 :state :done} :conversation conversation :locale :en}) :en)]
        (is (contains? (set (strings (find-tag (page bot) :div.composer))) "Claude Opus 5.5"))
        (is (nil? (switch run)))))))

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
      (is (= "clip" (:data-artifact (second (card (made "project" {:name "clip"} "artifacts/clip/ is ready, made from the template hyperframes"))))))
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
    (testing "the newest answer's request is what the context holds; the cost adds up (the factor 1 here)"
      (let [env (.-env js/process)]
        (try
          (aset env "RRAGENT_COST_FACTOR" "1")
          (is (= {:used 775200 :cost 1.75}
                 (view/context-usage {:entries [(user 1 "hi")
                                                (with-usage 2 {:input 1000 :output 200 :cacheRead 0 :cacheWrite 0 :cost {:total 0.5}})
                                                (user 3 "more")
                                                (with-usage 4 {:input 5200 :output 1000 :cacheRead 700000 :cacheWrite 69000 :cost {:total 1.25}})]})))
          (finally
            (js-delete env "RRAGENT_COST_FACTOR")))))
    (testing "the cost as the person pays it: times RRAGENT_COST_FACTOR, Storrito's markup by default"
      (let [env (.-env js/process)
            entries [(with-usage 2 {:input 1000 :output 200 :cacheRead 0 :cacheWrite 0 :cost {:total 0.5}})]]
        (try
          (js-delete env "RRAGENT_COST_FACTOR")
          (is (= 0.65 (:cost (view/context-usage {:entries entries}))))
          (aset env "RRAGENT_COST_FACTOR" "2")
          (is (= 1 (:cost (view/context-usage {:entries entries}))))
          (finally
            (js-delete env "RRAGENT_COST_FACTOR")))))
    (testing "without an answer there is nothing to show; without prices no cost"
      (is (nil? (view/context-usage {:entries [(user 1 "hi")]})))
      (is (= {:used 1200 :cost nil}
             (view/context-usage {:entries [(with-usage 2 {:input 1000 :output 200})]}))))
    (testing "the header carries the ring with the window of the model"
      (let [page (view/page {:bot {:id "b1" :name "Bot" :model "m" :context-window 1000000}
                             :conversation {:entries [(with-usage 2 {:input 1000 :output 200 :cacheRead 0 :cacheWrite 0})]}
                             :locale :en
                            })
            ring (second (find-tag page :ui/context))]
        (is (= 1200 (:ui/used ring)))
        (is (= 1000000 (:ui/window ring)))
        (is (= "Context" (:title (:ui/labels (second (find-tag (i18n/translate page :en) :ui/context)))))))
      (is (nil? (find-tag (view/page {:bot {:id "b1"} :conversation nil :locale :en}) :ui/context)) "no ring before the first answer"))
    (testing "the ring is every bot's, with the button that summarizes"
      (let [page (view/page {:bot {:id "b1" :name "Bot" :model "m" :context-window 1000000 :spend 33.8}
                             :conversation {:entries [(with-usage 2 {:input 1000 :output 200 :cost {:total 0.5}})]}
                             :locale :en})]
        (is (some? (find-tag page :ui/context)))
        (is (contains? (set (strings (i18n/translate page :en))) "Summarize older messages now"))))
    (testing "the cost is the bot's, its subagents included, when known, times the factor (1.3 by default): a compaction shortens the transcript, not what it cost"
      (let [conversation {:entries [(with-usage 2 {:input 1000 :output 200 :cost {:total 0.5}})]}
            shown (fn [bot] (:ui/cost (second (find-tag (view/page {:bot bot :conversation conversation :locale :en}) :ui/context))))]
        (is (< (js/Math.abs (- 43.94 (shown {:id "b1" :context-window 200000 :spend 33.8}))) 1e-9))
        (is (= 0.65 (shown {:id "b1" :context-window 200000})) "before it is read, the transcript's")))
    (testing "the card's button names the bot it summarizes, and says so while a compaction runs; a run's page has no button"
      (let [conversation {:entries [(with-usage 2 {:input 1000 :output 200})]}
            card (fn [opts]
                   (second (find-tag (i18n/translate (view/page (merge {:bot {:id "b1" :context-window 200000} :conversation conversation :locale :en} opts)) :en)
                                     :ui/context)))]
        (is (= "b1" (:ui/bot (card {}))))
        (is (false? (:ui/compacting? (card {}))))
        (is (true? (:ui/compacting? (card {:conversation (assoc conversation :live {:compactions [{:taskId "c1" :reason "manual"}]})}))))
        (is (= "Summarize older messages now" (:compact (:ui/labels (card {})))))
        (is (= "Summarizing older messages …" (:compacting (:ui/labels (card {})))))
        (let [run (card {:run {:id "r1" :state :done :trigger {:kind "hook" :name "h"} :started 1}})]
          (is (= 1200 (:ui/used run)) "the run's own usage")
          (is (nil? (:ui/bot run)) "the run's transcript is not the bot's to summarize")
          (is (nil? (:ui/compacting? run))))))))

(deftest update-banner-test
  (testing "nothing while nothing newer is known"
    (is (nil? (view/update-banner nil)))
    (is (nil? (view/update-banner {:current "0.1.2" :auto? true}))))
  (testing "a newer release: that one is ready, the button; the version and the notes are the settings'"
    (let [banner (i18n/translate (view/update-banner {:current "0.1.2" :version "0.1.3" :notes "Subagents" :state :known}) :en)
          text (set (strings banner))]
      (is (= :div.banner.update (first banner)))
      (is (contains? text "A new version of rragent is ready."))
      (is (not (contains? text "rragent 0.1.3 is available.")))
      (is (not (contains? text "Subagents")))
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
    (testing "the sidebar's rows are the way back: no bot is marked as the current one while the settings show, each is the link to its chat"
      (let [opts {:bots [{:id "7" :name "Assistant" :label "Assistant" :state :idle}
                         {:id "8" :name "Content week" :label "Content week" :state :idle}]
                  :bot {:id "8" :name "Content week"}
                  :conversation nil :locale :en}
            current (fn [page] (filter #(and (vector? %) (= :li (first %)) (some #{"current"} (:class (second %)))) (nodes page)))
            chat (i18n/translate (view/page opts) :en)
            settings (i18n/translate (view/page (assoc opts :settings? true)) :en)]
        (is (= ["Content week"] (map #(last (find-tag % :span.name)) (current chat))) "the chat marks its bot")
        (is (empty? (current settings)) "the settings mark none")
        (is (= "current" (:class (second (find-tag settings :button.settings)))))
        (is (= ["/bot/7" "/bot/8"] (map #(:href (second %)) (filter #(and (vector? %) (= :a.row (first %))) (nodes settings))))
            "each row the link to its bot, which the client turns into the tab's pick")))
    (testing "in place of the chat: the title, the one section with the version and the button"
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
        (is (nil? (find-tag page :button.link.close)) "no button back: the sidebar's rows are the way")
        (is (not (contains? text "Back to the chat")))
        (is (= "current" (:class (second (find-tag page :button.settings)))) "marked in the sidebar")
        (is (nil? (find-tag page :div.banner.update)))
        (is (not-any? #(and (string? %) (str/includes? % ":i18n")) text) "every text is translated")))
    (testing "the Input section: which key sends, the browser's choice"
      (let [input (i18n/translate (view/page {:agent {} :conversation nil :locale :de :settings? true :settings-section :input}) :de)]
        (is (= ["Updates" "Auf dem Handy" "Eingabe" "Aufnahme" "MCP-Server" "Branding"] (map last (filter #(and (vector? %) (= :button.tab (first %))) (nodes input)))) "the sections")
        (is (= {:ui/ctrl-enter "Strg+Enter sendet (⌘+Enter auf dem Mac), Enter bricht die Zeile um"
                :ui/enter "Enter sendet, Strg+Enter (⌘+Enter auf dem Mac) bricht die Zeile um"}
               (second (find-tag input :ui/send-key))))))
    (testing "the Recording section: when the webcam's effects are made, the browser's choice"
      (let [recording (i18n/translate (view/page {:agent {} :conversation nil :locale :de :settings? true :settings-section :recording}) :de)
            choices (second (find-tag recording :ui/effects-timing))]
        (is (str/starts-with? (:ui/live choices) "Live"))
        (is (str/starts-with? (:ui/after choices) "Nach der Aufnahme"))))
    (testing "the composer's title for either key"
      (let [composer (second (find-tag (i18n/translate (view/page {:agent {} :conversation nil :locale :en}) :en) :ui/composer))]
        (is (str/starts-with? (:title composer) "Ctrl+Enter sends"))
        (is (str/starts-with? (:ui/enter-title composer) "Enter sends"))))
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
        (is (not (contains? text "Zurück zum Chat")))))))

(deftest screen-test
  (let [c {:entries [(user 2 [{:type "text" :text "Make it pink\non screen beside the chat: artifacts/stories/a.html"}])]}
        item (first (view/items c))
        page (i18n/translate (view/page {:agent {} :conversation c :locale :en}) :en)]
    (testing "the screen line is no text of the message, but a hint under it"
      (is (= "Make it pink" (:text item)))
      (is (= "stories/a.html" (:screen item)))
      (is (some #{"Open: stories/a.html"} (strings page)))
      (is (not-any? #(and (string? %) (str/includes? % "on screen beside")) (strings page)))))
  (testing "the pane says what it shows, for the tab's next message"
    (let [pane {:artifacts/list [{:path "stories/a.html" :kind :page :title "a"}] :base "/bot/1" :bot "1"}
          screen #(:data-screen (second (view/artifacts (merge pane %))))]
      (is (= "stories/a.html" (screen {:artifacts/current "stories/a.html"})))
      (is (= "stories/" (screen {:artifacts/folder "stories"})))
      (is (nil? (screen {:artifacts/current "gone.html" :artifacts/folder "gone"})) "a pick and a folder gone since"))))

(deftest csv-artifact-test
  (let [pane {:artifacts/list [{:path "reviews.csv" :kind :text :ext "csv" :title "Reviews" :size 40 :mtime 1}]
              :artifacts/current "reviews.csv"
              :base "/bot/1"
              :bot "1"}
        shown (fn [text] (i18n/translate (view/artifacts (assoc pane :artifacts/text text)) :en))]
    (testing "a CSV on screen is a table: the header, the rows, the count"
      (let [page (shown {:text "guest,stars,text\nAnna,5,\"Great, really.\"\nTom,4,Noisy\n" :truncated? false})
            table (find-tag page :table)
            ths (map last (filter #(and (vector? %) (= :th (first %))) (nodes table)))
            tds (map #(last (last %)) (filter #(and (vector? %) (= :td (first %))) (nodes table)))]
        (is (some? (find-tag page :div.document.csv)))
        (is (nil? (find-tag page :ui/code-editor)) "the table, not the editor, until Edit")
        (is (= ["guest" "stars" "text"] ths))
        (is (= ["Anna" "5" "Great, really." "Tom" "4" "Noisy"] tds))
        (is (some #{"2 rows · 3 columns"} (strings page)))
        (is (not-any? #(and (string? %) (str/includes? % "longer than this")) (strings page)))))
    (testing "a row that does not read: the note under the table"
      (let [page (shown {:text "a,b\n1,\"open\n3,4\n" :truncated? false})]
        (is (some #(and (string? %) (str/includes? % "1 lines could not be read as CSV")) (strings page)))))
    (testing "the start of a longer file: the cut row dropped, the note"
      (let [page (shown {:text "a,b\n1,2\n3,4\n5," :truncated? true})
            tds (map #(last (last %)) (filter #(and (vector? %) (= :td (first %))) (nodes (find-tag page :table))))]
        (is (= ["1" "2" "3" "4"] tds))
        (is (some #(and (string? %) (str/includes? % "longer than this")) (strings page)))))
    (testing "another text file is in the editor"
      (let [page (i18n/translate (view/artifacts (-> pane
                                                     (assoc :artifacts/list [{:path "notes.txt" :kind :text :ext "txt" :title "Notes" :size 4 :mtime 1}]
                                                            :artifacts/current "notes.txt"
                                                            :artifacts/text {:text "a,b\n1,2" :truncated? false})))
                                  :en)]
        (is (some? (find-tag page :ui/code-editor)))
        (is (nil? (find-tag page :table)))))
    (testing "Edit in the bar puts the CSV's text in the editor, Preview the table back"
      (let [page (shown {:text "a,b\n1,2\n" :truncated? false})
            toggle (find-tag page :button.link.edit)]
        (is (= "Edit" (last toggle)))
        (is (= {:command/kind :artifacts/edit :command/data {:bot "1" :path "reviews.csv" :on? true}}
               (second (first (get-in toggle [1 :on :click]))))))
      (let [page (i18n/translate (view/artifacts (assoc pane
                                                        :artifacts/editing "reviews.csv"
                                                        :artifacts/text {:text "a,b\n1,2\n" :truncated? false}))
                                 :en)
            toggle (find-tag page :button.link.edit)]
        (is (nil? (find-tag page :table)))
        (is (some? (find-tag page :ui/code-editor)))
        (is (= "Preview" (last toggle)))
        (is (false? (get-in (second (first (get-in toggle [1 :on :click]))) [:command/data :on?])))))))

(deftest editor-test
  (let [pane {:artifacts/list [{:path "card/main.js" :kind :text :ext "js" :title "Main" :size 40 :mtime 7 :project "card"}
                               {:path "README.md" :kind :markdown :ext "md" :title "README" :size 12 :mtime 3}
                               {:path "card" :kind :project :title "Card" :size 0 :mtime 7}]
              :base "/bot/1"
              :bot "1"}
        ;; the list at the root, or in `folder`: a file inside a project
        ;; has its row there
        shown (fn [current text & [editing folder]]
                (i18n/translate (view/artifacts (cond-> (assoc pane
                                                               :artifacts/current current
                                                               :artifacts/text text)
                                                  editing (assoc :artifacts/editing editing)
                                                  folder (assoc :artifacts/folder folder)))
                                :en))]
    (testing "a text file is in the editor: the bot, the path, the text, its time as the version, the extension for the syntax, the words of the strip"
      (let [page (shown "card/main.js" {:text "const a = 1;\n" :truncated? false})
            [_ attrs] (find-tag page :ui/code-editor)]
        (is (some? (find-tag page :div.document.code)))
        (is (= {:ui/bot "1" :ui/path "card/main.js" :ui/ext "js" :ui/rev 7 :ui/text "const a = 1;\n" :ui/read-only? false}
               (select-keys attrs [:ui/bot :ui/path :ui/ext :ui/rev :ui/text :ui/read-only?])))
        (is (= ["Save" "Saving …" "Unsaved changes" "The file could not be saved." "The file changed on disk meanwhile." "Take that version"]
               (map attrs [:ui/save-label :ui/saving-label :ui/unsaved-label :ui/failed-label :ui/disk-label :ui/reload-label])))
        (is (nil? (find-tag page :button.link.edit)) "nothing to toggle: the editor is the view")
        (is (not-any? #(and (string? %) (str/includes? % "longer than this")) (strings page)))))
    (testing "the start of a long file is read only, with the note"
      (let [page (shown "card/main.js" {:text "const" :truncated? true})
            [_ attrs] (find-tag page :ui/code-editor)]
        (is (true? (:ui/read-only? attrs)))
        (is (some #(and (string? %) (str/includes? % "longer than this")) (strings page)))))
    (testing "a markdown file with a frontmatter, a skill's SKILL.md, shows its fields as a list above the body, no heading made of them"
      (let [page (shown "README.md" {:text "---\nname: \"review-stories\"\ndescription: \"A story from a review.\"\nmetadata:\n  title: \"Review stories\"\n  ask: \"Which apartment?\"\n---\n\n# Hello\n" :truncated? false})]
        (is (= [["Name" "review-stories"] ["Description" "A story from a review."] ["Title" "Review stories"] ["Ask" "Which apartment?"]]
               (map (fn [[_ [_ k] [_ v]]] [k v]) (rest (find-tag page :dl.frontmatter)))))
        (is (nil? (find-tag page :h2)) "the frontmatter is no heading")
        (is (some? (find-tag page :h1)) "the body is rendered")))
    (testing "a markdown file is rendered, with Edit in the bar; editing, its text is in the editor, with Preview"
      (let [page (shown "README.md" {:text "# Hello\n" :truncated? false})]
        (is (some? (find-tag page :div.markdown)))
        (is (nil? (find-tag page :ui/code-editor)))
        (is (= "Edit" (last (find-tag page :button.link.edit)))))
      (let [page (shown "README.md" {:text "# Hello\n" :truncated? false} "README.md")
            [_ attrs] (find-tag page :ui/code-editor)]
        (is (nil? (find-tag page :div.markdown)))
        (is (= "md" (:ui/ext attrs)))
        (is (= "Preview" (last (find-tag page :button.link.edit)))))
      (let [page (shown "card/main.js" {:text "x" :truncated? false} "README.md")]
        (is (some? (find-tag page :ui/code-editor)) "another file's editing does not matter here")
        (is (nil? (find-tag page :button.link.edit)))))
    (testing "Edit in the menu of a text file's row opens it in the editor, a step of the tab's; a project's menu has none"
      (let [page (shown "card/main.js" {:text "x" :truncated? false})
            inside (shown "card/main.js" {:text "x" :truncated? false} nil "card")
            menu-of (fn [page path]
                      (some #(when (and (vector? %) (= :li (first %)) (= path (:replicant/key (second %))))
                               (find-tag % :details.actions))
                            (nodes page)))
            edit (fn [page path] (find-tag (menu-of page path) :button.item.edit))]
        (is (= {:command/kind :artifacts/edit :command/data {:bot "1" :path "README.md" :on? true :tab :ui/tabid}}
               (get-in (second (edit page "README.md")) [:on :click 0 1])))
        (is (= "Edit" (last (edit page "README.md"))))
        (is (nil? (menu-of page "card/main.js")) "a file inside a project is no row of the root")
        (is (= "card/main.js" (get-in (second (edit inside "card/main.js")) [:on :click 0 1 :command/data :path])) "a code file's row too, in its folder")
        (is (some? (menu-of page "card")))
        (is (nil? (edit page "card")) "a project is played, not edited")))
    (testing "the menu's item reads Preview while the markdown file's text is in the editor, the view back; a code file's stays Edit, the editor is its view"
      (let [item-of (fn [page path]
                      (some #(when (and (vector? %) (= :li (first %)) (= path (:replicant/key (second %))))
                               (find-tag % :button.item.edit))
                            (nodes page)))
            page (shown "README.md" {:text "# Hello\n" :truncated? false} "README.md")]
        (is (= "Preview" (last (item-of page "README.md"))))
        (is (= {:command/kind :artifacts/edit :command/data {:bot "1" :path "README.md" :on? false :tab :ui/tabid}}
               (get-in (second (item-of page "README.md")) [:on :click 0 1])))
        (is (= "Edit" (last (item-of (shown "README.md" {:text "# Hello\n" :truncated? false} "README.md" "card") "card/main.js")))))
      (let [page (shown "card/main.js" {:text "x" :truncated? false} "card/main.js" "card")
            item (some #(when (and (vector? %) (= :li (first %)) (= "card/main.js" (:replicant/key (second %))))
                          (find-tag % :button.item.edit))
                       (nodes page))]
        (is (= "Edit" (last item)) "a code file's menu never reads Preview")
        (is (true? (get-in (second item) [:on :click 0 1 :command/data :on?])))))
    (testing "a text file that could not be read has no editor"
      (let [page (shown "README.md" nil)]
        (is (nil? (find-tag page :ui/code-editor)))
        (is (nil? (find-tag page :button.link.edit)))
        (is (some #(and (string? %) (str/includes? % "no preview")) (strings page)))))))

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
      (let [page (i18n/translate (view/settings {:current "1.0.0"} nil nil nil nil) :en)]
        (is (str/includes? (texts page) "Check for updates"))
        (is (str/includes? (texts page) "MCP servers"))))
    (testing "the MCP section: a row per server with its state, a field per missing secret, the setup button"
      (let [page (i18n/translate (view/settings nil :mcp {:servers [{:name "gh" :address "https://gh.example/mcp" :tools 0 :needs ["GH_TOKEN"]}
                                                                         {:name "files" :address "npx" :tools 12 :connected true}
                                                                         {:name "old" :tools 0 :off true}]
                                                               :secrets ["OTHER_TOKEN"]}
                                                          nil nil)
                                          :en)
            text (texts page)]
        (is (str/includes? text "Waiting for GH_TOKEN"))
        (is (str/includes? text "Connected, 12 tools"))
        (is (str/includes? text "Turn on"))
        (is (str/includes? text "Connect a service"))
        (is (str/includes? text "OTHER_TOKEN"))
        (is (= "GH_TOKEN" (:ui/name (second (find-tag page :ui/secret-field)))))
        (is (nil? (:ui/bot (second (find-tag page :ui/secret-field)))) "no bot is told of a secret saved in the settings")))
    (testing "a remove asks in the row first"
      (let [page (i18n/translate (view/settings nil :mcp {:servers [{:name "gh" :tools 0}] :removing "gh"} nil nil) :en)]
        (is (str/includes? (texts page) "Remove gh? Your bots lose its tools."))))
    (testing "no servers"
      (is (str/includes? (texts (i18n/translate (view/settings nil :mcp {:servers []} nil nil) :en)) "No MCP server is connected yet.")))))

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
  (testing "an OAuth server: Sign in while it waits for one, Sign out once connected"
    (let [waiting (view/mcp-section {:servers [{:name "linear" :tools 0 :oauth true :sign-in true}]})
          connected (view/mcp-section {:servers [{:name "linear" :tools 3 :oauth true :connected true}]})]
      (is (some #(and (vector? %) (= :a.link.sign-in (first %)) (= "/mcp/oauth/start/linear" (:href (second %)))) (tree-seq coll? seq waiting)))
      (is (some #(= [:i18n :mcp/needs-sign-in] %) (tree-seq coll? seq waiting)))
      (is (some #(= [:i18n :mcp/sign-out] %) (tree-seq coll? seq connected)))))
  (testing "a remove asks in the row first"
    (let [section (view/mcp-section {:servers [{:name "github" :tools 1 :connected true}] :removing "github"})]
      (is (some #(= [:i18n :mcp/remove-confirm {:name "github"}] %) (tree-seq coll? seq section)))))
  (testing "nothing yet"
    (is (some #(= [:i18n :mcp/none] %) (tree-seq coll? seq (view/mcp-section {}))))))

(deftest settings-tabs-test
  (let [tree (view/settings {:current "1.0"} :mcp {:servers []} nil)]
    (is (some #(= [:i18n :mcp/title] %) (tree-seq coll? seq tree)) "the MCP section when the tab picked it")
    (is (not (some #(= [:i18n :updates/title] %) (tree-seq coll? seq tree)))))
  (is (some #(= [:i18n :updates/title] %) (tree-seq coll? seq (view/settings {:current "1.0"} nil nil nil))) "the updates without a pick")
  (is (some #(= [:i18n :updates/title] %) (tree-seq coll? seq (view/settings {:current "1.0"} :advanced nil nil))) "a section of before, the advanced switch's, is none: the updates")
  (testing "the phone: the link as a QR code and the browser's install"
    (let [tree (view/settings {:current "1.0"} :mobile nil "https://a.test/?token=s")
          tags (set (keep #(when (vector? %) (first %)) (tree-seq coll? seq tree)))]
      (is (contains? tags :svg.qr))
      (is (contains? tags :ui/install))
      (is (some #(= [:i18n :mobile/title] %) (tree-seq coll? seq tree))))
    (is (not (some #(and (vector? %) (= :svg.qr (first %)))
                   (tree-seq coll? seq (view/settings {:current "1.0"} :mobile nil nil))))
        "no code without a link")))

(deftest branding-test
  (let [page (fn [opts & [locale]]
               (let [locale (or locale :en)]
                 (i18n/translate (view/page (merge {:agent {} :conversation nil :locale locale :bot {:id "7" :name "Assistant"}} opts)) locale)))
        corner (fn [page] (find-tag page :div.brand))
        logo (str (apply str (repeat 32 "a")) ".svg")]
    (testing "the corner of a machine without a name of its own: rragent, no logo, the tab's title the same"
      (let [page (page {:update {:current "0.1.2"}})]
        (is (= "rragent" (last (find-tag (corner page) :span.name))))
        (is (nil? (find-tag (corner page) :img.logo)))
        (is (nil? (:class (second (corner page)))))
        (is (= "0.1.2" (last (find-tag (corner page) :span.version))))
        (is (= "rragent" (:ui/title (second (find-tag page :ui/url)))))))
    (testing "its own name and its logo in front of it, in the chat and in the settings alike"
      (doseq [opts [{} {:settings? true}]
              :let [page (page (assoc opts :brand {:name "Acme Studio" :logo logo}))
                    [tag attrs] (nth (corner page) 2)]]
        (is (= :img.logo tag) "the logo first")
        (is (= (str "/branding/logo/" logo) (:src attrs)))
        (is (= "" (:alt attrs)) "the name beside it says what it is")
        (is (= "Acme Studio" (last (find-tag (corner page) :span.name))))
        (is (= "Acme Studio" (:ui/title (second (find-tag page :ui/url)))))))
    (testing "a logo without a name stands alone: no rragent beside a logo that spells the name itself"
      (let [page (page {:brand {:logo logo} :update {:current "0.1.2"}} :de)]
        (is (= "logo-only" (:class (second (corner page)))))
        (is (nil? (find-tag (corner page) :span.name)))
        (is (= "Das Logo" (:alt (second (find-tag (corner page) :img.logo)))) "the picture is all a screen reader has")
        (is (= "0.1.2" (last (find-tag (corner page) :span.version))) "the version stays")
        (is (= "rragent" (:ui/title (second (find-tag page :ui/url)))) "the tab's title is the name's alone")))
    (testing "the Branding section: the field with the saved name, the picker of a logo"
      (let [page (page {:settings? true :settings-section :branding})
            field (second (find-tag page :ui/brand-name))
            upload (second (find-tag page :ui/logo-upload))
            section (find-tag page :section.branding)]
        (is (some #{"Name and logo"} (strings section)))
        (is (= {:ui/name "" :ui/label "Name" :ui/placeholder "rragent" :ui/max-chars 40 :ui/button "Save"
                :ui/saved "Saved." :ui/not-saved "The name could not be saved. Try again."
                :ui/note "Save an empty field and the corner says rragent again."}
               field))
        (is (= "/branding/logo" (:ui/url upload)))
        (is (nil? (:ui/logo upload)))
        (is (= "image/png,image/jpeg,image/webp,image/gif,image/svg+xml" (:ui/accept upload)))
        (is (= (* 2 1024 1024) (:ui/max-bytes upload)))
        (is (= "Upload a logo" (:ui/choose upload)))
        (is (nil? (find-tag section :img.logo)) "no logo yet")
        (is (nil? (find-tag section :button.link.danger)) "and none to remove")))
    (testing "with a name and a logo saved: the name in the field, the logo shown, replaced or removed"
      (let [page (page {:settings? true :settings-section :branding :brand {:name "Acme Studio" :logo logo}} :de)
            section (find-tag page :section.branding)]
        (is (= "Acme Studio" (:ui/name (second (find-tag section :ui/brand-name)))))
        (is (= "Ein leeres Feld speichern, und oben links steht das Logo allein."
               (:ui/note (second (find-tag section :ui/brand-name))))
            "what an empty field leaves, with a logo")
        (is (= (str "/branding/logo/" logo) (:src (second (find-tag section :img.logo)))))
        (is (nil? (:class (second (find-tag section :div.logo-row)))))
        (is (= logo (:ui/logo (second (find-tag section :ui/logo-upload)))) "the upload knows the saved one")
        (is (= "Logo ersetzen" (:ui/choose (second (find-tag section :ui/logo-upload)))))
        (is (= [[:data/command {:command/kind :branding/remove-logo}]]
               (get-in (second (find-tag section :button.link.danger)) [:on :click])))
        (is (= "Logo entfernen" (last (find-tag section :button.link.danger))))
        (is (not-any? #(and (string? %) (str/includes? % ":i18n")) (strings page)) "every text is translated")))
    (testing "the saved logo of a machine without a name is shown as wide as the corner shows it"
      (let [section (find-tag (page {:settings? true :settings-section :branding :brand {:logo logo}}) :section.branding)]
        (is (= "alone" (:class (second (find-tag section :div.logo-row)))))))))

(deftest message-items-test
  (let [conversation {:entries [(user 1 "Ask the counter.")
                                {:id 2 :kind "rragent.message" :data {:from 7 :name "Counter" :text "Seven." :answer true}
                                 :model [{:role "user" :content "Counter (bot 7) answers your message: Seven.\n\nTell the person what matters to them of it."}]}]
                      :live {}
                      :inbox {:items [{:id "s1" :mode "followUp"
                                       :content "A message from Watcher (bot 1 of this machine): How many?"
                                       :entry {:kind "rragent.message" :data {:from 1 :name "Watcher" :text "How many?"}}}]}}
        items (view/items conversation)
        [person answer queued] items]
    (testing "a bot's message is a user item with the bot as its author and its own words as the text"
      (is (= {:kind :user :id 2 :author {:name "Counter" :url "/bot/7"} :markdown {:base "/bot/7" :bot "7"} :text "Seven."} answer) "its words as markdown, its files under its page")
      (is (= {:kind :user :id "s1" :author {:name "Watcher" :url "/bot/1"} :markdown {:base "/bot/1" :bot "1"} :text "How many?" :pending? true} queued) "one waiting in the inbox too")
      (is (nil? (:author person))))
    (testing "the feed shows the bot's name, a link to its page, in place of You"
      (let [feed (i18n/translate (view/feed items nil {:base "/bot/1/artifacts" :bot "1"}) :en)
            text (set (strings feed))
            link (find-tag feed :a)]
        (is (contains? text "From the bot Counter"))
        (is (contains? text "You"))
        (is (= "/bot/7" (:href (second link))))))))

(deftest fold-test
  (let [page (fn [expanded]
               (i18n/translate (view/page {:agent {} :conversation conversation :locale :en :expanded expanded}) :en))
        folds (fn [page]
                (filter #(and (vector? %) (= :details.work (first %))) (nodes page)))
        placeholder? #(= [:pre.output.pending "…"] (last %))]
    (testing "a folded row sends its line and a placeholder, not its body: a frame is the whole page"
      (let [page (page nil)
            text (set (strings page))]
        (is (contains? text "Fetched a page"))
        (is (not (contains? text "https://x.test")) "the call's arguments stay on the server")
        (is (not (contains? text "HTTP 200")) "its result too")
        (is (contains? text "Hook deploy was called") "a hook's line")
        (is (not (contains? text "Hook deploy was called.")) "not its input")
        (is (= 4 (count (folds page))) "the tool call, two hooks and the schedule")
        (is (every? placeholder? (folds page)))
        (is (= {:toggle [[:data/command {:command/kind :chat/fold :command/data {:tab :ui/tabid :id "c1" :fold :ui/fold}}]]}
               (:on (second (first (folds page)))))
            "a toggle tells the server, the tab and the state filled in by the browser")
        (is (= 6 (get-in (second (second (folds page))) [:on :toggle 0 1 :command/data :id])) "a call row by its entry id")
        (is (= page (transit/read-str (transit/write-str page))))))
    (testing "the rows the tab opened carry their body"
      (let [page (page #{"c1" 6})
            text (set (strings page))]
        (is (contains? text "https://x.test"))
        (is (contains? text "HTTP 200"))
        (is (contains? text "Hook deploy was called."))
        (is (not (contains? text "Hook \"drive\" (files) was called.\nBody:\n{}")) "a closed one does not")
        (is (= 2 (count (filter placeholder? (folds page)))))))))

(deftest compaction-note-test
  (let [conversation {:entries [{:id 9 :kind "pi.compaction" :head 5 :model [{:role "user" :content "Summary: two capitals were asked."}]}
                                (user 10 "And Italy?")]
                      :live {}
                      :inbox {}}
        items (view/items conversation)
        page (fn [expanded]
               (i18n/translate (view/page {:agent {} :conversation conversation :locale :en :expanded expanded}) :en))]
    (testing "the compaction's note carries what the model keeps"
      (is (= [:note :user] (map :kind items)))
      (is (= "Summary: two capitals were asked." (:summary (first items)))))
    (testing "on the page the summary is a fold under the note, its body for the tab that opens it"
      (let [closed (page nil)
            fold (first (filter #(and (vector? %) (= :details.work (first %))) (nodes closed)))]
        (is (some #{"From here on the model reads a summary of the messages above."} (strings closed)))
        (is (some #{"What the model keeps of them"} (strings closed)))
        (is (not (some #{"Summary: two capitals were asked."} (strings closed))))
        (is (= 9 (get-in (second fold) [:on :toggle 0 1 :command/data :id])))
        (is (some #{"Summary: two capitals were asked."} (strings (page #{9}))))))))


(deftest bot-message-markdown-test
  (let [entries [{:id 1 :kind "pi.user" :model [{:role "user" :content "Hi" :timestamp 1}]}
                 {:id 2 :kind "rragent.message" :data {:from 184 :name "101 Modern Japandi template" :text "**Ready for review:** `review-101`\n\n- [the poster](artifacts/review-101/poster.png)"}}]
        page (i18n/translate (view/page {:agent {} :conversation {:entries entries} :locale :en :bot {:id "7"}}) :en)
        item (some #(when (and (vector? %) (= :div.item.user (first %)) (= "item-2" (:id (second %)))) %) (tree-seq coll? seq page))]
    (testing "a bot's message is markdown, its files under the sender's page"
      (is (some? item))
      (is (some #(and (vector? %) (= :strong (first %))) (tree-seq coll? seq item)) "bold rendered")
      (is (some #(and (vector? %) (= :div.markdown (first %))) (tree-seq coll? seq item)))
      (is (some #(and (map? %) (= "/bot/184/artifacts/review-101/poster.png" (:href %))) (tree-seq coll? seq item)) "the link is the sender's file"))))

(deftest open-card-test
  (let [person (fn [id text] {:id id :kind "pi.user" :model [{:role "user" :content text :timestamp id}]})
        card (assistant 2 [{:type "text" :text "The 203 template is ready.\n\n```hiccup\n[:button {:send \"203: approve\"} \"Approve\"]\n```"}])
        report {:id 3 :kind "rragent.message" :data {:from 184 :name "101 template" :text "Ready too."}}
        reply (assistant 4 [{:type "text" :text "Noted the 101 report."}])
        items (fn [entries] (view/items {:entries entries}))]
    (testing "the card is the last item: on screen, nothing pinned"
      (is (nil? (view/open-card (items [(person 1 "Go") card])))))
    (testing "a worker's report and the bot's reply push the card up: pinned until the person answers"
      (is (= 2 (:id (view/open-card (items [(person 1 "Go") card report])))))
      (is (= 2 (:id (view/open-card (items [(person 1 "Go") card report reply]))))))
    (testing "the person answered: nothing open"
      (is (nil? (view/open-card (items [(person 1 "Go") card report reply (person 5 "203: approve")])))))
    (testing "an answer without a question is no card"
      (is (nil? (view/open-card (items [(person 1 "Go") reply report])))))
    (testing "the strip above the composer: what it asks and the way up"
      (let [page (i18n/translate (view/page {:agent {} :conversation {:entries [(person 1 "Go") card report]} :locale :en :bot {:id "7"}}) :en)
            strip (find-tag page :div.waiting)]
        (is (some? strip))
        (is (= "#item-2" (get-in strip [1 1 :href])))
        (is (some #(= "Waiting for your answer:" %) (tree-seq coll? seq strip)))
        (is (some #(= "The 203 template is ready." %) (tree-seq coll? seq strip))))
      (is (nil? (find-tag (view/page {:agent {} :conversation {:entries [(person 1 "Go") card]} :locale :en :bot {:id "7"}}) :div.waiting)) "the card itself is the last item"))))

(deftest sidebar-test
  (let [bots [{:id "1" :name "Assistant" :label "Assistant" :state :idle}
              {:id "2" :name "Old one" :label "Old one" :state :idle :archived? true}
              {:id "3" :name "Content week" :label "Content week" :state :running :needs 2}
              {:id "4" :name "Importer" :label "Importer" :state :idle :tasks 1}
              {:id "5" :name "Stories" :label "Stories" :state :asleep :needs 1}]
        page (i18n/translate (view/page {:bots bots :bot {:id "3" :name "Content week"} :conversation nil :locale :en}) :en)
        sidebar (find-tag page :aside.sidebar)
        rows (filter #(and (vector? %) (= :li (first %))) (nodes sidebar))
        row (fn [name] (some #(when (= name (last (find-tag % :span.name))) %) rows))
        classes (fn [name] (set (:class (second (row name)))))]
    (testing "every bot a row, in the order made, the archived ones last; the way to make one over them, for everyone"
      (is (= ["Assistant" "Content week" "Importer" "Stories" "Old one"] (map #(last (find-tag % :span.name)) rows)))
      (is (= [[:data/command {:command/kind :bot/create :command/data {:tab :ui/tabid}}]]
             (get-in (second (find-tag sidebar :button.new)) [:on :click])))
      (is (= "/bot/4" (:href (second (find-tag (row "Importer") :a.row)))))
      (is (some? (find-tag (row "Importer") :details.actions)) "each row with its menu: rename, delete"))
    (testing "the one shown is marked"
      (is (contains? (classes "Content week") "current"))
      (is (not (contains? (classes "Assistant") "current"))))
    (testing "Needs you while a card of the bot waits, the count from the second on, in place of its state"
      (is (= ["Needs you" " · 2"] (rest (find-tag (row "Content week") :span.badge.waits))))
      (is (= ["Needs you" nil] (rest (find-tag (row "Stories") :span.badge.waits))))
      (is (nil? (find-tag (row "Content week") :span.state)))
      (is (contains? (classes "Stories") "waits"))
      (is (nil? (find-tag (row "Assistant") :span.badge.waits))))
    (testing "what works is marked: a run that goes, or a task of a bot that is ready itself"
      (is (contains? (classes "Content week") "working"))
      (is (some? (find-tag (row "Content week") :span.dot)) "beside the badge, which took the state's place")
      (is (contains? (classes "Importer") "working"))
      (is (= "running" (last (find-tag (row "Importer") :span.state))) "a task goes: the state says so")
      (is (not (contains? (classes "Assistant") "working")))
      (is (nil? (find-tag (row "Stories") :span.dot))))
    (testing "an archived bot says so"
      (is (contains? (classes "Old one") "archived"))
      (is (= "archived" (last (find-tag (row "Old one") :span.state.archived)))))))

(deftest needs-test
  (let [person (fn [id text] {:id id :kind "pi.user" :model [{:role "user" :content text :timestamp id}]})
        card (fn [id] (assistant id [{:type "text" :text "Ready.\n\n```hiccup\n[:button {:send \"approve\"} \"Approve\"]\n```"}]))
        items (fn [entries] (view/items {:entries entries}))]
    (is (= 0 (view/needs (items [(person 1 "Go") (assistant 2 [{:type "text" :text "Done."}])]))))
    (is (= 1 (view/needs (items [(person 1 "Go") (card 2)]))) "a card waits")
    (is (= 0 (view/needs (items [(person 1 "Go") (card 2) (person 3 "approve")]))) "answered")
    (let [conversation {:entries [(person 1 "Go") (card 2)]}]
      (is (identical? (view/items conversation) (view/items conversation)) "the items of a conversation are made once")
      (is (= 1 (view/needs (view/items conversation)) (view/needs (view/items conversation)))))))

(deftest topic-of-before-test
  (testing "a message from the time the machine was one conversation: the topic behind the name, the label gone from the text"
    (let [entry (fn [id data content] {:id id :kind "rragent.person" :data data :model [{:role "user" :content content :timestamp id}]})
          [in-topic fresh plain] (view/items {:entries [(entry 1 {:name "Lydia" :topic "content-week"} "Lydia (Content week): The covers, please.")
                                                        (entry 2 {:fresh "t-1"} "(new topic): A Pinterest short.")
                                                        (entry 3 {:name "Lydia"} "Lydia: And now?")]})]
      (is (= "Lydia · content-week" (:person in-topic)))
      (is (= "The covers, please." (:text in-topic)))
      (is (= "A Pinterest short." (:text fresh)))
      (is (= "Lydia" (:person plain)))
      (is (= "And now?" (:text plain))))))
