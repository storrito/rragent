(ns rragent.client
  "The browser bundle of the page: the rframes runtime, which opens the
   frame stream of the page it is on and renders every frame with
   Replicant, with aliases of our own: the composer, the canvas of the
   artifacts pane, the iframe that shows a page or a project and reloads
   it in place (the canvas is rchat's), the filter above the pane's
   list (`files-filter`), and the button of a ```hiccup block that
   sends the boxes ticked in it.

   The composer takes files, the way pchat's does: an image picked,
   dropped or pasted goes up scaled and in one request, a video or an
   audio file in pieces of `piece-bytes` with its progress on its chip,
   and a message sent while clips go up goes once the last is in
   (`rragent.uploads` on the server). The attachments are client state
   beside the draft, a frame from the server does not reset them. Files
   dragged over the page bring up a veil that takes them anywhere, as in
   ChatGPT, LibreChat or Open WebUI, and a file dropped beside it never
   opens in the tab instead of the page.

   The microphone button beside them dictates into the draft
   (`rragent.client.dictation`); a button in the header switches between
   the light and the dark theme.

   The bots of the machine are in the sidebar, each a link to its page,
   `/bot/<id>`: a click changes the URL without a reload (`pushState`)
   and tells the server which bot this tab shows (`:bot/show`, with the
   tab's id), the back and forward buttons do the same with the URL
   they return to, and the `:ui/url` alias corrects the address bar
   when the server shows another bot than the URL named (one that was
   deleted). The way pchat does it."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [rframes.client :as rframes]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]
            [rragent.client.dictation :as dictation]
            [rragent.client.phone :as phone]
            [rragent.client.recorder :as recorder]))

(def max-side
  "Pixels on the long side of an uploaded image, pchat's."
  1600)

(def piece-bytes
  "The size of one request of a clip upload."
  (* 8 1024 1024))

(def retry-delays-ms
  "The pauses before a failed piece is sent again, one per try: some two
   minutes in all, enough for a dropped connection or a restarting
   machine, so a clip of gigabytes survives what one piece meets."
  [2000 5000 15000 30000 60000])

(def piece-timeout-ms
  "A piece that has not gone up in this time failed: 8 MB in five
   minutes is some 27 KB/s."
  (* 5 60 1000))

(def clip-types
  "The media type of a clip by its extension, for a browser that names
   none (an .mkv, a .flac) and for the type the server knows."
  {"mp4" "video/mp4"
   "m4v" "video/mp4"
   "mov" "video/quicktime"
   "webm" "video/webm"
   "mkv" "video/x-matroska"
   "mp3" "audio/mpeg"
   "wav" "audio/wav"
   "m4a" "audio/mp4"
   "aac" "audio/aac"
   "ogg" "audio/ogg"
   "oga" "audio/ogg"
   "flac" "audio/flac"})

(defn- image-file?
  [^js file]
  (and file (str/starts-with? (str (.-type file)) "image/")))

(defn- extension
  [^js file]
  (str/lower-case (or (second (re-find #"\.([^./]+)$" (str (.-name file)))) "")))

(defn- clip-type
  "The media type to send a clip with, or nil when the file is no clip."
  [^js file]
  (let [type (str (.-type file))]
    (or (get clip-types (extension file))
        (when (re-find #"^(video|audio)/" type)
          type))))

(defn- scaled
  "Calls `callback` with the image as a blob of at most `max-side`
   pixels on its long side: the file itself when it is small enough,
   else a scaled copy from a canvas, PNG for a PNG and JPEG for the
   rest."
  [^js file callback]
  (let [url (js/URL.createObjectURL file)
        image (js/Image.)]
    (set! (.-onload image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (let [w (.-naturalWidth image)
                  h (.-naturalHeight image)
                  scale (min 1 (/ max-side (max w h 1)))]
              (if (= 1 scale)
                (callback file)
                (let [canvas (js/document.createElement "canvas")
                      cw (js/Math.round (* w scale))
                      ch (js/Math.round (* h scale))
                      png? (= "image/png" (.-type file))]
                  (set! (.-width canvas) cw)
                  (set! (.-height canvas) ch)
                  (.drawImage (.getContext canvas "2d") image 0 0 cw ch)
                  (.toBlob canvas
                           (fn [blob]
                             (callback (or blob file)))
                           (if png? "image/png" "image/jpeg")
                           0.85))))))
    (set! (.-onerror image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (js/console.error "[upload] not an image the browser can read")))
    (set! (.-src image) url)))

(defn- read-answer
  [text]
  (t/read (t/reader :json) text))

(defn- add-attachment!
  "The attachment into the store, once per name."
  [store attachments-key attachment]
  (swap! store update attachments-key
         (fn [attachments]
           (if (some #(= (:name attachment) (:name %)) attachments)
             attachments
             (conj (vec attachments) attachment)))))

(defn- upload-image!
  "Uploads the image scaled and adds `{:name :url :kind}` of the stored
   file to the attachments."
  [store attachments-key upload-url ^js file]
  (scaled file
          (fn [^js blob]
            (-> (js/fetch upload-url #js {:method "POST"
                                          :headers #js {"Content-Type" (.-type blob)}
                                          :body blob})
                (.then (fn [^js response]
                         (if (.-ok response)
                           (.text response)
                           (throw (js/Error. (str "upload failed with " (.-status response)))))))
                (.then (fn [text]
                         (add-attachment! store attachments-key
                                          (assoc (select-keys (read-answer text) [:name :url])
                                                 :kind :image))))
                (.catch (fn [error]
                          (js/console.error "[upload]" error)))))))

(defn- random-id
  "32 hex digits, the name of a clip while it goes up."
  []
  (let [bytes (js/Uint8Array. 16)]
    (js/crypto.getRandomValues bytes)
    (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bytes)))))

(defn- send-piece!
  "POSTs one piece with an XMLHttpRequest, which reports the progress of
   its upload: `on-progress` with the bytes of the piece sent so far,
   `on-answer` with the server's answer, `on-error` with the status and
   the answer when it reads (0 without one: no connection, a timeout)."
  [url ^js blob type {:keys [on-progress on-answer on-error]}]
  (let [xhr (js/XMLHttpRequest.)
        answer (fn []
                 (try
                   (read-answer (.-responseText xhr))
                   (catch :default _)))]
    (.open xhr "POST" url)
    (.setRequestHeader xhr "Content-Type" type)
    (set! (.-timeout xhr) piece-timeout-ms)
    (set! (.. xhr -upload -onprogress) (fn [^js event]
                                         (on-progress (.-loaded event))))
    (set! (.-onload xhr) (fn [_]
                           (let [status (.-status xhr)
                                 answer (answer)]
                             (if (and (<= 200 status 299) (map? answer))
                               (on-answer answer)
                               (on-error status answer)))))
    (set! (.-onerror xhr) (fn [_]
                            (on-error 0 nil)))
    (set! (.-ontimeout xhr) (fn [_]
                              (on-error 0 nil)))
    (.send xhr blob)))

(defn- retry?
  "Whether a piece that failed with `status` may go again: no
   connection, a timeout, the server or its proxy failing; not what the
   server refused (too large, a bad piece, a type it does not take)."
  [status]
  (or (zero? status)
      (= 408 status)
      (= 429 status)
      (<= 500 status)))

(defn- upload-clip!
  "Uploads a video or an audio file in pieces. Its chip is in the
   attachments from the start, `:uploading?` with its `:progress`; the
   last answer turns it into `{:name :url :kind :size}` of the stored
   file. A piece that fails goes again after the pauses of
   `retry-delays-ms`, the chip counting the tries (`:retry`); the server
   answers a piece that does not start where its part file ends with
   what it holds (409, `:received`), and the upload goes on from there,
   so what a broken piece left is kept. A chip the person removes stops
   the upload after the piece under way."
  [store attachments-key upload-url ^js file]
  (let [type (clip-type file)
        id (random-id)
        total (.-size file)
        chip (str "uploading-" id)
        present? (fn []
                   (some #(= chip (:chip %)) (get @store attachments-key)))
        update-chip! (fn [f]
                       (swap! store update attachments-key
                              (fn [attachments]
                                (mapv #(if (= chip (:chip %)) (f %) %) attachments))))]
    (add-attachment! store attachments-key {:name chip
                                            :chip chip
                                            :kind (if (str/starts-with? type "audio/") :audio :video)
                                            :file-name (.-name file)
                                            :size total
                                            :uploading? true
                                            :progress 0})
    ((fn step [offset attempt]
       (let [end (min total (+ offset piece-bytes))]
         (send-piece! (str upload-url "?id=" id "&offset=" offset "&total=" total)
                      (.slice file offset end)
                      type
                      {:on-progress (fn [loaded]
                                      (update-chip! #(update % :progress max (/ (+ offset loaded) (max total 1)))))
                       :on-answer (fn [{:keys [url] :as answer}]
                                    (cond
                                      url (update-chip! #(-> %
                                                             (merge (select-keys answer [:name :url :kind :size]))
                                                             (dissoc :uploading? :progress :retry)))
                                      (present?) (do (update-chip! #(dissoc % :retry))
                                                     (step end 0))))
                       :on-error (fn [status answer]
                                   (let [received (:received answer)]
                                     (cond
                                       (not (present?)) nil

                                       (and (= 409 status) (int? received) (<= 0 received total))
                                       (step received (if (< offset received) 0 attempt))

                                       (and (retry? status) (< attempt (count retry-delays-ms)))
                                       (do (js/console.warn "[upload] a piece failed with" status "- again in"
                                                            (nth retry-delays-ms attempt) "ms")
                                           (update-chip! #(assoc % :retry (inc attempt)))
                                           (js/setTimeout #(when (present?)
                                                             (step offset (inc attempt)))
                                                          (nth retry-delays-ms attempt)))

                                       :else
                                       (do (js/console.error "[upload] a piece failed with" status)
                                           (update-chip! #(-> %
                                                              (dissoc :uploading? :progress :retry)
                                                              (assoc :failed? true)))))))})))
     0 0)))

(defn- upload-all!
  [store attachments-key upload-url files]
  (doseq [^js file files]
    (cond
      (clip-type file) (upload-clip! store attachments-key upload-url file)
      (image-file? file) (upload-image! store attachments-key upload-url file))))

(defonce ^:private inner-drag*
  ;; a drag that started on the page, a picture of the transcript say:
  ;; the browser lists "Files" for it too, but it brings none
  (atom false))

(defn- dragging-files?
  "A drag that carries files from outside the page, not a selection of
   its text, a link or a picture of the page."
  [^js event]
  (and (not @inner-drag*)
       (boolean (some #{"Files"} (some-> event .-dataTransfer .-types js/Array.from)))))

(defn- watch-drags!
  "Brings up the veil when files are dragged into `win`: the page's
   window, and the canvas's, which takes the drags over the artifacts
   pane until the veil covers it."
  [^js win]
  (.addEventListener win "dragstart" (fn [_event]
                                       (reset! inner-drag* true)))
  (.addEventListener win "dragend" (fn [_event]
                                     (reset! inner-drag* false)))
  (.addEventListener win "dragenter" (fn [^js event]
                                       (when (and (dragging-files? event)
                                                  (not (:ui/dropping? @state/store)))
                                         (swap! state/store assoc :ui/dropping? true)))))

;; the var is nobody's, the listeners are the point
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private drop-listeners
  ;; the window takes every drag of files, so that a drop beside the veil
  ;; does not open the file in the tab; the veil itself is the composer's.
  ;; Escape takes a veil away that a browser forgot to (no dragleave).
  (do (watch-drags! js/window)
      (.addEventListener js/window "dragover"
                         (fn [^js event]
                           (when (dragging-files? event)
                             (.preventDefault event))))
      (.addEventListener js/window "drop"
                         (fn [^js event]
                           (when (dragging-files? event)
                             (.preventDefault event)
                             (swap! state/store dissoc :ui/dropping?))))
      (.addEventListener js/window "keydown"
                         (fn [^js event]
                           (when (and (= "Escape" (.-key event))
                                      (:ui/dropping? @state/store))
                             (swap! state/store dissoc :ui/dropping?))))
      true))

(defn- drop-veil
  "Over the whole page while files are dragged over it: a drop anywhere
   attaches them. Only the veil takes the drag, its card none of it, so
   leaving the veil is leaving the window (or Escape); a click takes a
   veil away that stayed."
  [store attachments-key upload-url {:keys [title hint]}]
  [:div.drop-veil {:replicant/key "drop-veil"
                   :on {:dragover (fn [^js event]
                                    (.preventDefault event)
                                    (set! (.. event -dataTransfer -dropEffect) "copy"))
                        :dragleave (fn [_event]
                                     (swap! store dissoc :ui/dropping?))
                        :click (fn [_event]
                                 (swap! store dissoc :ui/dropping?))
                        :drop (fn [^js event]
                                (.preventDefault event)
                                (.stopPropagation event)
                                (swap! store dissoc :ui/dropping?)
                                (upload-all! store attachments-key upload-url
                                             (array-seq (.. event -dataTransfer -files))))}}
   [:div.drop-card {:role "status"}
    [:span.icon {:aria-hidden "true"} "📎"]
    [:strong (or title "Add files")]
    [:span.hint (or hint "Drop images, videos or audio files to attach them to your message.")]]])

(defn- pasted-files
  "The images and clips of a paste; a paste of text has none, and
   pastes as text."
  [^js event]
  (->> (array-seq (or (some-> event .-clipboardData .-items) #js []))
       (filter #(= "file" (.-kind ^js %)))
       (map #(.getAsFile ^js %))
       (remove nil?)
       (filter #(or (image-file? %) (clip-type %)))))

(defn- short-name
  "A file name of at most 28 characters, the end kept."
  [file-name]
  (let [file-name (str file-name)]
    (if (< 28 (count file-name))
      (str (subs file-name 0 16) "…" (subs file-name (- (count file-name) 10)))
      file-name)))

(defn- megabytes
  [size]
  (let [mb (/ size 1024 1024)]
    (str (if (< mb 10) (.toFixed mb 1) (js/Math.round mb)) " MB")))

(defn- clip-chip
  [{:keys [kind file-name name size uploading? progress retry failed?]} uploading-label failed-label]
  [:span.clip
   [:span.icon (if (= :audio kind) "🎵" "🎬")]
   [:span.file (short-name (or file-name name))]
   [:small (megabytes size)
    (cond
      uploading? (str " · " (or uploading-label "Uploading") " "
                      (js/Math.round (* 100 progress)) " %"
                      (when retry
                        (str " · ↻ " retry "/" (count retry-delays-ms))))
      failed? (str " · " (or failed-label "The upload failed")))]])

(defn- attachments-view
  [store attachments-key attachments {:keys [remove-title uploading-label failed-label]}]
  (when (seq attachments)
    [:div.attachments {:replicant/key "attachments"}
     (for [{:keys [name url kind failed?] :as attachment} attachments]
       [:span.attachment {:replicant/key name
                          :class (when failed? "failed")}
        (if (= :image kind)
          [:img {:src url
                 :alt (str name)}]
          (clip-chip attachment uploading-label failed-label))
        [:button.remove {:type "button"
                         :title (or remove-title "Remove")
                         :on {:click (fn [_event]
                                       (swap! store update attachments-key
                                              (fn [attachments]
                                                (vec (remove #(= name (:name %)) attachments)))))}}
         "×"]])]))

(defn- pending-step
  "What a message sent while its files go up or its dictation is written
   down does on a change of the store: `:wait` while a file is uploading
   or the dictation runs, `:send` once neither does, `:cancel` when a
   file it waited for failed or was taken off (the person sees the
   failure and the draft stays), or when nothing is left to send."
  [{:keys [store-key attachments-key chips]} state]
  (let [attachments (get state attachments-key)
        present (set (keep :chip attachments))]
    (cond
      (some #(or (not (present %))
                 (some (fn [a] (and (= % (:chip a)) (:failed? a))) attachments))
            chips) :cancel
      (dictation/active? state) :wait
      (some :uploading? attachments) :wait
      (or (not (str/blank? (get state store-key)))
          (some #(not (:failed? %)) attachments)) :send
      :else :cancel)))

;; a message sent while its files go up or its dictation is written down
;; goes once neither is, with the draft as it is then. Not in the watch itself: the render watch,
;; notified after it, would draw this change's state over the newer one
(add-watch state/store ::send-pending
           (fn [_ _ _ state]
             (when-let [pending (:ui/send-pending state)]
               (when-let [step (#{:send :cancel} (pending-step pending state))]
                 (js/queueMicrotask
                  (fn []
                    (when (identical? pending (:ui/send-pending @state/store))
                      (swap! state/store dissoc :ui/send-pending)
                      (when (= :send step)
                        (state/dispatch! (:actions pending))))))))))

(defn composer
  "The textarea of the page with its attachments. The text lives in the
   store under `:ui/store-key`, the attachments under
   `:ui/attachments-key`, so that a frame from the server resets
   neither; files go to `:ui/upload-url`. Ctrl+Enter (Cmd+Enter on a
   Mac) and the Send button run `:ui/send`, the actions of the server,
   with a text, files or both; while files go up they run once the last
   is in (`pending-step`), the button says so (`:ui/send-pending-label`)
   and a click on it takes the send back. Enter breaks the line, on a
   phone too, where there is no Shift. The microphone button, or
   Space held in the text, dictates into the draft, its pieces go to
   `:ui/dictation-url`; while it runs the draft is the dictation's, and
   Ctrl+Enter or Send end it and send once its text is in
   (`:ui/send-after-dictation-label`). A paste with
   files on the clipboard uploads them, a paste of text pastes; files dragged over the page
   bring up the veil (`drop-veil`), a drop on it uploads them. With
   `:ui/stop`, actions, a Stop button beside Send runs them (a run
   goes). With `:ui/record-url`, where a screencast's pieces go, the
   row has the screencast button and the panel above the text
   (`rragent.client.recorder`), its words in `:ui/recorder`. Send and Stop carry their word and an icon; the stylesheet
   shows the word on a wide screen and the icon on a phone, where the
   row is a card like the Claude app's. The labels are the server's,
   translated; the other attributes pass through to the textarea."
  [{:keys [ui/store-key ui/attachments-key ui/upload-url ui/send ui/send-label ui/send-pending-label
           ui/send-cancel-title ui/attach-title ui/remove-title ui/uploading-label ui/failed-label
           ui/drop-title ui/drop-hint ui/dictation-url ui/dictate-title ui/dictate-stop-title
           ui/transcribing-title ui/dictation-failed-title ui/send-after-dictation-label ui/state
           ui/stop ui/stop-label ui/record-url ui/recorder ui/phone]
    :as attrs}
   _children]
  (let [store state/store
        attachments-key (or attachments-key :attachments)
        upload-url (or upload-url "/upload")
        dictation-url (or dictation-url "/dictation")
        draft (str (get state store-key ""))
        attachments (get state attachments-key [])
        uploading? (some :uploading? attachments)
        pending? (some? (:ui/send-pending state))
        dictating (#{:recording :finishing} (get-in state [:ui/dictation :status]))
        ready? (and (not (dictation/active? state))
                    (or (not (str/blank? draft))
                        (some #(not (:failed? %)) attachments))
                    (not pending?)
                    (not (ui/kind-issued? state :chat/send)))
        send-later! (fn []
                      (swap! store assoc :ui/send-pending {:actions send
                                                           :store-key store-key
                                                           :attachments-key attachments-key
                                                           :chips (set (keep #(when (:uploading? %) (:chip %))
                                                                             attachments))}))
        send! (fn []
                (cond
                  (and dictating (not pending?) (not (ui/kind-issued? state :chat/send)))
                  (do (when (= :recording dictating)
                        (dictation/stop! store store-key dictation-url))
                      (send-later!))

                  ready?
                  (if uploading?
                    (send-later!)
                    (state/dispatch! send))))]
    [:div.composer-input
     (when (:ui/dropping? state)
       (drop-veil store attachments-key upload-url {:title drop-title
                                                    :hint drop-hint}))
     (dictation/indicator state)
     (when record-url
       (recorder/panel store state recorder phone))
     (attachments-view store attachments-key attachments {:remove-title remove-title
                                                          :uploading-label uploading-label
                                                          :failed-label failed-label})
     ;; keyed, as all the composer's children that come and go: the strip
     ;; of a dictation appearing above would otherwise make Replicant build
     ;; the row again, and the text would lose the focus and its caret
     [:div.row {:replicant/key "row"}
      [:textarea
       (-> (dissoc attrs :ui/store-key :ui/attachments-key :ui/upload-url :ui/send
                   :ui/send-label :ui/send-pending-label :ui/send-cancel-title :ui/attach-title :ui/remove-title :ui/uploading-label
                   :ui/failed-label :ui/drop-title :ui/drop-hint :ui/dictation-url :ui/dictate-title
                   :ui/dictate-stop-title :ui/transcribing-title :ui/dictation-failed-title
                   :ui/send-after-dictation-label :ui/state :ui/stop :ui/stop-label
                   :ui/record-url :ui/recorder :ui/phone)
           (assoc :value draft
                  :readonly (dictation/active? state)
                  :on {:input [[:store/assoc store-key :event/target.value]]
                       :paste (fn [^js event]
                                (let [files (pasted-files event)]
                                  (when (seq files)
                                    (.preventDefault event)
                                    (upload-all! store attachments-key upload-url files))))
                       :keydown (fn [^js event]
                                  (dictation/key-down! store store-key dictation-url event)
                                  ;; Ctrl+Enter sends, Cmd+Enter on a Mac;
                                  ;; Enter alone breaks the line, as in a
                                  ;; text field anywhere, on a phone too
                                  (when (and (= "Enter" (.-key event))
                                             (or (.-ctrlKey event) (.-metaKey event))
                                             (not (.-isComposing event)))
                                    (.preventDefault event)
                                    (send!)))
                       :keyup (fn [^js event]
                                (dictation/key-up! store store-key dictation-url event))
                       :blur (fn [_]
                               (dictation/blur! store store-key dictation-url))}))]
      (when record-url
        (recorder/button store state {:url record-url
                                      :store-key store-key
                                      :labels recorder}))
      (dictation/button store store-key dictation-url state
                        {:dictate-title dictate-title
                         :dictate-stop-title dictate-stop-title
                         :transcribing-title transcribing-title
                         :failed-title dictation-failed-title})
      [:label.attach {:title (or attach-title "Attach an image, a video or an audio file")}
       "📎"
       [:input {:type "file"
                :accept "image/*,video/*,audio/*,.mkv,.flac,.m4a"
                :multiple true
                :style {:display "none"}
                :on {:change (fn [^js event]
                               (upload-all! store attachments-key upload-url
                                            (array-seq (.. event -target -files)))
                               (set! (.. event -target -value) ""))}}]]
      (when stop
        [:button.stop {:type "button"
                       :title stop-label
                       :on {:click (fn [_event] (state/dispatch! stop))}}
         [:span.word (or stop-label "Stop")]
         [:span.icon {:aria-hidden "true"} "■"]])
      [:button.primary.send {:disabled (not (or ready? pending? dictating))
                             :class (when pending? "pending")
                             :title (if pending? send-cancel-title (or send-label "Send"))
                             :on {:click (fn [_event]
                                           (if pending?
                                             (swap! store dissoc :ui/send-pending)
                                             (send!)))}}
       [:span.word
        (cond
          (and pending? (dictation/active? state)) (or send-after-dictation-label "Sends once the text is in")
          pending? (or send-pending-label "Sends after the upload")
          :else (or send-label "Send"))]
       [:span.icon {:aria-hidden "true"} "↑"]]]]))

(defonce ^:private canvas-node*
  (atom nil))

(defonce ^:private canvas-src*
  (atom nil))

(defonce ^:private canvas-scroll*
  (atom nil))

(defn- canvas-scroll
  "The scroll position of the page in the iframe, same origin."
  []
  (try
    (when-let [^js node @canvas-node*]
      (let [^js win (.-contentWindow node)]
        [(.-scrollX win) (.-scrollY win)]))
    (catch :default _
      nil)))

(defn- restore-canvas-scroll!
  [_event]
  (when-let [[x y] @canvas-scroll*]
    (reset! canvas-scroll* nil)
    (try
      (when-let [^js node @canvas-node*]
        (.scrollTo (.-contentWindow node) x y))
      (catch :default _
        nil))))

(defn- watch-canvas-drags!
  "The drags into the canvas's page, same origin; each load is a new
   window."
  [^js event]
  (try
    (watch-drags! (.. event -target -contentWindow))
    (catch :default _)))

(defn canvas
  "The creation of the agent: an iframe over the page the server names.
   A new `:ui/rev` changes the src and reloads the page at the position
   the person was looking at; everything else Replicant leaves alone.
   `:ui/title` is the title of the iframe."
  [{:keys [ui/src ui/rev ui/title]} _children]
  (let [src (str src "?rev=" rev)]
    (when (and @canvas-src* (not= src @canvas-src*))
      (reset! canvas-scroll* (canvas-scroll)))
    (reset! canvas-src* src)
    [:iframe.canvas {:replicant/key "canvas"
                     :src src
                     :title (or title "Your creation")
                     :replicant/on-mount (fn [{:keys [replicant/node]}]
                                           (reset! canvas-node* node)
                                           (.addEventListener ^js node "load" restore-canvas-scroll!)
                                           (.addEventListener ^js node "load" watch-canvas-drags!))
                     :replicant/on-unmount (fn [_]
                                             (reset! canvas-node* nil)
                                             (reset! canvas-src* nil))}]))

(defn- checked-values
  "The `value`s of the inputs ticked in the ```hiccup block around
   `node`, comma-separated."
  [^js node]
  (let [block (or (some-> node (.closest ".genui")) js/document)]
    (->> (array-seq (.querySelectorAll block "input:checked"))
         (map #(.-value ^js %))
         (remove str/blank?)
         (str/join ", "))))

(defn send-checked
  "A button of a ```hiccup block (`rragent.genui`) whose text to send
   has `{checked}` in it: a click puts the values ticked in its block
   there and sends the text as the person's message to the bot of the
   block (`:ui/bot`), the way a button with a plain `:send` does. Which
   boxes are ticked only the page knows, so the text is made here."
  [{:keys [ui/send ui/bot] :as attrs} children]
  (into [:button (-> (dissoc attrs :ui/send :ui/bot)
                     (assoc :on {:click (fn [^js event]
                                          ;; split and join, not str/replace: a `$` in a value is no replacement pattern
                                          (let [text (str/join (checked-values (.-currentTarget event))
                                                               (str/split (str send) #"\{checked\}" -1))]
                                            (state/dispatch! [[:data/command {:command/kind :chat/send
                                                                              :command/data {:bot bot :text text}}]])))}))]
        children))

(defn bot-id-of
  "The bot id of a page path, `/bot/<id>`; nil for any other path."
  [path]
  (second (re-matches #"/bot/([0-9]+)/?" (str path))))

(defn- show!
  "Tells the server which bot this tab shows now: `id`, or nil for
   whatever the URL names."
  [id]
  (state/dispatch! [[:data/command {:command/kind :bot/show
                                    :command/data {:id id
                                                   :tab state/tabid}}]]))

(defn- close-drawer!
  "On a phone the sidebar is a drawer over a checkbox; a tap on a bot
   closes it."
  []
  (when-let [^js nav (js/document.getElementById "nav")]
    (set! (.-checked nav) false)))

(defn- go!
  "To the bot's page without a reload."
  [id]
  (js/history.pushState nil "" (str "/bot/" id js/location.search))
  (close-drawer!)
  (show! id))

(defn- plain-click?
  "A left click without a modifier: the ones that open in a new tab or
   window stay the browser's."
  [^js event]
  (and (zero? (.-button event))
       (not (or (.-metaKey event) (.-ctrlKey event) (.-shiftKey event) (.-altKey event)))))

(defn- close-menus!
  "The open actions menus of the sidebar (`<details>`, see
   `rragent.view/bot-actions`) but the one whose three dots were just
   clicked: the browser toggles that one itself."
  [^js target]
  (doseq [^js details (array-seq (js/document.querySelectorAll "details.actions[open]"))]
    (when-not (and target
                   (.contains details target)
                   (.closest target "summary"))
      (.removeAttribute details "open"))))

;; the var is nobody's, the listeners are the point
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private bot-listeners
  ;; a click on a link to a bot (`a[data-bot]`, the sidebar rows) stays on
  ;; the page; the back and forward buttons show the bot of the URL they
  ;; return to; a click anywhere, or Escape, closes the actions menus
  (do (js/document.addEventListener
       "click"
       (fn [^js event]
         (close-menus! (.-target event))
         ;; the new bot opens in this tab with the next frame
         (when (some-> (.-target event) (.closest ".sidebar button.new"))
           (close-drawer!))
         (when-let [^js link (some-> (.-target event) (.closest "a[data-bot]"))]
           (when (plain-click? event)
             (.preventDefault event)
             (go! (.getAttribute link "data-bot"))))
         ;; a card in the chat (`a[data-artifact]`, rragent.view/artifact-card)
         ;; opens the artifact in the pane, and the pane on a phone
         (when-let [^js card (some-> (.-target event) (.closest "a[data-artifact]"))]
           (when (plain-click? event)
             (.preventDefault event)
             (state/dispatch! [[:data/command {:command/kind :artifacts/open
                                               :command/data {:bot (.getAttribute card "data-artifact-bot")
                                                              :path (.getAttribute card "data-artifact")}}]])
             (when-let [^js made (js/document.getElementById "view-made")]
               (set! (.-checked made) true))))))
      (js/window.addEventListener
       "popstate"
       (fn [_]
         (show! (bot-id-of js/location.pathname))))
      (js/document.addEventListener
       "keydown"
       (fn [^js event]
         (when (= "Escape" (.-key event))
           (close-menus! nil))))
      true))

(defn url-alias
  "`[:ui/url {:ui/path \"/bot/<id>\"}]` in a frame: the page of the bot
   the server shows; when the address bar says another, it is corrected
   in place, no entry in the history. Renders nothing."
  [{:keys [ui/path]} _children]
  (when (and (seq path) (not= path js/location.pathname))
    (js/history.replaceState nil "" (str path js/location.search)))
  [:span.url {:hidden true}])

(def theme-key
  "Where the browser keeps the theme the person chose."
  "rragent.theme")

(defn- dark?
  "Whether the page shows dark: the theme chosen, else the system's."
  [state]
  (case (or (:ui/theme state) (.. js/document -documentElement -dataset -theme))
    "dark" true
    "light" false
    (.-matches (js/matchMedia "(prefers-color-scheme: dark)"))))

(defn- choose-theme!
  "`theme` on the page and kept for the next visit; a browser that keeps
   nothing (a private window) still switches."
  [theme]
  (set! (.. js/document -documentElement -dataset -theme) theme)
  (try
    (.setItem js/localStorage theme-key theme)
    (catch :default _ nil))
  (swap! state/store assoc :ui/theme theme))

;; the var is nobody's, the listener is the point
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private system-theme-listener
  ;; the system's theme changes while none is chosen (night falls): the
  ;; stylesheet follows at once, the button's symbol with a change of the
  ;; store
  (.addEventListener (js/matchMedia "(prefers-color-scheme: dark)") "change"
                     (fn [^js event]
                       (swap! state/store assoc :ui/system-dark? (.-matches event)))))

(defn theme-toggle
  "The header's button that switches between the light and the dark
   theme, showing the one it switches to. The head's script
   (`rragent.assets/head`) puts the kept theme on the page before it
   paints; without one the system's applies."
  [{:keys [ui/light-title ui/dark-title ui/state] :as attrs} _children]
  (let [dark (dark? state)]
    [:button.theme (-> (dissoc attrs :ui/light-title :ui/dark-title :ui/state)
                       (assoc :title (if dark light-title dark-title)
                              :aria-label (if dark light-title dark-title)
                              :on {:click (fn [_] (choose-theme! (if dark "light" "dark")))}))
     ;; U+FE0E: the text symbol, not a colored emoji on a phone
     (if dark "\u2600\uFE0E" "\u263E\uFE0E")]))

(defn- narrow
  "The hiccup with `hidden` on every node that carries a `data-name`
   (a row of the list, a group of rows) without `query` in it; the
   group's `data-name` is its rows' together, so that it hides with
   them."
  [query node]
  (cond
    (vector? node)
    (let [[tag attrs & children] node
          named? (and (map? attrs) (contains? attrs :data-name))]
      (into (if (map? attrs)
              [tag (cond-> attrs
                     named? (assoc :hidden (not (str/includes? (str (:data-name attrs)) query))))]
              [tag])
            (map #(narrow query %) (if (map? attrs) children (rest node)))))

    (seq? node)
    (map #(narrow query %) node)

    :else node))

(defn files-filter
  "The list of the artifacts (`rragent.view/artifacts`, the rows as
   the children) with a field above it that narrows the rows: the
   text lives in the store under `:ui/store-key`, per bot, so that a
   frame does not reset it; a row whose `data-name` (its title and
   path, lowercase) does not have the text is hidden, the rows stay
   the server's. The field only with `:ui/field?`, a long list."
  [{:keys [ui/store-key ui/field? ui/placeholder ui/state]} children]
  (let [text (str (get state store-key ""))
        query (if field? (str/lower-case (str/trim text)) "")]
    [:div.list
     (when field?
       [:input.filter {:type "search"
                       :value text
                       :placeholder placeholder
                       :aria-label placeholder
                       :on {:input [[:store/assoc store-key :event/target.value]]}}])
     (into [:ul.files] (map #(narrow query %) children))]))

(defn- tokens
  "A count of tokens as the Claude app writes it: 775.2k, 1M."
  [n]
  (let [n (or n 0)]
    (cond
      (>= n 1000000) (let [m (/ n 1000000)]
                       (str (if (= m (js/Math.floor m)) (js/Math.round m) (.toFixed m 1)) "M"))
      (>= n 1000) (str (.toFixed (/ n 1000) 1) "k")
      :else (str n))))

(defn- fill-in
  "`{name}` in a label replaced by the values."
  [label values]
  (reduce (fn [text [k v]] (str/replace text (str "{" (name k) "}") (str v)))
          (str label)
          values))

(defn- ring
  "A ring, `fraction` of it drawn, as the Claude app's: an SVG circle
   whose dash is the fraction of its circumference."
  [fraction]
  (let [r 7
        c (* 2 js/Math.PI r)]
    [:svg.ring {:viewBox "0 0 20 20" :width 20 :height 20 :aria-hidden "true"}
     [:circle.track {:cx 10 :cy 10 :r r}]
     [:circle.fill {:cx 10 :cy 10 :r r
                    :style {:stroke-dasharray (str (* c (js/Math.min 1 (js/Math.max 0 fraction))) " " c)}}]]))

(defn context-alias
  "`[:ui/context {:ui/used :ui/window :ui/cost :ui/labels}]` in the
   header: how full the model's context is, a ring; a click opens the
   usage card below it (`:ui/context-open?`, client state): the
   percent and a bar, the tokens of the window, the cost of the
   conversation so far, and a word of advice when it gets full."
  [{:keys [ui/used ui/window ui/cost ui/labels ui/state]} _children]
  (let [fraction (when (and window (pos? window)) (/ used window))
        percent (when fraction (js/Math.round (* 100 fraction)))
        level (cond
                (nil? fraction) nil
                (>= fraction 0.85) "full"
                (>= fraction 0.6) "warn"
                :else nil)
        open? (:ui/context-open? state)]
    [:div.context {:class level}
     [:button.ring-button {:type "button"
                           :title (if percent
                                    (fill-in (:ring labels) {:percent percent})
                                    (fill-in (:unknown labels) {:used (tokens used)}))
                           :aria-expanded (str (boolean open?))
                           :on {:click (fn [_] (swap! state/store update :ui/context-open? not))}}
      (ring (or fraction 0))]
     (when open?
       [:div.usage {:replicant/key "usage"}
        [:div.head
         [:strong (:title labels)]
         [:button.link {:type "button"
                        :on {:click (fn [_] (swap! state/store dissoc :ui/context-open?))}}
          (:close labels)]]
        (if percent
          (list
           [:div.row
            [:span (fill-in (:used labels) {:percent percent})]]
           [:div.bar [:div.filled {:style {:width (str (js/Math.min 100 percent) "%")}}]]
           [:p.muted (fill-in (:of labels) {:used (tokens used) :window (tokens window)})])
          [:p.muted (fill-in (:unknown labels) {:used (tokens used)})])
        (when cost
          [:p.muted (fill-in (:cost labels) {:cost (str "$" (.toFixed cost 2))})])
        (when (and fraction (>= fraction 0.7))
          [:p.hint (:hint labels)])])]))

(def pane-width-key
  "Where the browser keeps the width of the pane the person dragged."
  "rragent.pane-width")

(defn- pane-width
  []
  (try
    (some-> (js/localStorage.getItem pane-width-key) js/parseInt (as-> n (when-not (js/isNaN n) n)))
    (catch :default _
      nil)))

(defn- set-pane-width!
  "The pane's width on the body the splitter is in, kept within what
   leaves the chat room; nil clears it."
  [^js splitter width]
  (when-let [^js body (.-parentElement splitter)]
    (if width
      (let [room (.-clientWidth body)
            width (js/Math.round (js/Math.min (js/Math.max width 320) (js/Math.max 320 (- room 420))))]
        (.setProperty (.-style body) "--pane-width" (str width "px"))
        width)
      (.removeProperty (.-style body) "--pane-width"))))

(defn splitter
  "`[:ui/splitter]`: the handle between the chat and the pane. Dragged,
   it sets the pane's width (`--pane-width` on the body, read by the
   stylesheet) and the browser remembers it; a double click gives the
   half back. While it drags the iframes take no pointer (`.dragging`
   on the body, the stylesheet), so the drag does not stop over a
   page."
  [_attrs _children]
  [:div.splitter {:role "separator"
                  :aria-orientation "vertical"
                  :replicant/on-mount (fn [{:keys [replicant/node]}]
                                        (when-let [width (pane-width)]
                                          (set-pane-width! node width)))
                  :on {:pointerdown (fn [^js event]
                                      (let [^js node (.-currentTarget event)
                                            ^js body (.-parentElement node)]
                                        (.preventDefault event)
                                        (.setPointerCapture node (.-pointerId event))
                                        (.add (.-classList body) "dragging")))
                       :pointermove (fn [^js event]
                                      (let [^js node (.-currentTarget event)
                                            ^js body (.-parentElement node)]
                                        (when (.hasPointerCapture node (.-pointerId event))
                                          (set-pane-width! node (- (.-right (.getBoundingClientRect body)) (.-clientX event))))))
                       :pointerup (fn [^js event]
                                    (let [^js node (.-currentTarget event)
                                          ^js body (.-parentElement node)]
                                      (when (.hasPointerCapture node (.-pointerId event))
                                        (.releasePointerCapture node (.-pointerId event))
                                        (.remove (.-classList body) "dragging")
                                        (when-let [width (set-pane-width! node (- (.-right (.getBoundingClientRect body)) (.-clientX event)))]
                                          (try
                                            (js/localStorage.setItem pane-width-key (str width))
                                            (catch :default _
                                              nil))))))
                       :dblclick (fn [^js event]
                                   (set-pane-width! (.-currentTarget event) nil)
                                   (try
                                     (js/localStorage.removeItem pane-width-key)
                                     (catch :default _
                                       nil)))}}])

(def register
  "The aliases of the page, the URL of the bot, and `:ui/tabid` in a
   command, the id of this tab."
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}
   {:ui.alias/kind :ui/splitter
    :ui.alias/fn splitter}
   {:ui.alias/kind :ui/context
    :ui.alias/fn context-alias}
   {:ui.alias/kind :ui/files-filter
    :ui.alias/fn files-filter}
   {:ui.alias/kind :ui/canvas
    :ui.alias/fn canvas}
   {:ui.alias/kind :ui/send-checked
    :ui.alias/fn send-checked}
   {:ui.alias/kind :ui/theme-toggle
    :ui.alias/fn theme-toggle}
   {:ui.alias/kind :ui/url
    :ui.alias/fn url-alias}
   {:ui.action-enricher/kind :ui/tabid
    :ui.action-enricher/fn (fn [{:keys [ui.action/x]}]
                             (when (= :ui/tabid x)
                               state/tabid))}])

(defn init!
  "The page's runtime; on the phone's page (`#phone`, see
   `rragent.phone`) the phone's app instead."
  []
  (if-let [node (js/document.getElementById "phone")]
    (phone/mount! node)
    (rframes/init! {:register register})))

(init!)
