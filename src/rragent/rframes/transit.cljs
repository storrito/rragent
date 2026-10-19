(ns rragent.rframes.transit
  "Transit JSON, the encoding of the wire in both directions: the frames
   the server pushes and the commands the client posts. The port of
   rframes.transit to transit-cljs. A request body is a stream that is
   read to its end first, so `read-stream` answers a promise."
  (:require [cognitect.transit :as t]))

(defn write-str
  "`x` as a transit JSON string, which has no line breaks."
  [x]
  (t/write (t/writer :json) x))

(defn read-str
  [s]
  (t/read (t/reader :json) s))

(defn ^:async read-body
  "A readable stream, the body of a request, read to its end as one
   UTF-8 string."
  [readable]
  (let [chunks #js []]
    (await (js/Promise. (fn [resolve reject]
                          (.on readable "data" (fn [chunk] (.push chunks chunk)))
                          (.on readable "end" resolve)
                          (.on readable "error" reject))))
    (.toString (js/Buffer.concat chunks) "utf8")))

(defn ^:async read-stream
  "One transit value from a readable stream."
  [readable]
  (read-str (await (read-body readable))))
