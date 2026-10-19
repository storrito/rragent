(ns design
  "A motion design: what is on screen at a time. The data of the design
  lives in design.edn; `design-view` is the frame at time `t` in seconds,
  a pure function of the design and the time; `controls` are what the
  person plays with, each one writing into the design. Change a function
  here and save, and the page beside the chat shows it at once."
  (:require [fnmotion.captions :as captions]
            [fnmotion.core :as fm]
            [fnmotion.timeline :as tl]
            [rragent.motion.player :as player]))

(def size
  "Width and height of the video in pixels: a story, upright."
  [1080 1920])

(def duration
  "Seconds."
  6)

(defn design-view
  "The frame at `t`: fills the stage of `size`."
  [{:keys [title subtitle color size]} t]
  (let [enter (fm/spring (fm/progress t 0 1.2))]
    [:div {:style {:position "absolute" :inset 0
                   :display "flex" :flex-direction "column"
                   :align-items "center" :justify-content "center"
                   :gap "32px" :padding "0 96px" :text-align "center"
                   :background "#fff7ed" :font-family "system-ui, sans-serif"}}
     [:h1 {:style {:margin 0
                   :font-size (fm/px size)
                   :line-height 1.1
                   :color color
                   :opacity (fm/progress t 0 0.4)
                   :transform (str "translateY(" (fm/px (fm/lerp 160 0 enter)) ")")}}
      title]
     [:p {:style {:margin 0
                  :font-size (fm/px (* 0.4 size))
                  :color "#57534e"
                  :opacity (fm/interpolate t [0.8 1.4] [0 1])}}
      subtitle]
     [:div {:style {:position "absolute" :left 0 :bottom 0 :height "20px"
                    :width (fm/pct (/ t duration)) :background color}}]]))

(defn controls
  "What the person plays with; every change is visible at once."
  [{:keys [title subtitle color size]}]
  [:div.controls
   [:label "Title " [:input {:type "text" :value title :on (player/setter [:title])}]]
   [:label "Subtitle " [:input {:type "text" :value subtitle :on (player/setter [:subtitle])}]]
   [:label "Size " [:input {:type "range" :min 48 :max 240 :value size :on (player/setter [:size])}]]
   [:label "Color " [:input {:type "color" :value color :on (player/setter [:color])}]]])

(def styles
  "CSS for the frames, on the page and in the video: fonts, classes a
  frame uses."
  "")
