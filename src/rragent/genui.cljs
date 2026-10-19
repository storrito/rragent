(ns rragent.genui
  "Hiccup the agent writes into an answer, shown as a part of the page:
   cards with a link each, a choice as buttons, a few accounts as
   checkboxes, a picture. A ```hiccup block in the markdown holds EDN
   hiccup,

       ```hiccup
       [:div [:p \"Where should it go?\"]
        [:label [:input {:type \"checkbox\" :value \"Reel @anna\" :checked true}] \" Reel\"]
        [:button {:send \"Post it on: {checked}\"} \"Take these\"]
        [:button {:send \"Not now\"} \"Later\"]]
       ```

   and `block` turns it into a part of the page (`rragent.markdown` calls
   it for a closed fence). The agent writes data, never markup: only the
   tags and attributes of the allow-lists get through, a link or a file
   points at this host's artifacts or uploads or at https, an input is a
   checkbox or a radio button. `:send` on a button sends its text as the
   person's next message; `{checked}` in it becomes the values ticked in
   the same block, which only the browser knows, so such a button is the
   client's alias `:ui/send-checked` (`rragent.client`). `:open` shows an
   artifact. A block that does not read is shown as code with what went
   wrong. The contract the skills write against is pchat's
   (`pchat.genui`) with the inputs added."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

(def tags
  #{:div :span :p :ul :ol :li :strong :em :b :i :s :small :h1 :h2 :h3 :h4 :a
    :img :audio :video :button :table :thead :tbody :tr :th :td :figure
    :figcaption :code :pre :br :hr :blockquote :label :input})

(def attributes
  #{:class :href :src :poster :title :alt :controls :preload :loop :muted
    :width :height :colspan :rowspan :download :style :send :open
    :type :name :value :checked :disabled})

(def input-types
  #{"checkbox" "radio"})

(def urls
  "Where a link, a file or a picture may point: this host's artifacts and
   uploads, the web over https, an e-mail."
  ["/artifacts/" "/uploads/" "https://" "mailto:"])

(def checked-mark
  "The place in a button's `:send` for the values ticked in its block."
  "{checked}")

(defn- url
  "The URL when it is allowed, a relative `artifacts/…` or `uploads/…`
   made absolute; nil otherwise."
  [value]
  (when (string? value)
    (let [value (cond-> value
                  (re-find #"^(artifacts|uploads)/" value) (->> (str "/")))]
      (when (some #(str/starts-with? value %) urls)
        value))))

(defn- base-tag
  "`:div.card` is a div."
  [tag]
  (keyword (first (str/split (name tag) #"[.#]"))))

(defn- style
  "The style map with plain values; a value that loads something
   (`url(…)`) goes, a picture is an `img`."
  [value]
  (when (map? value)
    (into {}
          (for [[k v] value
                :when (and (or (keyword? k) (string? k))
                           (or (string? v) (number? v))
                           (not (str/includes? (str/lower-case (str v)) "url(")))]
            [(keyword k) v]))))

(defn- send-actions
  [text]
  {:click [[:data/command {:command/kind :chat/send
                           :command/data {:text (str text)}}]]})

(defn- open-actions
  "The artifact of `artifacts/<path>`, a project's folder with or
   without its slash."
  [path]
  {:click [[:data/command {:command/kind :artifacts/open
                           :command/data {:path (-> (str path)
                                                    (str/replace #"^/?artifacts/" "")
                                                    (str/replace #"/+$" ""))}}]]})

(defn- attrs
  "The attributes that are allowed, the ones with a URL checked, `:send`
   and `:open` turned into the commands behind a click."
  [tag m]
  (reduce (fn [acc [k v]]
            (cond
              (not (contains? attributes k)) acc
              (#{:href :src :poster} k) (if-let [v (url v)]
                                          (cond-> (assoc acc k v)
                                            (and (= :href k) (= :a tag)) (assoc :target "_blank" :rel "noopener noreferrer"))
                                          acc)
              (= :style k) (if-let [v (style v)] (assoc acc :style v) acc)
              (= :send k) (assoc acc :on (send-actions v))
              (= :open k) (assoc acc :on (open-actions v))
              (or (string? v) (number? v) (boolean? v)) (assoc acc k v)
              :else acc))
          {}
          m))

(defn- send-checked?
  "A button whose `:send` waits for the values ticked in its block."
  [base m]
  (and (= :button base)
       (string? (:send m))
       (str/includes? (:send m) checked-mark)))

(defn sanitize
  "The hiccup with only what the allow-lists know; an input of another
   type than checkbox or radio goes."
  [hiccup]
  (cond
    (and (vector? hiccup) (keyword? (first hiccup)))
    (let [[tag & body] hiccup
          base (base-tag tag)
          ;; `:ui/div` is no div: a tag with a namespace names an alias of the client
          allowed? (and (nil? (namespace tag)) (contains? tags base))
          [m body] (if (map? (first body)) [(first body) (rest body)] [{} body])]
      (cond
        (and allowed? (= :input base) (not (input-types (:type m))))
        nil

        (and allowed? (send-checked? base m))
        (into [:ui/send-checked (-> (attrs base (dissoc m :send :open))
                                    (assoc :ui/send (:send m)))]
              (map sanitize body))

        allowed?
        (into [tag (attrs base m)] (map sanitize body))

        ;; a tag that is not allowed is a span of its children, without a click
        :else
        (into [:span (attrs :span (dissoc m :send :open))] (map sanitize body))))

    (sequential? hiccup) (map sanitize hiccup)
    (or (string? hiccup) (number? hiccup) (nil? hiccup)) hiccup
    (keyword? hiccup) (name hiccup)
    :else (pr-str hiccup)))

(defn parse
  "`{:hiccup …}` or `{:error \"…\"}`; a tagged literal is its value."
  [text]
  (try
    (let [value (reader/read-string {:default (fn [_ value] value)} (str text))]
      (if (and (vector? value) (keyword? (first value)))
        {:hiccup value}
        {:error "not a hiccup vector, [:tag …]"}))
    (catch :default e
      {:error (ex-message e)})))

(defn block
  "One block as a part of the page, or as code with what went wrong."
  [text]
  (let [{:keys [hiccup error]} (parse text)]
    (if error
      [:div.genui.genui-error
       [:p.error [:i18n :genui/rejected {:error error}]]
       [:pre [:code text]]]
      [:div.genui (sanitize hiccup)])))

(comment
  (block "[:ul [:li [:audio {:src \"artifacts/card/a.mp3\" :controls true}] [:button {:send \"Take a\"} \"Take this one\"]]]")
  (block "[:div {:onclick \"x\"} [:script \"alert(1)\"] [:input {:type \"text\"}]]")
  (block "[:div [:input {:type \"checkbox\" :value \"a\"}] [:button {:send \"On: {checked}\"} \"Go\"]]")
  (block "[:div")
  )
