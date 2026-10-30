(ns main
  "The program's entry: `main` gets the words of the command line
   (`./run <command> …`, see README.md) and does one command.
   `import <file-or-address>` reads a JSON array of items with an `id`
   each, prints every new or changed one as a JSON line with its
   `status`, and remembers them all in the store, `state.sqlite`
   beside this file (`--db <file>` names another); `seen` lists the
   ids the store has. A file named on the command line is relative to
   where the program was started. An error is one line on stderr and
   exit code 1, so that a shell sees it. The last form runs `main`
   when this file is the one nbb was started with, not when a test
   requires it."
  (:require ["node:fs/promises" :as fs]
            ["node:path" :as path]
            [nbb.core :refer [*file* invoked-file]]
            [promesa.core :as p]
            [store]))

(def state-file
  "The store's file unless `--db` names another: beside this file,
   whatever the working directory (`*file*` is this file while it
   loads)."
  (path/join (path/dirname *file*) "state.sqlite"))

(defn parse
  "`{:command … :source … :db …}` of the command line's words."
  [words]
  (loop [words (seq words)
         result {:db state-file}
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

(defn items-of
  "A promise of the items of `source`, a JSON file or an http(s)
   address: a vector of maps with keyword keys."
  [source]
  (p/let [text (if (re-find #"^https?://" source)
                 (p/let [response (js/fetch source)]
                   (when-not (.-ok response)
                     (throw (js/Error. (str source " answered " (.-status response)))))
                   (.text response))
                 (fs/readFile source "utf8"))
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

(defn main
  "Does the command of `words`; a promise that settles once it is done,
   its errors said on stderr and in the exit code."
  [words]
  (-> (p/let [{:keys [command source db]} (parse words)]
        (case command
          "import" (if source
                     (p/let [items (items-of source)]
                       (doseq [item (import! (store/open db) items)]
                         (println (js/JSON.stringify (clj->js item)))))
                     (throw (js/Error. "import needs a file or an address")))
          "seen" (doseq [id (store/ids (store/open db))]
                   (println id))
          (do (js/console.error "usage: run import <file-or-address> [--db <file>] | run seen [--db <file>]")
              (set! (.-exitCode js/process) 2))))
      (p/catch (fn [error]
                 (js/console.error (or (.-message error) (str error)))
                 (set! (.-exitCode js/process) 1)))))

(when (= *file* (invoked-file))
  (main (vec *command-line-args*)))
