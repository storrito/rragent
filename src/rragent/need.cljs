(ns rragent.need
  "What a program asks the host for: a call of the gateway, of an MCP
   tool, a change of the MCP servers, the gateway's list again, an
   event to remember. A program runs in a worker that reaches nothing
   outside itself; a binding that would (`rragent.codemode`) looks
   its call up among the `answers` the host gave for this form, by
   the call's position, and when there is none, notes the need and
   throws. The host performs the first need of the attempt as a
   nested tool call under a key of the form and the position
   (`rragent.tools.eval`), and runs the form again with the answer
   among the answers: the program walks its earlier calls from the
   answers, no request made, and reaches the next unanswered one, or
   its end. Whatever the attempt did after the need, a catch of it
   included, is thrown away: the attempt ran in a fork of the
   context, and a need that was noted ends it. A recorded failure is
   thrown where the call is, an error the program may catch; a
   recorded request that differs from the one the program makes now
   is an error of its own, the program having taken another path."
  (:require [clojure.string :as str]))

(def ^:dynamic *attempt*
  "The attempt of a form, an atom: `{:answers [...] :index n :need
   {...}}`, the answers the host gave so far, the position of the
   next call, the first need noted; nil outside a form."
  nil)

(defn need?
  "Whether `e` is the throw of a need."
  [e]
  (boolean (and (some? e) (:need (ex-data e)))))

(defn message
  [{:keys [tool args]}]
  (str "Waiting for the host: " tool " " (pr-str args)
       ". This is no error of the program: the call is made by the host and the form runs again with its answer; "
       "do not catch it, and do not count on what follows it in this run."))

(defn- same?
  "Whether the request the program makes now is the one recorded."
  [recorded {:keys [tool args]}]
  (and (= tool (:tool recorded)) (= args (:args recorded))))

(defn ask
  "The answer of the host to the call `{:tool :args}` of the program,
   by its position in the form: the recorded value, the recorded
   failure thrown, or the need noted and thrown. `describe` is how an
   error names the call."
  [{:keys [tool args] :as request}]
  (let [attempt *attempt*]
    (when-not attempt
      (throw (js/Error. (str "No host to answer " tool " here."))))
    (let [{:keys [answers index need]} @attempt
          answer (get answers index)]
      (swap! attempt update :index inc)
      (cond
        need
        (throw (ex-info (message need) {:need need}))

        (nil? answer)
        (do (swap! attempt assoc :need (assoc request :index index))
            (throw (ex-info (message request) {:need (assoc request :index index)})))

        (not (same? (:request answer) request))
        (throw (js/Error. (str "The program asked something else than before at call " (inc index) " of this form: "
                               tool " " (pr-str args) " now, " (:tool (:request answer)) " " (pr-str (:args (:request answer)))
                               " before. A form is run again from its start until its calls are answered, so it must ask the same calls in the same order.")))

        (:ok answer)
        (:value answer)

        :else
        (throw (ex-info (str (:error answer)) {:status (:status answer) :body (:body answer)}))))))

(defn attempt
  "An attempt over `answers`, for `*attempt*`."
  [answers]
  (atom {:answers (vec answers) :index 0 :need nil}))

(defn noted
  "The need the attempt noted; nil when every call was answered."
  [attempt]
  (:need @attempt))

(defn describe
  "The need as a line: `gateway echo /x`."
  [{:keys [tool args]}]
  (str tool " " (str/join " " (keep (fn [k] (some-> (get args k) str)) [:which :path :server :tool :procedure :event :admin :name]))))
