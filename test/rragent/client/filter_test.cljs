(ns rragent.client.filter-test
  "The field of the list's filter and the server that narrows the
   list: when the field tells its text, a wait after the first letter
   the server does not have and once per text, and again when the
   server lost it."
  (:require [clojure.test :refer [async deftest is testing]]
            [rframes.client.state :as state]
            [rragent.client.filter :as list-filter]))

(deftest seen-test
  (testing "the entry holds the field's text and what the rows are narrowed by"
    (is (= {:text "pl" :query ""} (list-filter/seen nil "pl" ""))))
  (testing "the text asked for stays until the server has it"
    (is (= {:text "plan" :query "pl" :asked "plan"} (list-filter/seen {:asked "plan"} "plan" "pl")))
    (is (= {:text "plan" :query "plan"} (list-filter/seen {:asked "plan"} "plan" "plan"))))
  (testing "a wait that runs is kept"
    (is (= {:text "p" :query "" :timer 7} (list-filter/seen {:timer 7} "p" "")))))

(deftest due-test
  (is (false? (list-filter/due? {:text "" :query ""})) "nothing typed, nothing narrowed")
  (is (true? (list-filter/due? {:text "pl" :query ""})) "a text the server does not have")
  (is (true? (list-filter/due? {:text "" :query "pl"})) "a field emptied")
  (is (false? (list-filter/due? {:text "pl" :query "" :asked "pl"})) "sent, the frame is on its way")
  (is (true? (list-filter/due? {:text "plan" :query "" :asked "pl"})) "typed on since"))

(defn- wait
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest follow-test
  (async done
    (let [sent (atom [])
          texts #(mapv (fn [[_ command]] (get-in command [:command/data :text])) @sent)
          key [:artifacts-filter "7"]
          past-wait #(wait (+ list-filter/wait-ms 60))]
      (reset! list-filter/fields* {})
      (swap! state/store assoc :replicant/dispatch! (fn [{:keys [actions]}] (swap! sent into actions)))
      (-> ((^:async fn []
             (testing "a field that holds what the rows are narrowed by tells nothing"
               (list-filter/follow! key "7" "" "")
               (await (past-wait))
               (is (= [] @sent)))
             (testing "the letters typed within the wait are one command, with the text the field holds at its end"
               (list-filter/follow! key "7" "p" "")
               (list-filter/follow! key "7" "pl" "")
               (is (= [] @sent) "a render sends nothing itself")
               (await (past-wait))
               (is (= [[:data/command {:command/kind :artifacts/filter
                                       :command/data {:bot "7" :text "pl" :tab state/tabid}}]]
                      @sent)))
             (testing "the renders before the server's frame, and a frame of an older text, ask nothing again"
               (list-filter/follow! key "7" "pl" "")
               (list-filter/follow! key "7" "pl" "p")
               (await (past-wait))
               (is (= ["pl"] (texts))))
             (testing "a text typed on meanwhile is told; the frame of the text before it changes nothing"
               (list-filter/follow! key "7" "plan" "")
               (await (past-wait))
               (list-filter/follow! key "7" "plan" "pl")
               (await (past-wait))
               (is (= ["pl" "plan"] (texts))))
             (testing "the server has the text: nothing more; a server that lost it, a machine that woke, is told again"
               (list-filter/follow! key "7" "plan" "plan")
               (await (past-wait))
               (is (= ["pl" "plan"] (texts)))
               (list-filter/follow! key "7" "plan" "")
               (await (past-wait))
               (is (= ["pl" "plan" "plan"] (texts))))
             (testing "a text taken back within the wait is not told"
               (list-filter/follow! key "7" "plan" "plan")
               (list-filter/follow! key "7" "plans" "plan")
               (list-filter/follow! key "7" "plan" "plan")
               (await (past-wait))
               (is (= ["pl" "plan" "plan"] (texts))))
             (testing "a field emptied tells the blank text; each bot's field for itself"
               (list-filter/follow! key "7" "" "plan")
               (list-filter/follow! [:artifacts-filter "8"] "8" "x" "")
               (await (past-wait))
               (is (= #{{:bot "7" :text "" :tab state/tabid} {:bot "8" :text "x" :tab state/tabid}}
                      (set (map #(get-in % [1 :command/data]) (drop 3 @sent))))))
             (testing "letters typed slower than the wait are a command each: the list follows the typing"
               (reset! sent [])
               (list-filter/follow! key "7" "" "")
               (doseq [text ["s" "sh" "sho"]]
                 (list-filter/follow! key "7" text "")
                 (await (past-wait)))
               (is (= ["s" "sh" "sho"] (texts))))
             (testing "a wait that runs is left to run: a letter in it starts none of its own"
               (reset! sent [])
               (list-filter/follow! key "7" "sho" "sho")
               (list-filter/follow! key "7" "shot" "sho")
               (let [timer (get-in @list-filter/fields* [key :timer])]
                 (is (some? timer))
                 (list-filter/follow! key "7" "shot-" "sho")
                 (is (identical? timer (get-in @list-filter/fields* [key :timer])) "the wait of the first letter, not one after the last"))
               (await (past-wait))
               (is (= ["shot-"] (texts)))
               (is (nil? (get-in @list-filter/fields* [key :timer])) "over, the wait is gone"))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (swap! state/store dissoc :replicant/dispatch!)
                      (reset! list-filter/fields* {})
                      (done)))))))
