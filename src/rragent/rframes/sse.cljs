(ns rragent.rframes.sse
  "The SSE view connections: the connection registry, the coalesced refresh
   and the stream handler that holds a POST open and pushes one encoded
   frame per refresh. Frame encoding belongs to the caller, this namespace
   only knows channels and timing. The port of rframes.sse: the refresh
   waits on a timer instead of a thread, the gzip of a connection is a
   node:zlib stream, and the lock of a connection is a promise chain."
  (:require ["node:zlib" :as zlib]
            [clojure.string :as str]))

(defonce ^:private conns
  (atom {}))

(defonce ^:private next-conn-id
  (atom 0))

(defn connections
  "How many pages hold a view stream open right now."
  []
  (count @conns))

(defn refresh-all!
  []
  (doseq [{:keys [render]} (vals @conns)]
    (render)))

(defonce ^:private refresh-pending?
  (atom false))

(defn schedule-refresh!
  "A refresh of every connection, coalesced to at most 10 per second."
  []
  (when (compare-and-set! refresh-pending? false true)
    (js/setTimeout (fn []
                     (reset! refresh-pending? false)
                     (refresh-all!))
                   100)))

(defn watch!
  "Refreshes every connection after a change of the atom `db`. With this
   watch in place, mutating the atom is all a command has to do to update
   every open page."
  [db]
  (add-watch db ::refresh
             (fn [_key _ref old-value new-value]
               (when (not= old-value new-value)
                 (schedule-refresh!)))))

(defn unwatch!
  "The watch of `watch!` taken off the atom `db` again."
  [db]
  (remove-watch db ::refresh))

(def gzip?
  "Gzip the stream (default). Every frame is compressed against the frames
   before it, so a frame that barely differs from the last one costs little.
   RFRAMES_SSE_GZIP=0 turns it off, for proxies that buffer encoded streams."
  (not= "0" (aget (.-env js/process) "RFRAMES_SSE_GZIP")))

(defn- accepts-gzip?
  [request]
  (str/includes? (get-in request [:headers "accept-encoding"] "") "gzip"))

(defn gzip-encoder
  "A function from a frame string to a promise of its compressed bytes. The
   gzip stream is never finished, every frame ends with a sync flush, and
   the frames are answered in the order they came."
  []
  (let [gzip (zlib/createGzip)
        chunks (atom [])
        queue (atom (js/Promise.resolve nil))]
    (.on gzip "data" (fn [chunk] (swap! chunks conj chunk)))
    (fn [frame]
      (let [compressed (.then @queue
                              (fn []
                                (js/Promise. (fn [resolve]
                                               (.write gzip frame "utf8")
                                               (.flush gzip (.-Z_SYNC_FLUSH zlib/constants)
                                                       (fn []
                                                         (let [out (js/Buffer.concat (to-array @chunks))]
                                                           (reset! chunks [])
                                                           (resolve out))))))))]
        (reset! queue compressed)
        compressed))))

(defn- view
  [render-fn w]
  (try
    (render-fn w)
    (catch :default e
      (js/console.error "[render]" (str e))
      nil)))

(defn stream-handler
  "A handler `w -> w` that holds an SSE view connection. `:render/fn` is
   `w -> view`, called per refresh with the world of the connection, which
   is captured at connect. `encode` turns a view into a frame string, nil
   skips the frame. The response carries `:on-open` and `:on-close` for the
   adapter (`rragent.rframes.http/respond!`)."
  [{render-fn :render/fn} encode]
  (fn [w]
    (let [request (:ring/request w)
          conn-id (swap! next-conn-id inc)
          w (assoc w :conn-id conn-id)
          compress (when (and gzip? (accepts-gzip? request))
                     (gzip-encoder))
          headers (cond-> {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-store"}
                    compress (assoc "Content-Encoding" "gzip"))
          ;; The refresh and the open may render at the same time, the gzip
          ;; stream needs the frames in order: one chain of promises per
          ;; connection, a frame after the one before it.
          chain (atom (js/Promise.resolve nil))]
      (assoc w
             :ring/response
             {:status 200
              :headers headers
              :on-open (fn [{:keys [send!]}]
                         (let [render (fn []
                                        (swap! chain
                                               (fn [previous]
                                                 (-> previous
                                                     (.then (fn []
                                                              (when-let [frame (some-> (view render-fn w)
                                                                                       (encode))]
                                                                (if compress
                                                                  (.then (compress frame) send!)
                                                                  (send! frame)))))
                                                     (.catch (fn [error]
                                                               (js/console.error "[sse]" (str error))))))))]
                           (swap! conns assoc conn-id {:render render})
                           (render)))
              :on-close (fn []
                          (swap! conns dissoc conn-id))}))))
