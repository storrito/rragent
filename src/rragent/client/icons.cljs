(ns rragent.client.icons
  "The icons of the composer's row: a paperclip, a video camera, a
   microphone and the arrow of Send, each drawn as paths in the manner of Lucide and Feather,
   a 24 by 24 box, strokes of 2 with round caps and joins, no fill. They
   take the button's color through `currentColor`, the same on every
   platform; an emoji is colored and heavy, and looks different on each.
   Each is hiccup for Replicant, hidden from a screen reader, the
   button's title says what it does.")

(defn- icon
  "An SVG of the row's size around `shapes`."
  [& shapes]
  (into [:svg {:viewBox "0 0 24 24"
               :width 20
               :height 20
               :fill "none"
               :stroke "currentColor"
               :stroke-width 2
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden "true"}]
        shapes))

(def paperclip
  "A clip on the diagonal: the outer loop, the long way round, the
   inner one back up."
  (icon [:path {:d "M21 11.5 12.5 20a5.5 5.5 0 0 1-7.8-7.8l9-9a3.5 3.5 0 0 1 5 5l-9 9a1.5 1.5 0 0 1-2.1-2.1l8.3-8.3"}]))

(def camera
  "A video camera: the body, the lens flap to the right."
  (icon [:rect {:x 3 :y 6 :width 13 :height 12 :rx 2.5}]
        [:path {:d "m16 10 5-3v10l-5-3"}]))

(def microphone
  "A microphone: the capsule, the cradle around it, the stand."
  (icon [:rect {:x 9 :y 2.5 :width 6 :height 11.5 :rx 3}]
        [:path {:d "M5 11.5a7 7 0 0 0 14 0"}]
        [:path {:d "M12 18.5v3M8.5 21.5h7"}]))

(def arrow-up
  "The arrow of Send: a line up, the chevron at its tip."
  (icon [:path {:d "M12 19V5"}]
        [:path {:d "m5 12 7-7 7 7"}]))
