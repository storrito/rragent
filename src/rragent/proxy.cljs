(ns rragent.proxy
  "One request passed on to a process on a local port and its answer
   streamed back: the page of an artifact project in the iframe of the
   pane (`rragent.projects`). The port of the proxy of pchat.projects
   to node:http: the upstream response is the body of the ring
   response, a readable stream the adapter pipes (`rragent.rframes.http/respond!`),
   so a stream the project holds open comes through as it comes."
  (:require ["node:http" :as http]
            [clojure.string :as str]))

(def skipped-request-headers
  #{"host" "content-length" "connection" "transfer-encoding" "expect" "upgrade"})

(def skipped-response-headers
  #{"transfer-encoding" "content-length" "connection"})

(defn request-headers
  "The headers passed on, without the ones of the connection itself."
  [headers]
  (into {}
        (remove (fn [[k _]] (skipped-request-headers (str/lower-case (str k)))))
        headers))

(defn response-headers
  [^js headers]
  (into {}
        (keep (fn [[k v]]
                (when-not (skipped-response-headers (str/lower-case (str k)))
                  [k (if (array? v) (str/join ", " v) (str v))])))
        (js/Object.entries headers)))

(defn- failed
  [error]
  {:status 502
   :headers {"Content-Type" "text/plain"}
   :body (str "The artifact did not answer: " (or (.-message error) (str error)))})

(defn request
  "The ring request sent to `127.0.0.1:port` at `/<rest>` with its
   query string; a promise of the ring response, the upstream response
   streaming as its body. The request's body, a readable stream when
   there is one, is piped on."
  [port rest {:keys [request-method query-string headers body]}]
  (js/Promise.
   (fn [resolve]
     (let [upstream (http/request #js {:host "127.0.0.1"
                                       :port port
                                       :method (str/upper-case (name (or request-method :get)))
                                       :path (str "/" rest (when (seq query-string) (str "?" query-string)))
                                       :headers (clj->js (request-headers headers))}
                                  (fn [^js response]
                                    (resolve {:status (.-statusCode response)
                                              :headers (response-headers (.-headers response))
                                              :body response})))]
       (.on upstream "error" (fn [error] (resolve (failed error))))
       (if (and body (fn? (.-pipe body)))
         (.pipe body upstream)
         (.end upstream))))))
