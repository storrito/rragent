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
   :artifacts/back "Artifacts"
   :artifacts/project "project"
   :artifacts/pick "Pick a file on the left."
   :artifacts/truncated "The file is longer than this; download it from above for all of it."
   :artifacts/notes [:fn/str "Notes · {{:n}}"]
   :artifacts/filter "Filter the list"
   :artifacts/actions "More"
   :artifacts/rename "Rename"
   :artifacts/name "Name"
   :artifacts/delete "Delete"
   :artifacts/delete-sure [:fn/str "Move “{{:title}}” to the trash?"]
   :artifacts/delete-yes "Delete"
   :artifacts/keep "Keep"
   :artifacts/render "Make the video"
   :artifacts/rendering [:fn/str "Making the video {{:progress}}"]
   :artifacts/render-stop "Stop"
   :artifacts/render-failed "The video could not be made:"
   :artifacts/creation "Your creation"
   :artifacts/empty "What the agent makes for you appears here."
   :artifacts/no-preview "There is no preview for this file. Download it from above."
   :artifacts/starting "Starting this artifact…"
   :artifacts/stopped [:fn/str "This artifact's process ended (exit code {{:exit}}). Its .log file says why; it starts again."]

   :render/no-project [:fn/str "{{:name}} is no project: a project is a folder under artifacts/ with a package.json."]
   :render/no-template [:fn/str "{{:name}} was not made from a template, so nothing knows how to render it."]
   :render/template-missing [:fn/str "The template {{:template}} of {{:name}} is not installed."]
   :render/renders-nothing [:fn/str "The template {{:template}} renders nothing."]
   :render/no-script [:fn/str "The template {{:template}} has no render script."]
   :render/not-ready [:fn/str "The template {{:template}} needs {{:programs}} on this machine, and it is not installed: whoever runs the machine installs it."]
   :render/timed-out [:fn/str "The render took longer than {{:minutes}} minutes and was ended."]
   :render/script-failed [:fn/str "The render script ended with an error (exit code {{:exit}}):"]

   :chat/you "You"
   :chat/hook [:fn/str "Hook {{:name}}"]
   :chat/schedule [:fn/str "Schedule {{:name}}"]
   :chat/fact [:fn/str "Noted: {{:event}}"]
   :chat/working "Working"
   :chat/ready "Ready"
   :chat/empty "Nothing here yet. Write the first message."
   :chat/queued "Waits until the agent is done"
   :chat/reset "The context was reset."
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
   :tool/eval "Ran code"
   :tool/fetch "Fetched a page"
   :tool/remember "Noted a fact"
   :tool/recall "Recalled its facts"
   :tool/schedule "Changed a schedule"
   :tool/hook "Changed a hook"
   :tool/render "Made a video"
   :tool/project "Started an artifact"
   :tool/image "Made a picture"
   :tool/voice "Made a voice-over"
   :tool/music "Made music"
   :tool/subagent "Handed a task to a subagent"
   :tool/bot "Made or listed bots"
   :tool/other [:fn/str "Used {{:name}}"]
   :tool/running " …"
   :tool/failed " · did not work"
   :theme/light "Switch to the light theme"
   :theme/dark "Switch to the dark theme"

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
   :record/test "5-second test with every source ticked"
   :record/testing "Recording the test …"
   :record/start "Start recording"
   :record/screen-hint "“Start recording” opens Chrome's picker: choose the whole screen. The screen and the webcam are recorded as two files, both with the microphone; afterwards you tell the agent how the two go together."
   :record/recording "Recording the screen and the webcam. Chrome's own “Stop sharing” ends it too."
   :record/stop "Stop"
   :record/saving "Saving the recording …"
   :record/failed "The recording did not work: {error}"
   :record/draft "New recording in {folder}: {files}; every file has the microphone's audio. "
   :record/file-screen "the screen"
   :record/file-webcam "the webcam"
   :record/file-phone "the phone's camera"
   :record/camera-choice "Record"
   :record/source-screen "Screen"
   :record/source-webcam "Webcam"
   :record/source-phone "Phone"
   :record/recording-local "Recording. Stop ends it."
   :record/no-screen-hint "The screen is not recorded: “Start recording” records the cameras ticked at once, a phone through its page. A vertical video for the agent to cut."
   :record/none "Tick what to record: the screen, the webcam, a phone."
   :record/blocked "The camera or the microphone is blocked for this site: allow them in the browser (the icon left of the address), then open the panel again."
   :record/no-devices "No camera or microphone the browser can use: check they are plugged in and allowed for this site, then open the panel again."
   :record/pair "Pair a phone"
   :record/pair-hint "A phone can be a camera too, instead of the webcam or besides it: pair it with a QR code, it records its own file."
   :record/phone-connected "Phone connected: {device}"
   :record/phone-none "No phone is connected: open the pairing link on the phone first."
   :record/phone-waiting "The phone's file is still uploading…"
   :phone/title "Camera for a screencast"
   :phone/connecting "Asking for the camera …"
   :phone/waiting "Ready. The recording starts and stops from the laptop."
   :phone/reconnecting "Ready; reconnecting to the laptop …"
   :phone/recording "Recording. Keep this page open."
   :phone/testing "Test: a few seconds for the laptop's panel."
   :phone/tested "Test clip sent; the laptop shows it. Ready."
   :phone/uploading "Sending the last pieces …"
   :phone/done "Done: the file of {stamp} is uploaded. Carry on at the laptop."
   :phone/failed "That did not work: {error}"
   :phone/flip "Flip the camera"
   :phone/keep-open "Keep this page open and the screen on while it records; the phone records at full quality and sends the file to the computer itself."
   :starting/title "The agent is starting …"
   :starting/hint "This takes a few seconds; the page reloads by itself."
   :page/js-required "This page needs JavaScript."
   :page/splitter "Drag to make the panes wider or narrower; double-click for half and half"
   :page/version "The version of rragent"
   :auth/title "Access required"
   :auth/hint "Open this page with the link you were given."
   :phone/unknown-code "This pairing code is unknown or too old. Show the QR code on the laptop again."

   :update/check-title "Check for a newer rragent version"
   :update/available [:fn/str "rragent {{:version}} is available."]
   :update/button "Update"
   :update/retry "Try again"
   :update/auto "It installs itself once the agent is idle; the page then reloads by itself."
   :update/applying [:fn/str "Updating to rragent {{:version}}… the page reloads by itself."]
   :update/failed [:fn/str "The update to {{:version}} did not work: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} is the newest."]
   :update/lookup-failed "The newest release could not be checked."

   :bots/title "Bots"
   :bots/menu "Open the list of bots"
   :context/title "Context window"
   :context/used "{percent}% used"
   :context/of "{used} of {window} tokens"
   :context/unknown "{used} tokens; the window of this model is not known."
   :context/cost "This conversation so far: {cost}"
   :context/hint "A full context makes the model slow and forgetful. A new bot starts with an empty one; it can read this one's files."
   :context/ring "Context: {percent}% used"
   :context/close "Close"
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
   :bot/not-open [:fn/str "This bot could not be opened: {{:error}}"]})

(def de
  {:page/title "rragent"
   :page/artifacts "Artefakte"
   :page/view-chat "Chat"

   :artifacts/open "Öffnen"
   :artifacts/download "Herunterladen"
   :artifacts/back "Artefakte"
   :artifacts/project "Projekt"
   :artifacts/pick "Wähle links eine Datei."
   :artifacts/truncated "Hier steht nur der Anfang der Datei; oben kannst du sie ganz herunterladen."
   :artifacts/notes [:fn/str "Notizen · {{:n}}"]
   :artifacts/filter "Liste filtern"
   :artifacts/actions "Mehr"
   :artifacts/rename "Umbenennen"
   :artifacts/name "Name"
   :artifacts/delete "Löschen"
   :artifacts/delete-sure [:fn/str "„{{:title}}“ in den Papierkorb verschieben?"]
   :artifacts/delete-yes "Löschen"
   :artifacts/keep "Behalten"
   :artifacts/render "Video erstellen"
   :artifacts/rendering [:fn/str "Video wird erstellt {{:progress}}"]
   :artifacts/render-stop "Stopp"
   :artifacts/render-failed "Das Video konnte nicht erstellt werden:"
   :artifacts/creation "Dein Werk"
   :artifacts/empty "Was der Agent für dich macht, erscheint hier."
   :artifacts/no-preview "Für diese Datei gibt es keine Vorschau. Lade sie oben herunter."
   :artifacts/starting "Dieses Artefakt startet …"
   :artifacts/stopped [:fn/str "Der Prozess dieses Artefakts wurde beendet (Exit-Code {{:exit}}). Den Grund nennt seine .log-Datei; er startet neu."]

   :render/no-project [:fn/str "{{:name}} ist kein Projekt: Ein Projekt ist ein Ordner unter artifacts/ mit einer package.json."]
   :render/no-template [:fn/str "{{:name}} wurde nicht aus einer Vorlage erstellt, deshalb weiß rragent nicht, wie daraus ein Video wird."]
   :render/template-missing [:fn/str "Die Vorlage {{:template}} von {{:name}} ist nicht installiert."]
   :render/renders-nothing [:fn/str "Die Vorlage {{:template}} erstellt keine Videos."]
   :render/no-script [:fn/str "Die Vorlage {{:template}} hat kein Render-Skript."]
   :render/not-ready [:fn/str "Die Vorlage {{:template}} braucht {{:programs}} auf diesem Rechner, und das ist nicht installiert: Wer den Rechner betreibt, installiert es."]
   :render/timed-out [:fn/str "Das Rendern hat länger als {{:minutes}} Minuten gedauert und wurde abgebrochen."]
   :render/script-failed [:fn/str "Das Render-Skript ist mit einem Fehler abgebrochen (Exit-Code {{:exit}}):"]

   :chat/you "Du"
   :chat/hook [:fn/str "Hook {{:name}}"]
   :chat/schedule [:fn/str "Zeitplan {{:name}}"]
   :chat/fact [:fn/str "Gemerkt: {{:event}}"]
   :chat/working "Arbeitet"
   :chat/ready "Bereit"
   :chat/empty "Noch nichts hier. Schreib die erste Nachricht."
   :chat/queued "Wartet, bis der Agent fertig ist"
   :chat/reset "Der Kontext wurde zurückgesetzt."
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
   :tool/eval "Code ausgeführt"
   :tool/fetch "Seite abgerufen"
   :tool/remember "Fakt gemerkt"
   :tool/recall "Gemerktes abgerufen"
   :tool/schedule "Zeitplan geändert"
   :tool/hook "Hook geändert"
   :tool/render "Video erstellt"
   :tool/project "Artefakt angelegt"
   :tool/image "Bild erstellt"
   :tool/voice "Sprachaufnahme erstellt"
   :tool/music "Musik erstellt"
   :tool/subagent "Aufgabe an einen Subagenten übergeben"
   :tool/bot "Bots angelegt oder aufgelistet"
   :tool/other [:fn/str "{{:name}} benutzt"]
   :tool/running " …"
   :tool/failed " · hat nicht geklappt"
   :theme/light "Zum hellen Design wechseln"
   :theme/dark "Zum dunklen Design wechseln"

   :composer/placeholder "Was soll der Agent tun?"
   :composer/keys "Strg+Enter sendet (⌘+Enter auf dem Mac), Enter bricht die Zeile um; ein Bild kann eingefügt werden."
   :composer/send "Senden"
   :composer/send-pending "Sendet nach dem Hochladen"
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
   :record/opening "Zugriff auf Mikrofon und Kamera wird angefragt …"
   :record/mic "Mikrofon"
   :record/camera "Kamera"
   :record/test "5-Sekunden-Test mit allen gewählten Quellen"
   :record/testing "Test läuft …"
   :record/start "Aufnahme starten"
   :record/screen-hint "„Aufnahme starten“ öffnet die Auswahl von Chrome: wähle den ganzen Bildschirm. Bildschirm und Webcam werden als zwei Dateien aufgenommen, beide mit dem Mikrofon; danach sagst du dem Agenten, wie die beiden zusammengehören."
   :record/recording "Bildschirm und Webcam werden aufgenommen. Chromes eigenes „Freigabe beenden“ beendet sie auch."
   :record/stop "Stopp"
   :record/saving "Aufnahme wird gespeichert …"
   :record/failed "Die Aufnahme hat nicht geklappt: {error}"
   :record/draft "Neue Aufnahme in {folder}: {files}; jede Datei enthält den Mikrofonton. "
   :record/file-screen "der Bildschirm"
   :record/file-webcam "die Webcam"
   :record/file-phone "die Kamera des Handys"
   :record/camera-choice "Aufnehmen"
   :record/source-screen "Bildschirm"
   :record/source-webcam "Webcam"
   :record/source-phone "Handy"
   :record/recording-local "Aufnahme läuft. Stopp beendet sie."
   :record/no-screen-hint "Der Bildschirm wird nicht aufgenommen: „Aufnahme starten“ nimmt die angekreuzten Kameras sofort auf, ein Handy über seine Seite. Ein Hochkant-Video, das der Agent schneidet."
   :record/none "Kreuze an, was aufgenommen werden soll: Bildschirm, Webcam, Handy."
   :record/blocked "Kamera oder Mikrofon sind für diese Seite gesperrt: erlaube sie im Browser (das Symbol links neben der Adresse) und öffne das Aufnahmefenster dann noch einmal."
   :record/no-devices "Keine Kamera und kein Mikrofon, die der Browser nutzen kann: prüfe, ob sie angeschlossen und für diese Seite erlaubt sind, und öffne das Aufnahmefenster dann noch einmal."
   :record/pair "Handy koppeln"
   :record/pair-hint "Auch ein Handy kann Kamera sein, statt der Webcam oder zusätzlich: kopple es mit einem QR-Code, es nimmt seine eigene Datei auf."
   :record/phone-connected "Handy verbunden: {device}"
   :record/phone-none "Kein Handy verbunden: öffne zuerst den Kopplungslink auf dem Handy."
   :record/phone-waiting "Die Datei vom Handy wird noch hochgeladen …"
   :phone/title "Kamera für einen Screencast"
   :phone/connecting "Zugriff auf die Kamera wird angefragt …"
   :phone/waiting "Bereit. Die Aufnahme startet und stoppt vom Laptop aus."
   :phone/reconnecting "Bereit; Verbindung zum Laptop wird wiederhergestellt …"
   :phone/recording "Aufnahme läuft. Lass diese Seite offen."
   :phone/testing "Test: ein paar Sekunden für das Aufnahmefenster am Laptop."
   :phone/tested "Testclip geschickt; der Laptop zeigt ihn. Bereit."
   :phone/uploading "Die letzten Teile werden gesendet …"
   :phone/done "Fertig: Die Datei von {stamp} ist hochgeladen. Weiter geht es am Laptop."
   :phone/failed "Das hat nicht geklappt: {error}"
   :phone/flip "Kamera wechseln"
   :phone/keep-open "Lass diese Seite offen und den Bildschirm an, solange aufgenommen wird; das Handy nimmt in voller Qualität auf und schickt die Datei selbst an den Rechner."
   :starting/title "Der Agent startet …"
   :starting/hint "Das dauert ein paar Sekunden; die Seite lädt von selbst neu."
   :page/js-required "Diese Seite braucht JavaScript."
   :page/splitter "Ziehen, um die Bereiche breiter oder schmaler zu machen; Doppelklick teilt sie in der Mitte"
   :page/version "Die Version von rragent"
   :auth/title "Zugang nötig"
   :auth/hint "Öffne diese Seite mit dem Link, den du bekommen hast."
   :phone/unknown-code "Dieser Kopplungscode ist unbekannt oder zu alt. Zeig den QR-Code am Laptop noch einmal an."

   :update/check-title "Nach einer neuen rragent-Version suchen"
   :update/available [:fn/str "rragent {{:version}} ist da."]
   :update/button "Aktualisieren"
   :update/retry "Noch einmal versuchen"
   :update/auto "Das Update installiert sich von selbst, sobald der Agent nichts zu tun hat; die Seite lädt dann von selbst neu."
   :update/applying [:fn/str "Aktualisiert auf rragent {{:version}} … die Seite lädt von selbst neu."]
   :update/failed [:fn/str "Die Aktualisierung auf {{:version}} hat nicht geklappt: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} ist die neueste Version."]
   :update/lookup-failed "Die neueste Version konnte nicht abgerufen werden."

   :bots/title "Bots"
   :bots/menu "Bot-Liste öffnen"
   :context/title "Kontextfenster"
   :context/used "{percent} % belegt"
   :context/of "{used} von {window} Tokens"
   :context/unknown "{used} Tokens; das Fenster dieses Modells ist nicht bekannt."
   :context/cost "Dieses Gespräch bisher: {cost}"
   :context/hint "Ein voller Kontext macht das Modell langsam und vergesslich. Ein neuer Bot fängt mit leerem Kontext an; er kann die Dateien dieses Bots lesen."
   :context/ring "Kontext: {percent} % belegt"
   :context/close "Schließen"
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
   :bot/failed "Fehler"
   :bot/missing "Diesen Bot gibt es nicht mehr; das hier ist ein anderer."
   :bot/not-open [:fn/str "Dieser Bot konnte nicht geöffnet werden: {{:error}}"]})

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
