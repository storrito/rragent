(ns rragent.tools.topic
  "The `topic` tool of the loop (docs/one-loop.md §3): a topic is one
   matter of the person's with a name, a view of the sidebar and a tag
   on the turns that belong to it. `create` with a name makes one, its
   id the name's slug, in the structure document of the machine
   (`rragent.machine`), and tags the turn with it; `tag` with a topic
   tags the turn, when the turn belongs to another topic than the
   person's message said, or to a second one as well (the page reads
   the call, nothing is stored); `rename` and `archive` change one;
   `list` names them. The tool is made over the functions of the
   machine, so that the harness needs nothing of it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineExtension defineTool]]
            [clojure.string :as str]
            [rragent.tools.work :as work]))

(def description
  (str "The topics of this conversation: one per matter of the person's, with a name, each a view in the sidebar. "
       "`create` with `name` makes one and puts this turn under it. "
       "`tag` with `topic` (its name or id) puts this turn under a topic too, when the message came from another view or from the view of all. "
       "`rename` with `id` and `name` renames one, `archive` with `id` folds one away whose work is over; `list` names them."))

(defn slug
  "A topic's id from its name: lower case, letters and digits, dashes
   between; `topic` for a name without any."
  [name]
  (let [s (-> (str name)
              (str/lower-case)
              ;; the u flag, for the letter classes
              (str/replace (re-pattern "(?u)[^\\p{L}\\p{N}]+") "-")
              (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) "topic" s)))

(defn listed
  "The topics as a text for the model."
  [topics]
  (if (empty? topics)
    "No topics yet."
    (str/join "\n" (map (fn [[id {:keys [name archived?]}]]
                          (str id ": " name (when archived? " · archived")))
                        (sort-by (fn [[_ t]] (str/lower-case (str (:name t)))) topics)))))

(defn resolve-id
  "The id of the topic `given` names, by id or by name (case does not
   matter); nil for none."
  [topics given]
  (let [given (str/trim (str given))
        lower (str/lower-case given)]
    (or (when (contains? topics given) given)
        (some (fn [[id {:keys [name]}]] (when (= lower (str/lower-case (str name))) id)) topics)
        (when (contains? topics (slug given)) (slug given)))))

(defn tool
  "The tool over the functions of the machine: `topics` (the topics,
   id to `{:name :created :archived?}`), `create!` (a name to a
   promise of the id), `rename!` (an id and a name to a promise),
   `archive!` (an id to a promise)."
  [{:keys [topics create! rename! archive!]}]
  (defineTool
   #js {:name "topic"
        :description description
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "tag") (.Literal Type "rename") (.Literal Type "archive") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "create, rename: the topic's name, as the person calls the matter"}))
                                       :id (.Optional Type (.String Type #js {:description "rename, archive: the topic's id, as list shows it"}))
                                       :topic (.Optional Type (.String Type #js {:description "tag: the topic, by name or id"}))})
        :execute (^:async fn [args _api _call-context]
                   (case (.-action args)
                     "create" (let [name (str/trim (str (.-name args)))]
                                (when (str/blank? name)
                                  (throw (js/Error. "create needs the name")))
                                (if-let [id (resolve-id (topics) name)]
                                  (work/text (str "The topic " name " exists already as " id "; this turn is under it."))
                                  (let [id (await (create! name))]
                                    (work/text (str "Made the topic " name " (" id "); this turn is under it.")))))
                     "tag" (let [given (str/trim (str (.-topic args)))]
                             (when (str/blank? given)
                               (throw (js/Error. "tag needs the topic")))
                             (if-let [id (resolve-id (topics) given)]
                               (work/text (str "This turn is under " (:name (get (topics) id)) " (" id ") too."))
                               (throw (js/Error. (str "No topic " given ". The topics:\n" (listed (topics)) "\nMake it with create first.")))))
                     "rename" (let [id (str/trim (str (.-id args)))
                                    name (str/trim (str (.-name args)))]
                                (when (or (str/blank? id) (str/blank? name))
                                  (throw (js/Error. "rename needs the id and the new name")))
                                (when-not (contains? (topics) id)
                                  (throw (js/Error. (str "No topic " id ". The topics:\n" (listed (topics))))))
                                (await (rename! id name))
                                (work/text (str "The topic " id " is named " name " now.")))
                     "archive" (let [id (str/trim (str (.-id args)))]
                                 (when (str/blank? id)
                                   (throw (js/Error. "archive needs the id")))
                                 (when-not (contains? (topics) id)
                                   (throw (js/Error. (str "No topic " id ". The topics:\n" (listed (topics))))))
                                 (await (archive! id))
                                 (work/text (str "Archived the topic " (:name (get (topics) id)) " (" id "): its view folds away, nothing is lost.")))
                     "list" (work/text (listed (topics)))))}))

(defn extension
  "The extension of the tool, for the registry of the machine."
  [fns]
  (defineExtension #js {:name "topics"
                        :tools #js [(tool fns)]}))
