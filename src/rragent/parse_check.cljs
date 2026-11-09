(ns rragent.parse-check
  "A Clojure file a bot wrote or edited, read at once the way nbb reads
   it. A bot that writes a namespace of two hundred lines in one go
   learns of a missing paren only when nbb refuses the file, a command
   later, and then looks for the place with `nl` and `sed`, often in
   several rounds. After every `write` and `edit` of a `.cljs`, `.clj`,
   `.cljc`, `.bb` or `.edn` file (pi-durable's `afterTool`, before the
   result is committed) the file's text is parsed with edamame, nbb's
   own parser, and a form that does not close or closes too often is
   said in the tool's result, with nbb's message, its line and its
   column (`note`). A file that parses gets nothing added: the check
   costs no token where nothing is wrong."
  (:require ["@earendil-works/pi-durable" :refer [AgentDoc ToolTask defineExtension hook]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rragent.home :as home]))

(def extensions
  "The files that are Clojure: source of nbb, Clojure, ClojureScript
   and babashka, and EDN."
  #{".clj" ".cljs" ".cljc" ".bb" ".edn"})

(defn clojure-file?
  "Whether `file` is one of the `extensions`."
  [file]
  (contains? extensions (str/lower-case (path/extname (str file)))))

(def options
  "edamame's options for a whole file of source as a reader takes it:
   every syntax (`:all`), reader conditionals of either platform, an
   auto-resolved keyword of any alias, any tag; a regex literal kept as
   its string, since the JVM's syntax is not JavaScript's and the check
   is about the parens, not the pattern."
  {:all true
   :read-cond :allow
   :features #{:clj :cljs}
   :auto-resolve (fn [_] 'user)
   :readers (fn [_] identity)
   :regex (fn [pattern] (list 're-pattern pattern))})

(defn problem
  "Why `text` does not read as Clojure, `{:message :line :column}`, nil
   when it reads."
  [text]
  (try
    (edamame/parse-string-all (str text) options)
    nil
    (catch :default e
      (let [{:keys [row col]} (ex-data e)]
        {:message (or (ex-message e) (str e)) :line row :column col}))))

(defn note
  "The line the tool's result gets for `file`, whose text has
   `problem`."
  [file {:keys [message line column]}]
  (str file " does not read as Clojure: " message
       (when line (str " (line " line (when column (str ", column " column)) ")"))
       ". nbb refuses the file as it stands; fix this form first, with an edit."))

(defn resolve-path
  "The file the tools' `path` names, as pi-durable's tools resolve it:
   a leading `@` dropped, `~/` the home folder, a relative one in
   `cwd`."
  [cwd file]
  (let [file (cond-> (str file) (str/starts-with? (str file) "@") (subs 1))]
    (cond
      (str/starts-with? file "~/") (path/join (os/homedir) (subs file 2))
      (path/isAbsolute file) file
      :else (path/resolve cwd file))))

(defn- read-file
  "The text of `file`, nil when it cannot be read."
  [file]
  (try (fs/readFileSync file "utf8") (catch :default _ nil)))

(defn ^:async text-of
  "The text of the Clojure file the call `call` changed: what `write`
   wrote, what an `edit` left in the file (read in the conversation's
   directory, `cwd-of`); nil for any other call."
  [^js call cwd-of]
  (let [args (.-arguments call)
        file (some-> args (aget "path") str)]
    (when (and file (clojure-file? file))
      (case (.-name call)
        "write" (some-> (aget args "content") str)
        "edit" (read-file (resolve-path (await (cwd-of)) file))
        nil))))

(defn ^:async checked
  "The tool `result` of `call` with the `note` of its file added when
   the file does not read as Clojure; nil when it reads, when the call
   failed, or when it was not a `write` or an `edit` of such a file.
   `cwd-of` answers the directory a relative path is in."
  [^js call ^js result cwd-of]
  (when-not (.-isError result)
    (when-let [text (await (text-of call cwd-of))]
      (when-let [p (problem text)]
        (let [output (or (.-output result) #js [])]
          (js/Object.assign #js {} result
                            #js {:output (.concat output #js [#js {:type "text" :text (note (aget (.-arguments call) "path") p)}])}))))))

(defn extension
  "The extension that checks every Clojure file a `write` or an `edit`
   leaves (`checked`), its relative path in the conversation's `cwd`,
   which its agent keeps, else the bot's `work/` in `home`, as the
   harness's environment takes it."
  [home]
  (defineExtension
   #js {:name "rragent-parse-check"
        :hooks #js [(hook ToolTask
                          #js {:afterTool (fn [call result ^js api context]
                                            (-> (checked call result
                                                         (^:async fn []
                                                          (let [id (.-conversationId api)
                                                                agent (await (.snapshot api AgentDoc id context))]
                                                            (or (some-> agent .-cwd)
                                                                (path/join (home/bot-dir home id) "work")))))
                                                (.then (fn [r] (or r js/undefined)))))})]}))
