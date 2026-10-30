(ns app.main
  "The program's entry: `main` gets the words of the command line
   (`./run <command> …`, see README.md) and does one command.
   `import <file-or-address>` reads a JSON array of items with an `id`
   each, prints every new or changed one as a JSON line with its
   `status`, and remembers them all in the store, `state.sqlite` in the
   working directory (`--db <file>` names another); `seen` lists the
   ids the store has. An error is one line on stderr and exit code 1,
   so that a shell sees it."
  (:require ["node:fs/promises" :as fs]
            [app.store :as store]))

(enable-console-print!)

(defn parse
  "`{:command … :source … :db …}` of the command line's words."
  [words]
  (loop [words (seq words)
         result {:db "state.sqlite"}
         rest []]
    (cond
      (nil? words)
      (let [[command source] rest]
        (assoc result :command command :source source))

      (= "--db" (first words))
      (recur (nnext words)
             (assoc result :db (or (second words) (throw (js/Error. "--db needs a file"))))
             rest)

      :else
      (recur (next words) result (conj rest (first words))))))

(defn ^:async items-of
  "The items of `source`, a JSON file or an http(s) address: a vector
   of maps with keyword keys."
  [source]
  (let [text (if (re-find #"^https?://" source)
               (let [response (await (js/fetch source))]
                 (when-not (.-ok response)
                   (throw (js/Error. (str source " answered " (.-status response)))))
                 (await (.text response)))
               (await (fs/readFile source "utf8")))
        items (js->clj (js/JSON.parse text) :keywordize-keys true)]
    (when-not (and (vector? items) (every? #(and (map? %) (contains? % :id)) items))
      (throw (js/Error. (str source " holds no JSON array of items with an id each"))))
    items))

(defn import!
  "The items of `items` that are new or changed since the store `db`
   saw them, each with its `:status`; every item is remembered."
  [db items]
  (reduce (fn [found item]
            (let [status (store/status db item)]
              (store/remember! db item)
              (if (= :known status)
                found
                (conj found (assoc item :status (name status))))))
          []
          items))

(defn ^:async main
  [words]
  (try
    (let [{:keys [command source db]} (parse words)]
      (case command
        "import" (if source
                   (doseq [item (import! (store/open db) (await (items-of source)))]
                     (println (js/JSON.stringify (clj->js item))))
                   (throw (js/Error. "import needs a file or an address")))
        "seen" (doseq [id (store/ids (store/open db))]
                 (println id))
        (do (js/console.error "usage: run import <file-or-address> [--db <file>] | run seen [--db <file>]")
            (set! (.-exitCode js/process) 2))))
    (catch :default error
      (js/console.error (or (.-message error) (str error)))
      (set! (.-exitCode js/process) 1))))
