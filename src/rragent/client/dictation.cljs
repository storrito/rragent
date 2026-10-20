(ns rragent.client.dictation
  "The microphone button of the composer (`rragent.client`): the person
   speaks and the draft fills piece by piece while they speak.

   Held, the button records until it is let go; a click starts and a
   second click stops (`hold-ms` tells them apart). Space held in the
   text records too, until it is let go, as in Claude Code: the key's
   repeat says it is held, the one space it typed before goes again; a
   space typed is a space.

   The page records, and at every pause in the speech (`silence-ms`, at
   the latest after `max-segment-ms`) it cuts the recording and sends
   the piece to the server (`rragent.dictation`), whose model detects
   the language and writes the piece down with a confidence for every
   word. The pieces go to the draft in the order they were spoken,
   whichever comes back first. Once the last piece is in and one of
   them has a word the model was unsure of, a model corrects the whole
   dictation, once, with what the conversation knows
   (`rragent.correction`), and its text takes the place of the pieces;
   the dictation ends then.

   While it records, a strip above the text shows a red dot, the time
   and the level of the voice, after the stop dots until the last piece
   is back (`indicator`): no words, the same in every language.

   The state is in the store under `:ui/dictation`: the draft as it was
   (`:base`), the pieces sent (`:segments`, each with its `:text`, its
   `:words` and whether one is `:unsure?` once back), the `:seconds`
   recorded, the `:status`, and the `:correction` (`:running`, `:done`)
   with its text (`:corrected`). Every change writes
   the draft; while a dictation runs the draft is the dictation's. What
   is no data (the stream, the recorder, the level meter and its node)
   is in `session*`; the level goes to the node directly, twenty times
   a second, not through the store and a render of the page."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]))

(def silence-ms
  "The pause after which the piece spoken so far is sent."
  700)

(def min-segment-ms
  "A piece is never shorter; a pause right after a cut does not cut again."
  1500)

(def max-segment-ms
  "A piece is cut after this long even without a pause: OpenRouter gives
   a transcription sixty seconds."
  30000)

(def tick-ms
  "How often the level of the microphone is measured."
  50)

(def hold-ms
  "A press of the button longer than this holds it: the dictation ends
   when it is let go. A shorter one is a click, the dictation goes on."
  400)

(defonce ^:private session*
  (atom nil))

(defonce ^:private press*
  (atom nil))

(defn join-text
  "The texts trimmed, the blank ones left out, one space between."
  [texts]
  (->> texts
       (map #(str/trim (str %)))
       (remove str/blank?)
       (str/join " ")))

(defn with-dictation
  "The draft with the dictated `text` at its end, a space between."
  [draft text]
  (let [draft (str draft)]
    (cond
      (str/blank? text) draft
      (or (str/blank? draft) (re-find #"\s$" draft)) (str draft text)
      :else (str draft " " text))))

(defn draft
  "The draft of a dictation: what was there before, then the corrected
   dictation or every piece the model wrote down so far."
  [{:keys [base segments corrected]}]
  (with-dictation base (or (not-empty corrected)
                           (join-text (map :text segments)))))

(defn words-to-correct
  "The words of every piece in the order spoken when one of them is
   unsure, nil otherwise."
  [{:keys [segments]}]
  (when (some :unsure? segments)
    (vec (mapcat :words segments))))

(defn pending?
  [dictation]
  (some :pending? (:segments dictation)))

(defn- update!
  "Applies `f` to the dictation and writes the draft from it."
  [{:keys [store store-key]} f & args]
  (swap! store (fn [state]
                 (let [dictation (apply f (:ui/dictation state) args)]
                   (assoc state
                          :ui/dictation dictation
                          store-key (draft dictation))))))

(defn- read-answer
  [text]
  (t/read (t/reader :json) text))

(defn- set-segment!
  [ctx id f]
  (update! ctx update :segments (fn [segments]
                                  (mapv #(if (= id (:id %)) (f %) %) segments))))

(defn- answer-of
  "A promise of the server's answer to `response`; a refusal throws."
  [^js response]
  (.then (.text response)
         (fn [text]
           (let [answer (read-answer text)]
             (if (.-ok response)
               answer
               (throw (js/Error. (str "dictation failed with " (.-status response) ": "
                                      (or (:message answer) (:error answer))))))))))

(defn- end!
  "The dictation is over, the draft stays as it is; with a piece that
   did not come back the button says so."
  [{:keys [store]}]
  (swap! store (fn [state]
                 (if (some :failed? (get-in state [:ui/dictation :segments]))
                   (assoc state :ui/dictation {:status :failed})
                   (dissoc state :ui/dictation)))))

(defn- correct-dictation!
  "Has the dictation of `words` corrected with the agent's context and
   puts the correction in place of the pieces; a correction that fails
   leaves them as they are."
  [{:keys [url store] :as ctx} words]
  (update! ctx assoc :correction :running)
  (-> (js/fetch (str url "/correct")
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify (clj->js {:words words
                                                        :before (get-in @store [:ui/dictation :base])}))})
      (.then answer-of)
      (.then (fn [{:keys [text]}]
               (update! ctx #(cond-> (assoc % :correction :done)
                               (not (str/blank? text)) (assoc :corrected (str/trim text))))))
      (.catch (fn [error]
                (js/console.error "[dictation] no correction" error)
                (update! ctx assoc :correction :done)))
      (.finally (fn []
                  (end! ctx)))))

(defn- finish-if-done!
  "Once the dictation is stopped, the recorder has handed over the last
   piece and every piece is back, it is corrected when a word is
   unsure, else over."
  [{:keys [store] :as ctx}]
  (let [dictation (:ui/dictation @store)]
    (when (and (= :finishing (:status dictation))
               (nil? @session*)
               (not (pending? dictation))
               (nil? (:correction dictation)))
      (if-let [words (words-to-correct dictation)]
        (correct-dictation! ctx words)
        (end! ctx)))))

(defn- send-segment!
  "Posts one piece of the recording and puts the model's text in its
   place in the draft."
  [{:keys [url] :as ctx} id ^js blob]
  (-> (js/fetch url #js {:method "POST"
                         :headers #js {"Content-Type" (or (not-empty (.-type blob)) "audio/webm")}
                         :body blob})
      (.then answer-of)
      (.then (fn [{:keys [text words unsure?]}]
               (set-segment! ctx id #(-> %
                                         (assoc :text (str/trim (str text))
                                                :words words
                                                :unsure? unsure?)
                                         (dissoc :pending?)))))
      (.catch (fn [error]
                (js/console.error "[dictation]" error)
                (set-segment! ctx id #(-> %
                                          (assoc :failed? true)
                                          (dissoc :pending?)))))
      (.finally (fn []
                  (finish-if-done! ctx)))))

(defn- now
  []
  (js/performance.now))

(defn- start-recorder!
  "A recorder for the next piece. When it stops, the piece it recorded
   goes to the segment `cut!` made for it, unless no voice was in it:
   silence alone the speech model refuses."
  [ctx ^js stream]
  (let [recorder (js/MediaRecorder. stream)
        chunks #js []
        segment (atom nil)]
    (set! (.-ondataavailable recorder) (fn [^js event]
                                         (when (pos? (.. event -data -size))
                                           (.push chunks (.-data event)))))
    (set! (.-onstop recorder) (fn [_]
                                (let [{:keys [id voiced?]} @segment
                                      blob (js/Blob. chunks #js {:type (.-mimeType recorder)})]
                                  (if (and voiced? (pos? (.-size blob)))
                                    (send-segment! ctx id blob)
                                    (set-segment! ctx id #(dissoc % :pending?))))
                                (when (:ended? @session*)
                                  (reset! session* nil))
                                (finish-if-done! ctx)))
    (.start recorder)
    (swap! session* assoc
           :recorder recorder
           :segment segment
           :segment-start (now)
           :voiced? false
           :silent-since nil)))

(defn- cut!
  "Ends the piece being recorded: it becomes a segment that waits for
   the model, the recorder of the next piece starts unless the
   dictation ends."
  [ctx last?]
  (let [{:keys [recorder segment stream next-id voiced?]} @session*
        id (or next-id 0)]
    (reset! segment {:id id :voiced? voiced?})
    (update! ctx update :segments (fnil conj []) {:id id
                                                  :pending? true})
    (swap! session* assoc :next-id (inc id))
    (when last?
      (swap! session* assoc :ended? true))
    (.stop ^js recorder)
    (when-not last?
      (start-recorder! ctx stream))))

(defn- level
  "The loudness of the microphone now, the root mean square of a frame."
  [^js analyser ^js samples]
  (.getFloatTimeDomainData analyser samples)
  (let [n (.-length samples)]
    (loop [i 0
           sum 0]
      (if (< i n)
        (let [x (aget samples i)]
          (recur (inc i) (+ sum (* x x))))
        (js/Math.sqrt (/ sum (max n 1)))))))

(defn- show!
  "The level on the meter, 0 to 1 above the noise of the room, and the
   seconds recorded in the store once they change."
  [{:keys [store]} {:keys [meter started floor]} level t]
  (when meter
    (.setProperty (.-style ^js meter) "--level" (str (min 1 (/ (max 0 (- level floor)) 0.12)))))
  (let [seconds (js/Math.floor (/ (- t started) 1000))]
    (when (not= seconds (get-in @store [:ui/dictation :seconds]))
      (swap! store assoc-in [:ui/dictation :seconds] seconds))))

(defn- tick!
  "Measures the microphone, shows it and cuts at a pause after speech.
   The threshold of speech follows the noise of the room: a slow
   average of the levels below it, two and a half times."
  [ctx]
  (let [{:keys [analyser samples floor segment-start voiced? silent-since] :as session} @session*]
    (when (and session (not (:ended? session)))
      (let [level (level analyser samples)
            threshold (max 0.01 (* 2.5 floor))
            speaking? (< threshold level)
            t (now)
            silent-since (if speaking? nil (or silent-since t))
            voiced? (or voiced? speaking?)]
        (show! ctx session level t)
        (swap! session* assoc
               :floor (if speaking? floor (+ (* 0.95 floor) (* 0.05 level)))
               :voiced? voiced?
               :silent-since silent-since)
        (when (or (< max-segment-ms (- t segment-start))
                  (and voiced?
                       silent-since
                       (<= min-segment-ms (- t segment-start))
                       (<= silence-ms (- t silent-since))))
          (cut! ctx false))))))

(declare stop!)

(defn start!
  "Starts a dictation into the draft under `store-key`, its pieces
   posted to `url`. The browser asks for the microphone the first time."
  [store store-key url]
  (let [ctx {:store store :store-key store-key :url url}]
    (swap! store assoc :ui/dictation {:status :starting
                                      :base (str (get @store store-key ""))
                                      :segments []})
    (-> (js/navigator.mediaDevices.getUserMedia #js {:audio true})
        (.then (fn [^js stream]
                 (let [audio-context (js/AudioContext.)
                       analyser (.createAnalyser audio-context)]
                   (set! (.-fftSize analyser) 2048)
                   (.connect (.createMediaStreamSource audio-context stream) analyser)
                   (reset! session* {:stream stream
                                     :audio-context audio-context
                                     :analyser analyser
                                     :samples (js/Float32Array. (.-fftSize analyser))
                                     :floor 0.004
                                     :started (now)})
                   (start-recorder! ctx stream)
                   (swap! session* assoc :timer (js/setInterval #(tick! ctx) tick-ms))
                   (update! ctx assoc :status :recording :seconds 0)
                   ;; let go while the browser was still asking for the microphone
                   (when (get-in @store [:ui/dictation :stop-when-ready?])
                     (stop! store store-key url)))))
        (.catch (fn [error]
                  (js/console.error "[dictation]" error)
                  (swap! store assoc :ui/dictation {:status :failed}))))))

(defn stop!
  "Ends the dictation: the last piece is sent, the microphone is
   released; the draft is final once every piece is back."
  [store store-key url]
  (let [ctx {:store store :store-key store-key :url url}
        {:keys [timer stream audio-context]} @session*]
    (when @session*
      (update! ctx assoc :status :finishing)
      (js/clearInterval timer)
      (cut! ctx true)
      (doseq [^js track (.getTracks stream)]
        (.stop track))
      (.close ^js audio-context))))

(defn active?
  "Whether a dictation owns the draft now."
  [state]
  (contains? #{:starting :recording :finishing} (get-in state [:ui/dictation :status])))

(defn- status
  [store]
  (get-in @store [:ui/dictation :status]))

(defn- let-go!
  "The end of a dictation held: now, or once the browser has the
   microphone."
  [store store-key url]
  (if @session*
    (stop! store store-key url)
    (when (= :starting (status store))
      (swap! store assoc-in [:ui/dictation :stop-when-ready?] true))))

(defn- without-typed-space
  "The text of `textarea` without the space before the caret, the one
   the held key typed before it repeated."
  [^js textarea]
  (let [text (.-value textarea)
        at (.-selectionStart textarea)]
    (if (and (pos? at) (= " " (subs text (dec at) at)))
      (str (subs text 0 (dec at)) (subs text at))
      text)))

(defn key-down!
  "Space held in the text starts a dictation once it repeats; its
   repeats while it records type nothing."
  [store store-key url ^js event]
  (when (= " " (.-key event))
    (cond
      (= :space (:by @press*))
      (.preventDefault event)

      (and (.-repeat event)
           (not (contains? #{:starting :recording :finishing} (status store))))
      (do (.preventDefault event)
          (swap! store assoc store-key (without-typed-space (.-target event)))
          (reset! press* {:by :space})
          (start! store store-key url)))))

(defn key-up!
  "Space let go ends the dictation it held."
  [store store-key url ^js event]
  (when (and (= " " (.-key event)) (= :space (:by @press*)))
    (.preventDefault event)
    (reset! press* nil)
    (let-go! store store-key url)))

(defn blur!
  "The text losing the focus while Space holds a dictation lets it go:
   its key-up goes elsewhere."
  [store store-key url]
  (when (= :space (:by @press*))
    (reset! press* nil)
    (let-go! store store-key url)))

(defn- press!
  "The button pressed: a dictation that records stops (a second click),
   otherwise one starts and the press is timed."
  [store store-key url ^js event]
  (when (zero? (.-button event))
    (case (status store)
      :recording (stop! store store-key url)
      (:starting :finishing) nil
      (do (reset! press* {:by :button :at (now)})
          (start! store store-key url)))))

(defn- release!
  "The button let go: held longer than `hold-ms` while it recorded, the
   dictation ends; a click leaves it running. Let go before the browser
   had the microphone (its question in between) counts as a click."
  [store store-key url]
  (when-let [{:keys [by at]} @press*]
    (when (= :button by)
      (reset! press* nil)
      (when (and (<= hold-ms (- (now) at))
                 (= :recording (status store)))
        (stop! store store-key url)))))

(defn- clock
  "Seconds as m:ss."
  [seconds]
  (str (quot seconds 60) ":" (.padStart (str (rem seconds 60)) 2 "0")))

(defn indicator
  "The strip above the text while a dictation runs: a red dot, the time
   and the meter while it records, dots while the last pieces are
   written down."
  [state]
  (case (get-in state [:ui/dictation :status])
    :recording [:div.dictation {:replicant/key "recording"}
                [:span.dot]
                [:span.time (clock (get-in state [:ui/dictation :seconds] 0))]
                [:span.meter {:replicant/on-mount (fn [{:keys [replicant/node]}]
                                                    (swap! session* #(some-> % (assoc :meter node))))
                              :replicant/on-unmount (fn [_]
                                                      (swap! session* #(some-> % (dissoc :meter))))}
                 [:span] [:span] [:span] [:span] [:span]]]
    :finishing [:div.dictation {:replicant/key "finishing"}
                [:span.dots [:span] [:span] [:span]]]
    nil))

(defn button
  "The microphone button: held, it records until let go; a click starts
   a dictation, a click while it records (red, see app.css) stops it;
   while the last pieces are written down it waits. A click from the
   keyboard (Enter, Space on the button) starts and stops. Only in a
   browser that records."
  [store store-key url state {:keys [dictate-title dictate-stop-title transcribing-title failed-title]}]
  (when (and (exists? js/MediaRecorder)
             (exists? js/AudioContext)
             (some-> js/navigator .-mediaDevices .-getUserMedia))
    (let [status (get-in state [:ui/dictation :status])]
      [:button.attach.dictate {:type "button"
                               :class (case status
                                        :recording "recording"
                                        :failed "failed"
                                        nil)
                               :disabled (contains? #{:starting :finishing} status)
                               :title (case status
                                        :recording (or dictate-stop-title "End the dictation")
                                        :finishing (or transcribing-title "Writing down what you said")
                                        :failed (or failed-title "The dictation did not come through")
                                        (or dictate-title "Dictate the message"))
                               :on {:pointerdown (fn [event]
                                                   (press! store store-key url event))
                                    :pointerup (fn [_]
                                                 (release! store store-key url))
                                    :pointercancel (fn [_]
                                                     (release! store store-key url))
                                    :pointerleave (fn [_]
                                                    (release! store store-key url))
                                    :contextmenu (fn [^js event]
                                                   (.preventDefault event))
                                    :click (fn [^js event]
                                             ;; a click of a pointer was its press and its release
                                             (when (zero? (.-detail event))
                                               (if (= :recording status)
                                                 (stop! store store-key url)
                                                 (start! store store-key url))))}}
       "🎤"])))
