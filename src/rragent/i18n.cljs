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

   :activity/label "Activity"
   :activity/empty "No runs yet. A hook call or a schedule starts one; it shows here."
   :run/waiting "waiting"
   :run/running "running"
   :run/done "done"
   :run/failed "failed"
   :run/stopped "stopped"
   :run/started-by [:fn/str "Started by {{:about}}"]
   :run/empty "The run has not started yet; it waits for its turn."
   :run/definition "The definition this run works from"
   :run/definition-changed "the bot's has changed since"

   :artifacts/open "Open"
   :artifacts/download "Download"
   :artifacts/back "Artifacts"
   :artifacts/project "project"
   :artifacts/project-files "Files"
   :artifacts/pick "Pick a file on the left."
   :artifacts/truncated "The file is longer than this; download it from the menu of its row for all of it."
   :artifacts/edit "Edit"
   :artifacts/preview "Preview"
   :editor/unsaved "Unsaved changes"
   :editor/save "Save"
   :editor/saving "Saving …"
   :editor/failed "The file could not be saved."
   :editor/disk "The file changed on disk meanwhile."
   :editor/reload "Take that version"
   :csv/rows [:fn/str "{{:rows}} rows · {{:columns}} columns"]
   :csv/some [:fn/str "The first {{:shown}} of {{:rows}} rows · {{:columns}} columns"]
   :csv/errors [:fn/str "{{:n}} lines could not be read as CSV; the table may be off there."]
   :artifacts/files [:fn/str "{{:n}} files"]
   :artifacts/one-file "1 file"
   :artifacts/filter "Filter the list"
   :artifacts/actions "More"
   :artifacts/rename "Rename"
   :artifacts/name "Name"
   :artifacts/delete "Delete"
   :artifacts/delete-sure [:fn/str "Delete {{:title}}? It goes to the trash."]
   :artifacts/delete-yes "Delete"
   :artifacts/keep "Keep"
   :artifacts/render "Make the video"
   :artifacts/render-open "Open the video"
   :artifacts/rendering [:fn/str "Making the video {{:progress}}"]
   :artifacts/render-waiting "Waiting for another video to be made first"
   :artifacts/render-stop "Stop"
   :artifacts/render-failed [:fn/str "The video did not come out: {{:error}}"]
   :artifacts/creation "Your creation"
   :artifacts/empty "What the agent makes for you appears here."
   :artifacts/no-preview "There is no preview for this file. Download it from the menu of its row."
   :artifacts/starting "Starting this artifact …"
   :artifacts/stopped [:fn/str "This artifact's process stopped (exit {{:exit}}). Its .log says why; it is started again."]

   :chat/you "You"
   :chat/from-bot [:fn/str "From the bot {{:name}}"]
   :chat/waiting "Waiting for your answer:"
   :person/title "Your first name, please"
   :person/why "Several people can talk to this bot. When it knows who is talking right now, it answers better. Only your first name; it stays in this browser."
   :person/field "First name"
   :person/ok "That's me"
   :person/you [:fn/str "You: {{:name}}"]
   :person/change "change"
   :chat/hook-called [:fn/str "Hook {{:name}} was called"]
   :chat/hook-event [:fn/str "{{:event}} · hook {{:name}}"]
   :chat/music-picked [:fn/str "Song picked: {{:song}}"]
   :chat/schedule-ran [:fn/str "Schedule {{:name}} ran"]
   :chat/run-failed [:fn/str "A run failed · {{:about}}"]
   :chat/run-report "A run reported"
   :chat/run-started [:fn/str "Started by {{:about}}"]
   :chat/task-done [:fn/str "A task is done · {{:line}}"]
   :chat/task-started "Started as a task"
   :chat/compaction-notice "The wiki is written before the memory shrinks"
   :chat/cap-notice "The budget nears its cap"
   :chat/fact [:fn/str "Noted: {{:event}}"]
   :chat/working "Working"
   :chat/ready "Ready"
   :chat/empty "Nothing here yet. Write the first message."
   :chat/queued "Waits for the current run to end"
   :chat/steers "Reaches the bot after its current step"
   :chat/on-screen [:fn/str "Open: {{:path}}"]
   :chat/reset "The context started over."
   :chat/compacted "From here on the model reads a summary of the messages above."
   :chat/summary-kept "What the model keeps of them"
   :chat/retry [:fn/str "The model call failed, trying again: {{:error}}"]
   :chat/model-failed [:fn/str "The model call failed: {{:error}}"]
   :chat/aborted "Stopped."
   :chat/picture "Attached picture"
   :genui/rejected [:fn/str "This part of the answer could not be shown ({{:error}}):"]

   :tool/bash "Ran a command"
   :tool/read "Looked at a file"
   :tool/write "Wrote a file"
   :tool/edit "Changed a file"
   :tool/machine "Ran a machine"
   :tool/fetch "Fetched a page"
   :tool/remember "Noted an event"
   :tool/recall "Recalled its events"
   :tool/schedule "Changed a schedule"
   :tool/hook "Changed a hook"
   :tool/render "Made a video"
   :tool/project "Started an artifact"
   :tool/image "Made a picture"
   :tool/voice "Made a voice"
   :tool/music "Made music"
   :tool/music-pick "Asked for a song to pick"
   :tool/subagent "Handed a task to a subagent"
   :tool/report "Reported to the bot's chat"
   :tool/bot "Made or listed bots"
   :tool/other [:fn/str "Used {{:name}}"]
   :tool/running " …"
   :tool/failed " · did not work"
   :theme/light "Light theme"
   :theme/dark "Dark theme"

   :composer/placeholder "What should the agent do?"
   :composer/keys "Ctrl+Enter sends (⌘+Enter on a Mac), Enter breaks the line; a picture can be pasted."
   :composer/keys-enter "Enter sends, Ctrl+Enter (⌘+Enter on a Mac) or Shift+Enter breaks the line; a picture can be pasted."
   :composer/send "Send"
   :composer/send-pending "Sends after the upload"
   :composer/send-cancel "Click to keep the message instead; a failed upload keeps it too"
   :composer/stop "Stop"
   :composer/attach "Attach a file: a picture, a video, an audio clip, a document (PDF, Word, Excel), a text or data file (Markdown, CSV, JSON, code) or an archive; files can also be pasted or dropped anywhere on the page"
   :composer/remove "Remove"
   :composer/uploading "Uploading"
   :composer/upload-failed "The upload failed"
   :composer/drop-title "Add files"
   :composer/drop-hint "Drop files to attach them to your message: pictures, videos, audio, documents, text or data files."

   :switch/title "The model and the thinking level the bot works with"
   :switch/model "Model"
   :switch/effort "Effort"
   :switch/effort-hint "More effort means more thorough answers, but each takes longer and costs more."
   :model/price [:fn/str "${{:in}} in · ${{:out}} out per million tokens"]
   :model/times [:fn/str "{{:times}}× the price of {{:model}}"]
   :level/low "Low"
   :level/medium "Medium"
   :level/high "High"
   :level/xhigh "Extra"
   :level/max "Max"
   :level/max-hint "several times the cost"
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
   :record/pause "Pause"
   :record/resume "Resume"
   :record/paused "Paused: nothing is recorded until you resume, on the phone neither."
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
   :record/usb-choose "Which phone's screen?"
   :record/usb-network "network"
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
   :record/background "Background"
   :record/background-off "As it is"
   :record/background-blur "Blurred"
   :record/background-green "Removed: green, to key out"
   :record/background-black "Removed: black"
   :record/auto-light "Light and colour automatic"
   :record/touch-up "Touch-up"
   :record/touch-up-off "Off"
   :record/touch-up-subtle "Subtle"
   :record/touch-up-smooth "Smooth"
   :record/effects-loading "Loading the model that finds you in the picture …"
   :record/effects-failed "The effect did not load: {error}. The webcam is recorded as it is."
   :record/effects-applying "Putting the effects on the webcam … {percent} %. Keep this page open."
   :record/effects-apply-failed "The webcam's effects could not be applied ({error}): the webcam was not saved. "
   :record/effects-slow "The effects make only {fps} frames a second on this computer: the webcam will stutter. Settings → Recording can put them on after the recording instead."
   :record/green-hint "Only you stay in the picture, on plain green: the agent keys the green out when it lays the webcam over the screen."
   :record/file-webcam-green "the webcam, the person cut out on plain green #00ff00 for the agent to key out"
   :record/file-webcam-black "the webcam, the person cut out on black"
   :record/no-mic "No microphone: what is recorded here has no sound."
   :record/no-camera "No camera"
   :record/draft-no-mic "New recording in {folder}: {files}; recorded without a microphone, the files have no sound. "

   :phone/title "Camera for a screencast"
   :phone/connecting "Asking for the camera …"
   :phone/waiting "Ready. The recording starts and stops from the laptop."
   :phone/reconnecting "Ready; reconnecting to the laptop …"
   :phone/recording "Recording. Keep this page open."
   :phone/paused "Paused on the laptop. Keep this page open."
   :phone/testing "Test: a few seconds for the laptop's panel."
   :phone/tested "Test clip sent; the laptop shows it. Ready."
   :phone/uploading "Sending the last pieces …"
   :phone/done "Done: the file of {stamp} is up. The laptop goes on from here."
   :phone/failed "That did not work: {error}"
   :phone/flip "Flip the camera"
   :phone/keep-open "Keep this page open and the screen on while it records; the phone records at full quality and sends the file to the machine itself."
   :starting/title "The agent is starting …"
   :starting/hint "This takes a few seconds; the page comes by itself."

   :settings/button "Settings"
   :settings/title "Settings"
   :settings/close "Back to the chat"
   :settings/version-title "Look for a newer rragent in the settings"
   :updates/title "Updates"
   :updates/current [:fn/str "You run rragent {{:version}}."]
   :updates/pinned [:fn/str "This machine is pinned to the release {{:pin}} (RRAGENT_UPDATE in .env); no other release is taken."]
   :updates/explain "A newer release is looked up at start and once an hour; this looks right now."
   :updates/check "Check for updates"
   :updates/checking "Checking …"
   :update/ready "A new version of rragent is ready."
   :update/available [:fn/str "rragent {{:version}} is available."]
   :update/button "Update"
   :update/retry "Try again"
   :update/auto "It installs itself once the agent is idle; the page comes back by itself."
   :update/applying [:fn/str "Updating to rragent {{:version}} … the page comes back by itself."]
   :update/failed [:fn/str "The update to {{:version}} did not work: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} is the newest."]
   :update/lookup-failed "The newest release could not be looked up."
   :settings/tabs "Sections of the settings"
   :settings/updates "Updates"
   :budget/reached "The month's budget is spent: nothing starts until the cap is raised in Settings → Budget."
   :settings/mcp "MCP servers"
   :settings/input "Input"
   :input/title "Sending a message"
   :input/explain "Which key sends a message; the other one breaks the line. The choice holds for this browser; on a phone without a keyboard Enter always breaks the line."
   :input/ctrl-enter "Ctrl+Enter sends (⌘+Enter on a Mac), Enter breaks the line"
   :input/enter "Enter sends, Ctrl+Enter (⌘+Enter on a Mac) breaks the line"
   :settings/recording "Recording"
   :recording/title "The webcam's effects"
   :recording/explain "When a blurred background, the touch-up and the light are put on the webcam. The choice holds for this browser; the raw picture never leaves the laptop either way."
   :recording/live "Live: recorded as the preview shows it; on a busy computer the webcam can stutter"
   :recording/after "After the recording, in this browser: never stutters; when the recording ends the page stays busy for a while"
   :settings/mobile "On the phone"
   :mobile/title "The page on your phone"
   :mobile/explain "Scan this with the phone's camera and the page opens there, signed in. The code is the key to this page: show it to no one else."
   :mobile/add "Then add the page to the home screen and it opens like an app, the burrito its icon: in Safari on an iPhone, Share and Add to Home Screen; in Chrome on Android, the menu and Add to Home screen."
   :mobile/install "Install the app"
   :mobile/install-explain "This browser installs the page as an app of its own: a window without the browser's bars, an icon among the apps."
   :mobile/installed "This is the installed app."
   :settings/advanced "Advanced"
   :advanced/title "Advanced"
   :advanced/explain "The sidebar shows the views of this one conversation: all of it and the topics, marked while one needs you. This adds the raw list of the conversations of before and the tasks' transcripts in the activity, with New bot and the menus to rename and delete one. It also shows how full the model's context is, with what the conversation cost and the button that summarizes older messages, and the model and its effort under the message field. The choice holds for this machine; every tab sees it."
   :advanced/show-all "Show every bot, the raw list and the technical details"
   :mcp/title "MCP servers"
   :mcp/explain "Services your bots use over MCP. Every bot of this machine has their tools."
   :mcp/none "No MCP server is connected yet."
   :mcp/setup "Connect a service …"
   :mcp/setup-explain "Opens a bot that finds out how the service connects and asks you for what it needs."
   :mcp/connected [:fn/str "Connected, {{:count}} tools"]
   :mcp/connecting "Connecting …"
   :mcp/off "Off"
   :mcp/needs [:fn/str "Waiting for {{:names}}"]
   :mcp/failed [:fn/str "Failed: {{:error}}"]
   :mcp/idle "Not connected yet"
   :mcp/test "Test connection"
   :mcp/needs-sign-in "Waiting for a sign-in"
   :mcp/sign-in "Sign in …"
   :mcp/sign-in-to [:fn/str "Sign in to {{:name}} …"]
   :mcp/sign-out "Sign out"
   :mcp/testing "Testing …"
   :mcp/test-ok [:fn/str "Test passed: reconnected, {{:count}} tools, answered in {{:ms}} ms."]
   :mcp/test-failed [:fn/str "Test failed after {{:ms}} ms: {{:error}}"]
   :mcp/turn-off "Turn off"
   :mcp/turn-on "Turn on"
   :mcp/remove "Remove"
   :mcp/remove-confirm [:fn/str "Remove {{:name}}? Your bots lose its tools."]
   :mcp/keep "Keep it"
   :mcp/secrets "Saved secrets"
   :mcp/secrets-explain "Tokens the servers use, saved on this machine only. The bots never see them."
   :mcp/secret-remove "Remove"
   :secret/save "Save"
   :secret/placeholder "Paste it here"
   :secret/note "Saved on this machine only; the bot never sees it."

   :bots/title "Bots"
   :bots/menu "The bots"
   :bots/manager "Manager"
   :bots/needs-you "Needs you"
   :bots/projects "Projects"
   :bots/other "Other"
   :bots/all "All bots"
   :views/all "All"
   :views/topics "Topics"
   :views/archived "Archived"
   :views/in-topic [:fn/str "in {{:topic}}"]
   :views/empty "Nothing here yet."
   :views/new "+ New topic"
   :views/new-placeholder "What is the new topic about?"
   :views/new-empty "A new topic starts with your first message here: say what it is about."
   :views/in-new "in a new topic"
   :context/title "Context"
   :context/model "Model: {model}"
   :context/used "{percent} % used"
   :context/of "{used} of {window} tokens; past that, older messages are summarized."
   :context/unknown "{used} tokens; the window of this model is not known."
   :context/cost "This bot so far, its subagents included: {cost}"
   :context/hint "A full context makes the model slow and forgetful. A new bot starts with an empty one; it can read this one's files."
   :context/ring "Context: {percent} % used"
   :context/close "Close"
   :context/compact "Summarize older messages now"
   :context/compacting "Summarizing older messages …"
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
   :bot/archived "archived"
   :bot/missing "That bot is not here any more; this is another one."
   :bot/not-open [:fn/str "This bot did not open: {{:error}}"]})

(def de
  {:page/title "rragent"
   :page/artifacts "Artefakte"

   :activity/label "Aktivität"
   :activity/empty "Noch keine Durchläufe. Ein Hook-Aufruf oder ein Zeitplan startet einen; er erscheint hier."
   :run/waiting "wartet"
   :run/running "läuft"
   :run/done "fertig"
   :run/failed "fehlgeschlagen"
   :run/stopped "gestoppt"
   :run/started-by [:fn/str "Gestartet durch {{:about}}"]
   :run/empty "Der Durchlauf hat noch nicht begonnen; er wartet, bis er dran ist."
   :run/definition "Die Definition, mit der dieser Durchlauf arbeitet"
   :run/definition-changed "die des Bots hat sich seitdem geändert"
   :page/view-chat "Chat"

   :artifacts/open "Öffnen"
   :artifacts/download "Herunterladen"
   :artifacts/back "Artefakte"
   :artifacts/project "Projekt"
   :artifacts/project-files "Dateien"
   :artifacts/pick "Wähle links eine Datei."
   :artifacts/truncated "Die Datei ist länger als das hier; über das Menü ihrer Zeile lässt sie sich ganz herunterladen."
   :artifacts/edit "Bearbeiten"
   :artifacts/preview "Vorschau"
   :editor/unsaved "Ungespeicherte Änderungen"
   :editor/save "Speichern"
   :editor/saving "Speichern …"
   :editor/failed "Die Datei ließ sich nicht speichern."
   :editor/disk "Die Datei hat sich inzwischen auf der Platte geändert."
   :editor/reload "Diese Version nehmen"
   :csv/rows [:fn/str "{{:rows}} Zeilen · {{:columns}} Spalten"]
   :csv/some [:fn/str "Die ersten {{:shown}} von {{:rows}} Zeilen · {{:columns}} Spalten"]
   :csv/errors [:fn/str "{{:n}} Zeilen ließen sich nicht als CSV lesen; die Tabelle kann dort abweichen."]
   :artifacts/files [:fn/str "{{:n}} Dateien"]
   :artifacts/one-file "1 Datei"
   :artifacts/filter "Liste filtern"
   :artifacts/actions "Mehr"
   :artifacts/rename "Umbenennen"
   :artifacts/name "Name"
   :artifacts/delete "Löschen"
   :artifacts/delete-sure [:fn/str "{{:title}} löschen? Es kommt in den Papierkorb."]
   :artifacts/delete-yes "Löschen"
   :artifacts/keep "Behalten"
   :artifacts/render "Video erstellen"
   :artifacts/render-open "Video öffnen"
   :artifacts/rendering [:fn/str "Video wird erstellt {{:progress}}"]
   :artifacts/render-waiting "Wartet, bis ein anderes Video fertig ist"
   :artifacts/render-stop "Stopp"
   :artifacts/render-failed [:fn/str "Das Video ist nicht gelungen: {{:error}}"]
   :artifacts/creation "Dein Werk"
   :artifacts/empty "Was der Agent für dich macht, erscheint hier."
   :artifacts/no-preview "Für diese Datei gibt es keine Vorschau. Lade sie über das Menü ihrer Zeile herunter."
   :artifacts/starting "Dieses Artefakt startet …"
   :artifacts/stopped [:fn/str "Der Prozess dieses Artefakts hat sich beendet (Exit {{:exit}}). Seine .log sagt warum; er wird neu gestartet."]

   :chat/you "Du"
   :chat/from-bot [:fn/str "Vom Bot {{:name}}"]
   :chat/waiting "Wartet auf deine Antwort:"
   :person/title "Dein Vorname, bitte"
   :person/why "Mit diesem Bot können mehrere Personen sprechen. Wenn er weiß, wer gerade mit ihm spricht, antwortet er besser. Nur der Vorname; er bleibt in diesem Browser."
   :person/field "Vorname"
   :person/ok "Das bin ich"
   :person/you [:fn/str "Du: {{:name}}"]
   :person/change "ändern"
   :chat/hook-called [:fn/str "Hook {{:name}} wurde aufgerufen"]
   :chat/hook-event [:fn/str "{{:event}} · Hook {{:name}}"]
   :chat/music-picked [:fn/str "Song gewählt: {{:song}}"]
   :chat/schedule-ran [:fn/str "Zeitplan {{:name}} lief"]
   :chat/run-failed [:fn/str "Ein Durchlauf ist fehlgeschlagen · {{:about}}"]
   :chat/run-report "Ein Durchlauf hat gemeldet"
   :chat/run-started [:fn/str "Gestartet durch {{:about}}"]
   :chat/task-done [:fn/str "Eine Aufgabe ist fertig · {{:line}}"]
   :chat/task-started "Als Aufgabe gestartet"
   :chat/compaction-notice "Das Wiki wird geschrieben, bevor das Gedächtnis schrumpft"
   :chat/cap-notice "Das Budget nähert sich der Grenze"
   :chat/fact [:fn/str "Gemerkt: {{:event}}"]
   :chat/working "Arbeitet"
   :chat/ready "Bereit"
   :chat/empty "Noch nichts hier. Schreib die erste Nachricht."
   :chat/queued "Wartet, bis der laufende Durchgang endet"
   :chat/steers "Erreicht den Bot nach seinem aktuellen Schritt"
   :chat/on-screen [:fn/str "Geöffnet: {{:path}}"]
   :chat/reset "Der Kontext hat neu begonnen."
   :chat/compacted "Ab hier liest das Modell eine Zusammenfassung der Nachrichten oben."
   :chat/summary-kept "Was das Modell davon behält"
   :chat/retry [:fn/str "Der Aufruf des Modells ist fehlgeschlagen, nächster Versuch: {{:error}}"]
   :chat/model-failed [:fn/str "Der Aufruf des Modells ist fehlgeschlagen: {{:error}}"]
   :chat/aborted "Gestoppt."
   :chat/picture "Angehängtes Bild"
   :genui/rejected [:fn/str "Dieser Teil der Antwort ließ sich nicht anzeigen ({{:error}}):"]

   :tool/bash "Befehl ausgeführt"
   :tool/read "Datei angesehen"
   :tool/write "Datei geschrieben"
   :tool/edit "Datei geändert"
   :tool/machine "Maschine ausgeführt"
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
   :tool/music-pick "Song zur Auswahl angefragt"
   :tool/subagent "Aufgabe an einen Subagenten gegeben"
   :tool/report "Dem Bot gemeldet"
   :tool/bot "Bots angelegt oder aufgelistet"
   :tool/other [:fn/str "{{:name}} benutzt"]
   :tool/running " …"
   :tool/failed " · hat nicht geklappt"
   :theme/light "Helles Design"
   :theme/dark "Dunkles Design"

   :composer/placeholder "Was soll der Agent tun?"
   :composer/keys "Strg+Enter sendet (⌘+Enter auf dem Mac), Enter bricht die Zeile um; ein Bild kann eingefügt werden."
   :composer/keys-enter "Enter sendet, Strg+Enter (⌘+Enter auf dem Mac) oder Umschalt+Enter bricht die Zeile um; ein Bild kann eingefügt werden."
   :composer/send "Senden"
   :composer/send-pending "Sendet nach dem Upload"
   :composer/send-cancel "Klicken, um die Nachricht doch nicht zu senden; schlägt ein Upload fehl, bleibt sie auch stehen"
   :composer/stop "Stopp"
   :composer/attach "Eine Datei anhängen: ein Bild, ein Video, eine Tonaufnahme, ein Dokument (PDF, Word, Excel), eine Text- oder Datendatei (Markdown, CSV, JSON, Code) oder ein Archiv; Dateien lassen sich auch einfügen oder irgendwo auf der Seite ablegen"
   :composer/remove "Entfernen"
   :composer/uploading "Lädt hoch"
   :composer/upload-failed "Hochladen fehlgeschlagen"
   :composer/drop-title "Dateien anhängen"
   :composer/drop-hint "Dateien hier ablegen, um sie der Nachricht anzuhängen: Bilder, Videos, Tonaufnahmen, Dokumente, Text- oder Datendateien."

   :switch/title "Das Modell und die Denkstufe, mit denen der Bot arbeitet"
   :switch/model "Modell"
   :switch/effort "Aufwand"
   :switch/effort-hint "Mehr Aufwand heißt gründlichere Antworten, aber jede dauert länger und kostet mehr."
   :model/price [:fn/str "${{:in}} Eingabe · ${{:out}} Ausgabe pro Million Tokens"]
   :model/times [:fn/str "{{:times}}× der Preis von {{:model}}"]
   :level/low "Niedrig"
   :level/medium "Mittel"
   :level/high "Hoch"
   :level/xhigh "Extra"
   :level/max "Max"
   :level/max-hint "ein Vielfaches der Kosten"
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
   :record/pause "Pause"
   :record/resume "Fortsetzen"
   :record/paused "Pausiert: bis zum Fortsetzen wird nichts aufgenommen, auch auf dem Handy nicht."
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
   :record/usb-choose "Welches Handy?"
   :record/usb-network "WLAN"
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
   :record/background "Hintergrund"
   :record/background-off "Wie er ist"
   :record/background-blur "Unscharf"
   :record/background-green "Entfernt: grün, zum Freistellen"
   :record/background-black "Entfernt: schwarz"
   :record/auto-light "Licht und Farbe automatisch"
   :record/touch-up "Retusche"
   :record/touch-up-off "Aus"
   :record/touch-up-subtle "Dezent"
   :record/touch-up-smooth "Glatt"
   :record/effects-loading "Lade das Modell, das dich im Bild findet …"
   :record/effects-failed "Der Effekt hat nicht geladen: {error}. Die Webcam wird aufgenommen, wie sie ist."
   :record/effects-applying "Die Effekte kommen auf die Webcam … {percent} %. Lass diese Seite offen."
   :record/effects-apply-failed "Die Effekte der Webcam ließen sich nicht anwenden ({error}): die Webcam wurde nicht gespeichert. "
   :record/effects-slow "Die Effekte schaffen auf diesem Rechner nur {fps} Bilder pro Sekunde: die Webcam wird ruckeln. Unter Settings → Aufnahme lassen sie sich stattdessen nach der Aufnahme anwenden."
   :record/green-hint "Nur du bleibst im Bild, auf reinem Grün: der Agent keyt das Grün heraus, wenn er die Webcam über den Bildschirm legt."
   :record/file-webcam-green "die Webcam, die Person freigestellt auf reinem Grün #00ff00, das der Agent herauskeyt"
   :record/file-webcam-black "die Webcam, die Person freigestellt auf Schwarz"
   :record/no-mic "Kein Mikrofon: was hier aufgenommen wird, hat keinen Ton."
   :record/no-camera "Keine Kamera"
   :record/draft-no-mic "Neue Aufnahme in {folder}: {files}; ohne Mikrofon aufgenommen, die Dateien haben keinen Ton. "


   :phone/title "Kamera für einen Screencast"
   :phone/connecting "Frage nach der Kamera …"
   :phone/waiting "Bereit. Die Aufnahme startet und stoppt vom Laptop aus."
   :phone/reconnecting "Bereit; verbinde neu mit dem Laptop …"
   :phone/recording "Aufnahme läuft. Lass diese Seite offen."
   :phone/paused "Am Laptop pausiert. Lass diese Seite offen."
   :phone/testing "Test: ein paar Sekunden für das Feld am Laptop."
   :phone/tested "Testclip geschickt; der Laptop zeigt ihn. Bereit."
   :phone/uploading "Sende die letzten Stücke …"
   :phone/done "Fertig: die Datei von {stamp} ist oben. Der Laptop macht weiter."
   :phone/failed "Das hat nicht geklappt: {error}"
   :phone/flip "Kamera wechseln"
   :phone/keep-open "Lass diese Seite offen und den Bildschirm an, solange aufgenommen wird; das Handy nimmt in voller Qualität auf und schickt die Datei selbst an die Maschine."
   :starting/title "Der Agent startet …"
   :starting/hint "Das dauert ein paar Sekunden; die Seite kommt von selbst."

   :settings/button "Einstellungen"
   :settings/title "Einstellungen"
   :settings/close "Zurück zum Chat"
   :settings/version-title "In den Einstellungen nach einer neueren rragent-Version sehen"
   :updates/title "Updates"
   :updates/current [:fn/str "Du nutzt rragent {{:version}}."]
   :updates/pinned [:fn/str "Diese Maschine ist auf die Version {{:pin}} festgelegt (RRAGENT_UPDATE in .env); keine andere wird genommen."]
   :updates/explain "Beim Start und einmal pro Stunde wird nach einer neueren Version gesehen; das hier sieht sofort nach."
   :updates/check "Nach Updates suchen"
   :updates/checking "Suche …"
   :update/ready "Eine neue Version von rragent ist da."
   :update/available [:fn/str "rragent {{:version}} ist da."]
   :update/button "Aktualisieren"
   :update/retry "Noch einmal versuchen"
   :update/auto "Es installiert sich von selbst, sobald der Agent nichts zu tun hat; die Seite kommt von selbst zurück."
   :update/applying [:fn/str "Aktualisiert auf rragent {{:version}} … die Seite kommt von selbst zurück."]
   :update/failed [:fn/str "Die Aktualisierung auf {{:version}} hat nicht geklappt: {{:error}}"]
   :update/newest [:fn/str "rragent {{:version}} ist die neueste Version."]
   :update/lookup-failed "Die neueste Version ließ sich nicht nachsehen."
   :settings/tabs "Bereiche der Einstellungen"
   :settings/updates "Updates"
   :budget/reached "Das Budget des Monats ist aufgebraucht: Nichts startet, bis die Grenze unter Einstellungen → Budget angehoben wird."
   :settings/mcp "MCP-Server"
   :settings/input "Eingabe"
   :input/title "Nachricht senden"
   :input/explain "Welche Taste eine Nachricht sendet; die andere bricht die Zeile um. Die Wahl gilt für diesen Browser; auf einem Handy ohne Tastatur bricht Enter immer die Zeile um."
   :input/ctrl-enter "Strg+Enter sendet (⌘+Enter auf dem Mac), Enter bricht die Zeile um"
   :input/enter "Enter sendet, Strg+Enter (⌘+Enter auf dem Mac) bricht die Zeile um"
   :settings/recording "Aufnahme"
   :recording/title "Die Effekte der Webcam"
   :recording/explain "Wann weichgezeichneter Hintergrund, Touch-up und Licht auf die Webcam kommen. Die Wahl gilt für diesen Browser; das Rohbild verlässt den Laptop in beiden Fällen nie."
   :recording/live "Live: aufgenommen, wie die Vorschau es zeigt; auf einem ausgelasteten Rechner kann die Webcam ruckeln"
   :recording/after "Nach der Aufnahme, in diesem Browser: ruckelt nie; nach dem Stopp ist die Seite eine Weile beschäftigt"
   :settings/mobile "Auf dem Handy"
   :mobile/title "Die Seite auf deinem Handy"
   :mobile/explain "Scanne das mit der Kamera des Handys, und die Seite öffnet sich dort, angemeldet. Der Code ist der Schlüssel zu dieser Seite: zeig ihn niemandem sonst."
   :mobile/add "Dann die Seite zum Home-Bildschirm hinzufügen, und sie öffnet sich wie eine App, der Burrito ihr Icon: in Safari auf dem iPhone Teilen und Zum Home-Bildschirm; in Chrome auf Android das Menü und Zum Startbildschirm hinzufügen."
   :mobile/install "App installieren"
   :mobile/install-explain "Dieser Browser installiert die Seite als eigene App: ein Fenster ohne die Leisten des Browsers, ein Icon unter den Apps."
   :mobile/installed "Das hier ist die installierte App."
   :settings/advanced "Erweitert"
   :advanced/title "Erweitert"
   :advanced/explain "Die Seitenleiste zeigt die Ansichten dieses einen Gesprächs: alles und die Themen, markiert, solange eines dich braucht. Das hier hängt die rohe Liste der Gespräche von früher und die Protokolle der Aufgaben in der Aktivität an, mit Neuer Bot und den Menüs zum Umbenennen und Löschen. Es zeigt außerdem, wie voll der Kontext des Modells ist, mit den Kosten des Gesprächs und dem Knopf, der ältere Nachrichten zusammenfasst, und unter dem Nachrichtenfeld das Modell und seinen Aufwand. Die Wahl gilt für diese Maschine; jeder Tab sieht sie."
   :advanced/show-all "Jeden Bot, die rohe Liste und die technischen Details zeigen"
   :mcp/title "MCP-Server"
   :mcp/explain "Dienste, die deine Bots über MCP nutzen. Jeder Bot dieser Maschine hat ihre Werkzeuge."
   :mcp/none "Noch ist kein MCP-Server verbunden."
   :mcp/setup "Einen Dienst verbinden …"
   :mcp/setup-explain "Öffnet einen Bot, der herausfindet, wie sich der Dienst verbindet, und dich nach dem fragt, was er braucht."
   :mcp/connected [:fn/str "Verbunden, {{:count}} Werkzeuge"]
   :mcp/connecting "Verbindet …"
   :mcp/off "Aus"
   :mcp/needs [:fn/str "Wartet auf {{:names}}"]
   :mcp/failed [:fn/str "Fehlgeschlagen: {{:error}}"]
   :mcp/idle "Noch nicht verbunden"
   :mcp/test "Verbindung testen"
   :mcp/needs-sign-in "Wartet auf eine Anmeldung"
   :mcp/sign-in "Anmelden …"
   :mcp/sign-in-to [:fn/str "Bei {{:name}} anmelden …"]
   :mcp/sign-out "Abmelden"
   :mcp/testing "Teste …"
   :mcp/test-ok [:fn/str "Test bestanden: neu verbunden, {{:count}} Werkzeuge, Antwort in {{:ms}} ms."]
   :mcp/test-failed [:fn/str "Test fehlgeschlagen nach {{:ms}} ms: {{:error}}"]
   :mcp/turn-off "Ausschalten"
   :mcp/turn-on "Einschalten"
   :mcp/remove "Entfernen"
   :mcp/remove-confirm [:fn/str "{{:name}} entfernen? Deine Bots verlieren seine Werkzeuge."]
   :mcp/keep "Behalten"
   :mcp/secrets "Gespeicherte Geheimnisse"
   :mcp/secrets-explain "Tokens, die die Server nutzen, nur auf dieser Maschine gespeichert. Die Bots sehen sie nie."
   :mcp/secret-remove "Entfernen"
   :secret/save "Speichern"
   :secret/placeholder "Hier einfügen"
   :secret/note "Nur auf dieser Maschine gespeichert; der Bot sieht es nie."

   :bots/title "Bots"
   :bots/menu "Die Bots"
   :bots/manager "Manager"
   :bots/needs-you "Braucht dich"
   :bots/projects "Projekte"
   :bots/other "Andere"
   :bots/all "Alle Bots"
   :views/all "Alles"
   :views/topics "Themen"
   :views/archived "Archiviert"
   :views/in-topic [:fn/str "in {{:topic}}"]
   :views/empty "Hier ist noch nichts."
   :views/new "+ Neues Thema"
   :views/new-placeholder "Worum geht es im neuen Thema?"
   :views/new-empty "Ein neues Thema beginnt mit deiner ersten Nachricht hier: sag, worum es geht."
   :views/in-new "in einem neuen Thema"
   :context/title "Kontext"
   :context/model "Modell: {model}"
   :context/used "{percent} % belegt"
   :context/of "{used} von {window} Tokens; darüber werden ältere Nachrichten zusammengefasst."
   :context/unknown "{used} Tokens; das Fenster dieses Modells ist nicht bekannt."
   :context/cost "Dieser Bot bisher, mit seinen Subagenten: {cost}"
   :context/hint "Ein voller Kontext macht das Modell langsam und vergesslich. Ein neuer Bot fängt mit leerem Kontext an; er kann die Dateien dieses Bots lesen."
   :context/ring "Kontext: {percent} % belegt"
   :context/close "Schließen"
   :context/compact "Ältere Nachrichten jetzt zusammenfassen"
   :context/compacting "Ältere Nachrichten werden zusammengefasst …"
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
   :bot/archived "archiviert"
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
