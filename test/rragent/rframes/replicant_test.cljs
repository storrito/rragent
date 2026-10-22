(ns rragent.rframes.replicant-test
  (:require ["node:stream" :refer [Readable]]
            ["node:zlib" :as zlib]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.rframes.replicant :as replicant]
            [rragent.rframes.sse :as sse]
            [rragent.rframes.transit :as transit]))

(defn- read-frame
  [frame]
  (let [[_ data] (re-matches #"event: frame\ndata: ([\s\S]*)\n\n" frame)]
    (transit/read-str data)))

(deftest frame-event-test
  (let [view [:div {:on {:click [[:store/assoc :open? true]]}}
              (for [n (range 2)]
                [:span {:replicant/key n} "line\nbreak"])]
        frame (replicant/frame-event view)]
    (testing "one frame is one SSE event on one data line"
      (is (= ["event: frame" "" "" ""]
             (assoc (str/split frame #"\n" -1) 1 ""))
          frame)
      (is (str/starts-with? (second (str/split-lines frame)) "data: [")))
    (testing "the client reads the same hiccup, a fragment stays a seq"
      (is (= view (read-frame frame)))
      (is (seq? (nth (read-frame frame) 2))))
    (testing "a view that transit cannot write is skipped"
      (let [quiet (.-error js/console)]
        (set! (.-error js/console) (fn [& _]))
        (try
          (is (nil? (replicant/frame-event [:button {:on {:click (fn [] nil)}}])))
          (finally
            (set! (.-error js/console) quiet)))))))

(defn- body
  [s]
  (.from Readable #js [(js/Buffer.from s "utf8")]))

(deftest tabid-test
  (async done
    (-> ((^:async fn []
           (is (= "a1b2c3d4" (await (replicant/tabid {:body (body (transit/write-str {:tabid "a1b2c3d4"}))}))))
           (is (nil? (await (replicant/tabid {:body (body (transit/write-str [1 2]))}))))
           (is (nil? (await (replicant/tabid {:body (body "not transit")}))))
           (is (nil? (await (replicant/tabid {}))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest shim-test
  (let [{:keys [ring/route ring/handler]} (replicant/expand-entry {:replicant/shim "/"
                                                                   :head "<title>t</title>"})
        {:keys [status body]} (:ring/response (handler {}))]
    (is (= [:get "/"] route))
    (is (= 200 status))
    (is (str/includes? body "<title>t</title>"))
    (is (str/includes? body "data-rframes-view"))
    (is (str/includes? body "<html lang=\"en\">") "English when the page says no language"))
  (testing "the page's language and its words for a browser without JavaScript"
    (let [html (replicant/shim-html "" {:lang "de" :noscript "Diese Seite braucht JavaScript."})]
      (is (str/includes? html "<html lang=\"de\">"))
      (is (str/includes? html "<noscript>Diese Seite braucht JavaScript.</noscript>")))))

(deftest expand-test
  (is (= [:post "/"] (:ring/route (replicant/expand-entry {:replicant/render "/"
                                                           :render/fn identity}))))
  (is (= {:ring/route [:get "/x"]} (replicant/expand-entry {:ring/route [:get "/x"]}))))

(defn- gunzip
  "The text of gzip chunks of a stream that is not finished."
  [chunks]
  (.toString (zlib/gunzipSync (js/Buffer.concat (to-array chunks))
                              #js {:finishFlush (.-Z_SYNC_FLUSH zlib/constants)})
             "utf8"))

(deftest gzip-encoder-test
  (async done
    (-> ((^:async fn []
           (let [encode (sse/gzip-encoder)
                 frame (str "event: frame\ndata: " (apply str (repeat 500 "[\"~:div\",\"text\"]")) "\n\n")
                 first-chunk (await (encode frame))
                 second-chunk (await (encode frame))]
             (testing "every frame can be read as soon as it arrived"
               (is (= frame (gunzip [first-chunk])))
               (is (= (str frame frame) (gunzip [first-chunk second-chunk]))))
             (testing "a frame that repeats the one before costs next to nothing"
               (is (< (.-length second-chunk) (quot (.-length first-chunk) 2)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
