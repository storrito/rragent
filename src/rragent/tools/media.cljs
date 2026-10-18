(ns rragent.tools.media
  "Pictures, voices and music from the models behind OpenRouter, written
   into `work/artifacts/`: the `image` tool (an image model, a picture
   from a description, a ratio, a picture to start from), the `voice`
   tool (text to speech into a project's folder) and the `music` tool
   (a piece from a description, a clip or a whole song). The
   counterparts of pchat's bin/image, bin/voice and bin/music; the key
   is OPENROUTER_API_KEY, the models RRAGENT_IMAGE_MODEL,
   RRAGENT_VOICE_MODEL, RRAGENT_MUSIC_MODEL and RRAGENT_MUSIC_MODEL_LONG,
   the address OPENROUTER_BASE_URL (a test points it at a fake)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.tools.work :as work]))

(defn- env
  [name default]
  (or (not-empty (aget (.-env js/process) name)) default))

(defn base-url
  []
  (str/replace (env "OPENROUTER_BASE_URL" "https://openrouter.ai/api/v1") #"/+$" ""))

(defn- key!
  [what]
  (or (not-empty (aget (.-env js/process) "OPENROUTER_API_KEY"))
      (throw (js/Error. (str "OPENROUTER_API_KEY is not set, so no " what ".")))))

(defn- check-path
  "A path under artifacts/, nothing above it."
  [file]
  (when (or (str/blank? file) (str/includes? file "..") (str/starts-with? file "/"))
    (throw (js/Error. "The file is a path under artifacts/, like card/pool.png."))))

(defn- write!
  [file ^js bytes]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file bytes))

(defn seconds-of
  "The length of an audio file, by ffprobe when it is there."
  [file]
  (try
    (let [result (child/spawnSync "ffprobe" #js ["-v" "error" "-show_entries" "format=duration" "-of" "csv=p=0" file] #js {:encoding "utf8" :timeout 20000})]
      (when (zero? (or (.-status result) 1))
        (some-> (str/trim (str (.-stdout result))) parse-double (* 10) js/Math.round (/ 10))))
    (catch :default _
      nil)))

(defn- ^:async post
  "One POST to OpenRouter: the response, or a throw with its status and
   message."
  [what route body key]
  (let [response (await (js/fetch (str (base-url) route)
                                  #js {:method "POST"
                                       :headers #js {"Authorization" (str "Bearer " key)
                                                     "Content-Type" "application/json"}
                                       :body (js/JSON.stringify (clj->js body))
                                       :signal (js/AbortSignal.timeout 300000)}))]
    (when-not (.-ok response)
      (let [text (await (.text response))
            message (try (aget (js/JSON.parse text) "error" "message") (catch :default _ nil))]
        (throw (js/Error. (str "The " what " service answered " (.-status response) ": " (or message text))))))
    response))

(defn- cost-text
  [cost]
  (when (number? cost)
    (str ", $" (.toFixed cost 3))))

;;; image

(def image-model
  (env "RRAGENT_IMAGE_MODEL" "google/gemini-3.1-flash-image"))

(defn- data-url
  [file]
  (let [extension (str/lower-case (subs (path/extname file) 1))
        type (case extension
               "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp" "gif" "image/gif"
               "image/png")]
    (str "data:" type ";base64," (.toString (fs/readFileSync file) "base64"))))

(defn- source-file
  "The picture `from` names, under artifacts/ or the working directory."
  [dir work from]
  (let [from (str/replace (str from) #"^/" "")]
    (or (some #(when (fs/existsSync %) %) [(path/join dir from) (path/join work from)])
        (throw (js/Error. (str "There is no picture " from " under artifacts/."))))))

(defn ^:async image!
  "The picture written; answers the line for the model."
  [{:keys [dir work prompt file ratio from]}]
  (check-path file)
  (let [key (key! "picture")
        response (await (post "image" "/images"
                              (cond-> {:model image-model :prompt prompt :n 1}
                                (seq ratio) (assoc :aspect_ratio ratio)
                                (seq from) (assoc :input_references [{:type "image_url"
                                                                      :image_url {:url (data-url (source-file dir work from))}}]))
                              key))
        body (await (.json response))
        first-image (aget (or (.-data body) #js []) 0)
        b64 (some-> first-image .-b64_json)
        type (or (some-> first-image .-media_type) "image/png")]
    (when (str/blank? b64)
      (throw (js/Error. "The image service sent no picture.")))
    (let [bytes (js/Buffer.from b64 "base64")
          extension (case type "image/jpeg" "jpg" "image/webp" "webp" "image/gif" "gif" "png")
          target (path/join dir (if (seq (path/extname file)) file (str file "." extension)))]
      (write! target bytes)
      (str "artifacts/" (path/relative dir target) ": " (.-length bytes) " bytes" (cost-text (some-> body .-usage .-cost)) ", model " image-model))))

(def image
  (defineTool
   #js {:name "image"
        :description (str "A picture from a description, by an image model (about 7 cents). `file` is a path under artifacts/: "
                          "card/pool.png goes into a project's folder, pool.png beside the chat as a picture of its own. "
                          "`ratio` 9:16 for a story, 16:9 or 4:5 otherwise; `from` a picture under artifacts/ to start from, for a photo in another style. "
                          "Describe the scene, the light and the style, and say photo or illustration.")
        :parameters (.Object Type #js {:prompt (.String Type #js {:description "What to show"})
                                       :file (.String Type #js {:description "The path under artifacts/"})
                                       :ratio (.Optional Type (.String Type #js {:description "1:1, 9:16, 16:9, 4:5, 3:4 ..."}))
                                       :from (.Optional Type (.String Type #js {:description "A picture under artifacts/ to start from"}))})
        :execute (^:async fn [args api _call-context]
                   (work/text (await (image! {:dir (work/artifacts-dir api)
                                              :work (work/dir api)
                                              :prompt (.-prompt args)
                                              :file (.-file args)
                                              :ratio (.-ratio args)
                                              :from (.-from args)}))))}))

;;; voice

(def voice-model
  (env "RRAGENT_VOICE_MODEL" "elevenlabs/eleven-multilingual-v2"))

(def default-voice
  (env "RRAGENT_VOICE" "sarah"))

(defn ^:async voice!
  [{:keys [dir project text file voice]}]
  (let [voice (or (not-empty voice) default-voice)
        project-dir (path/join dir (str project))
        target (path/join project-dir (or (not-empty file) "voice.mp3"))]
    (check-path (str project "/" (or file "voice.mp3")))
    (when-not (fs/existsSync project-dir)
      (throw (js/Error. (str "artifacts/" project " does not exist."))))
    (let [key (key! "voice")
          response (await (post "voice" "/audio/speech" {:model voice-model :input text :voice voice :response_format "mp3"} key))
          bytes (js/Buffer.from (await (.arrayBuffer response)))]
      (when (zero? (.-length bytes))
        (throw (js/Error. "The voice service sent no audio.")))
      (write! target bytes)
      (str "artifacts/" (path/relative dir target) ": " (.-length bytes) " bytes"
           (when-let [seconds (seconds-of target)] (str ", " seconds " s"))
           ;; the voice service bills per character, two cents a thousand
           ", about $" (.toFixed (* 0.00002 (count text)) 4)
           ", voice " voice ", model " voice-model))))

(def voice
  (defineTool
   #js {:name "voice"
        :description (str "The words spoken by an AI voice, in the language of the words (a tenth of a cent a sentence), written as artifacts/<project>/voice.mp3 or the file named: "
                          "a track for the design like music. Voices: sarah (the default), george, adam, alice, bella, bill.")
        :parameters (.Object Type #js {:project (.String Type #js {:description "The project's name, its folder under artifacts/"})
                                       :text (.String Type #js {:description "The words"})
                                       :file (.Optional Type (.String Type #js {:description "The file in the project's folder, voice.mp3 by default"}))
                                       :voice (.Optional Type (.String Type #js {:description "The voice, sarah by default"}))})
        :execute (^:async fn [args api _call-context]
                   (work/text (await (voice! {:dir (work/artifacts-dir api)
                                              :project (.-project args)
                                              :text (.-text args)
                                              :file (.-file args)
                                              :voice (.-voice args)}))))}))

;;; music

(def music-models
  {:clip (env "RRAGENT_MUSIC_MODEL" "google/lyria-3-clip-preview")
   :long (env "RRAGENT_MUSIC_MODEL_LONG" "google/lyria-3-pro-preview")})

(defn audio-of-stream
  "The audio of a streamed chat completion with the audio modality and
   the cost, `{:bytes :cost}`: every chunk's base64 decoded on its own
   and the bytes joined, as the OpenAI examples do it, since a chunk
   may carry its own padding."
  [text]
  (let [out (reduce (fn [out line]
                      (if (and (str/starts-with? line "data: ") (not= "data: [DONE]" line))
                        (let [chunk (js/JSON.parse (subs line 6))
                              data (some-> (aget (or (.-choices chunk) #js []) 0) .-delta .-audio .-data)]
                          (when-let [error (.-error chunk)]
                            (throw (js/Error. (str "The music service failed: " (.-message error)))))
                          (cond-> out
                            data (update :chunks conj (js/Buffer.from data "base64"))
                            (some-> chunk .-usage .-cost) (assoc :cost (.. chunk -usage -cost))))
                        out))
                    {:chunks [] :cost nil}
                    (str/split-lines text))]
    {:bytes (js/Buffer.concat (to-array (:chunks out)))
     :cost (:cost out)}))

(defn ^:async music!
  [{:keys [dir project prompt file long?]}]
  (let [project-dir (path/join dir (str project))
        target (path/join project-dir (or (not-empty file) "music.mp3"))
        model (get music-models (if long? :long :clip))]
    (check-path (str project "/" (or file "music.mp3")))
    (when-not (fs/existsSync project-dir)
      (throw (js/Error. (str "artifacts/" project " does not exist."))))
    (let [key (key! "music")
          response (await (post "music" "/chat/completions"
                                {:model model
                                 :modalities ["text" "audio"]
                                 :audio {:format "mp3"}
                                 :stream true
                                 :messages [{:role "user" :content prompt}]}
                                key))
          {:keys [bytes cost]} (audio-of-stream (await (.text response)))]
      (when (zero? (.-length bytes))
        (throw (js/Error. "The music service sent no audio.")))
      (write! target bytes)
      (str "artifacts/" (path/relative dir target) ": " (.-length bytes) " bytes"
           (when-let [seconds (seconds-of target)] (str ", " seconds " s"))
           (cost-text cost)
           ", model " model))))

(def music
  (defineTool
   #js {:name "music"
        :description (str "A piece of music from a description, by an AI music model: a 30-second clip (about 4 cents), a whole song with `long` (about 8 cents), "
                          "written as artifacts/<project>/music.mp3 or the file named, composition/music.mp3 for a HyperFrames project. "
                          "Describe the style, the mood, the tempo and the instruments, and say instrumental when nobody should sing.")
        :parameters (.Object Type #js {:project (.String Type #js {:description "The project's name, its folder under artifacts/"})
                                       :prompt (.String Type #js {:description "What it should sound like"})
                                       :file (.Optional Type (.String Type #js {:description "The file in the project's folder, music.mp3 by default"}))
                                       :long (.Optional Type (.Boolean Type #js {:description "True: a whole song"}))})
        :execute (^:async fn [args api _call-context]
                   (work/text (await (music! {:dir (work/artifacts-dir api)
                                              :project (.-project args)
                                              :prompt (.-prompt args)
                                              :file (.-file args)
                                              :long? (true? (.-long args))}))))}))
