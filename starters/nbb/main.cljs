(ns main
  "The program's entry: `main` gets the words of the command line
   (`./run <command> …`, see README.md) and does one command.

   `import <file-or-address>` reads a JSON array of items, keeps it as
   it came under `imports/<source>/<stamp>.json` and notes one event
   `imported` in the bot's log, with the file. `new` replays: the
   items of every import, the newest version of each (by `id`, or by
   content when the items have none), without those a `posted` event
   names, as JSON lines with their `key`. `posted <key> <channel>`
   notes that event. Nothing else is kept: a changed rule applies to
   the whole past on the next `new`.

   `--source <name>` names the import's folder (the address's host or
   the file's name otherwise); `--events <file>` and `--imports <dir>`
   point elsewhere than the bot's log and `imports/` folder, which
   `defaults` finds from this file's place in the bot's folder. An
   error is one line on stderr and exit code 1, so that a shell sees
   it. The last form runs `main` when this file is the one nbb was
   started with, not when a test requires it."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            [clojure.string :as str]
            [events]
            [nbb.core :refer [*file* invoked-file]]
            [promesa.core :as p]))

(def defaults
  "The bot's log and its imports, from this file's place:
   `<bot>/work/programs/<name>/main.cljs` beside `<bot>/events.edn`
   and `<bot>/work/imports/` (`*file*` is this file while it loads)."
  (let [here (path/dirname *file*)]
    {:events (path/resolve here ".." ".." ".." "events.edn")
     :imports (path/resolve here ".." ".." "imports")}))

(defn parse
  "`{:command … :args [...] :events … :imports … :source …}` of the
   command line's words."
  [words]
  (loop [words (seq words)
         result defaults
         rest []]
    (let [[word value] words]
      (cond
        (nil? words)
        (assoc result :command (first rest) :args (vec (next rest)))

        (#{"--events" "--imports" "--source"} word)
        (recur (nnext words)
               (assoc result (keyword (subs word 2)) (or value (throw (js/Error. (str word " needs a value")))))
               rest)

        :else
        (recur (next words) result (conj rest word))))))

(defn source-name
  "A folder name for `source`: an address's host, a file's name."
  [source]
  (if (re-find #"^https?://" source)
    (str/replace (.-hostname (js/URL. source)) #"^www\." "")
    (path/basename source (path/extname source))))

(defn text-of
  "A promise of the text of `source`, a file or an http(s) address."
  [source]
  (if (re-find #"^https?://" source)
    (p/let [response (js/fetch source)]
      (when-not (.-ok response)
        (throw (js/Error. (str source " answered " (.-status response)))))
      (.text response))
    (fsp/readFile source "utf8")))

(defn items-of
  "The items in `text`, a JSON array of objects, as maps with keyword
   keys; `source` names the text in the error."
  [text source]
  (let [items (js->clj (js/JSON.parse text) :keywordize-keys true)]
    (when-not (and (vector? items) (every? map? items))
      (throw (js/Error. (str source " holds no JSON array of items"))))
    items))

(defn stamp
  "The time as a file name: 2026-10-30T10-19-42-123Z."
  []
  (str/replace (.toISOString (js/Date.)) #"[:.]" "-"))

(defn import!
  "`source` kept as it came under the imports as `<name>/<stamp>.json`
   and noted in the log; a promise of the event."
  [{:keys [events imports source]} source-arg]
  (p/let [text (text-of source-arg)
          items (items-of text source-arg)
          dir (path/join imports (or source (source-name source-arg)))
          file (path/join dir (str (stamp) ".json"))
          data {:source source-arg
                :file (path/relative (path/dirname events) file)
                :count (count items)}]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync file text)
    (events/append! events "imported" data)
    data))

(defn key-of
  "What tells an item from the others: its `id` as a string, or a hash
   of its content when it has none (an edit is a new item then)."
  [item]
  (if (contains? item :id)
    (str (:id item))
    (-> (crypto/createHash "sha256")
        (.update (pr-str (into (sorted-map) item)))
        (.digest "hex")
        (subs 0 16))))

(defn- with-import
  "`[order versions]` with the items of the import `file` (relative to
   `root`) folded in: a key seen first joins the order, a later
   version of an item wins. An import file that is gone is an error,
   the files are kept."
  [root [order versions] {:keys [file]}]
  (let [path (path/resolve root file)]
    (when-not (fs/existsSync path)
      (throw (js/Error. (str "the import " file " is gone; the files under imports/ are kept"))))
    (reduce (fn [[order versions] item]
              (let [key (key-of item)]
                [(if (contains? versions key) order (conj order key))
                 (assoc versions key (assoc item :key key))]))
            [order versions]
            (items-of (fs/readFileSync path "utf8") file))))

(defn replay
  "The items of every import the log names, in the order first seen,
   each in its newest version, with its `:key`: a transduce over the
   log's `imported` events, the log never held whole."
  [events-file]
  (let [root (path/dirname events-file)
        [order versions] (transduce (events/kind "imported")
                                    (completing (partial with-import root))
                                    [[] {}]
                                    (events/reducible events-file))]
    (mapv versions order)))

(defn new-items
  "The replayed items without those a `posted` event names by key."
  [events-file]
  (let [posted (into #{} (comp (events/kind "posted") (map :key)) (events/reducible events-file))]
    (remove #(posted (:key %)) (replay events-file))))

(defn main
  "Does the command of `words`; a promise that settles once it is done,
   its errors said on stderr and in the exit code."
  [words]
  (-> (p/let [{:keys [command args events] :as options} (parse words)]
        (case command
          "import" (if-let [source (first args)]
                     (p/let [{:keys [file count]} (import! options source)]
                       (println (str "kept " count " items as " file)))
                     (throw (js/Error. "import needs a file or an address")))
          "new" (doseq [item (new-items events)]
                  (println (js/JSON.stringify (clj->js item))))
          "posted" (let [[key channel] args]
                     (when-not (and key channel)
                       (throw (js/Error. "posted needs the item's key and the channel")))
                     (events/append! events "posted" {:key key :channel channel})
                     (println (str "noted " key " posted on " channel)))
          (do (js/console.error "usage: run import <file-or-address> [--source <name>] | run new | run posted <key> <channel>")
              (set! (.-exitCode js/process) 2))))
      (p/catch (fn [error]
                 (js/console.error (or (.-message error) (str error)))
                 (set! (.-exitCode js/process) 1)))))

(when (= *file* (invoked-file))
  (main (vec *command-line-args*)))
