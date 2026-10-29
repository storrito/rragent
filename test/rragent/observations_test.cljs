(ns rragent.observations-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rragent.harness :as harness]
            [rragent.observations :as observations]
            [rragent.test-home :as test-home]
            [rragent.tools-test :as tools-test]))

(defn- user [text]
  #js {:role "user" :content text :timestamp 0})

(defn- call [id name args]
  #js {:role "assistant" :content #js [#js {:type "toolCall" :id id :name name :arguments (clj->js args)}]})

(defn- result [id & content]
  #js {:role "toolResult" :toolCallId id :toolName "t" :isError false :timestamp 0 :content (into-array content)})

(defn- text [s]
  #js {:type "text" :text s})

(def picture
  #js {:type "image" :data "AAAA" :mimeType "image/webp"})

(def long-text
  (str (apply str (repeat 1000 "a")) (apply str (repeat 8000 "b")) (apply str (repeat 1000 "c"))))

(deftest shorten-test
  (is (identical? "short" (observations/shorten "short")) "a short text stays itself")
  (let [short (observations/shorten long-text)]
    (is (str/starts-with? short (apply str (repeat 1000 "a"))) "its start")
    (is (str/ends-with? short (apply str (repeat 1000 "c"))) "its end")
    (is (str/includes? short "[… 8000 characters of this earlier result are left out here") "and what was left out")
    (is (not (str/includes? short "b")))))

(deftest mask-test
  (testing "two turns or fewer: the same array"
    (let [messages #js [(user "one") (call "c1" "read" {:path "a.png"}) (result "c1" picture) (user "two")]]
      (is (identical? messages (observations/mask messages)))))
  (testing "the results before the last two turns masked, the rest the same objects"
    (let [old-picture (result "c1" picture (text "a.png, 640×480"))
          old-text (result "c2" (text long-text))
          old-short (result "c3" (text "ok"))
          kept-picture (result "c4" picture)
          kept-text (result "c5" (text long-text))
          messages #js [(user "one")
                        (call "c1" "read" {:path "artifacts/a.png"}) old-picture
                        (call "c2" "bash" {:command "ffprobe x"}) old-text
                        (call "c3" "bash" {:command "true"}) old-short
                        (user "two")
                        (call "c4" "read" {:path "b.png"}) kept-picture
                        (user "three")
                        (call "c5" "bash" {:command "ffprobe y"}) kept-text]
          masked (observations/mask messages)]
      (is (not (identical? messages masked)))
      (is (= 13 (.-length masked)))
      (is (= "[The picture artifacts/a.png was here; pictures of earlier turns are left out. Read the file again to see it.]"
             (.-text (aget (.-content (aget masked 2)) 0)))
          "a picture as a line naming the file its call read")
      (is (= "a.png, 640×480" (.-text (aget (.-content (aget masked 2)) 1))) "its text beside stays")
      (is (= "c1" (.-toolCallId (aget masked 2))) "the result keeps its call")
      (is (< (count (.-text (aget (.-content (aget masked 4)) 0))) 2200) "a long text shortened")
      (is (identical? old-short (aget masked 6)) "a short result stays the same object")
      (is (identical? kept-picture (aget masked 9)) "the turn before the running one is whole")
      (is (identical? kept-text (aget masked 12)) "the running turn is whole")
      (is (identical? picture (aget (.-content kept-picture) 0)) "and the conversation's own messages are untouched")
      (is (= (js->clj masked) (js->clj (observations/mask messages))) "the same messages give the same request, for the cache")))
  (testing "a picture whose call is unknown"
    (let [messages #js [(user "one") (result "gone" picture) (user "two") (user "three")]]
      (is (= "[A picture was here; pictures of earlier turns are left out.]"
             (.-text (aget (.-content (aget (observations/mask messages) 1)) 0)))))))

(deftest masked-request-test
  (async done
    (-> ((^:async fn []
           (let [home (test-home/make)
                 {:keys [faux models]} (tools-test/faux-models)
                 sent (atom [])
                 answer (fn [text]
                          (fn [context _options _state _model]
                            (swap! sent conj (js/JSON.stringify (.-messages context)))
                            (tools-test/answer text)))]
             (.setResponses faux #js [(tools-test/tool-call "bash" {:command "printf 'x%.0s' $(seq 1 9000)"} "b1")
                                      (answer "Nine thousand x.")
                                      (answer "Two.")
                                      (answer "Three.")])
             (let [agent (await (harness/open {:home home :models models :storage (harness/memory-storage) :provider "faux"}))
                   xs (apply str (repeat 9000 "x"))]
               (try
                 (is (= "Nine thousand x." (await (harness/ask agent "Print nine thousand x."))))
                 (is (= "Two." (await (harness/ask agent "And now?"))))
                 (is (= "Three." (await (harness/ask agent "And now?"))))
                 (let [[first-turn second-turn third-turn] @sent]
                   (is (str/includes? first-turn xs) "the result whole in its own turn")
                   (is (str/includes? second-turn xs) "and in the turn after")
                   (is (not (str/includes? third-turn xs)) "shortened two turns later")
                   (is (str/includes? third-turn "characters of this earlier result are left out here")))
                 (let [view (await (.context (:root agent) harness/context))]
                   (is (str/includes? (js/JSON.stringify (.-messages view)) xs) "the conversation keeps it whole"))
                 (finally
                   (await (harness/close agent))
                   (test-home/remove! home)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
