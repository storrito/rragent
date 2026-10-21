(ns rragent.hooks
  "The agent's hooks: addresses other systems call to start a turn.
   Each has a name, a purpose and a secret, kept in the document
   `rragent.hooks` of the bot's conversation; its address is
   `/hook/<name>/<secret>` on the machine's page, which tries the
   call on every bot: the secret says whose it is. A call with the right secret submits the call's body
   as an input, as if the person had typed it, with the headers that
   say who sent it, the definition read again first
   (`rragent.reload`)."
  (:require ["node:crypto" :as crypto]
            ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.reload :as reload]))

(def Hooks
  (defineDoc #js {:kind "rragent.hooks"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "initial"
                  :initial (fn [] #js {})}))

(def max-body-chars
  20000)

(defn path
  [name secret]
  (str "/hook/" name "/" secret))

(defn address
  "The hook's full address when `RRAGENT_URL` names the page's, else
   its path."
  [name secret]
  (str (str/replace (str (aget (.-env js/process) "RRAGENT_URL")) #"/+$" "") (path name secret)))

(defn ^:async create!
  "The hook `name` made (or its secret renewed) with `purpose`. Answers
   its address."
  [api name purpose call-context]
  (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
    (throw (js/Error. "a hook's name is lowercase letters, digits and dashes")))
  (let [secret (.toString (crypto/randomBytes 16) "hex")]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Hooks (.-conversationId api)))]
                        (aset doc name #js {:secret secret :purpose purpose :createdAt (js/Date.now)})))
                    call-context))
    (address name secret)))

(defn ^:async delete!
  [api name call-context]
  (let [found (atom false)]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Hooks (.-conversationId api)))]
                        (reset! found (some? (aget doc name)))
                        (js-delete doc name)))
                    call-context))
    @found))

(defn listed
  [hooks]
  (->> (js/Object.entries (or hooks #js {}))
       (map (fn [[name h]] (str name ": " (address name (.-secret h)) " (" (.-purpose h) ")")))
       (str/join "\n")))

(defn- message
  [name purpose headers body]
  (let [body (str body)
        cut? (< max-body-chars (count body))]
    (str "Hook \"" name "\" (" purpose ") was called.\n"
         (when (seq headers)
           (str "Headers: " (str/join ", " (map (fn [[k v]] (str k ": " v)) headers)) "\n"))
         "Body:\n" (if cut? (str (subs body 0 max-body-chars) "\n(cut at " max-body-chars " characters)") body))))

(def kept-headers
  ["content-type" "user-agent" "x-github-event" "x-hook-source"])

(defn ^:async receive!
  "A call of the hook `name` with `secret`: the input submitted when
   the secret is the hook's, answering `{:ok true :submission id}`;
   `{:ok false}` otherwise, for a 403. `headers` is a map of lowercase
   names."
  [{:keys [harness root work]} name secret headers body context]
  (let [hooks (await (.snapshot harness Hooks (.-id root) context))
        hook (some-> hooks (aget name))]
    (if (and hook (= secret (.-secret hook)))
      (let [kept (into {} (keep (fn [h] (when-let [v (get headers h)] [h v]))) kept-headers)
            _ (await (reload/reload! root work context))
            submission (await (.submit root #js {:type "input"
                                                  :content (message name (.-purpose hook) kept body)
                                                  :whenBusy "followUp"
                                                  :entry #js {:kind "rragent.hook" :data #js {:name name}}}
                                       context))]
        {:ok true :submission (.-id submission)})
      {:ok false})))
