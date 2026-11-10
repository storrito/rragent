(ns rragent.views-test
  "The views of the one conversation (docs/one-loop.md §3): every
   turn tagged with its topics, the topic the person's message came
   from and the topics the `topic` tool calls name; the sidebar's
   rows, the topics, those that need the person marked and first,
   then by their last activity, the archived ones folded, no view of
   everything at once; a view that filters the feed, the default one,
   the feed's window; the composer that names the view's topic and
   sends it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rragent.i18n :as i18n]
            [rragent.tools.topic :as topic-tool]
            [rragent.view :as view]))

(defn- person [id name topic text]
  {:id id :kind "rragent.person" :data (cond-> {:name name} topic (assoc :topic topic))
   :model [{:role "user" :content (str name (when topic (str " (" (case topic "content-week" "Content week" "pinterest" "Pinterest" topic) ")")) ": " text) :timestamp id}]})

(defn- assistant [id content]
  {:id id :kind "pi.assistant" :model [{:role "assistant" :content content :stopReason "stop" :timestamp id}]})

(defn- tool-result [id call-id text]
  {:id id :kind "pi.tool-result" :model [{:role "toolResult" :toolCallId call-id :content [{:type "text" :text text}] :isError false}]})

(def card
  "An answer with a card: a button that sends."
  "Covers v2 are in.\n```hiccup\n[:div [:button {:send \"202: approve\"} \"Approve\"]]\n```")

(def topics
  {"content-week" {:name "Content week" :created 10 :archived? false}
   "pinterest" {:name "Pinterest" :created 20 :archived? false}
   "old" {:name "Old job" :created 1 :archived? true}})

(def conversation
  {:entries [{:id 1 :kind "pi.system" :model [{:role "system" :content ""}]}
             ;; a turn of no topic until the model makes one
             (person 2 "Lydia" nil "Let us do a Pinterest short.")
             (assistant 3 [{:type "toolCall" :id "c1" :name "topic" :arguments {:action "create" :name "Pinterest"}}])
             (tool-result 4 "c1" "Made the topic Pinterest (pinterest); this turn is under it.")
             (assistant 5 [{:type "text" :text "Pinterest it is."}])
             ;; a turn from the content-week view, with a card that waits
             (person 6 "Lydia" "content-week" "How are the covers?")
             (assistant 7 [{:type "text" :text card}])
             ;; a task's result, tagged with its topic, and the loop's words, tagged too by a tag call
             {:id 8 :kind "rragent.task-done" :data {:task 9 :topic "content-week" :state "done" :line "Eight covers."} :model [{:role "user" :content "The task 9 is done."}]}
             (assistant 9 [{:type "toolCall" :id "c2" :name "topic" :arguments {:action "tag" :topic "Pinterest"}}])
             (tool-result 10 "c2" "This turn is under Pinterest (pinterest) too.")
             (assistant 11 [{:type "text" :text "Eight covers, and one fits Pinterest."}])]
   :live {}
   :inbox {:items []}})

(defn- page [opts]
  (i18n/translate (view/page (merge {:bots [{:id "7" :name "Assistant" :label "Assistant" :state :running :loop? true}
                                            {:id "1" :name "watcher" :label "watcher" :state :idle :archived? true}]
                                     :bot {:id "7" :name "Assistant"}
                                     :conversation conversation
                                     :topics topics
                                     :advanced? false}
                                    opts))
                  :en))

(defn- nodes [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- find-tag [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(defn- find-tags [tree tag]
  (filter #(and (vector? %) (= tag (first %))) (nodes tree)))

(defn- strings [tree]
  (filter string? (tree-seq coll? seq tree)))

(defn- feed-strings [p]
  (strings (find-tag p :section.feed)))

(deftest with-topics-test
  (let [items (view/items conversation topics)
        by-id (into {} (map (juxt :id identity)) items)]
    (is (= #{"pinterest"} (:topics (get by-id 2))) "a create call tags the turn with the new topic")
    (is (= #{"pinterest"} (:topics (get by-id 5))))
    (is (= #{"content-week"} (:topics (get by-id 6))) "the view the message came from")
    (is (= #{"content-week"} (:topics (get by-id 7))))
    (is (= #{"content-week" "pinterest"} (:topics (get by-id 8))) "a task's result and the turn after it, tagged by the tag call too")
    (is (= #{"content-week" "pinterest"} (:topics (get by-id 11))))
    (is (= "Let us do a Pinterest short." (:text (get by-id 2))) "the label is stripped for the page")
    (is (= "How are the covers?" (:text (get by-id 6))))
    (is (= 1 (count (view/open-cards items))) "one card waits")
    (is (= {"content-week" 1} (view/topic-counts items)))
    (is (= {"pinterest" 11 "content-week" 11} (view/topic-activity items)) "both topics were last touched by the last turn")))

(deftest topic-by-name-test
  (let [conversation {:entries [(person 1 "Lydia" nil "Go.")
                                ;; the task was started with the topic's name, not its id
                                {:id 2 :kind "rragent.task-done" :data {:task 3 :topic "Content week" :state "done" :line "Done."} :model [{:role "user" :content "The task 3 (Content week) is done."}]}
                                (assistant 3 [{:type "text" :text "Done, Lydia."}])
                                {:id 4 :kind "rragent.schedule" :data {:name "tick" :topic "Nowhere" :at 4} :model [{:role "user" :content "Tick."}]}
                                (assistant 5 [{:type "text" :text "Ticked."}])]
                      :live {}
                      :inbox {:items []}}
        items (view/items conversation topics)
        by-id (into {} (map (juxt :id identity)) items)]
    (is (= #{"content-week"} (:topics (get by-id 2))) "a task's topic given by its name tags the turn with the topic's id")
    (is (= #{"content-week"} (:topics (get by-id 3))))
    (is (= 2 (count (view/visible items {:kind :topic :topic "content-week"}))) "the topic's view shows the turn")
    (is (= #{"Nowhere"} (:topics (get by-id 4))) "a topic nothing matches stays as given")
    (is (= {"content-week" 3 "Nowhere" 5} (view/topic-activity items)))))

(deftest queued-topic-test
  (let [conversation {:entries [(person 1 "Lydia" "content-week" "Covers?")
                                (assistant 2 [{:type "text" :text "Eight."}])]
                      :live {}
                      :inbox {:items [{:id "q1" :mode "followUp" :content "And the captions?"
                                       :entry {:kind "rragent.person" :data {:name "Lydia" :topic "content-week"}}}]}}
        items (view/items conversation topics)
        queued (some #(when (= "q1" (:id %)) %) items)]
    (is (:pending? queued))
    (is (= #{"content-week"} (:topics queued)) "a waiting message keeps the topic of the view it was sent from")
    (is (= 3 (count (view/visible items {:kind :topic :topic "content-week"}))))))

(deftest resolve-or-keep-test
  (reset! topic-tool/topics-fn (fn [] topics))
  (try
    (is (= "content-week" (topic-tool/resolve-or-keep "Content week")) "by name")
    (is (= "content-week" (topic-tool/resolve-or-keep "content-week")) "by id")
    (is (= "pinterest" (topic-tool/resolve-or-keep " Pinterest ")))
    (is (= "Nowhere" (topic-tool/resolve-or-keep "Nowhere")) "kept when no topic matches")
    (is (nil? (topic-tool/resolve-or-keep "  ")))
    (is (nil? (topic-tool/resolve-or-keep nil)))
    (finally (reset! topic-tool/topics-fn (fn [] {})))))

(deftest sidebar-views-test
  (let [p (page {:view {:kind :topic :topic "content-week"}})
        views (find-tag p :section.loop-views)
        topics-section (find-tag p :section.topics)]
    (testing "not under the class of the phone's tabs, `.views`, which the stylesheet hides on a wide screen"
      (is (nil? (find-tag p :section.views)))
      (is (nil? (find-tag p :ul.views))))
    (testing "nothing over the topics: no view of everything, no row of the open cards while every open card has a topic"
      (is (nil? views))
      (is (not-any? #{"All"} (strings p)))
      (is (= ["+ New topic" "Content week" "Pinterest" "Old job"] (map #(second (find-tag % :span.name)) (find-tags topics-section :li)))))
    (testing "the topic that needs the person first and marked, then by last activity; the archived one folded, the current marked"
      (let [rows (find-tags (find-tag topics-section :ul.topics) :li)]
        (is (= ["Content week" "Pinterest"] (map #(second (find-tag % :span.name)) rows)) "both touched by the last turn and Pinterest made later, but the card of content-week waits")
        (is (= ["current" nil] (map #(:class (second %)) rows)))
        (is (= [[:span.badge.waits "Needs you" nil]] (find-tags (first rows) :span.badge.waits)) "the open card of content-week")
        (is (empty? (find-tags (second rows) :span.badge.waits))))
      (let [archived (find-tag topics-section :details.archived)]
        (is (some? archived))
        (is (nil? (:open (second archived))))
        (is (= ["Old job"] (map #(second (find-tag % :span.name)) (find-tags archived :li))))))
    (testing "without an open card the topics order by their last activity, then the newest made"
      (let [answered (update conversation :entries conj (person 12 "Lydia" "content-week" "202: approve"))
            rows (find-tags (find-tag (find-tag (page {:conversation answered}) :section.topics) :ul.topics) :li)]
        (is (= ["Content week" "Pinterest"] (map #(second (find-tag % :span.name)) rows)) "content-week touched last")
        (is (empty? (find-tags rows :span.badge.waits))))
      (let [quiet (update conversation :entries #(vec (remove (comp #{6 7} :id) %)))
            rows (find-tags (find-tag (find-tag (page {:conversation quiet}) :section.topics) :ul.topics) :li)]
        (is (= ["Pinterest" "Content week"] (map #(second (find-tag % :span.name)) rows)) "both touched by the last turn, Pinterest made later")))
    (testing "several open cards of a topic: their count beside the words; a card of an archived topic opens the fold"
      ;; no message of the person's between them: a message answers what was asked before it
      (let [more (update conversation :entries into [{:id 12 :kind "rragent.task-done" :data {:task 10 :topic "content-week" :state "done" :line "Captions."} :model [{:role "user" :content "The task 10 is done."}]}
                                                     (assistant 13 [{:type "text" :text card}])
                                                     {:id 14 :kind "rragent.task-done" :data {:task 11 :topic "old" :state "done" :line "Old job."} :model [{:role "user" :content "The task 11 is done."}]}
                                                     (assistant 15 [{:type "text" :text card}])])
            section (find-tag (page {:conversation more}) :section.topics)]
        (is (= [[:span.badge.waits "Needs you" " · 2"]] (find-tags (find-tag section :ul.topics) :span.badge.waits)))
        (is (true? (:open (second (find-tag section :details.archived)))))))
    (testing "an open card of no topic gets a row of its own, marked, which shows the turns of no topic"
      (let [loose (update conversation :entries into [{:id 12 :kind "rragent.schedule" :data {:name "tick" :at 12} :model [{:role "user" :content "Tick."}]}
                                                      (assistant 13 [{:type "text" :text card}])
                                                      {:id 14 :kind "rragent.task-done" :data {:task 10 :topic "pinterest" :state "done" :line "Pins."} :model [{:role "user" :content "The task 10 is done."}]}
                                                      (assistant 15 [{:type "text" :text "Pins are in."}])
                                                      {:id 16 :kind "rragent.schedule" :data {:name "tick" :at 16} :model [{:role "user" :content "Tick."}]}
                                                      (assistant 17 [{:type "text" :text "Ticked again."}])])
            row (find-tag (page {:conversation loose :view {:kind :topic :topic "pinterest"}}) :section.loop-views)
            [_ attrs] (find-tag row :button.row)]
        (is (= ["Without topic"] (map #(second (find-tag % :span.name)) (find-tags row :li))))
        (is (= [[:span.badge.waits "Needs you" nil]] (find-tags row :span.badge.waits)))
        (is (= {:tab :ui/tabid :kind :loose} (get-in attrs [:on :click 0 1 :command/data])))
        (let [p (page {:conversation loose :view {:kind :loose}})
              s (feed-strings p)]
          (is (= "current" (:class (second (find-tag (find-tag p :section.loop-views) :li)))))
          (is (not (some #{"How are the covers?"} s)) "no turn of a topic")
          (is (not (some #{"Pins are in."} s)))
          (is (some #{"Ticked again."} s))
          (is (some? (find-tag p :div.waiting)) "the card pushed up by the next turn of no topic is pinned in its view")
          (is (nil? (find-tag (page {:conversation loose :view {:kind :topic :topic "pinterest"}}) :div.waiting))))))
    (testing "the row of no topic stays while the tab shows that view, the card answered"
      (is (some? (find-tag (page {:view {:kind :loose}}) :section.loop-views))))
    (testing "a row sends the view for the tab"
      (let [[_ attrs] (find-tag (second (find-tags (find-tag topics-section :ul.topics) :li)) :button.row)]
        (is (= {:tab :ui/tabid :kind :topic :topic "pinterest"} (get-in attrs [:on :click 0 1 :command/data])))))
    (testing "no raw list without the advanced switch"
      (is (nil? (find-tag p :section.all)))
      (is (some? (find-tag (page {:advanced? true}) :section.all))))))

(deftest view-filters-test
  (testing "no view of everything: a tab that picked none shows the first topic of the sidebar"
    (let [p (page {:view nil})
          s (feed-strings p)]
      (is (not (some #{"Let us do a Pinterest short."} s)) "Pinterest's turn is not content-week's")
      (is (some #{"How are the covers?"} s))
      (is (= "in Content week" (second (find-tag p :span.topic))))
      (is (= ["Content week"] (keep #(when (= "current" (:class (second %))) (second (find-tag % :span.name)))
                                    (find-tags p :li))))))
  (testing "a topic: its turns alone"
    (let [s (feed-strings (page {:view {:kind :topic :topic "pinterest"}}))]
      (is (some #{"Let us do a Pinterest short."} s))
      (is (not (some #{"How are the covers?"} s)) "the content-week turn is not Pinterest's")
      (is (some #{"Eight covers, and one fits Pinterest."} s) "the turn tagged with both"))
    (let [s (feed-strings (page {:view {:kind :topic :topic "content-week"}}))]
      (is (not (some #{"Let us do a Pinterest short."} s)))
      (is (some #{"How are the covers?"} s))))
  (testing "a topic without anything says so"
    (is (some #{"Nothing here yet."} (feed-strings (page {:view {:kind :topic :topic "old"}})))))
  (testing "the waiting card is pinned where it is shown: in its topic's view, not in another topic's or in the New topic view"
    (is (some? (find-tag (page {:view {:kind :topic :topic "content-week"}}) :div.waiting)))
    (is (nil? (find-tag (page {:view {:kind :topic :topic "pinterest"}}) :div.waiting)))
    (is (nil? (find-tag (page {:view {:kind :topic :topic "old"}}) :div.waiting)))
    (is (nil? (find-tag (page {:view {:kind :new}}) :div.waiting))))
  (testing "the pinned line is plain text: no marks of emphasis"
    (let [strip (find-tag (i18n/translate (view/waiting {:id 7 :text "It went from **1:35** to `1:20`."}) :en) :span.preview)]
      (is (= "It went from 1:35 to 1:20." (last strip)))))
  (testing "another conversation than the loop shows whole"
    (let [s (feed-strings (page {:bot {:id "1" :name "watcher"} :view {:kind :topic :topic "pinterest"}}))]
      (is (some #{"How are the covers?"} s)))))

(def fresh-conversation
  "Two messages from the \"New topic\" view under one token: the first
   the model only answered, in the second's turn it made the topic."
  {:entries [(person 1 "Lydia" "content-week" "Covers?")
             (assistant 2 [{:type "text" :text "Eight."}])
             {:id 3 :kind "rragent.person" :data {:name "Lydia" :fresh "t-1"}
              :model [{:role "user" :content "Lydia (new topic): Something for autumn." :timestamp 3}]}
             (assistant 4 [{:type "text" :text "What is it about?"}])
             {:id 5 :kind "rragent.person" :data {:name "Lydia" :fresh "t-1"}
              :model [{:role "user" :content "Lydia (new topic): A sale." :timestamp 5}]}
             (assistant 6 [{:type "toolCall" :id "c1" :name "topic" :arguments {:action "create" :name "Autumn sale"}}])]
   :live {}
   :inbox {:items [{:id "q1" :mode "steer" :content "Lydia (new topic): From Monday."
                    :entry {:kind "rragent.person" :data {:name "Lydia" :fresh "t-1"}}}]}})

(deftest new-topic-view-test
  (let [new-view {:kind :new :fresh "t-1"}
        streaming (view/items fresh-conversation topics)
        made (view/items (update fresh-conversation :entries conj
                                 (tool-result 7 "c1" "Made the topic Autumn sale (autumn-sale); this turn is under it.")
                                 (assistant 8 [{:type "text" :text "Autumn sale it is."}]))
                         topics)
        by-id (fn [items] (into {} (map (juxt :id identity)) items))]
    (testing "a message from the view: the token on its turn, the text without the label"
      (is (= "t-1" (:fresh (get (by-id streaming) 3))))
      (is (= "t-1" (:fresh (get (by-id streaming) 4))) "the answer is of the turn")
      (is (nil? (:fresh (get (by-id streaming) 2))))
      (is (= "Something for autumn." (:text (get (by-id streaming) 3))))
      (is (= "t-1" (:fresh (get (by-id streaming) "q1"))) "a waiting message too"))
    (testing "the view before a message shows nothing, after it the turns of its token"
      (is (empty? (view/visible streaming {:kind :new})))
      (is (= [3 4 5 6 "c1" "q1"] (map :id (view/visible streaming new-view))))
      (is (empty? (view/visible streaming {:kind :new :fresh "other"}))))
    (testing "the view stays while the call that makes the topic has not run"
      (is (= new-view (view/settled new-view streaming topics)))
      (is (= {:kind :new} (view/settled {:kind :new} made topics)) "no token, nothing to find")
      (is (= {:kind :all} (view/settled {:kind :all} made topics))))
    (testing "the topic made: the view is the topic's, with every turn of the token"
      (is (= {:kind :topic :topic "autumn-sale"} (view/settled new-view made topics)))
      (is (= #{"autumn-sale"} (:topics (get (by-id made) 3))) "what was said before the topic was made is the topic's too")
      (is (= [3 4 5 6 "c1" 8 "q1"] (map :id (view/visible made {:kind :topic :topic "autumn-sale"})))))
    (testing "a failed call makes no topic's view"
      (let [failed (view/items (update fresh-conversation :entries conj
                                       {:id 7 :kind "pi.tool-result" :model [{:role "toolResult" :toolCallId "c1" :content [{:type "text" :text "No."}] :isError true}]})
                               topics)]
        (is (= new-view (view/settled new-view failed topics)))))
    (testing "a tag of a topic that exists moves the view there"
      (let [tagged (view/items {:entries [{:id 3 :kind "rragent.person" :data {:name "Lydia" :fresh "t-1"}
                                           :model [{:role "user" :content "Lydia (new topic): More pins." :timestamp 3}]}
                                          (assistant 4 [{:type "toolCall" :id "c1" :name "topic" :arguments {:action "tag" :topic "Pinterest"}}])
                                          (tool-result 5 "c1" "This turn is under Pinterest (pinterest).")]
                                :live {}
                                :inbox {:items []}}
                               topics)]
        (is (= {:kind :topic :topic "pinterest"} (view/settled new-view tagged topics)))))))

(deftest new-topic-page-test
  (testing "the row is there without any topic, over the topics, and sends the view"
    (let [p (page {:view {:kind :topic :topic "pinterest"}})
          section (find-tag p :section.topics)
          row (find-tag section :ul.topics.new)
          [_ attrs] (find-tag row :button.row)]
      (is (= "+ New topic" (second (find-tag row :span.name))))
      (is (= {:tab :ui/tabid :kind :new} (get-in attrs [:on :click 0 1 :command/data])))
      (is (nil? (:class (second (find-tag row :li))))))
    (let [p (page {:topics {}})
          row (find-tag (find-tag p :section.topics) :ul.topics.new)]
      (is (some? row) "the Topics section stands without a topic")
      (is (= "current" (:class (second (find-tag row :li)))) "and its view is the one a machine without a topic shows")))
  (testing "the empty view: the row marked, a line that says how it starts, the composer asking for the topic"
    (let [p (page {:view {:kind :new} :bot {:id "7" :name "Assistant" :model "faux-1" :models [{:id "faux-1" :name "Faux"}]}})
          composer (find-tag p :ui/composer)
          send (:ui/send (second composer))]
      (is (= "current" (:class (second (find-tag (find-tag p :ul.topics.new) :li)))))
      (is (not-any? #(= "current" (:class (second %))) (find-tags (find-tag p :ul.topics) :li)))
      (is (some #{"A new topic starts with your first message here: say what it is about."} (feed-strings p)))
      (is (not (some #{"How are the covers?"} (feed-strings p))))
      (is (= "What is the new topic about?" (:placeholder (second composer))))
      (is (= "in a new topic" (second (find-tag p :span.topic))))
      (is (= :ui/tabid (get-in send [0 1 :command/data :tab])) "the tab's view says that the message opens a topic")
      (is (nil? (get-in send [0 1 :command/data :topic])))
      (is (= [:draft "7/new topic"] (:ui/store-key (second composer))) "a draft of its own")))
  (testing "after the first message the view shows its turns; once the topic is made the page shows the topic's view"
    (let [p (page {:conversation fresh-conversation :view {:kind :new :fresh "t-1"}})]
      (is (some #{"Something for autumn."} (feed-strings p)))
      (is (not (some #{"Covers?"} (feed-strings p))))
      (is (= "current" (:class (second (find-tag (find-tag p :ul.topics.new) :li))))))
    (let [p (page {:conversation (update fresh-conversation :entries conj
                                         (tool-result 7 "c1" "Made the topic Autumn sale (autumn-sale); this turn is under it."))
                   :topics (assoc topics "autumn-sale" {:name "Autumn sale" :created 30 :archived? false})
                   :view {:kind :new :fresh "t-1"}})
          composer (find-tag p :ui/composer)]
      (is (= "in Autumn sale" (second (find-tag p :span.topic))))
      (is (= "autumn-sale" (get-in (:ui/send (second composer)) [0 1 :command/data :topic])))
      (is (some #{"Something for autumn."} (feed-strings p)))
      (is (nil? (:class (second (find-tag (find-tag p :ul.topics.new) :li)))))
      (is (= ["Autumn sale"] (keep #(when (= "current" (:class (second %))) (second (find-tag % :span.name)))
                                   (find-tags (find-tag p :ul.topics) :li)))))))

(deftest composer-topic-test
  (testing "in a topic's view the composer names it and sends it"
    (let [p (page {:view {:kind :topic :topic "content-week"} :bot {:id "7" :name "Assistant" :model "faux-1" :models [{:id "faux-1" :name "Faux"}]}})
          composer (find-tag p :ui/composer)
          send (:ui/send (second composer))]
      (is (= "in Content week" (second (find-tag p :span.topic))))
      (is (= "content-week" (get-in send [0 1 :command/data :topic])))
      (is (= [:draft "7/content-week"] (:ui/store-key (second composer))) "a draft per view")))
  (testing "in the view of the turns of no topic neither"
    (let [p (page {:view {:kind :loose}})
          composer (find-tag p :ui/composer)]
      (is (nil? (find-tag p :span.topic)))
      (is (nil? (get-in (:ui/send (second composer)) [0 1 :command/data :topic])))
      (is (= [:draft "7"] (:ui/store-key (second composer)))))))

(deftest default-view-test
  (let [items (view/items conversation topics)]
    (testing "the first topic of the sidebar's order: the one a card waits in, else the last touched, never an archived one"
      (is (= {:kind :topic :topic "content-week"} (view/default-view items topics)))
      (let [quiet (view/items (update conversation :entries #(vec (remove (comp #{6 7} :id) %))) topics)]
        (is (= {:kind :topic :topic "pinterest"} (view/default-view quiet topics)) "both touched by the last turn, Pinterest made later"))
      (is (= {:kind :topic :topic "pinterest"} (view/default-view [] topics)) "nothing said yet: the newest made"))
    (testing "a machine without a topic, or with archived ones alone: the New topic view"
      (is (= {:kind :new} (view/default-view items {})))
      (is (= {:kind :new} (view/default-view items (select-keys topics ["old"])))))
    (testing "the view a tab shows: its pick, the default without one"
      (is (= {:kind :topic :topic "pinterest"} (view/shown-view {:kind :topic :topic "pinterest"} items topics)))
      (is (= {:kind :new} (view/shown-view {:kind :new} items topics)))
      (is (= {:kind :loose} (view/shown-view {:kind :loose} items topics)))
      (is (= {:kind :topic :topic "content-week"} (view/shown-view nil items topics))))
    (testing "the turns of no topic"
      (is (empty? (view/visible items {:kind :loose})) "every turn of the conversation got a topic")
      (let [loose (view/items (update conversation :entries conj (person 12 "Lydia" nil "Anything else?")) topics)]
        (is (= [12] (map :id (view/visible loose {:kind :loose}))))))))

(deftest items-kept-test
  (testing "the items of a conversation are made once per conversation and topics"
    (let [made (view/items conversation topics)]
      (is (vector? made))
      (is (identical? made (view/items conversation topics)))
      (is (identical? made (view/items conversation (into {} topics))) "topics that are equal")
      ;; an equal map of another identity: assoc of the same value answers the map itself
      (is (not (identical? made (view/items (into {} conversation) topics))) "another map, made anew")
      (is (= made (view/items (into {} conversation) topics)))
      (is (not (identical? made (view/items conversation {}))) "other topics, other tags")))
  (testing "no conversation: no items"
    (is (empty? (view/items nil topics)))))

(deftest feed-window-test
  (let [turns (fn [n] {:entries (vec (mapcat (fn [i] [(person (* 2 i) "Lydia" "content-week" (str "Question " i))
                                                      (assistant (inc (* 2 i)) [{:type "text" :text (str "Answer " i)}])])
                                             (range 1 (inc n))))
                       :live {}
                       :inbox {:items []}})
        texts (fn [p] (filter #(re-find #"^(Question|Answer) \d+$" %) (feed-strings p)))
        view {:kind :topic :topic "content-week"}]
    (testing "a short feed: every item, no row above"
      (let [p (page {:conversation (turns 3) :view view})]
        (is (= 6 (count (texts p))))
        (is (nil? (find-tag p :div.item.earlier)))))
    (testing "a long feed: the newest items of the window, the row that brings the earlier ones"
      (let [p (page {:conversation (turns 150) :view view})
            row (find-tag p :div.item.earlier)]
        (is (= view/feed-window (count (texts p))))
        (is (= "Question 51" (first (texts p))))
        (is (= "Answer 150" (last (texts p))))
        (is (= "Show earlier messages" (last (find-tag row :button))))
        (is (= [[:ui/keep-place]
                [:data/command {:command/kind :view/earlier :command/data {:tab :ui/tabid}}]]
               (get-in (second (find-tag row :button)) [:on :click]))
            "the reader's place is taken before the command goes")
        (is (= :section.feed (first (find-tag p :section.feed))))
        (is (= row (first (filter vector? (nodes (rest (rest (find-tag p :section.feed))))))) "the row is the first of the feed")))
    (testing "the tab's limit: more of them, then all and no row"
      (let [p (page {:conversation (turns 150) :view view :limit 250})]
        (is (= 250 (count (texts p))))
        (is (= "Question 26" (first (texts p))))
        (is (some? (find-tag p :div.item.earlier))))
      (let [p (page {:conversation (turns 150) :view view :limit 700})]
        (is (= 300 (count (texts p))))
        (is (nil? (find-tag p :div.item.earlier)))))
    (testing "the window reaches back to the card the page pins"
      (let [done (fn [id] {:id id :kind "rragent.task-done" :data {:task id :topic "content-week" :state "done" :line (str "Result " id)}
                           :model [{:role "user" :content (str "The task " id " is done.")}]})
            conversation {:entries (into [(person 1 "Lydia" "content-week" "Covers?")
                                          (assistant 2 [{:type "text" :text card}])]
                                         (map done (range 1000 1300)))
                          :live {}
                          :inbox {:items []}}
            p (page {:conversation conversation :view view})]
        (is (some? (find-tag p :div.waiting)))
        (is (some #(str/includes? % "Covers v2 are in.") (feed-strings p)) "the card itself is in the feed, so the pin's link finds it")
        (is (not (some #{"Covers?"} (feed-strings p))) "what came before the card is behind the row")
        (is (some? (find-tag p :div.item.earlier)))))))
