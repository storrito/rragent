(ns rragent.client.editor
  "The editor of a text file in the artifacts pane, `[:ui/code-editor]`
   (`rragent.view/editor-view`): CodeMirror 6 over the text the frame
   carries, the syntax of the file's extension colored (`language`),
   Clojure's by nextjournal's clojure-mode with its structural editing.
   The person saves with Ctrl+S (⌘S on a Mac) or the Save of the strip
   under the editor, which shows while something is unsaved: the text
   goes to the server as `:artifacts/save` (`rragent.page`), which
   writes the file, and the frame that follows carries the written
   text, which marks the editor clean.

   The DOM of the editor is CodeMirror's: Replicant mounts the
   `.surface` node once per file and never touches what is inside it;
   the frames reach the editor through `:replicant/on-render`. A new
   version of the file from the server (`:ui/rev`, the file's time)
   replaces the text while the person has not changed it; while they
   have, it is kept aside and the strip says the file changed on disk,
   with the way to take that version. What the person typed but did
   not save survives a switch to another file and back (a `:draft`),
   the way the composer's draft does.

   The state the strip renders from is in the store under `[:editor
   key]`, `:dirty?`, `:status` (`:saving`, `:failed`) and `:disk?`, so
   that it flows like everything else of the page; the CodeMirror
   views themselves are in `editors*`, by bot and path."
  (:require ["@codemirror/commands" :refer [indentWithTab]]
            ["@codemirror/lang-css" :refer [css]]
            ["@codemirror/lang-html" :refer [html]]
            ["@codemirror/lang-javascript" :refer [javascript]]
            ["@codemirror/lang-json" :refer [json]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@codemirror/lang-xml" :refer [xml]]
            ["@codemirror/lang-yaml" :refer [yaml]]
            ["@codemirror/language" :refer [HighlightStyle syntaxHighlighting]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap]]
            ["@lezer/highlight" :refer [tags]]
            ["@nextjournal/clojure-mode" :refer [complete_keymap default_extensions]]
            ["codemirror" :refer [basicSetup]]
            [rframes.client.state :as state]))

(defn editor-key
  "The key of a file's editor: the bot and the path."
  [bot path]
  (str bot "/" path))

(defn language
  "The extensions of CodeMirror for the syntax of the file's
   extension: HTML, CSS, JavaScript, JSON, Markdown, XML and YAML from
   CodeMirror's own packages, Clojure (clj, cljs, cljc, edn, bb) from
   nextjournal's clojure-mode with its paredit keymap; nil for a plain
   text."
  [ext]
  (case (str ext)
    ("html" "htm") (html)
    "css" (css)
    ("js" "mjs" "cjs") (javascript)
    "ts" (javascript #js {:typescript true})
    "json" (json)
    ("md" "markdown") (markdown)
    ("xml" "svg") (xml)
    ("yaml" "yml") (yaml)
    ("clj" "cljs" "cljc" "edn" "bb") #js [default_extensions (.of keymap complete_keymap)]
    nil))

(def ^:private highlight
  "The colors of the syntax as the stylesheet's variables (`--syn-…`,
   public/app.css), so that they follow the theme."
  (syntaxHighlighting
   (.define HighlightStyle
            #js [#js {:tag #js [(.-keyword tags) (.-modifier tags) (.-operatorKeyword tags) (.-controlKeyword tags)]
                      :color "var(--syn-keyword)"}
                 #js {:tag #js [(.-atom tags) (.-bool tags) (.-number tags) (.-null tags) (.-literal tags)]
                      :color "var(--syn-atom)"}
                 #js {:tag #js [(.-string tags) (.special tags (.-string tags)) (.-attributeValue tags) (.-regexp tags) (.-escape tags)]
                      :color "var(--syn-string)"}
                 #js {:tag (.-comment tags) :color "var(--syn-comment)" :fontStyle "italic"}
                 #js {:tag #js [(.-meta tags) (.-processingInstruction tags)] :color "var(--syn-comment)"}
                 #js {:tag #js [(.-tagName tags) (.-typeName tags) (.-className tags) (.-namespace tags)]
                      :color "var(--syn-tag)"}
                 #js {:tag #js [(.-attributeName tags) (.-propertyName tags) (.-labelName tags)
                                (.definition tags (.-variableName tags)) (.function tags (.-variableName tags))]
                      :color "var(--syn-attribute)"}
                 #js {:tag #js [(.-operator tags) (.-punctuation tags) (.-bracket tags) (.-separator tags)]
                      :color "var(--syn-punctuation)"}
                 #js {:tag (.-heading tags) :color "var(--syn-tag)" :fontWeight "bold"}
                 #js {:tag #js [(.-link tags) (.-url tags)] :color "var(--syn-link)" :textDecoration "underline"}
                 #js {:tag (.-emphasis tags) :fontStyle "italic"}
                 #js {:tag (.-strong tags) :fontWeight "bold"}
                 #js {:tag (.-strikethrough tags) :textDecoration "line-through"}
                 #js {:tag (.-invalid tags) :color "var(--accent)"}])))

(defonce ^:private editors*
  ;; The open editors by key: `:view`, the CodeMirror view, `:bot` and
  ;; `:path`, `:loaded`, the text last taken from the server, with its
  ;; `:rev`, and `:disk`, a newer `{:text :rev}` kept aside while the
  ;; person's changes are unsaved. An editor that was unmounted with
  ;; unsaved changes leaves `{:draft {:text :loaded :rev}}` behind.
  (atom {}))

(defn- doc-text
  [^js view]
  (.toString (.-doc (.-state view))))

(defn- set-state!
  "`v` under `[:editor key k]` of the store, when it is not there yet;
   after the current render, since a hook of Replicant calls this
   while it renders."
  [key k v]
  (js/queueMicrotask
   (fn []
     (when (not= v (get-in @state/store [:editor key k]))
       (state/dispatch! [[:store/assoc-in [:editor key k] v]])))))

(defn- mark-dirty!
  "`:dirty?` of the editor as the text compares to the one last taken
   from the server."
  [key ^js view]
  (set-state! key :dirty? (not= (doc-text view) (:loaded (get @editors* key)))))

(defn- take!
  "The editor takes `text`, the server's version `rev`, as what it
   shows and what is saved: the text replaced when it differs, the
   version kept aside forgotten."
  [key ^js view text rev]
  (when (not= text (doc-text view))
    (.dispatch view #js {:changes #js {:from 0 :to (.-length (.-doc (.-state view))) :insert text}}))
  (swap! editors* update key assoc :loaded text :rev rev :disk nil)
  (set-state! key :dirty? false)
  (set-state! key :disk? false))

(defn save!
  "The text of the editor to the server, `:artifacts/save`; the strip
   says Saving until the answer, and Failed after a refusal. The frame
   the write causes marks the editor clean."
  [key]
  (when-let [{:keys [view bot path]} (get @editors* key)]
    (state/dispatch! [[:store/assoc-in [:editor key :status] :saving]
                      [:data/command {:command/kind :artifacts/save
                                      :command/data {:bot bot :path path :text (doc-text view)}}
                       {:on-success [[:store/assoc-in [:editor key :status] nil]]
                        :on-error [[:store/assoc-in [:editor key :status] :failed]]}]])))

(defn- reload!
  "The version kept aside taken, the person's changes let go."
  [key]
  (when-let [{:keys [view disk]} (get @editors* key)]
    (when disk
      (take! key view (:text disk) (:rev disk)))))

(defn- extensions
  "The setup of an editor: CodeMirror's basic one, Ctrl+S and Tab,
   wrapped lines, the colors, the listener that keeps `:dirty?`, read
   only when asked, and the syntax of the file first, so that its own
   handling of a typed bracket comes before the basic one."
  [key ext read-only?]
  (let [lang (language ext)]
    (cond-> []
      lang (conj lang)
      true (into [(.of keymap #js [#js {:key "Mod-s" :run (fn [_] (save! key) true)} indentWithTab])
                  basicSetup
                  (.-lineWrapping EditorView)
                  highlight
                  (.of (.-updateListener EditorView)
                       (fn [^js update]
                         (when (.-docChanged update)
                           (mark-dirty! key (.-view update)))))
                  (.of (.-readOnly EditorState) (boolean read-only?))]))))

(defn- mount!
  "The editor of the file made in `node`, with the draft left behind
   by its last editor when there is one; then the frame's text taken
   as `update!` would."
  [node key {:keys [bot path text rev ext read-only?]}]
  (let [{:keys [draft]} (get @editors* key)
        view (EditorView. #js {:parent node
                               :state (.create EditorState #js {:doc (if draft (:text draft) text)
                                                                :extensions (into-array (extensions key ext read-only?))})})]
    (swap! editors* assoc key {:view view
                               :bot bot
                               :path path
                               :loaded (if draft (:loaded draft) text)
                               :rev (if draft (:rev draft) rev)})
    (mark-dirty! key view)
    view))

(defn- update!
  "A frame with the file: a new version from the server replaces the
   text when the person has not changed it, or when it is what they
   saved; otherwise it is kept aside, and the strip says so."
  [key text rev]
  (when-let [{:keys [view loaded disk] loaded-rev :rev} (get @editors* key)]
    (when (and (not= rev loaded-rev) (not= rev (:rev disk)))
      (let [doc (doc-text view)]
        (if (or (= doc text) (= doc loaded))
          (take! key view text rev)
          (do (swap! editors* update key assoc :disk {:text text :rev rev})
              (set-state! key :disk? true)))))))

(defn- unmount!
  "The editor's view destroyed; its unsaved text stays behind as the
   draft the next editor of the file starts from."
  [key]
  (when-let [{:keys [view loaded rev]} (get @editors* key)]
    (let [doc (doc-text view)]
      (.destroy view)
      (if (= doc loaded)
        (do (swap! editors* dissoc key)
            (set-state! key :dirty? false)
            (set-state! key :disk? false))
        (swap! editors* assoc key {:draft {:text doc :loaded loaded :rev rev}})))))

(def ^:private mac?
  (boolean (re-find #"Mac|iPhone|iPad" (str (some-> js/globalThis .-navigator .-platform)))))

(defn- strip
  "The strip under the editor, there while something is to be said:
   unsaved changes with Save and its key, Saving, a save that failed,
   the file changed on disk with the way to take that version."
  [key {:keys [dirty? status disk?]} {:keys [save saving unsaved failed disk reload]}]
  (let [save-button [:button.primary.save {:on {:click (fn [_] (save! key))}} save]]
    [:div.strip {:class (when-not (or dirty? status disk?) "quiet")}
     (cond
       (= :saving status) [:span.note saving]
       disk? (list [:span.note disk]
                   [:button.link {:on {:click (fn [_] (reload! key))}} reload]
                   save-button)
       :else (list [:span.note {:class (when (= :failed status) "error")}
                    (if (= :failed status) failed unsaved)]
                   save-button
                   [:kbd (if mac? "⌘S" "Ctrl+S")]))]))

(defn code-editor
  "`[:ui/code-editor {:ui/bot :ui/path :ui/rev :ui/text :ui/ext
   :ui/read-only? …}]`: the editor of the file, its surface mounted
   once per file (`mount!`), fed by every frame (`update!`), and the
   strip under it from the store."
  [{:keys [ui/bot ui/path ui/rev ui/text ui/ext ui/read-only? ui/state
           ui/save-label ui/saving-label ui/unsaved-label ui/failed-label ui/disk-label ui/reload-label]} _children]
  (let [key (editor-key bot path)
        props {:bot bot :path path :text text :rev rev :ext ext :read-only? read-only?}]
    [:div.code-editor
     [:div.surface {:replicant/key key
                    :replicant/on-render (fn [{:keys [replicant/node replicant/life-cycle]}]
                                           (case life-cycle
                                             :replicant.life-cycle/mount (mount! node key props)
                                             :replicant.life-cycle/unmount (unmount! key)
                                             (update! key text rev)))}]
     (strip key (get-in state [:editor key]) {:save save-label
                                              :saving saving-label
                                              :unsaved unsaved-label
                                              :failed failed-label
                                              :disk disk-label
                                              :reload reload-label})]))
