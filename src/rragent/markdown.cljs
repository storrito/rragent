(ns rragent.markdown
  "The answer of the model, markdown, as hiccup. markdown-it parses the
   text into its token stream and the walk below turns the stream into
   hiccup, so that the page stays data a frame carries and Replicant
   renders: every piece of text is a text node, which Replicant
   escapes. HTML is off, a tag the model writes is text; the typographer
   is off, the text stays the model's; the linkifier is on, a bare URL
   is a link. markdown-it drops a link with a `javascript:` or another
   unsafe scheme while it parses, so none reaches the walk. A token the
   walk does not know is its text, nothing of the answer is lost. A
   single newline is a line break, the way a chat reads it and the way
   the plain text showed before.

   The hiccup of a text is kept, by the text, in a cache: the page is
   rendered again for every frame, a parse takes about a millisecond
   per answer, and the answers of the transcript do not change."
  (:require ["markdown-it" :default MarkdownIt]
            [clojure.string :as str]))

(def ^:private parser
  (MarkdownIt. #js {:html false
                    :linkify true
                    :typographer false}))

(defn- style-map
  "`text-align:center`, the style markdown-it puts on a table cell, as
   the map Replicant takes."
  [style]
  (into {}
        (for [rule (str/split (str style) #";")
              :let [[k v] (map str/trim (str/split rule #":" 2))]
              :when (and (seq k) (seq v))]
          [(keyword k) v])))

(defn- attrs
  "The attributes of a token as a map."
  [^js token]
  (into {}
        (map (fn [pair]
               (let [k (aget pair 0)
                     v (aget pair 1)]
                 (if (= "style" k)
                   [:style (style-map v)]
                   [(keyword k) v]))))
        (array-seq (or (.-attrs token) #js []))))

(defn- element
  [tag attrs children]
  (into (if (seq attrs) [tag attrs] [tag]) children))

(defn- open-attrs
  "The attributes of an opening token: a link opens in a new tab,
   without a referrer; its href stays as the model wrote it, a relative
   one included."
  [^js token]
  (let [given (attrs token)]
    (if (= "link_open" (.-type token))
      (assoc given :target "_blank" :rel "noopener noreferrer")
      given)))

(defn- image-src
  "A picture the agent names by its upload, `uploads/<name>`, from the
   path the page serves it on."
  [src]
  (if (str/starts-with? (str src) "uploads/")
    (str "/" src)
    src))

(declare plain-text)

(defn- token-text
  [^js token]
  (case (.-type token)
    ("text" "text_special" "code_inline") (.-content token)
    "image" (plain-text (.-children token))
    ("softbreak" "hardbreak") "\n"
    ""))

(defn- plain-text
  "The text of inline tokens without their markup, the alt of an image."
  [tokens]
  (apply str (map token-text (array-seq (or tokens #js [])))))

(defn- image
  [^js token]
  (let [{:keys [src title]} (attrs token)]
    [:img (cond-> {:src (image-src src)
                   :alt (plain-text (.-children token))}
            (seq title) (assoc :title title))]))

(defn- language
  "The first word of a fence's info string, `clojure` of
   \"```clojure\"."
  [info]
  (first (str/split (str/trim (str info)) #"\s+")))

(defn- fence
  "The code block with its language as a class, `language-clojure`."
  [^js token]
  (let [lang (language (.-info token))]
    [:pre (element :code
                   (when (seq lang) {:class (str "language-" lang)})
                   [(.-content token)])]))

(defn- frame
  "A frame of the stack while the walk is inside an element: the tag
   and attributes the closing token completes, and the children so far.
   A hidden token (the paragraph of a tight list item) opens a frame
   without a tag, whose children go to the parent."
  [^js token]
  (let [tag (str (.-tag token))]
    (if (or (.-hidden token) (str/blank? tag))
      {:children []}
      {:tag (keyword tag)
       :attrs (open-attrs token)
       :children []})))

(defn- push-child
  [stack child]
  (update stack (dec (count stack)) update :children conj child))

(defn- push-text
  "A text as a child, unless it is empty: markdown-it leaves empty text
   tokens around nested markup."
  [stack text]
  (if (seq text)
    (push-child stack text)
    stack))

(defn- close
  [stack]
  (let [{:keys [tag attrs children]} (peek stack)
        stack (pop stack)]
    (if tag
      (push-child stack (element tag attrs children))
      (reduce push-child stack children))))

(defn- in-item?
  "Whether the walk is at the text of a list item: the paragraph on top
   of the stack is the item's."
  [stack]
  (= :li (:tag (nth stack (- (count stack) 2) nil))))

(defn- task
  "`[ ]` or `[x]` at the start of a list item's text: whether the item is
   a task, and whether it is done."
  [^js tokens]
  (let [first-token (aget tokens 0)]
    (when (and first-token (= "text" (.-type first-token)))
      (when-let [[_ mark] (re-find #"^\[([ xX])\] " (.-content first-token))]
        {:done? (not= " " mark)
         :text (subs (.-content first-token) 4)}))))

(declare walk)

(defn- task-item
  "The text of a task item as a checkbox that cannot be changed from the
   page, and the text after the mark; the item gets the class `task`."
  [stack tokens {:keys [done? text]}]
  (-> stack
      (update (- (count stack) 2) assoc-in [:attrs :class] "task")
      (push-child [:input (cond-> {:type "checkbox" :disabled true}
                            done? (assoc :checked true))])
      (push-text text)
      (walk (.slice tokens 1))))

(defn- inline
  [stack ^js token]
  (let [children (or (.-children token) #js [])]
    (if-let [found (when (in-item? stack) (task children))]
      (task-item stack children found)
      (walk stack children))))

(defn- leaf
  "A token that is no opening or closing one, as a child of the frame on
   top of the stack."
  [stack ^js token]
  (case (.-type token)
    "inline" (inline stack token)
    ("text" "text_special") (push-text stack (.-content token))
    "code_inline" (push-child stack [:code (.-content token)])
    "fence" (push-child stack (fence token))
    "code_block" (push-child stack [:pre [:code (.-content token)]])
    "hr" (push-child stack [:hr])
    ("softbreak" "hardbreak") (push-child stack [:br])
    "image" (push-child stack (image token))
    (push-text stack (str (.-content token)))))

(defn- step
  [stack ^js token]
  (case (.-nesting token)
    1 (conj stack (frame token))
    -1 (close stack)
    (leaf stack token)))

(defn- walk
  [stack tokens]
  (reduce step stack (array-seq tokens)))

(defn from-tokens
  "The hiccup of a token stream of markdown-it: a vector of the block
   elements."
  [tokens]
  (loop [stack (walk [{:children []}] tokens)]
    (if (= 1 (count stack))
      (:children (peek stack))
      (recur (close stack)))))

(defn parse
  [text]
  (from-tokens (.parse parser (str text) #js {})))

(def ^:private cache-size
  "Entries the cache holds before it is emptied: every chunk of a
   streamed answer is a text of its own, a long transcript has many."
  1024)

(defonce ^:private cache
  (js/Map.))

(defn hiccup
  "The hiccup of a markdown `text`, a vector of its block elements; the
   one of the last texts asked for again from the cache."
  [text]
  (let [text (str text)]
    (or (.get cache text)
        (let [result (parse text)]
          (when (<= cache-size (.-size cache))
            (.clear cache))
          (.set cache text result)
          result))))

(comment
  (parse "**Bold** and a [link](https://example.com).")
  (parse "- [ ] todo\n- [x] done")
  (parse "| a | b |\n|:--|--:|\n| 1 | 2 |")
  (parse "<script>alert(1)</script>")
  )
