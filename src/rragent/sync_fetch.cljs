(ns rragent.sync-fetch
  "One HTTP request that blocks until it is answered, for the programs
   of the REPL (`rragent.codemode`): SCI evaluates a program
   synchronously, so a `call` in the middle of a loop cannot await.
   The request runs in a worker thread of its own, which fetches and
   posts the answer on a message port; this thread waits on a shared
   flag (`Atomics.wait`, which Node allows on the main thread) and
   takes the message off the port synchronously
   (`receiveMessageOnPort`). The event loop of the process stands
   still meanwhile: nothing else of the agent happens until the answer
   is in or the timeout passes, which is why a server in the same
   process (a test's fake gateway) must live in a thread of its own.
   One worker for the life of the process, started at the first
   request, unreferenced so that it never keeps the process alive.
   Its code is a `data:` URL, a module wherever the bundle runs."
  (:require ["node:worker_threads" :refer [MessageChannel Worker receiveMessageOnPort]]))

(def default-timeout-ms
  60000)

(def worker-code
  "The worker: a job from the port, fetched, the answer posted back,
   the flag raised. A retry (`statuses`, `times`, `delayMs`) happens
   here, so that the wait on the other side spans it."
  "import { workerData } from 'node:worker_threads';
const { port, flag } = workerData;
const view = new Int32Array(flag);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const reason = (e) => String((e && e.message) || e) + (e && e.cause ? ' (' + (e.cause.code || e.cause.message) + ')' : '');
port.on('message', async (job) => {
  let result;
  try {
    for (let attempt = 0; ; attempt++) {
      const response = await fetch(job.url, { method: job.method, headers: job.headers, body: job.body === null ? undefined : job.body, redirect: 'follow', signal: AbortSignal.timeout(job.timeoutMs) });
      const text = await response.text();
      if (job.retry && job.retry.statuses.includes(response.status) && attempt < job.retry.times) {
        await sleep(job.retry.delayMs);
        continue;
      }
      result = { ok: true, status: response.status, headers: Object.fromEntries(response.headers), text: text };
      break;
    }
  } catch (e) {
    result = { ok: false, error: reason(e) };
  }
  port.postMessage(result);
  Atomics.store(view, 0, 1);
  Atomics.notify(view, 0);
});
")

(defn module-url
  "`code` as the URL of a module, for a worker: Node runs a `data:`
   URL through its module loader, whatever the package around the
   bundle says."
  [code]
  (js/URL. (str "data:text/javascript," (js/encodeURIComponent code))))

(defonce ^{:doc "The worker and this side's port while they run: `{:worker :port :view}`."}
  channel
  (atom nil))

(defn- start!
  []
  (let [pair (MessageChannel.)
        flag (js/SharedArrayBuffer. 4)
        worker (Worker. (module-url worker-code)
                        #js {:workerData #js {:port (.-port2 pair) :flag flag}
                             :transferList #js [(.-port2 pair)]})]
    (.unref worker)
    (.unref (.-port1 pair))
    {:worker worker :port (.-port1 pair) :view (js/Int32Array. flag)}))

(defn- channel!
  []
  (or @channel (reset! channel (start!))))

(defn stop!
  "The worker ended; the next request starts a new one. For a request
   that got no answer, whose late answer must not reach the next."
  []
  (when-let [{:keys [worker]} @channel]
    (reset! channel nil)
    (.terminate worker)))

(defn- budget-ms
  "How long to wait for the worker: every attempt's timeout and the
   delays between them, and a little for the worker itself."
  [timeout {:keys [times delay-ms]}]
  (+ (* (inc (or times 0)) timeout)
     (* (or times 0) (or delay-ms 0))
     5000))

(defn- job
  [{:keys [url method headers body timeout-ms retry]}]
  #js {:url url
       :method (or method "GET")
       :headers (clj->js (or headers {}))
       :body (if (nil? body) nil body)
       :timeoutMs (or timeout-ms default-timeout-ms)
       :retry (when retry
                #js {:statuses (clj->js (vec (:statuses retry)))
                     :times (or (:times retry) 0)
                     :delayMs (or (:delay-ms retry) 0)})})

(defn request
  "`{:url :method :headers :body :timeout-ms :retry}` answered as
   `{:status :headers :text}`, the headers lower-cased; a throw with
   the reason when the request failed (a refused connection, a name
   not found, the timeout). `retry`, `{:statuses #{503} :times 5
   :delay-ms 2000}`, tries again on those statuses."
  [{:keys [url method timeout-ms retry] :as options}]
  (let [{:keys [port view]} (channel!)
        timeout (or timeout-ms default-timeout-ms)]
    (js/Atomics.store view 0 0)
    (.postMessage port (job options))
    (js/Atomics.wait view 0 0 (budget-ms timeout retry))
    (if-let [message (receiveMessageOnPort port)]
      (let [^js result (.-message message)]
        (if (.-ok result)
          {:status (.-status result)
           :headers (js->clj (.-headers result))
           :text (.-text result)}
          (throw (js/Error. (str (or method "GET") " " url " failed: " (.-error result))))))
      (do (stop!)
          (throw (js/Error. (str (or method "GET") " " url " got no answer in time; the request worker was restarted.")))))))
