(ns rragent.i18n
  "The texts of the page in the language of the person. The page is
   hiccup with an m1p reference tuple where a text goes, `[:i18n :chat/you]`
   or `[:i18n :tool/other {:name \"fetch\"}]`, and `translate` folds the
   dictionary of a locale into it before the frame leaves the server. The
   dictionaries are maps here, one per locale, English the reference; a
   bundle has no classpath to read EDN files from. `rragent.i18n-test`
   checks that they have the same keys.

   The locale of a connection comes from its request: `?lang=de` on the
   page (or on the page a command was posted from) beats the browser's
   Accept-Language, English when neither names a language we have. The
   same reading as pchat's."
  (:require [clojure.string :as str]
            [m1p.core :as m1p]))

(def en
  {:page/title "rragent"
   :page/artifacts "Artifacts"
   :page/view-chat "Chat"

   :artifacts/open "Open"
   :artifacts/download "Download"
   :artifacts/video " · video"
   :artifacts/render "Make the video"
   :artifacts/rendering [:fn/str "Making the video {{:progress}}"]
   :artifacts/render-stop "Stop"
   :artifacts/render-failed [:fn/str "The video did not come out: {{:error}}"]
   :artifacts/creation "Your creation"
   :artifacts/empty "What the agent makes for you appears here."
   :artifacts/no-preview "There is no preview for this file. Download it from above."
   :artifacts/starting "Starting this artifact …"
   :artifacts/stopped [:fn/str "This artifact's process stopped (exit {{:exit}}). Its .log says why; it is started again."]

   :chat/you "You"
   :chat/hook [:fn/str "Hook {{:name}}"]
   :chat/schedule [:fn/str "Schedule {{:name}}"]
   :chat/fact [:fn/str "Noted: {{:event}}"]
   :chat/working "Working"
   :chat/ready "Ready"
   :chat/empty "Nothing here yet. Write the first message."
   :chat/queued "Waits for the current run to end"
   :chat/reset "The context started over."
   :chat/compacted "Older messages were summarized."
   :chat/retry [:fn/str "The model call failed, trying again: {{:error}}"]
   :chat/model-failed [:fn/str "The model call failed: {{:error}}"]
   :chat/aborted "Stopped."
   :chat/picture "Attached picture"
   :genui/rejected [:fn/str "This part of the answer could not be shown ({{:error}}):"]

   :tool/bash "Ran a command"
   :tool/read "Looked at a file"
   :tool/write "Wrote a file"
   :tool/edit "Changed a file"
   :tool/eval "Evaluated"
   :tool/fetch "Fetched a page"
   :tool/remember "Noted a fact"
   :tool/recall "Recalled its facts"
   :tool/schedule "Changed a schedule"
   :tool/hook "Changed a hook"
   :tool/render "Made a video"
   :tool/project "Started an artifact"
   :tool/image "Made a picture"
   :tool/voice "Made a voice"
   :tool/music "Made music"
   :tool/subagent "Handed a task to a subagent"
   :tool/bot "Made or listed bots"
   :tool/other [:fn/str "Used {{:name}}"]
   :tool/running " …"
   :tool/failed " · did not work"
   :theme/light "Light theme"
   :theme/dark "Dark theme"

   :composer/placeholder "What should the agent do?"
   :composer/keys "Ctrl+Enter sends (⌘+Enter on a Mac), Enter breaks the line; a picture can be pasted."
   :composer/send "Send"
   :composer/send-pending "Sends after the upload"
   :composer/send-cancel "Click to keep the message instead; a failed upload keeps it too"
   :composer/stop "Stop"
   :composer/attach "Attach an image, a video or an audio file; files can also be pasted or dropped anywhere on the page"
   :composer/remove "Remove"
   :composer/uploading "Uploading"
   :composer/upload-failed "The upload failed"
   :composer/drop-title "Add files"
   :composer/drop-hint "Drop images, videos or audio files to attach them to your message."
   :composer/dictate "Dictate: hold to talk, or click to start and click again to stop; in the text, hold Space"
   :composer/dictate-stop "End the dictation, the text goes into the message"
   :composer/transcribing "Writing down what you said"
   :composer/dictation-failed "The dictation did not come through; click to try again"
   :composer/send-after-dictation "Sends once the text is in"
   :record/button "Record a screencast: the screen and the webcam, both with the microphone"
   :record/title "Record a screencast"
   :record/close "Close"
   :record/opening "Asking for the microphone and the camera …"
   :record/mic "Microphone"
   :record/camera "Camera"
   :record/test "Test, 5 seconds"
   :record/testing "Recording the test …"
   :record/start "Start recording"
   :record/screen-hint "Start opens Chrome's picker: choose the whole screen. The screen and the webcam are recorded as two files, both with the microphone; afterwards you tell the agent how the two go together."
   :record/recording "Recording the screen and the webcam. Chrome's own Stop sharing ends it too."
   :record/stop "Stop"
   :record/saving "Saving the recording …"
   :record/failed "The recording did not work: {error}"
   :record/draft "I recorded a screencast: {folder}/screen.webm is the screen, {folder}/webcam.webm the webcam, both with the microphone. "
   :starting/title "The agent is starting …"
   :starting/hint "This takes a few seconds; the page comes by itself."

   :update/check-title "Look for a newer rragent"
   :update/available [:fn/str "rragent {{:version}} is available."]
   :update/button "Update"
   :update/retry "Try again"
   :update/auto "It installs itself once the agent is idle; the page comes back by itself."
   :update/applying [:fn/str "Updating to rragent {{:version}} … the page comes back by itself."]
   :update/failed [:fn/str "The update to {{:version}} did not work: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} is the newest."]
   :update/lookup-failed "The newest release could not be looked up."

   :bots/title "Bots"
   :bots/menu "The bots"
   :bot/new "New bot"
   :bot/new-at [:fn/str "New bot · {{:time}}"]
   :bot/name "Name"
   :bot/actions "More"
   :bot/rename "Rename"
   :bot/delete "Delete"
   :bot/delete-sure "Delete this bot? Its folder goes to the trash."
   :bot/delete-yes "Delete"
   :bot/keep "Keep"
   :bot/running "running"
   :bot/idle "idle"
   :bot/asleep "asleep"
   :bot/failed "failed"
   :bot/missing "That bot is not here any more; this is another one."
   :bot/not-open [:fn/str "This bot did not open: {{:error}}"]})

(def de
  {:page/title "rragent"
   :page/artifacts "Artefakte"
   :page/view-chat "Chat"

   :artifacts/open "Öffnen"
   :artifacts/download "Herunterladen"
   :artifacts/video " · Video"
   :artifacts/render "Video erstellen"
   :artifacts/rendering [:fn/str "Video wird erstellt {{:progress}}"]
   :artifacts/render-stop "Stopp"
   :artifacts/render-failed [:fn/str "Das Video ist nicht gelungen: {{:error}}"]
   :artifacts/creation "Dein Werk"
   :artifacts/empty "Was der Agent für dich macht, erscheint hier."
   :artifacts/no-preview "Für diese Datei gibt es keine Vorschau. Lade sie oben herunter."
   :artifacts/starting "Dieses Artefakt startet …"
   :artifacts/stopped [:fn/str "Der Prozess dieses Artefakts hat sich beendet (Exit {{:exit}}). Seine .log sagt warum; er wird neu gestartet."]

   :chat/you "Du"
   :chat/hook [:fn/str "Hook {{:name}}"]
   :chat/schedule [:fn/str "Zeitplan {{:name}}"]
   :chat/fact [:fn/str "Gemerkt: {{:event}}"]
   :chat/working "Arbeitet"
   :chat/ready "Bereit"
   :chat/empty "Noch nichts hier. Schreib die erste Nachricht."
   :chat/queued "Wartet, bis der laufende Durchgang endet"
   :chat/reset "Der Kontext hat neu begonnen."
   :chat/compacted "Ältere Nachrichten wurden zusammengefasst."
   :chat/retry [:fn/str "Der Aufruf des Modells ist fehlgeschlagen, nächster Versuch: {{:error}}"]
   :chat/model-failed [:fn/str "Der Aufruf des Modells ist fehlgeschlagen: {{:error}}"]
   :chat/aborted "Gestoppt."
   :chat/picture "Angehängtes Bild"
   :genui/rejected [:fn/str "Dieser Teil der Antwort ließ sich nicht anzeigen ({{:error}}):"]

   :tool/bash "Befehl ausgeführt"
   :tool/read "Datei angesehen"
   :tool/write "Datei geschrieben"
   :tool/edit "Datei geändert"
   :tool/eval "Ausgewertet"
   :tool/fetch "Seite abgerufen"
   :tool/remember "Hat sich etwas gemerkt"
   :tool/recall "Hat nachgesehen"
   :tool/schedule "Zeitplan geändert"
   :tool/hook "Hook geändert"
   :tool/render "Video erstellt"
   :tool/project "Artefakt angelegt"
   :tool/image "Bild erstellt"
   :tool/voice "Stimme erstellt"
   :tool/music "Musik erstellt"
   :tool/subagent "Aufgabe an einen Subagenten gegeben"
   :tool/bot "Bots angelegt oder aufgelistet"
   :tool/other [:fn/str "{{:name}} benutzt"]
   :tool/running " …"
   :tool/failed " · hat nicht geklappt"
   :theme/light "Helles Design"
   :theme/dark "Dunkles Design"

   :composer/placeholder "Was soll der Agent tun?"
   :composer/keys "Strg+Enter sendet (⌘+Enter auf dem Mac), Enter bricht die Zeile um; ein Bild kann eingefügt werden."
   :composer/send "Senden"
   :composer/send-pending "Sendet nach dem Upload"
   :composer/send-cancel "Klicken, um die Nachricht doch nicht zu senden; schlägt ein Upload fehl, bleibt sie auch stehen"
   :composer/stop "Stopp"
   :composer/attach "Ein Bild, ein Video oder eine Tonaufnahme anhängen; Dateien lassen sich auch einfügen oder irgendwo auf der Seite ablegen"
   :composer/remove "Entfernen"
   :composer/uploading "Lädt hoch"
   :composer/upload-failed "Hochladen fehlgeschlagen"
   :composer/drop-title "Dateien anhängen"
   :composer/drop-hint "Leg Bilder, Videos oder Tonaufnahmen hier ab, um sie an deine Nachricht anzuhängen."
   :composer/dictate "Diktieren: gedrückt halten und sprechen, oder klicken und zum Beenden noch einmal klicken; im Textfeld die Leertaste halten"
   :composer/dictate-stop "Diktat beenden, der Text kommt in die Nachricht"
   :composer/transcribing "Das Gesagte wird aufgeschrieben"
   :composer/dictation-failed "Das Diktat ist nicht angekommen; klicken, um es noch einmal zu versuchen"
   :composer/send-after-dictation "Sendet, sobald der Text da ist"
   :record/button "Screencast aufnehmen: den Bildschirm und die Webcam, beide mit dem Mikrofon"
   :record/title "Screencast aufnehmen"
   :record/close "Schließen"
   :record/opening "Frage nach Mikrofon und Kamera …"
   :record/mic "Mikrofon"
   :record/camera "Kamera"
   :record/test "Test, 5 Sekunden"
   :record/testing "Test läuft …"
   :record/start "Aufnahme starten"
   :record/screen-hint "Start öffnet die Auswahl von Chrome: wähle den ganzen Bildschirm. Bildschirm und Webcam werden als zwei Dateien aufgenommen, beide mit dem Mikrofon; danach sagst du dem Agenten, wie die beiden zusammengehören."
   :record/recording "Bildschirm und Webcam werden aufgenommen. Chromes eigenes „Freigabe beenden“ beendet sie auch."
   :record/stop "Stopp"
   :record/saving "Aufnahme wird gespeichert …"
   :record/failed "Die Aufnahme hat nicht geklappt: {error}"
   :record/draft "Ich habe einen Screencast aufgenommen: {folder}/screen.webm ist der Bildschirm, {folder}/webcam.webm die Webcam, beide mit dem Mikrofon. "
   :starting/title "Der Agent startet …"
   :starting/hint "Das dauert ein paar Sekunden; die Seite kommt von selbst."

   :update/check-title "Nach einer neueren rragent-Version sehen"
   :update/available [:fn/str "rragent {{:version}} ist da."]
   :update/button "Aktualisieren"
   :update/retry "Noch einmal versuchen"
   :update/auto "Es installiert sich von selbst, sobald der Agent nichts zu tun hat; die Seite kommt von selbst zurück."
   :update/applying [:fn/str "Aktualisiert auf rragent {{:version}} … die Seite kommt von selbst zurück."]
   :update/failed [:fn/str "Die Aktualisierung auf {{:version}} hat nicht geklappt: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} ist die neueste Version."]
   :update/lookup-failed "Die neueste Version ließ sich nicht nachsehen."

   :bots/title "Bots"
   :bots/menu "Die Bots"
   :bot/new "Neuer Bot"
   :bot/new-at [:fn/str "Neuer Bot · {{:time}}"]
   :bot/name "Name"
   :bot/actions "Mehr"
   :bot/rename "Umbenennen"
   :bot/delete "Löschen"
   :bot/delete-sure "Diesen Bot löschen? Sein Ordner kommt in den Papierkorb."
   :bot/delete-yes "Löschen"
   :bot/keep "Behalten"
   :bot/running "arbeitet"
   :bot/idle "wartet"
   :bot/asleep "schläft"
   :bot/failed "gescheitert"
   :bot/missing "Diesen Bot gibt es nicht mehr; das hier ist ein anderer."
   :bot/not-open [:fn/str "Dieser Bot hat sich nicht öffnen lassen: {{:error}}"]})

(def locales
  [:en :de])

(def default-locale
  :en)

(def dictionaries
  "The prepared dictionary per locale."
  {:en (m1p/prepare-dictionary en)
   :de (m1p/prepare-dictionary de)})

(defn- accepted-languages
  "The primary language subtags of an Accept-Language header, the
   preferred one first."
  [header]
  (->> (str/split (str header) #",")
       (map str/trim)
       (remove str/blank?)
       (map (fn [entry]
              (let [[tag & params] (map str/trim (str/split entry #";"))
                    q (some #(when (str/starts-with? % "q=")
                               (parse-double (subs % 2)))
                            params)]
                [(str/lower-case (first (str/split tag #"-"))) (or q 1.0)])))
       (sort-by second >)
       (map first)))

(defn- query-lang
  [query-string]
  (some-> (re-find #"(?:^|&)lang=([A-Za-z-]+)" (str query-string))
          second
          str/lower-case))

(defn- referer-query
  "The query string of the page a request comes from: a command is
   posted without the page's `?lang=`, the Referer header has it."
  [request]
  (some-> (get-in request [:headers "referer"])
          (str/split #"\?" 2)
          second))

(defn locale
  "The locale of a ring request, see the namespace."
  [request]
  (let [known (set (map name locales))
        wanted (cons (query-lang (or (not-empty (:query-string request))
                                     (referer-query request)))
                     (accepted-languages (get-in request [:headers "accept-language"])))]
    (or (some-> (some known wanted) keyword)
        default-locale)))

(defn- missing-key
  "The English text for a key the dictionary of the locale lacks, the key
   itself when English lacks it too, so that the gap shows on the page."
  [opt params k]
  (m1p/lookup (assoc opt :on-missing-dictionary-key (fn [_ _ k] (str k)))
              (get dictionaries default-locale)
              k
              params))

(defn translate
  "The hiccup with every `[:i18n k params]` replaced by the text of
   `locale`."
  [hiccup locale]
  (m1p/interpolate hiccup {:dictionaries {:i18n (get dictionaries locale (get dictionaries default-locale))}
                           :on-missing-dictionary-key missing-key}))

(comment
  (translate [:button [:i18n :composer/send]] :de)
  (translate [:p [:i18n :tool/other {:name "fetch"}]] :en)
  (locale {:headers {"accept-language" "de-DE,de;q=0.9,en;q=0.8"}})
  )
