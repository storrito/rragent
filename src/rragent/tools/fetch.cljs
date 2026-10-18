(ns rragent.tools.fetch
  "The `fetch` tool: one HTTP request, the answer's status, a few
   headers and its text, cut at a megabyte, so that a job reads a page
   or an API without curl's quirks and the transcript gets a clean
   result. `http` and `https` only."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [clojure.string :as str]))

(def max-chars
  (* 1024 1024))

(def timeout-ms
  60000)

(def kept-headers
  ["content-type" "content-length" "location" "etag" "last-modified" "retry-after"])

(defn- check-url
  [url]
  (let [parsed (try (js/URL. url) (catch :default _ nil))]
    (when-not (and parsed (contains? #{"http:" "https:"} (.-protocol parsed)))
      (throw (js/Error. (str "fetch takes an http or https URL, not " url))))
    parsed))

(defn ^:async request
  "The request made: `{:status :headers :text :truncated}`."
  [{:keys [url method headers body]}]
  (check-url url)
  (let [response (await (js/fetch url #js {:method (or method "GET")
                                           :headers (or headers #js {})
                                           :body (or body js/undefined)
                                           :redirect "follow"
                                           :signal (js/AbortSignal.timeout timeout-ms)}))
        text (await (.text response))
        truncated? (< max-chars (count text))]
    {:status (.-status response)
     :headers (into {} (keep (fn [name]
                               (when-let [value (.get (.-headers response) name)]
                                 [name value])))
                    kept-headers)
     :text (if truncated? (subs text 0 max-chars) text)
     :truncated truncated?}))

(defn- result
  [{:keys [status headers text truncated]}]
  (let [head (str "HTTP " status
                  (when (seq headers)
                    (str "\n" (str/join "\n" (map (fn [[k v]] (str k ": " v)) headers))))
                  (when truncated (str "\n(the body was cut at " max-chars " characters)")))]
    #js {:output #js [#js {:type "text" :text (str head "\n\n" text)}]
         :details #js {:status status :truncated truncated}}))

(def tool
  (defineTool
   #js {:name "fetch"
        :description (str "One HTTP request. Answers the status, a few headers and the body as text, cut at a megabyte. "
                          "GET by default; `headers` and `body` for a POST or PUT.")
        :parameters (.Object Type #js {:url (.String Type #js {:description "An http or https URL"})
                                       :method (.Optional Type (.String Type #js {:description "GET, POST, PUT, PATCH, DELETE or HEAD; GET by default"}))
                                       :headers (.Optional Type (.Record Type (.String Type) (.String Type) #js {:description "Request headers"}))
                                       :body (.Optional Type (.String Type #js {:description "The request body, for POST, PUT and PATCH"}))})
        :execute (^:async fn [args _api _call-context]
                   (result (await (request {:url (.-url args)
                                            :method (.-method args)
                                            :headers (.-headers args)
                                            :body (.-body args)}))))}))
