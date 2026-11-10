(ns rragent.views-test
  "The views of the one conversation (docs/one-loop.md §3): every
   turn tagged with its topics, the topic the person's message came
   from and the topics the `topic` tool calls name; the sidebar's
   rows, all, what needs the person with the count of the open cards,
   the topics by their last activity with their counts, the archived
   ones folded; a view that filters the feed; the composer that names
   the view's topic and sends it."
  (:require [clojure.test :refer [deftest is testing]]
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
             ;; a turn from the view of all: no topic until the model makes one
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
        views (find-tag p :section.views)
        topics-section (find-tag p :section.topics)]
    (testing "all and needs you, the badge the open cards"
      (is (= ["All" "Needs you"] (map #(second (find-tag % :span.name)) (find-tags views :li))))
      (is (= ["1"] (map second (find-tags views :span.badge)))))
    (testing "the topics by their last activity, then the newest made, the archived one folded, the current marked"
      (let [rows (find-tags (find-tag topics-section :ul.topics) :li)]
        (is (= ["Pinterest" "Content week"] (map #(second (find-tag % :span.name)) rows)) "both touched by the last turn, Pinterest made later")
        (is (= [nil "current"] (map #(:class (second %)) rows)))
        (is (= ["1"] (map second (find-tags (second rows) :span.badge))) "the open card of content-week")
        (is (empty? (find-tags (first rows) :span.badge))))
      (let [archived (find-tag topics-section :details.archived)]
        (is (some? archived))
        (is (= ["Old job"] (map #(second (find-tag % :span.name)) (find-tags archived :li))))))
    (testing "a row sends the view for the tab"
      (let [[_ attrs] (find-tag (first (find-tags (find-tag topics-section :ul.topics) :li)) :button.row)]
        (is (= {:tab :ui/tabid :kind :topic :topic "pinterest"} (get-in attrs [:on :click 0 1 :command/data])))))
    (testing "no raw list without the advanced switch"
      (is (nil? (find-tag p :section.all)))
      (is (some? (find-tag (page {:advanced? true}) :section.all))))))

(deftest view-filters-test
  (testing "all: every turn"
    (let [s (feed-strings (page {:view {:kind :all}}))]
      (is (some #{"Let us do a Pinterest short."} s))
      (is (some #{"How are the covers?"} s))
      (is (some #{"Eight covers, and one fits Pinterest."} s))))
  (testing "a topic: its turns alone"
    (let [s (feed-strings (page {:view {:kind :topic :topic "pinterest"}}))]
      (is (some #{"Let us do a Pinterest short."} s))
      (is (not (some #{"How are the covers?"} s)) "the content-week turn is not Pinterest's")
      (is (some #{"Eight covers, and one fits Pinterest."} s) "the turn tagged with both"))
    (let [s (feed-strings (page {:view {:kind :topic :topic "content-week"}}))]
      (is (not (some #{"Let us do a Pinterest short."} s)))
      (is (some #{"How are the covers?"} s))))
  (testing "needs you: the open cards alone"
    (let [s (feed-strings (page {:view {:kind :needs}}))]
      (is (some #{"Covers v2 are in."} s))
      (is (not (some #{"How are the covers?"} s)))))
  (testing "a topic without anything says so"
    (is (some #{"Nothing here yet."} (feed-strings (page {:view {:kind :topic :topic "old"}})))))
  (testing "the waiting card is pinned whatever the view"
    (is (some? (find-tag (page {:view {:kind :topic :topic "pinterest"}}) :div.waiting))))
  (testing "another conversation than the loop shows whole"
    (let [s (feed-strings (page {:bot {:id "1" :name "watcher"} :view {:kind :topic :topic "pinterest"}}))]
      (is (some #{"How are the covers?"} s)))))

(deftest composer-topic-test
  (testing "in a topic's view the composer names it and sends it"
    (let [p (page {:view {:kind :topic :topic "content-week"} :bot {:id "7" :name "Assistant" :model "faux-1" :models [{:id "faux-1" :name "Faux"}]}})
          composer (find-tag p :ui/composer)
          send (:ui/send (second composer))]
      (is (= "in Content week" (second (find-tag p :span.topic))))
      (is (= "content-week" (get-in send [0 1 :command/data :topic])))
      (is (= [:draft "7/content-week"] (:ui/store-key (second composer))) "a draft per view")))
  (testing "in the view of all neither"
    (let [p (page {:view {:kind :all}})
          composer (find-tag p :ui/composer)]
      (is (nil? (find-tag p :span.topic)))
      (is (nil? (get-in (:ui/send (second composer)) [0 1 :command/data :topic])))
      (is (= [:draft "7"] (:ui/store-key (second composer)))))))
