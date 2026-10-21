(ns rragent.dictation-test
  "`POST /dictation` against a fake OpenRouter: the recording goes to
   `/audio/transcriptions` as base64 with its format and no language,
   which the model detects; the answer is the text with the words and
   their confidence."
  (:require ["node:http" :as node-http]
            [clojure.test :refer [async deftest is testing]]
            [rragent.dictation :as dictation]
            [rragent.rframes.http :as http]
            [rragent.rframes.transit :as transit]))

(defn- fake-openrouter
  "Answers `/audio/transcriptions` the way AssemblyAI's answer comes
   through OpenRouter; 401 without the key.
   Every request is noted in `seen`."
  [seen]
  (node-http/createServer
   (fn [^js req ^js res]
     (let [chunks #js []]
       (.on req "data" (fn [c] (.push chunks c)))
       (.on req "end" (fn []
                        (let [body (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8")) :keywordize-keys true)]
                          (swap! seen conj {:url (.-url req)
                                            :auth (aget (.-headers req) "authorization")
                                            :body body})
                          (.writeHead res (if (= "Bearer k3y" (aget (.-headers req) "authorization")) 200 401)
                                      #js {"Content-Type" "application/json"})
                          (.end res (js/JSON.stringify
                                     (clj->js {:text " Hallo Agent "
                                               :words [{:word "Hallo" :start 0.1 :end 0.4 :confidence 0.98}
                                                       {:word "Agent" :start 0.5 :end 0.9 :confidence 0.61}]
                                               :usage {:seconds 1 :cost 0.0001}}))))))))))

(deftest dictation-test
  (async done
    (let [seen (atom [])
          fake (fake-openrouter seen)
          env (.-env js/process)
          before {:url (aget env "OPENROUTER_BASE_URL") :key (aget env "OPENROUTER_API_KEY")}]
      (-> ((^:async fn []
             (await (js/Promise. (fn [resolve] (.listen fake 0 "127.0.0.1" resolve))))
             (aset env "OPENROUTER_BASE_URL" (str "http://127.0.0.1:" (.-port (.address fake)) "/v1"))
             (aset env "OPENROUTER_API_KEY" "k3y")
             (let [server (await (http/listen (http/ring-handler (constantly dictation/routes)) 0))
                   base (str "http://127.0.0.1:" (.-port (.address server)))
                   post (^:async fn [type body & [query]]
                          (let [response (await (js/fetch (str base "/dictation" (when query (str "?" query)))
                                                          #js {:method "POST"
                                                               :headers #js {"Content-Type" type}
                                                               :body body}))]
                            [(.-status response) (transit/read-str (await (.text response)))]))]
               (try
                 (testing "a recording comes back as text, the words with their confidence"
                   (is (= [200 {:text "Hallo Agent"
                                :words [{:word "Hallo" :confidence 0.98}
                                        {:word "Agent" :confidence 0.61}]
                                :unsure? true}]
                          (await (post "audio/webm;codecs=opus" (js/Buffer.from "opus bytes")))))
                   (let [{:keys [url auth body]} (last @seen)]
                     (is (= "/v1/audio/transcriptions" url))
                     (is (= "Bearer k3y" auth))
                     (is (= {:data (.toString (js/Buffer.from "opus bytes") "base64") :format "webm"} (:input_audio body)))
                     (is (not (contains? body :language)))
                     (is (= "verbose_json" (:response_format body)))
                     (is (= ["word"] (:timestamp_granularities body)))
                     (is (= dictation/model (:model body)))))
                 (testing "Safari's MP4 is m4a"
                   (await (post "audio/mp4" (js/Buffer.from "aac bytes")))
                   (is (= "m4a" (get-in (last @seen) [:body :input_audio :format]))))
                 (testing "no audio, no recording"
                   (is (= [415 {:error :not-audio}] (await (post "image/png" (js/Buffer.from "x")))))
                   (is (= [400 {:error :empty}] (await (post "audio/webm" (js/Buffer.from ""))))))
                 (testing "the service's refusal comes back as a 502 with its message"
                   (aset env "OPENROUTER_API_KEY" "wrong")
                   (let [[status answer] (await (post "audio/webm" (js/Buffer.from "x")))]
                     (is (= 502 status))
                     (is (= :transcription-failed (:error answer)))))
                 (finally
                   (.close server))))))
          (.catch (fn [e] (is (nil? e) (str e))))
          (.finally (fn []
                      (.close fake)
                      (if (:url before) (aset env "OPENROUTER_BASE_URL" (:url before)) (js-delete env "OPENROUTER_BASE_URL"))
                      (if (:key before) (aset env "OPENROUTER_API_KEY" (:key before)) (js-delete env "OPENROUTER_API_KEY"))
                      (done)))))))

(deftest answer-test
  (testing "no word below the threshold, none to correct"
    (is (false? (:unsure? (dictation/answer {:text "a b" :words [{:word "a" :confidence 0.99} {:word "b" :confidence 0.9}]})))))
  (testing "a model without confidences never asks for a correction"
    (is (false? (:unsure? (dictation/answer {:text "a" :words [{:word "a"}]}))))))
