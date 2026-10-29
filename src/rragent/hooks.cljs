(ns rragent.hooks
  "The agent's hooks: addresses other systems call to start a run.
   Each has a name, a purpose, a secret and a mode, kept in the
   document `rragent.hooks` of the bot's conversation; its address is
   `/hook/<name>/<secret>` on the machine's page, which tries the
   call on every bot: the secret says whose it is. A call with the
   right secret starts a run of the bot (`rragent.runs`) with the
   call's body as its text and the headers that say who sent it, the
   definition read again first (`rragent.reload`); a hook of the mode
   `message` submits the text as an input of the bot's own
   conversation instead, as if the person had typed it."
  (:require ["node:crypto" :as crypto]
            ["@earendil-works/pi-durable" :refer [defineDoc]]
            [clojure.string :as str]
            [rragent.reload :as reload]
            [rragent.runs :as runs]))

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

(def modes
  "`run`, a call starts a run of the bot; `message`, a call reaches the
   bot's own conversation."
  #{"run" "message"})

(defn ^:async create!
  "The hook `name` made (or its secret renewed) with `purpose` and
   `mode` (`run` without one). Answers its address."
  [api name purpose mode call-context]
  (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
    (throw (js/Error. "a hook's name is lowercase letters, digits and dashes")))
  (when-not (contains? modes (or mode "run"))
    (throw (js/Error. "a hook's mode is run or message")))
  (let [secret (.toString (crypto/randomBytes 16) "hex")]
    (await (.commit api
                    (^:async fn [tx]
                      (let [doc (await (.doc tx Hooks (.-conversationId api)))]
                        (aset doc name #js {:secret secret :purpose purpose :mode (or mode "run") :createdAt (js/Date.now)})))
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
       (map (fn [[name h]] (str name ": " (address name (.-secret h)) " (" (.-purpose h) ")" (when (= "message" (.-mode h)) " · mode message"))))
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
  ["content-type" "user-agent" "x-github-event" "x-hook-source" "x-storrito-event"])

(def toolkit-names
  "The names people know the services by, by Composio toolkit slug;
   another slug is shown capitalized."
  {"googledrive" "Google Drive" "googlesheets" "Google Sheets" "googledocs" "Google Docs"
   "googlecalendar" "Google Calendar" "gmail" "Gmail" "youtube" "YouTube" "github" "GitHub"
   "hubspot" "HubSpot" "onedrive" "OneDrive" "tiktok" "TikTok" "linkedin" "LinkedIn"})

(defn- subject
  "What the event is about, when its payload names it: a file's name, a
   title, a subject."
  [data]
  (when (map? data)
    (some (fn [path]
            (let [v (get-in data path)]
              (when (string? v) (not-empty v))))
          [[:file :name] [:name] [:title] [:subject] [:filename] [:file_name] [:message :subject]])))

(defn- parse-json
  [text]
  (try
    (js->clj (js/JSON.parse (str text)) :keywordize-keys true)
    (catch :default _
      nil)))

(defn event-line
  "The line a person reads about a Storrito integration trigger event
   in `body`, `Google Drive · file created · 4.txt`: the service and
   the event from the trigger slug the event names
   (`metadata.trigger_slug`), what the payload is about after them.
   nil for a body that is no such event."
  [body]
  (let [parsed (parse-json body)
        slug (when (map? parsed) (get-in parsed [:metadata :trigger_slug]))]
    (when (and (string? slug) (not (str/blank? slug)))
      (let [[toolkit & words] (str/split (str/lower-case slug) #"_+")
            words (cond-> (vec words)
                    (= "trigger" (last words)) pop)]
        (str/join " · " (remove str/blank? [(or (toolkit-names toolkit) (str/capitalize toolkit))
                                           (str/join " " words)
                                           (subject (:data parsed))]))))))

(defn entry-data
  "What the page shows of a call without reading its body: the hook's
   name, the body's size and, for a Storrito integration trigger event,
   its line (`event-line`)."
  [name body]
  (let [body (str body)
        event (event-line body)]
    (cond-> {:name name :bytes (count body)}
      event (assoc :event event))))

(defn ^:async receive!
  "A call of the hook `name` with `secret` on the bot's `conversation`
   (the `root` without one), when the secret is the hook's: a run of
   the bot started with the call as its text, `{:ok true :run id}`,
   the task's id; for a hook of the mode `message` the input submitted
   to the bot's conversation, `{:ok true :submission id}`. `{:ok
   false}` otherwise, for a 403. `headers` is a map of lowercase
   names."
  [{:keys [harness conversation root]} name secret headers body context]
  (let [conversation (or conversation root)
        hooks (await (.snapshot harness Hooks (.-id conversation) context))
        hook (some-> hooks (aget name))]
    (if (and hook (= secret (.-secret hook)))
      (let [kept (into {} (keep (fn [h] (when-let [v (get headers h)] [h v]))) kept-headers)
            content (message name (.-purpose hook) kept body)
            data (entry-data name body)]
        (await (reload/reload! conversation context))
        (if (= "message" (.-mode hook))
          (let [submission (await (.submit conversation #js {:type "input"
                                                      :content content
                                                      :whenBusy "followUp"
                                                      :entry #js {:kind "rragent.hook" :data (clj->js data)}}
                                           context))]
            {:ok true :submission (.-id submission)})
          (let [line (or (:event data) (runs/short-line (.-purpose hook)))
                ;; the event's line, else the hook's purpose: words for a person
                trigger (cond-> {:kind "hook" :name name}
                          line (assoc :line line))
                run (await (runs/start! conversation {:trigger trigger :content content} context))]
            {:ok true :run run})))
      {:ok false})))
