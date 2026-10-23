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
   :artifacts/delete-sure [:fn/str "Delete {{:title}}? It goes to the trash."]
   :artifacts/delete-yes "Delete"
   :artifacts/keep "Keep"
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
   :chat/hook-called [:fn/str "Hook {{:name}} was called"]
   :chat/hook-event [:fn/str "{{:event}} · hook {{:name}}"]
   :chat/schedule-ran [:fn/str "Schedule {{:name}} ran"]
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
   :record/button "Record: the screen and the webcam, both with the microphone, a phone, or the microphone alone as a voice memo"
   :record/title "Record"
   :record/close "Close"
   :record/opening "Asking for the microphone and the camera …"
   :record/mic "Microphone"
   :record/camera "Camera"
   :record/test "Test, 5 seconds, every source ticked"
   :record/testing "Recording the test …"
   :record/start "Start recording"
   :record/screen-hint "Choose the screen to see it here; Start records it, or opens Chrome's picker when none is chosen yet. The screen and the webcam are recorded as two files, both with the microphone; afterwards you tell the agent how the two go together."
   :record/recording "Recording the screen and the webcam. Chrome's own Stop sharing ends it too."
   :record/stop "Stop"
   :record/saving "Saving the recording …"
   :record/failed "The recording did not work: {error}"
   :record/draft "New recording in {folder}: {files}; every file carries a microphone. "
   :record/file-screen "the screen"
   :record/file-webcam "the webcam"
   :record/file-phone "the phone's camera"
   :record/file-audio "the microphone"
   :record/draft-audio "New recording in {folder}: {files}, the microphone alone. "
   :record/camera-choice "Record"
   :record/source-screen "Screen"
   :record/source-webcam "Webcam"
   :record/source-phone "Phone camera"
   :record/source-phone-screen "Phone screen (USB)"
   :record/file-phone-screen "the phone's screen"
   :record/usb-connect "Connect the phone (USB)"
   :record/usb-picking "Choose the phone in Chrome's window, then Connect"
   :record/usb-connecting "Allow USB debugging on the phone …"
   :record/usb-none "Connect the phone over USB first: plug it in, USB debugging on, then Connect the phone."
   :record/usb-busy "An adb server on this laptop holds the phone: start the bridge to it (node scripts/adb-bridge.mjs --origin <this page>), then connect again."
   :record/usb-no-phone "The adb server on this laptop knows no phone: plug it in with USB debugging on."
   :record/usb-dark "No picture yet: wake the phone's screen."
   :record/started "started at {time}"
   :record/hide-live "Hide live views"
   :record/show-live "Show live views"
   :record/usb-denied "Chrome may not use this USB device. On Linux it needs a udev rule for the phone."
   :record/usb-unsupported "This browser cannot reach a phone over USB: Chrome on a desktop can."
   :record/source-mic "Microphone alone"
   :record/recording-local "Recording. Stop ends it."
   :record/no-screen-hint "The screen is not recorded: Start records the cameras ticked at once, a phone through its page. A vertical video for the agent to cut."
   :record/none "Tick what to record: the screen, the webcam, a phone, or the microphone alone."
   :record/mic-hint "The microphone alone, a voice memo: Start records it, the machine makes an MP3 of it for the agent to cut, clean and transcribe."
   :record/recording-audio "Recording the microphone. Stop ends it."
   :record/blocked "The camera or the microphone is blocked for this site: allow them in the browser (the icon left of the address), then open the panel again."
   :record/no-devices "No camera or microphone the browser can use: check they are plugged in and allowed for this site, then open the panel again."
   :record/pair "Pair a phone"
   :record/pair-hint "A phone can be a camera too, instead of the webcam or besides it: pair it with a QR code, it records its own file."
   :record/phone-connected "Phone connected: {device}"
   :record/phone-back "back camera"
   :record/phone-front "front camera"
   :record/screen-choose "Choose the screen"
   :record/screen-change "Another screen"
   :record/phone-none "No phone is connected: open the pairing link on the phone first."
   :record/phone-waiting "Waiting for the phone's file to come up … If the phone's page is in the background, bring it to the front."
   :phone/title "Camera for a screencast"
   :phone/connecting "Asking for the camera …"
   :phone/waiting "Ready. The recording starts and stops from the laptop."
   :phone/reconnecting "Ready; reconnecting to the laptop …"
   :phone/recording "Recording. Keep this page open."
   :phone/testing "Test: a few seconds for the laptop's panel."
   :phone/tested "Test clip sent; the laptop shows it. Ready."
   :phone/uploading "Sending the last pieces …"
   :phone/done "Done: the file of {stamp} is up. The laptop goes on from here."
   :phone/failed "That did not work: {error}"
   :phone/flip "Flip the camera"
   :phone/keep-open "Keep this page open and the screen on while it records; the phone records at full quality and sends the file to the machine itself."
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
   :context/title "Context window"
   :context/used "{percent} % used"
   :context/of "{used} of {window} tokens"
   :context/unknown "{used} tokens; the window of this model is not known."
   :context/cost "This conversation so far: {cost}"
   :context/hint "A full context makes the model slow and forgetful. A new bot starts with an empty one; it can read this one's files."
   :context/ring "Context: {percent} % used"
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
   :bot/not-open [:fn/str "This bot did not open: {{:error}}"]})

(def de
  {:page/title "rragent"
   :page/artifacts "Artefakte"
   :page/view-chat "Chat"

   :artifacts/open "Öffnen"
   :artifacts/download "Herunterladen"
   :artifacts/back "Artefakte"
   :artifacts/project "Projekt"
   :artifacts/pick "Wähle links eine Datei."
   :artifacts/truncated "Die Datei ist länger als das hier; oben lässt sie sich ganz herunterladen."
   :artifacts/notes [:fn/str "Notizen · {{:n}}"]
   :artifacts/filter "Liste filtern"
   :artifacts/actions "Mehr"
   :artifacts/rename "Umbenennen"
   :artifacts/name "Name"
   :artifacts/delete "Löschen"
   :artifacts/delete-sure [:fn/str "{{:title}} löschen? Es kommt in den Papierkorb."]
   :artifacts/delete-yes "Löschen"
   :artifacts/keep "Behalten"
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
   :chat/hook-called [:fn/str "Hook {{:name}} wurde aufgerufen"]
   :chat/hook-event [:fn/str "{{:event}} · Hook {{:name}}"]
   :chat/schedule-ran [:fn/str "Zeitplan {{:name}} lief"]
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
   :record/button "Aufnehmen: den Bildschirm und die Webcam, beide mit dem Mikrofon, ein Handy, oder nur das Mikrofon als Sprachmemo"
   :record/title "Aufnehmen"
   :record/close "Schließen"
   :record/opening "Frage nach Mikrofon und Kamera …"
   :record/mic "Mikrofon"
   :record/camera "Kamera"
   :record/test "Test, 5 Sekunden, alles Angekreuzte"
   :record/testing "Test läuft …"
   :record/start "Aufnahme starten"
   :record/screen-hint "Wähle den Bildschirm, um ihn hier zu sehen; Start nimmt ihn auf, oder öffnet die Auswahl von Chrome, wenn noch keiner gewählt ist. Bildschirm und Webcam werden als zwei Dateien aufgenommen, beide mit dem Mikrofon; danach sagst du dem Agenten, wie die beiden zusammengehören."
   :record/recording "Bildschirm und Webcam werden aufgenommen. Chromes eigenes „Freigabe beenden“ beendet sie auch."
   :record/stop "Stopp"
   :record/saving "Aufnahme wird gespeichert …"
   :record/failed "Die Aufnahme hat nicht geklappt: {error}"
   :record/draft "Neue Aufnahme in {folder}: {files}; jede Datei hat ein Mikrofon. "
   :record/file-screen "der Bildschirm"
   :record/file-webcam "die Webcam"
   :record/file-phone "die Kamera des Handys"
   :record/file-audio "das Mikrofon"
   :record/draft-audio "Neue Aufnahme in {folder}: {files}, nur das Mikrofon. "
   :record/camera-choice "Aufnehmen"
   :record/source-screen "Bildschirm"
   :record/source-webcam "Webcam"
   :record/source-phone "Handy-Kamera"
   :record/source-phone-screen "Handy-Bildschirm (USB)"
   :record/file-phone-screen "der Bildschirm des Handys"
   :record/usb-connect "Handy verbinden (USB)"
   :record/usb-picking "Wähle das Handy in Chromes Fenster, dann Verbinden"
   :record/usb-connecting "Erlaube USB-Debugging auf dem Handy …"
   :record/usb-none "Verbinde zuerst das Handy über USB: einstecken, USB-Debugging an, dann Handy verbinden."
   :record/usb-busy "Ein adb-Server auf diesem Laptop hält das Handy: starte die Brücke zu ihm (node scripts/adb-bridge.mjs --origin <diese Seite>), dann verbinde neu."
   :record/usb-no-phone "Der adb-Server auf diesem Laptop kennt kein Handy: stecke es mit USB-Debugging an."
   :record/usb-dark "Noch kein Bild: wecke den Bildschirm des Handys."
   :record/started "gestartet um {time}"
   :record/hide-live "Live-Ansichten ausblenden"
   :record/show-live "Live-Ansichten zeigen"
   :record/usb-denied "Chrome darf dieses USB-Gerät nicht nutzen. Unter Linux braucht es eine udev-Regel für das Handy."
   :record/usb-unsupported "Dieser Browser erreicht kein Handy über USB: Chrome auf einem Desktop kann es."
   :record/source-mic "Nur Mikrofon"
   :record/recording-local "Aufnahme läuft. Stopp beendet sie."
   :record/no-screen-hint "Der Bildschirm wird nicht aufgenommen: Start nimmt die angekreuzten Kameras sofort auf, ein Handy über seine Seite. Ein Hochkant-Video, das der Agent schneidet."
   :record/none "Kreuz an, was aufgenommen wird: Bildschirm, Webcam, Handy, oder nur das Mikrofon."
   :record/mic-hint "Nur das Mikrofon, ein Sprachmemo: Start nimmt es auf, die Maschine macht eine MP3 daraus, die der Agent schneiden, säubern und transkribieren kann."
   :record/recording-audio "Das Mikrofon wird aufgenommen. Stopp beendet es."
   :record/blocked "Kamera oder Mikrofon sind für diese Seite gesperrt: erlaube sie im Browser (das Symbol links neben der Adresse) und öffne das Feld dann noch einmal."
   :record/no-devices "Keine Kamera und kein Mikrofon, die der Browser nutzen kann: prüfe, ob sie angeschlossen und für diese Seite erlaubt sind, und öffne das Feld dann noch einmal."
   :record/pair "Handy koppeln"
   :record/pair-hint "Auch ein Handy kann Kamera sein, statt der Webcam oder zusätzlich: koppel es mit einem QR-Code, es nimmt seine eigene Datei auf."
   :record/phone-connected "Handy verbunden: {device}"
   :record/phone-back "Rückkamera"
   :record/phone-front "Frontkamera"
   :record/screen-choose "Bildschirm wählen"
   :record/screen-change "Anderer Bildschirm"
   :record/phone-none "Kein Handy verbunden: öffne zuerst den Kopplungslink auf dem Handy."
   :record/phone-waiting "Warte, bis die Datei des Handys oben ist … Ist die Seite des Handys im Hintergrund, hol sie nach vorn."
   :phone/title "Kamera für einen Screencast"
   :phone/connecting "Frage nach der Kamera …"
   :phone/waiting "Bereit. Die Aufnahme startet und stoppt vom Laptop aus."
   :phone/reconnecting "Bereit; verbinde neu mit dem Laptop …"
   :phone/recording "Aufnahme läuft. Lass diese Seite offen."
   :phone/testing "Test: ein paar Sekunden für das Feld am Laptop."
   :phone/tested "Testclip geschickt; der Laptop zeigt ihn. Bereit."
   :phone/uploading "Sende die letzten Stücke …"
   :phone/done "Fertig: die Datei von {stamp} ist oben. Der Laptop macht weiter."
   :phone/failed "Das hat nicht geklappt: {error}"
   :phone/flip "Kamera wechseln"
   :phone/keep-open "Lass diese Seite offen und den Bildschirm an, solange aufgenommen wird; das Handy nimmt in voller Qualität auf und schickt die Datei selbst an die Maschine."
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
   :context/title "Kontextfenster"
   :context/used "{percent} % belegt"
   :context/of "{used} von {window} Tokens"
   :context/unknown "{used} Tokens; das Fenster dieses Modells ist nicht bekannt."
   :context/cost "Dieses Gespräch bisher: {cost}"
   :context/hint "Ein voller Kontext macht das Modell langsam und vergesslich. Ein neuer Bot fängt mit leerem Kontext an; er kann die Dateien dieses Bots lesen."
   :context/ring "Kontext: {percent} % belegt"
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
