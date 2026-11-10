(ns rragent.client.draft-test
  "The draft of the composer outside the store: the text by its key,
   the node of the text when one is mounted, and the store's word on
   whether there is a draft."
  (:require [clojure.test :refer [deftest is testing]]
            [rframes.client.state :as state]
            [rragent.client.draft :as draft]))

(defn- fresh!
  []
  (reset! draft/drafts* {})
  (swap! state/store dissoc [:draft "7"] [:draft "7/topic"]))

(deftest text-test
  (fresh!)
  (testing "no draft is an empty text"
    (is (= "" (draft/text [:draft "7"]))))
  (testing "a draft per key"
    (draft/write! [:draft "7"] "Hello")
    (draft/write! [:draft "7/topic"] "In the topic")
    (is (= "Hello" (draft/text [:draft "7"])))
    (is (= "In the topic" (draft/text [:draft "7/topic"]))))
  (testing "cleared, the draft is gone, the other stays"
    (draft/clear! [:draft "7"])
    (is (= "" (draft/text [:draft "7"])))
    (is (not (contains? @draft/drafts* [:draft "7"])))
    (is (= "In the topic" (draft/text [:draft "7/topic"])))))

(deftest store-flag-test
  (fresh!)
  (testing "the store learns that there is a draft, not what it says"
    (draft/write! [:draft "7"] "Hello")
    (is (true? (get @state/store [:draft "7"]))))
  (testing "spaces alone are kept as the text, but are no draft to the store"
    (draft/write! [:draft "7"] "   ")
    (is (= "   " (draft/text [:draft "7"])))
    (is (not (contains? @state/store [:draft "7"]))))
  (testing "cleared, the store forgets it"
    (draft/write! [:draft "7"] "Hello")
    (draft/clear! [:draft "7"])
    (is (not (contains? @state/store [:draft "7"])))))

(deftest flagged-test
  (testing "a text sets the key, a blank one removes it"
    (is (= {[:draft "7"] true} (draft/flagged {} [:draft "7"] "Hello")))
    (is (= {} (draft/flagged {[:draft "7"] true} [:draft "7"] "")))
    (is (= {} (draft/flagged {[:draft "7"] true} [:draft "7"] "   "))))
  (testing "a state that says so already is the same state"
    (let [state {[:draft "7"] true :other 1}]
      (is (identical? state (draft/flagged state [:draft "7"] "Hello"))))
    (let [state {:other 1}]
      (is (identical? state (draft/flagged state [:draft "7"] ""))))))

(defn- fake-node
  "A node with a `value` that counts how often it is set."
  []
  (let [sets (atom 0)
        value (atom "")
        node #js {}]
    (js/Object.defineProperty node "value"
                              #js {:get (fn [] @value)
                                   :set (fn [v] (swap! sets inc) (reset! value v))})
    {:node node :sets sets}))

(deftest mounted-node-test
  (fresh!)
  (testing "the node takes the draft of its key on the mount"
    (draft/write! [:draft "7"] "Typed before")
    (let [{:keys [node sets]} (fake-node)]
      (draft/mount! [:draft "7"] node)
      (is (= "Typed before" (.-value node)))
      (testing "a write to its key goes to the node, one to another key does not"
        (draft/write! [:draft "7"] "More")
        (draft/write! [:draft "7/topic"] "Elsewhere")
        (is (= "More" (.-value node))))
      (testing "a node that has the text already is left alone, the caret with it"
        (reset! sets 0)
        (draft/write! [:draft "7"] "More")
        (is (= 0 @sets))
        (draft/write! [:draft "7"] "More!")
        (is (= 1 @sets)))
      (testing "cleared, the node is empty"
        (draft/clear! [:draft "7"])
        (is (= "" (.-value node))))
      (testing "unmounted, a write reaches the atom alone"
        (draft/unmount! node)
        (draft/write! [:draft "7"] "Later")
        (is (= "" (.-value node)))
        (is (= "Later" (draft/text [:draft "7"]))))))
  (testing "the unmount of an older node leaves a newer one mounted"
    (let [{old :node} (fake-node)
          {new :node} (fake-node)]
      (draft/mount! [:draft "7"] old)
      (draft/mount! [:draft "7/topic"] new)
      (draft/unmount! old)
      (draft/write! [:draft "7/topic"] "Still here")
      (is (= "Still here" (.-value new)))
      (draft/unmount! new))))
