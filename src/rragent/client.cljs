(ns rragent.client
  "The browser bundle of the page: the rframes runtime, which opens the
   frame stream of the page it is on and renders every frame with
   Replicant, with aliases of our own: the composer, the canvas of the
   artifacts pane, the iframe that shows a page or a project and reloads
   it in place (the canvas is rchat's), and the button of a ```hiccup
   block that sends the boxes ticked in it.

   The composer takes files, the way pchat's does: an image picked,
   dropped or pasted goes up scaled and in one request, a video or an
   audio file in pieces of `piece-bytes` with its progress on its chip,
   and Send waits until every clip is in (`rragent.uploads` on the
   server). The attachments are client state beside the draft, a frame
   from the server does not reset them."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [rframes.client :as rframes]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]))

(def max-side
  "Pixels on the long side of an uploaded image, pchat's."
  1600)

(def piece-bytes
  "The size of one request of a clip upload."
  (* 8 1024 1024))

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
   `on-answer` with the server's answer, `on-error` with the status."
  [url ^js blob type {:keys [on-progress on-answer on-error]}]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" url)
    (.setRequestHeader xhr "Content-Type" type)
    (set! (.. xhr -upload -onprogress) (fn [^js event]
                                         (on-progress (.-loaded event))))
    (set! (.-onload xhr) (fn [_]
                           (if (<= 200 (.-status xhr) 299)
                             (on-answer (read-answer (.-responseText xhr)))
                             (on-error (.-status xhr)))))
    (set! (.-onerror xhr) (fn [_]
                            (on-error 0)))
    (.send xhr blob)))

(defn- upload-clip!
  "Uploads a video or an audio file in pieces. Its chip is in the
   attachments from the start, `:uploading?` with its `:progress`; the
   last answer turns it into `{:name :url :kind :size}` of the stored
   file. A chip the person removes stops the upload after the piece
   under way."
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
    ((fn step [offset]
       (let [end (min total (+ offset piece-bytes))]
         (send-piece! (str upload-url "?id=" id "&offset=" offset "&total=" total)
                      (.slice file offset end)
                      type
                      {:on-progress (fn [loaded]
                                      (update-chip! #(assoc % :progress (/ (+ offset loaded) (max total 1)))))
                       :on-answer (fn [{:keys [url] :as answer}]
                                    (cond
                                      url (update-chip! #(-> %
                                                             (merge (select-keys answer [:name :url :kind :size]))
                                                             (dissoc :uploading? :progress)))
                                      (present?) (step end)))
                       :on-error (fn [status]
                                   (js/console.error "[upload] a piece failed with" status)
                                   (update-chip! #(-> %
                                                      (dissoc :uploading? :progress)
                                                      (assoc :failed? true))))})))
     0)))

(defn- upload-all!
  [store attachments-key upload-url files]
  (doseq [^js file files]
    (cond
      (clip-type file) (upload-clip! store attachments-key upload-url file)
      (image-file? file) (upload-image! store attachments-key upload-url file))))

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
  [{:keys [kind file-name name size uploading? progress failed?]} uploading-label failed-label]
  [:span.clip
   [:span.icon (if (= :audio kind) "🎵" "🎬")]
   [:span.file (short-name (or file-name name))]
   [:small (megabytes size)
    (cond
      uploading? (str " · " (or uploading-label "Uploading") " "
                      (js/Math.round (* 100 progress)) " %")
      failed? (str " · " (or failed-label "The upload failed")))]])

(defn- attachments-view
  [store attachments-key attachments {:keys [remove-title uploading-label failed-label]}]
  (when (seq attachments)
    [:div.attachments
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

(defn composer
  "The textarea of the page with its attachments. The text lives in the
   store under `:ui/store-key`, the attachments under
   `:ui/attachments-key`, so that a frame from the server resets
   neither; files go to `:ui/upload-url`. Enter and the Send button run
   `:ui/send`, the actions of the server, once every upload is in;
   Shift+Enter breaks the line. A paste with files on the clipboard
   uploads them, a paste of text pastes; a drop anywhere on the composer
   uploads the files. The labels are the server's, translated; the
   other attributes pass through to the textarea."
  [{:keys [ui/store-key ui/attachments-key ui/upload-url ui/send ui/send-label
           ui/attach-title ui/remove-title ui/uploading-label ui/failed-label ui/state]
    :as attrs}
   _children]
  (let [store state/store
        attachments-key (or attachments-key :attachments)
        upload-url (or upload-url "/upload")
        draft (str (get state store-key ""))
        attachments (get state attachments-key [])
        uploading? (some :uploading? attachments)
        ready? (and (not uploading?)
                    (or (not (str/blank? draft))
                        (some #(not (:failed? %)) attachments))
                    (not (ui/kind-issued? state :chat/send)))
        send! (fn []
                (when ready?
                  (state/dispatch! send)))]
    [:div.composer-input {:on {:dragover (fn [^js event]
                                           (.preventDefault event))
                               :drop (fn [^js event]
                                       (.preventDefault event)
                                       (upload-all! store attachments-key upload-url
                                                    (array-seq (.. event -dataTransfer -files))))}}
     (attachments-view store attachments-key attachments {:remove-title remove-title
                                                          :uploading-label uploading-label
                                                          :failed-label failed-label})
     [:div.row
      [:textarea
       (-> (dissoc attrs :ui/store-key :ui/attachments-key :ui/upload-url :ui/send
                   :ui/send-label :ui/attach-title :ui/remove-title :ui/uploading-label
                   :ui/failed-label :ui/state)
           (assoc :value draft
                  :on {:input [[:store/assoc store-key :event/target.value]]
                       :paste (fn [^js event]
                                (let [files (pasted-files event)]
                                  (when (seq files)
                                    (.preventDefault event)
                                    (upload-all! store attachments-key upload-url files))))
                       :keydown (fn [^js event]
                                  (when (and (= "Enter" (.-key event))
                                             (not (.-shiftKey event))
                                             (not (.-isComposing event)))
                                    (.preventDefault event)
                                    (send!)))}))]
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
      [:button.primary {:disabled (not ready?)
                        :on {:click (fn [_event]
                                      (send!))}}
       (or send-label "Send")]]]))

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
                                           (.addEventListener ^js node "load" restore-canvas-scroll!))
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
   there and sends the text as the person's message, the way a button
   with a plain `:send` does. Which boxes are ticked only the page
   knows, so the text is made here."
  [{:keys [ui/send] :as attrs} children]
  (into [:button (-> (dissoc attrs :ui/send)
                     (assoc :on {:click (fn [^js event]
                                          (let [text (str/replace (str send) "{checked}" (checked-values (.-currentTarget event)))]
                                            (state/dispatch! [[:data/command {:command/kind :chat/send
                                                                              :command/data {:text text}}]])))}))]
        children))

(def register
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}
   {:ui.alias/kind :ui/canvas
    :ui.alias/fn canvas}
   {:ui.alias/kind :ui/send-checked
    :ui.alias/fn send-checked}])

(defn init!
  []
  (rframes/init! {:register register}))

(init!)
