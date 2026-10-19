(ns rragent.motion.player
  "The player of a motion design in the page of a motion project, on the
   server: fnmotion.rframes ported onto `rragent.rframes`. The app keeps
   everything in one atom: the design data under `:design`, the time on
   screen under `:motion/t`, whether it plays under `:motion/playing?`.
   The frame is a pure function of the design and the time,
   `(frame-fn design t)`, hiccup. `player` renders the frame at the
   current time with play, a scrubber and the clock; `commands` are the
   entries the controls send; while it plays, a tick loop advances the
   time with the wall clock and pushes a frame to every connection after
   each step, past rframes' coalescing, for sixty a second.

   A control in a frame is a control anywhere: `:design/set` writes a
   value into the design (a number stays a number) and the frame renders
   again. `watch-design!` keeps the design in a file across restarts.

   The classes match the browser player of fnmotion; `css` has the rules
   for a page without a stylesheet of its own."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rragent.rframes.command :as command]
            [rragent.rframes.sse :as sse]))

(def tick-ms
  "The pause between two pushed frames, for sixty a second."
  16)

;;; The frame

(defn data-only
  "A frame may hold a function by mistake; a function would drop the whole
   frame. It becomes nothing."
  [hiccup]
  (walk/postwalk (fn [x] (if (fn? x) nil x)) hiccup))

(defn frame
  "The frame at `t`, or the error it raised as a paragraph."
  [frame-fn design t]
  (try
    (data-only (frame-fn design t))
    (catch :default e
      [:p.error (str "The frame at " (.toFixed (or t 0) 1) " s failed: " (or (.-message e) (str e)))])))

;;; Playback

(defn- duration-of
  [duration]
  (if (fn? duration) (duration) duration))

(defn- tick!
  [db duration started t0]
  (when (:motion/playing? @db)
    (let [duration (duration-of duration)
          t (+ t0 (/ (- (js/Date.now) started) 1000))]
      (if (>= t duration)
        (swap! db assoc :motion/t duration :motion/playing? false)
        (do (swap! db assoc :motion/t t)
            (sse/refresh-all!)
            (js/setTimeout (fn [] (tick! db duration started t0)) tick-ms))))))

(defn toggle!
  "Plays or pauses; playing from the end starts over."
  [db duration]
  (let [playing? (not (:motion/playing? @db))
        t (:motion/t @db 0)]
    (swap! db assoc
           :motion/playing? playing?
           :motion/t (if (and playing? (>= t (duration-of duration))) 0 t))
    (when playing?
      (tick! db duration (js/Date.now) (:motion/t @db)))
    playing?))

(defn seek!
  "Pauses at the scrubber's `value`, 0..1000 of the duration, and pushes
   the frame at once: the scrubber should not wait for the coalescing."
  [db duration value]
  (swap! db assoc
         :motion/playing? false
         :motion/t (* (duration-of duration) (/ value 1000)))
  (sse/refresh-all!))

(defn stop!
  "Pauses at the start."
  [db]
  (swap! db assoc :motion/playing? false :motion/t 0))

;;; The design data

(defn coerce
  "The value of a control: a number stays a number."
  [value]
  (if (string? value)
    (if-let [number (parse-double value)]
      (if (str/includes? value ".") number (js/Math.trunc number))
      value)
    value))

(defn design-set!
  "Writes `value` at `path` into the design."
  [db p value]
  (swap! db assoc-in (into [:design] (map #(if (string? %) (keyword %) %)) p) (coerce value)))

(def design-poll-ms
  "How often `watch-design!` looks at the file for an edit from outside."
  500)

(defn read-design
  "The design in the file, nil when there is none or it does not parse."
  [file]
  (try
    (reader/read-string (fs/readFileSync file "utf8"))
    (catch :default _
      nil)))

(defn- read-design-file!
  "The design in the file into the atom and onto the page, when the file
   holds another design than the atom. True when the file could be read:
   a write in progress is empty or does not parse, and the next look sees
   the whole file."
  [db file]
  (when-let [design (read-design file)]
    (when (not= design (:design @db))
      (swap! db assoc :design design)
      (sse/refresh-all!))
    true))

(defn- mtime
  [file]
  (try
    (.-mtimeMs (fs/statSync file))
    (catch :default _
      nil)))

(defn watch-design!
  "Writes the design into `file` whenever it changes, reads it back first
   when the file exists, and reads it again when something else writes
   the file (an editor, an agent): the design survives a restart, and an
   edit of the file shows up on the page. Answers a function that ends
   the watch."
  [db file]
  (when-let [design (read-design file)]
    (swap! db assoc :design design))
  (add-watch db ::design
             (fn [_ _ old new]
               (when (not= (:design old) (:design new))
                 (fs/mkdirSync (path/dirname file) #js {:recursive true})
                 (fs/writeFileSync file (pr-str (:design new))))))
  (let [seen (atom (mtime file))
        timer (js/setInterval (fn []
                                (let [now (mtime file)]
                                  (when (and (not= @seen now) (read-design-file! db file))
                                    (reset! seen now))))
                              design-poll-ms)]
    (.unref timer)
    (fn []
      (js/clearInterval timer)
      (remove-watch db ::design))))

;;; The entries and the view

(defn commands
  "The commands of the controls: `:motion/toggle`, `:motion/seek` with
   `{:value 0..1000}`, `:design/set` with `{:path [...] :value ...}`.
   `duration` is seconds or a function of no arguments."
  [db {:keys [duration]}]
  [{:command/kind :motion/toggle
    :command/fn (fn [w]
                  (toggle! db duration)
                  (command/accepted w))}
   {:command/kind :motion/seek
    :command/fn (fn [w]
                  (seek! db duration (or (parse-double (str (:value (command/command-data w)))) 0))
                  (command/accepted w))}
   {:command/kind :design/set
    :command/fn (fn [w]
                  (let [{:keys [path value]} (command/command-data w)]
                    (if (sequential? path)
                      (do (design-set! db path value)
                          (command/accepted w))
                      (command/rejected w "A path is a vector."))))}])

(defn setter
  "The `:on` map of a control that writes its value at `path`:
   `[:input {:type \"range\" :value size :on (setter [:size])}]`."
  [p]
  {:input [[:data/command {:command/kind :design/set
                           :command/data {:path p
                                          :value :event/target.value}}]]})

(defn- timestamp
  [seconds]
  (let [seconds (or seconds 0)
        minutes (js/Math.floor (/ seconds 60))]
    (str minutes ":" (.padStart (.toFixed (- seconds (* 60 minutes)) 1) 4 "0"))))

(defn controls
  "Play or pause, the scrubber, the clock."
  [{:keys [motion/t motion/playing?] :or {t 0}} duration]
  (let [duration (duration-of duration)
        t (min t duration)]
    [:div.fnmotion-controls
     [:button {:on {:click [[:data/command {:command/kind :motion/toggle}]]}}
      (if playing? "Pause" "Play")]
     [:input {:type "range"
              :min 0
              :max 1000
              :value (js/Math.round (* 1000 (/ t (max duration 0.001))))
              :on {:input [[:data/command {:command/kind :motion/seek
                                           :command/data {:value :event/target.value}}]]}}]
     [:span.fnmotion-time (str (timestamp t) " / " (timestamp duration))]]))

(defn- stage
  "The frame in a box of the size of the video, scaled to the width of
   the pane by `script`."
  [[w h] frame]
  [:div.motion-frame {:data-width w
                      :data-height h
                      :style {:aspect-ratio (str w " / " h)
                              :max-width (str "calc(70vh * " w " / " h ")")}}
   [:div.motion-stage {:style {:width (str w "px")
                               :height (str h "px")
                               :transform "scale(var(--motion-scale, 1))"
                               :transform-origin "top left"}}
    frame]])

(defn audio-tracks
  "The audio tracks of the design as elements `script` keeps in step with
   the time. A track is `{:src :start :duration :media-start :volume
   :fade-in :fade-out :loop?}`, see rragent.motion.render;
   `video-duration` tells where a track without a duration ends."
  [tracks video-duration]
  (when (seq tracks)
    [:div.motion-audio
     (for [{:keys [src start duration media-start volume fade-in fade-out loop?]} tracks
           :let [start (or start 0)]]
       [:audio {:replicant/key src
                :src src
                :preload "auto"
                :data-start start
                :data-end (+ start (or duration (- video-duration start)))
                :data-media-start (or media-start 0)
                :data-volume (or volume 1)
                :data-fade-in (or fade-in 0)
                :data-fade-out (or fade-out 0)
                :loop (boolean loop?)}])]))

(defn player
  "The frame at the current time over the controls. `:frame` is the
   function of design and time, `:duration` seconds or a function, `:size`
   the width and height of the video in pixels, when the design has one,
   `:audio` its tracks, the `:audio` of the design when absent."
  [{:keys [design motion/t motion/playing?] :or {t 0} :as db-value} {:keys [frame duration size audio]}]
  (let [duration (duration-of duration)
        t (min t duration)
        the-frame (rragent.motion.player/frame frame design t)]
    [:div.motion {:data-t t
                  :data-playing (str (boolean playing?))}
     (if size
       (stage size the-frame)
       [:div.motion-frame the-frame])
     (audio-tracks (or audio (:audio design)) duration)
     (controls db-value duration)]))

(def script
  "For a <script> in the head of the page: scales the stage to the width
   of the pane, and keeps the audio tracks in step with the time, playing
   while the design plays, at the position of the scrubber otherwise, at
   their volume with their fades."
  "(function () {
  function fit() {
    var frame = document.querySelector('.motion-frame[data-width]');
    if (!frame) return;
    var width = parseFloat(frame.dataset.width);
    if (width > 0 && frame.clientWidth > 0) {
      document.documentElement.style.setProperty('--motion-scale', frame.clientWidth / width);
    }
  }
  function sync() {
    var motion = document.querySelector('.motion[data-t]');
    if (!motion) return;
    var t = parseFloat(motion.dataset.t) || 0;
    var playing = motion.dataset.playing === 'true';
    Array.prototype.forEach.call(motion.querySelectorAll('audio[data-start]'), function (audio) {
      var start = parseFloat(audio.dataset.start) || 0;
      var end = parseFloat(audio.dataset.end);
      var mediaStart = parseFloat(audio.dataset.mediaStart) || 0;
      var volume = parseFloat(audio.dataset.volume);
      var fadeIn = parseFloat(audio.dataset.fadeIn) || 0;
      var fadeOut = parseFloat(audio.dataset.fadeOut) || 0;
      var local = t - start;
      var on = local >= 0 && (isNaN(end) || t < end);
      var want = mediaStart + local;
      if (audio.loop && audio.duration > mediaStart) {
        want = mediaStart + (local % (audio.duration - mediaStart));
      }
      var gain = isNaN(volume) ? 1 : volume;
      if (fadeIn > 0) gain *= Math.max(0, Math.min(1, local / fadeIn));
      if (fadeOut > 0 && !isNaN(end)) gain *= Math.max(0, Math.min(1, (end - t) / fadeOut));
      audio.volume = Math.max(0, Math.min(1, gain));
      if (playing && on) {
        if (Math.abs(audio.currentTime - want) > 0.3) audio.currentTime = want;
        if (audio.paused) audio.play().catch(function () {});
      } else {
        if (!audio.paused) audio.pause();
        if (want >= 0 && Math.abs(audio.currentTime - want) > 0.3) audio.currentTime = want;
      }
    });
  }
  new MutationObserver(function () { fit(); sync(); })
    .observe(document.documentElement, {subtree: true, childList: true, attributes: true,
                                         attributeFilter: ['data-t', 'data-playing', 'data-width']});
  window.addEventListener('resize', fit);
  document.addEventListener('DOMContentLoaded', function () { fit(); sync(); });
})();")

(def css
  "The rules of the player for a page without a stylesheet of its own."
  ".motion { display: flex; flex-direction: column; gap: 12px; }
.motion-frame { position: relative; width: 100%; overflow: hidden; }
.motion-frame:not([data-width]) { min-height: 120px; }
.motion-stage { position: relative; overflow: hidden; }
.motion-audio { display: none; }
.fnmotion-controls { display: flex; align-items: center; gap: 10px; }
.fnmotion-controls input[type=range] { flex: 1; }
.fnmotion-controls button { padding: 6px 14px; border: 1px solid #ccc; border-radius: 6px; background: #fff; cursor: pointer; font: inherit; }
.fnmotion-time { font-variant-numeric: tabular-nums; color: #555; min-width: 7em; text-align: right; }
.error { color: #b00020; }")
