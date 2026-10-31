(ns rragent.rframes.http
  "Routing over a register and file responses with range requests, on
   node:http. The port of rframes.http: the world, the register entries
   and the ring-shaped request and response maps are the original's, the
   adapter at the end turns a Node request into the ring request and
   writes the ring response back. A handler may answer a promise of the
   world, so the router is asynchronous.

   A register is a sequence of entries. An entry with a route looks like

       {:ring/route [:get \"/project/*\"] :ring/handler #'handler}

   and its handler is a function `w -> w` of the world: it reads
   `:ring/request` and `:ring/route-params` and adds `:ring/response`."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "not found"})

(defn match
  "The route params if `route` matches the request, otherwise nil. A path
   that ends with /* matches its subtree, the rest of the path is under :*."
  [[method path] {:keys [request-method uri]}]
  (when (= method request-method)
    (if (str/ends-with? path "/*")
      (let [prefix (subs path 0 (dec (count path)))]
        (when (str/starts-with? uri prefix)
          {:* (subs uri (count prefix))}))
      (when (= path uri)
        {}))))

(defn- ^:async respond-with
  "The response of the first of the matching entries, `[entry route-params]`
   pairs, whose handler answers one; not-found when none does."
  [w matches]
  (if-let [[{handler :ring/handler} route-params] (first matches)]
    (if-let [response (:ring/response (await (handler (assoc w :ring/route-params route-params))))]
      response
      (await (respond-with w (rest matches))))
    not-found))

(defn ring-handler
  "The ring handler over `get-register`, a function from a ring request to
   a promise of the response. The register is consulted per request, so a
   redefined entry is live at once. The first matching entry wins."
  [get-register]
  (fn [request]
    (let [w {:ring/request request
             :system/get-register get-register}]
      (respond-with w (keep (fn [{:keys [ring/route] :as entry}]
                              (when-let [route-params (and route (match route request))]
                                [entry route-params]))
                            (get-register))))))

(def content-types
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "application/javascript; charset=utf-8"
   "mjs" "application/javascript; charset=utf-8"
   "wasm" "application/wasm"
   "json" "application/json; charset=utf-8"
   "svg" "image/svg+xml"
   "png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "webp" "image/webp"
   "avif" "image/avif"
   "mp4" "video/mp4"
   "m4v" "video/mp4"
   "webm" "video/webm"
   "mov" "video/quicktime"
   "mp3" "audio/mpeg"
   "m4a" "audio/mp4"
   "aac" "audio/aac"
   "wav" "audio/wav"
   "ogg" "audio/ogg"
   "woff" "font/woff"
   "woff2" "font/woff2"
   "ttf" "font/ttf"
   "otf" "font/otf"
   "txt" "text/plain; charset=utf-8"
   "vtt" "text/vtt; charset=utf-8"})

(defn content-type
  [file-name]
  (get content-types
       (str/lower-case (or (second (re-find #"\.([^./]+)$" file-name)) ""))
       "application/octet-stream"))

(defn- real-path
  "The canonical path, nil when there is nothing at `p`."
  [p]
  (try
    (fs/realpathSync p)
    (catch :default _
      nil)))

(defn regular-file?
  [file]
  (try
    (.isFile (fs/statSync file))
    (catch :default _
      false)))

(defn resolve-file
  "The file for the URL path `subpath` below `root`, or nil if it leaves the
   root or is not a regular file."
  [root subpath]
  (let [decoded (try
                  (js/decodeURIComponent (str/replace subpath "+" "%2B"))
                  (catch :default _
                    nil))
        root (real-path root)
        file (when (and root (seq decoded))
               (real-path (path/resolve root decoded)))]
    (when (and file
               (str/starts-with? file (str root path/sep))
               (regular-file? file))
      file)))

(defn parse-range
  "The inclusive byte positions [start end] of a Range header for a file of
   `length` bytes, or nil if the header does not describe one satisfiable
   range."
  [header length]
  (when-let [[_ from to] (re-matches #"bytes=(\d*)-(\d*)" (str/trim (or header "")))]
    (let [last-byte (dec length)]
      (cond
        (and (seq from) (seq to))
        (let [start (parse-long from)
              end (min (parse-long to) last-byte)]
          (when (<= start end)
            [start end]))

        (seq from)
        (let [start (parse-long from)]
          (when (<= start last-byte)
            [start last-byte]))

        (seq to)
        (let [suffix (parse-long to)]
          (when (and (pos? suffix) (pos? length))
            [(max 0 (- length suffix)) last-byte]))))))

(def max-range-bytes
  "A range response carries at most this many bytes. The browser asks for the
   rest with its next range request."
  (* 4 1024 1024))

(defn unsatisfiable?
  "Whether a Range header asks for bytes a file of `length` bytes does not
   have: well-formed, `bytes=` with a position, and yet no range of
   `parse-range`. Such a request gets a 416; a malformed header, or one
   whose first byte lies behind its last, is ignored instead (RFC 9110)."
  [header length]
  (boolean
   (when-let [[_ from to] (re-matches #"bytes=(\d*)-(\d*)" (str/trim (or header "")))]
     (and (or (seq from) (seq to))
          (not (and (seq from) (seq to) (< (parse-long to) (parse-long from))))
          (nil? (parse-range header length))))))

(defn file-response
  "Serves `file`, a path. A range request gets a 206, which <video> and
   <audio> need for seeking, one for bytes the file does not have a 416.
   The body is a read stream of the file; a HEAD gets the headers alone,
   the Content-Length of the body it does not carry among them."
  [request file]
  (let [length (.-size (fs/statSync file))
        head? (= :head (:request-method request))
        range (get-in request [:headers "range"])
        headers {"Content-Type" (content-type (path/basename file))
                 "Accept-Ranges" "bytes"
                 "Cache-Control" "no-cache"}
        stream (fn [options]
                 (when-not head?
                   (if options (fs/createReadStream file options) (fs/createReadStream file))))]
    (if-let [[start end] (parse-range range length)]
      (let [end (min end (+ start (dec max-range-bytes)))]
        {:status 206
         :headers (assoc headers
                         "Content-Range" (str "bytes " start "-" end "/" length)
                         "Content-Length" (inc (- end start)))
         :body (stream #js {:start start :end end})})
      (if (unsatisfiable? range length)
        {:status 416
         :headers (assoc headers "Content-Range" (str "bytes */" length))
         :body ""}
        {:status 200
         :headers (assoc headers "Content-Length" length)
         :body (stream nil)}))))

(defn files
  "A register entry that serves files on `path`: the one file `root` for a
   plain path, the files below the folder `root` for a path that ends with
   /*. The counterpart of the classpath resources of the Clojure original:
   a bundle has no classpath, the built client lies in a folder. `root`
   may be a vector of roots: the first that has the file serves it (the
   built folder, else the sources under the dev server).

       (files \"/app.css\" \"dist/public/app.css\")
       (files \"/assets/*\" \"dist/public/assets\")
       (files \"/effects/*\" [\"dist/public/effects\" \"public/effects\"])"
  [path root]
  {:ring/route [:get path]
   :ring/handler (fn [w]
                   (let [subpath (get-in w [:ring/route-params :*])
                         roots (if (vector? root) root [root])
                         file (cond
                                (nil? subpath)
                                (some #(when (regular-file? %) %) roots)

                                (or (str/blank? subpath) (str/includes? subpath ".."))
                                nil

                                :else
                                (some #(resolve-file % subpath) roots))]

                     (assoc w
                            :ring/response
                            (if file
                              (file-response (:ring/request w) file)
                              not-found))))})

(defn request-map
  "The ring request of a Node request: the method, the path and the query
   string, the headers (Node has them in lower case) and the body, the
   request stream itself."
  [^js req]
  (let [[uri query] (str/split (str (.-url req)) #"\?" 2)]
    {:request-method (keyword (str/lower-case (.-method req)))
     :uri uri
     :query-string query
     :headers (into {}
                    (map (fn [pair] [(aget pair 0) (aget pair 1)]))
                    (js/Object.entries (.-headers req)))
     :scheme :http
     :body req}))

(defn respond!
  "Writes a ring response to the Node response. A body is a string, a
   Buffer or a readable stream. A response with `:on-open` is a held
   connection, the SSE stream: the head goes out at once, `on-open` gets
   the channel with `:send!`, and `:on-close` runs when the browser is
   gone."
  [^js res {:keys [status headers body on-open on-close]}]
  (let [stream? (and body (fn? (.-pipe body)))
        headers (cond-> headers
                  (and body (not stream?)) (assoc "Content-Length" (js/Buffer.byteLength body)))]
    (.writeHead res status (clj->js headers))
    (cond
      on-open
      (do (.flushHeaders res)
          (when on-close
            (.on res "close" (fn [] (on-close))))
          (on-open {:send! (fn [data] (.write res data))}))

      stream?
      (.pipe body res)

      :else
      (.end res (or body "")))))

(defn node-handler
  "The request listener of a node:http server over a ring handler, which
   may answer a promise. A handler that throws answers 500, with a line on
   stderr."
  [handler]
  (fn [^js req ^js res]
    (-> (js/Promise.resolve)
        ;; a handler that throws before it answers a promise rejects here too
        (.then (fn [] (handler (request-map req))))
        (.then (fn [response] (respond! res response)))
        (.catch (fn [error]
                  (js/console.error "[http]" (.-url req) (str error))
                  (when-not (.-headersSent res)
                    (respond! res {:status 500
                                   :headers {"Content-Type" "text/plain"}
                                   :body "internal error"})))))))

(defn ^:async listen
  "A node:http server over `handler`, listening on `port`; 0 takes a free
   one, `(.-port (.address server))` names it. On every interface unless
   `host` names one."
  ([handler port]
   (listen handler port nil))
  ([handler port host]
   (let [server (http/createServer (node-handler handler))]
     (await (js/Promise. (fn [resolve reject]
                           (.once server "error" reject)
                           (if host
                             (.listen server port host resolve)
                             (.listen server port resolve)))))
     server)))

(comment
  (match [:get "/project/*"] {:request-method :get :uri "/project/assets/a.mp4"})
  (parse-range "bytes=0-" 1000)
  (parse-range "bytes=-500" 1000)
  )
