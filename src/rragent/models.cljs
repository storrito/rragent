(ns rragent.models
  "The models a person may pick for a bot, and the thinking levels:
   what the switch under the composer offers (`rragent.view/model-switch`).
   A pick goes into the bot's definition as `metadata.model` or
   `metadata.thinking` (`rragent.bots/set-metadata!`), which is read
   again before every turn (`rragent.reload`). The prices are the
   catalog's (`rragent.machine/model-cost`), so that the menu can say
   what a model costs beside the default."
  (:require [rragent.definition :as definition]))

(def choices
  "The models to pick from, the default first: the id as OpenRouter
   names it, and the name the menu shows."
  [{:id definition/default-model :name "Claude Opus 5.5"}
   {:id "openai/gpt-6.1-sol" :name "GPT-6.1 Sol"}])

(def levels
  "The thinking levels to pick from, as pi-ai names them: the ones both
   models take (neither has an off). `xhigh` is \"Extra\" on the page."
  ["low" "medium" "high" "xhigh" "max"])

(defn choice?
  "Whether `id` is one of the `choices`."
  [id]
  (boolean (some #(= (str id) (:id %)) choices)))

(defn level?
  "Whether `level` is one of the `levels`."
  [level]
  (boolean (some #{(str level)} levels)))

(defn task-model?
  "Whether a task may run on the model `id`: one of the `choices`, or
   `own`, the model of the bot that starts it (a faux model in a test,
   a model the bot's file names by hand). An id the model knows from
   its training and the page does not offer is none: a bot handed
   tasks to `anthropic/claude-opus-4.1`, at three times the price
   (Tobias, 2026-11-10)."
  [id own]
  (boolean (or (choice? id)
               (and (seq (str own)) (= (str id) (str own))))))

(defn times
  "How many times the default model's price a model at `cost` is, by
   the price of a million output tokens, to one decimal: 2 for a model
   at twice the default's price; nil without both costs, or when it is
   no dearer."
  [cost default-cost]
  (when (and (pos? (or (:output default-cost) 0)) (number? (:output cost)))
    (let [times (/ (js/Math.round (* 10 (/ (:output cost) (:output default-cost)))) 10)]
      (when (> times 1)
        times))))

(comment
  (times {:input 4 :output 20} {:input 2 :output 10})
  (times {:input 2 :output 10} {:input 2 :output 10})
  (choice? "openai/gpt-6.1-sol")
  )
