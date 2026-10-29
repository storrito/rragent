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
   points at the bot's artifacts or uploads (`artifacts/…`, served under
   the bot's page, the `:base` of `block`) or at https, an input is a
   checkbox or a radio button. `:send` on a button sends its text as the
   person's next message; `{checked}` in it becomes the values ticked in
   the same block, which only the browser knows, so such a button is the
   client's alias `:ui/send-checked` (`rragent.client`). `:open` shows an
   artifact. Once the person has answered the message, the block's
   choices are made: its buttons that send and its inputs are disabled,
   a button that opens an artifact still does. A block that does not
   read is shown as code with what went wrong. The contract the skills write against is pchat's
   (`pchat.genui`) with the inputs added.

   `[:secret {:name \"STORRITO_TOKEN\" :label \"Storrito API token\"}]`
   is a password field for a secret of the machine (`rragent.secrets`):
   what the person types there is saved by the page, never sent as a
   message, so the model never sees it; the bot learns that it was
   saved. It stays usable once answered, to replace the secret.
   `[:sign-in {:server \"github\"}]` is the link that signs the person in
   at an OAuth server's service, in a tab of its own; the bot is told
   when they are back."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

(def tags
  #{:div :span :p :ul :ol :li :strong :em :b :i :s :small :h1 :h2 :h3 :h4 :a
    :img :audio :video :button :table :thead :tbody :tr :th :td :figure
    :figcaption :code :pre :br :hr :blockquote :label :input})

(def attributes
  "The attributes of every allowed tag; `:name` only on an input (an `img`
   with a name would replace a function of `document`), `:send` and
   `:open` only on a button."
  #{:class :href :src :poster :title :alt :controls :preload :loop :muted
    :width :height :colspan :rowspan :download :style
    :type :value :checked :disabled})

(def input-types
  #{"checkbox" "radio"})

(def style-properties
  "What a style may set: layout inside the block (flex and grid),
   spacing, borders, colors and type. Nothing that takes an element out
   of the flow (`position`, `z-index`, `transform`) and over the page."
  #{:display :flex :flex-direction :flex-wrap :flex-grow :flex-shrink :flex-basis
    :grid-template-columns :grid-template-rows :grid-auto-rows :grid-auto-flow
    :grid-column :grid-row :justify-items :place-items
    :gap :row-gap :column-gap :align-items :align-self :justify-content
    :text-align :vertical-align
    :margin :margin-top :margin-right :margin-bottom :margin-left
    :padding :padding-top :padding-right :padding-bottom :padding-left
    :border :border-top :border-right :border-bottom :border-left
    :border-color :border-width :border-style :border-radius
    :background :background-color :color :opacity
    :font-size :font-weight :font-style :line-height :letter-spacing
    :text-decoration :white-space
    :width :height :min-width :max-width :min-height :max-height :aspect-ratio
    :object-fit :overflow :box-sizing :list-style})

(def button-style-properties
  "What the style of a button may set: its colors, its corners, its type
   and its size, which the stylesheet caps (a swatch is a button of 34
   pixels); no padding and no border width, which would make it a screen."
  #{:background :background-color :color :border-color :border-radius
    :font-size :font-weight :font-style :width :height})

(def style-functions
  "The CSS functions a style value may call: colors, sums, gradients (a
   skill's backgrounds are gradients) and the sizes of a grid's tracks,
   nothing that loads a file (`url`, `image-set`)."
  #{"rgb" "rgba" "hsl" "hsla" "calc" "repeat" "minmax" "fit-content"
    "linear-gradient" "radial-gradient" "conic-gradient"
    "repeating-linear-gradient" "repeating-radial-gradient" "repeating-conic-gradient"})

(def background-properties
  "The properties that paint a background, allowed or not: a color
   given with one that did not get through goes with it (`style`)."
  #{:background :background-color :background-image})

(def max-depth
  "Elements deeper than this go: markdown-it nests at most 100 deep, the
   page's own hiccup around a block adds some."
  32)

(def urls
  "Where a link, a file or a picture may point besides the bot's own
   files: the web over https, an e-mail."
  ["https://" "mailto:"])

(def checked-mark
  "The place in a button's `:send` for the values ticked in its block."
  "{checked}")

(defn- url
  "The URL when it is allowed: a file of the bot, `artifacts/…` or
   `uploads/…` with or without the slash, as the path the page serves
   it on under `base`; the web and mail as they are; nil otherwise."
  [base value]
  (when (string? value)
    (if-let [[_ file] (re-find #"^/?((?:artifacts|uploads)/.*)$" value)]
      (str base "/" file)
      (when (some #(str/starts-with? value %) urls)
        value))))

(defn- base-tag
  "`:div.card` is a div."
  [tag]
  (keyword (first (str/split (name tag) #"[.#]"))))

(defn- without-id
  "`:div#main.card` as `:div.card`: an id would replace a global of the
   page (`window.main`)."
  [tag]
  (keyword (str/replace (name tag) #"#[^.#]*" "")))

(defn- style-value?
  "A plain value: no escape, and only the functions of `style-functions`."
  [v]
  (let [v (str v)]
    (and (not (str/includes? v "\\"))
         (every? style-functions (map (comp str/lower-case second) (re-seq #"([A-Za-z-]+)\s*\(" v))))))

(defn- style
  "The style map with the `properties` and plain values; nil when none is
   left. A color goes with a background that did not get through: it was
   meant for that background, on the page's it may not read."
  [value properties]
  (when (map? value)
    (let [given (for [[k v] value
                      :let [k (when (or (keyword? k) (string? k)) (keyword (str/lower-case (name k))))]]
                  [k v])
          kept (into {}
                     (filter (fn [[k v]]
                               (and (contains? properties k)
                                    (or (string? v) (number? v))
                                    (style-value? v))))
                     given)
          lost-background? (some (fn [[k _]]
                                   (and (background-properties k) (not (contains? kept k))))
                                 given)]
      (not-empty (cond-> kept
                   lost-background? (dissoc :color))))))

(defn- send-actions
  [text bot]
  {:click [[:data/command {:command/kind :chat/send
                           :command/data (cond-> {:text (str text)}
                                           bot (assoc :bot bot))}]]})

(defn- open-actions
  "The artifact of `artifacts/<path>`, a project's folder with or
   without its slash, under the bot's page or not."
  [path base bot]
  {:click [[:data/command {:command/kind :artifacts/open
                           :command/data (cond-> {:path (-> (str path)
                                                            (str/replace #"^/+" "/")
                                                            (as-> p (if (and (seq base) (str/starts-with? p (str base "/"))) (subs p (count base)) p))
                                                            (str/replace #"^/?artifacts/" "")
                                                            (str/replace #"/+$" ""))}
                                           bot (assoc :bot bot))}]
           [:ui/pane-step]]})

(defn- attrs
  "The attributes that are allowed, the ones with a URL checked; on a
   button `:send` and `:open` turned into the commands behind a click,
   for the `bot` of the block, the text it sends its title (a title of
   the model's own could hide it); on an input the `:name` in the
   block's `scope`, so that radio buttons of two answers are two
   groups."
  [tag m {:keys [scope base bot]}]
  (reduce (fn [acc [k v]]
            (cond
              (and (= :name k) (= :input tag) (string? v)) (assoc acc :name (str scope v))
              (and (= :send k) (= :button tag) (string? v)) (assoc acc :on (send-actions v bot) :title v)
              (and (= :open k) (= :button tag) (string? v)) (assoc acc :on (open-actions v base bot))
              (= :title k) (cond-> acc (and (string? v) (not (and (= :button tag) (contains? m :send))))
                             (assoc k v))
              (not (contains? attributes k)) acc
              (#{:href :src :poster} k) (if-let [v (url base v)]
                                          (cond-> (assoc acc k v)
                                            (and (= :href k) (= :a tag)) (assoc :target "_blank" :rel "noopener noreferrer"))
                                          acc)
              (= :style k) (if-let [v (style v (if (= :button tag) button-style-properties style-properties))]
                             (assoc acc :style v)
                             acc)
              (or (string? v) (number? v) (boolean? v)) (assoc acc k v)
              :else acc))
          {}
          m))

(defn- secret-field
  "The password field of `[:secret {:name :label}]`: the client's alias
   `:ui/secret-field`, which saves what is typed (`:secret/save`) for
   the `bot` of the block; nil for a name `${NAME}` could not name."
  [m bot]
  (let [secret-name (:name m)
        label (:label m)]
    (when (and (string? secret-name) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" secret-name))
      [:ui/secret-field (cond-> {:ui/name secret-name
                                 :ui/label (if (string? label) label secret-name)
                                 :ui/button [:i18n :secret/save]
                                 :ui/placeholder [:i18n :secret/placeholder]
                                 :ui/note [:i18n :secret/note]}
                          bot (assoc :ui/bot bot))])))

(defn- sign-in-link
  "The sign-in of `[:sign-in {:server :label}]`: a link to the page's
   start of the OAuth sign-in at the server's service, in a tab of its
   own, the bot of the block told when it is done; nil for a name no
   server could have."
  [m bot]
  (let [server-name (:server m)
        label (:label m)]
    (when (and (string? server-name) (re-matches #"[A-Za-z0-9_-]+" server-name))
      [:a.sign-in {:href (str "/mcp/oauth/start/" server-name (when bot (str "?bot=" (js/encodeURIComponent (str bot)))))
                   :target "_blank"
                   :rel "noopener"}
       (if (string? label) label [:i18n :mcp/sign-in-to {:name server-name}])])))

(defn- send-checked?
  "A button whose `:send` waits for the values ticked in its block."
  [base m]
  (and (= :button base)
       (string? (:send m))
       (str/includes? (:send m) checked-mark)))

(defn- printed
  "A value that is no hiccup as text, at most a few levels and items of
   it: a set nested a thousand deep would overflow the printer."
  [value]
  (binding [*print-level* 3
            *print-length* 20]
    (pr-str value)))

(defn sanitize
  "The hiccup with only what the allow-lists know; an input of another
   type than checkbox or radio goes, and so does what is nested deeper
   than `max-depth`. A button in a label is a span: clicking anywhere on
   the label would click it, a label as big as the screen a button too.
   `scope` prefixes the names of the inputs; with `answered?` a button
   that sends and an input are disabled; `base` is the bot's page,
   under which its files are served, `bot` its id for the commands."
  ([hiccup]
   (sanitize hiccup {}))
  ([hiccup {:keys [depth in-label? answered?] :or {depth 0} :as opts}]
   (let [opts (merge {:scope "" :base ""} opts)
         deeper (assoc opts :depth (inc depth))]
     (cond
       (< max-depth depth)
       nil

       (and (vector? hiccup) (keyword? (first hiccup)))
       (let [[tag & body] hiccup
             base (base-tag tag)
             ;; `:ui/div` is no div: a tag with a namespace names an alias of the client
             allowed? (and (nil? (namespace tag)) (contains? tags base))
             [m body] (if (map? (first body)) [(first body) (rest body)] [{} body])
             allowed? (and allowed? (not (and in-label? (= :button base))))
             children (map #(sanitize % (cond-> deeper (= :label base) (assoc :in-label? true))) body)]
         (cond
           (and (nil? (namespace tag)) (= :secret base) (not in-label?))
           (secret-field m (:bot opts))

           (and (nil? (namespace tag)) (= :sign-in base) (not in-label?))
           (sign-in-link m (:bot opts))

           (and allowed? (= :input base) (not (input-types (:type m))))
           nil

           (and allowed? answered? (or (= :input base) (and (= :button base) (string? (:send m)))))
           (into [(without-id tag) (-> (attrs base m opts)
                                       (dissoc :on)
                                       (assoc :disabled true))]
                 children)

           (and allowed? (send-checked? base m))
           (into [:ui/send-checked (cond-> (-> (attrs base (dissoc m :send :open :title) opts)
                                               (assoc :ui/send (:send m) :title (:send m)))
                                     (:bot opts) (assoc :ui/bot (:bot opts)))]
                 children)

           allowed?
           (into [(without-id tag) (attrs base m opts)] children)

           ;; a tag that is not allowed is a span of its children
           :else
           (into [:span (attrs :span m opts)] children)))

       ;; eager: what is lazy would run after `block`'s try, in the page's walk
       (sequential? hiccup) (doall (map #(sanitize % deeper) hiccup))
       (or (string? hiccup) (number? hiccup) (nil? hiccup)) hiccup
       (keyword? hiccup) (name hiccup)
       :else (printed hiccup)))))

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
  "One block as a part of the page, or as code with what went wrong. The
   names of its inputs get the block's own prefix, from its text;
   `:answered?` for a block of a message the person has answered since;
   `:base` the bot's page, `:bot` its id."
  ([text]
   (block text {}))
  ([text {:keys [answered? base bot]}]
   (let [{:keys [hiccup error]} (parse text)
         [part error] (if error
                        [nil error]
                        ;; whatever the model wrote, the page goes on: a block that fails is code
                        (try
                          [(sanitize hiccup {:scope (str "b" (abs (hash text)) "-") :answered? answered? :base base :bot bot}) nil]
                          (catch :default e
                            [nil (or (ex-message e) (str e))])))]
     (if error
       [:div.genui.genui-error
        [:p.error [:i18n :genui/rejected {:error error}]]
        [:pre [:code text]]]
       (if answered?
         [:div.genui.answered part]
         [:div.genui part])))))

(comment
  (block "[:ul [:li [:audio {:src \"artifacts/card/a.mp3\" :controls true}] [:button {:send \"Take a\"} \"Take this one\"]]]")
  (block "[:div {:onclick \"x\"} [:script \"alert(1)\"] [:input {:type \"text\"}]]")
  (block "[:div [:input {:type \"checkbox\" :value \"a\"}] [:button {:send \"On: {checked}\"} \"Go\"]]")
  (block "[:div")
  )
