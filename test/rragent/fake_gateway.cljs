(ns rragent.fake-gateway
  "A Sprites gateway for the tests, in a worker thread of its own: the
   REPL's calls block the main thread until they are answered
   (`rragent.sync-fetch`), so a fake in that thread would never
   answer. It serves `/v1/gateway/list` (what a test gives, `{base}`
   in it replaced by the fake's URL, a connection without
   `gateway_base_url` given the fake's), refuses a request with an
   Authorization header (the gateway adds the credential, nothing
   else may), and behind `/v1/gateway/<provider>/<id>/` the
   connections of the list: `echo` answers every request as JSON
   (`?status=` sets the status, `?text=1` answers plain text), the
   custom API `conn_composio` the four meta tools at `execute_meta`
   (a search, schemas, a run of GMAIL_SEND_EMAIL that completes and of
   any other tool that fails, the connection manager with a link; the
   session `trs_dead` is unknown, any other path 403 as the policy
   would), the custom API `conn_storrito` the procedures of a small
   Storrito catalog (`flaky-once` answers 503 once), any other custom
   API an echo. The catalog itself is at `/storrito` of the fake, the
   organization's host, outside the gateway."
  (:require ["node:worker_threads" :refer [Worker]]
            [rragent.sync-fetch :as sync-fetch]))

(def catalog
  "A catalog as the organization's host serves it: a procedure
   without input, one with the schema features of the real ones, one
   that fails once."
  {:catalogVersion 1
   :baseUrlTemplate "https://ORG_UUID.storrito.com/api/v1/"
   :procedures [{:name "generate-uuid"
                 :doc "Generates a UUID.\n\n```\ncurl -X POST https://ORG_UUID.storrito.com/api/v1/generate-uuid\n```\n\n   "
                 :inputSchema {:type "object" :properties {} :additionalProperties false}}
                {:name "status-instagram-story"
                 :doc "The status of a story post.\n\n```bash\ncurl ...\n```\n\n\n   Second paragraph."
                 :inputSchema {:type "object"
                               :properties {:storyPostUuid {:type "string" :format "uuid" :description "The UUID of the story post"}
                                            :verbose {:type "boolean"}
                                            :limit {:type "integer" :minimum 1}
                                            :destination {:anyOf [{:type "string"} {:type "integer"}]}}
                               :required ["storyPostUuid"]
                               :additionalProperties false}}
                {:name "flaky-once"
                 :doc "Answers 503 the first time."
                 :inputSchema {:type "object" :properties {}}}]})

(def connections
  "The connections a test usually wants, as the gateway lists them:
   an echo, OpenRouter, and three custom APIs: Composio, Storrito
   (its base the fake's `/storrito`, where the catalog is) and
   another one, so that `custom_api` names no single connection."
  [{:provider "echo" :id "conn_echo" :notes [] :provider_info {}}
   {:provider "openrouter" :id "conn_openrouter" :notes [] :provider_info {}}
   {:provider "custom_api" :id "conn_composio" :notes []
    :provider_info {:auth_method "custom_header" :auth_custom_header_name "x-api-key"
                    :base_api_url "https://backend.composio.dev" :test_url "https://backend.composio.dev/api/v3/toolkits"}}
   {:provider "custom_api" :id "conn_storrito" :notes [{:title "What this is" :body "The organization's Storrito API."}]
    :provider_info {:auth_method "bearer" :base_api_url "{base}/storrito"}}
   {:provider "custom_api" :id "conn_other" :notes []
    :provider_info {:auth_method "bearer" :base_api_url "{base}/other"}}])

(def available
  [{:provider "github" :display_name "GitHub" :description "Repositories, issues, pull requests" :default_scopes ["repo"]}])

(def default-list
  {:connections connections :available available})

(def code
  "import { parentPort, workerData } from 'node:worker_threads';
import http from 'node:http';
let list = null;
const catalog = JSON.parse(workerData.catalog);
let flaky = 0;
const json = (res, status, value) => { res.setHeader('content-type', 'application/json'); res.writeHead(status); res.end(JSON.stringify(value)); };
const read = (req) => new Promise((resolve) => { let body = ''; req.on('data', (c) => body += c); req.on('end', () => resolve(body)); });
const server = http.createServer(async (req, res) => {
  const body = await read(req);
  const url = new URL(req.url, 'http://fake');
  const path = url.pathname;
  let parsed = body;
  try { parsed = JSON.parse(body); } catch (e) {}
  if (path === '/v1/gateway/list') return json(res, 200, list);
  if (path === '/storrito/documentation/api/v1/catalog.json') return json(res, 200, catalog);
  if (path.startsWith('/storrito/') || path.startsWith('/other/')) return json(res, 404, { error: 'not through the gateway' });
  const m = path.match(/^\\/v1\\/gateway\\/([^/]+)\\/([^/]+)(\\/.*)?$/);
  if (!m) return json(res, 404, { error: 'no such route' });
  const provider = m[1], id = m[2], rest = m[3] || '/';
  if (req.headers.authorization) return json(res, 400, { error: 'an Authorization header reached the gateway' });
  if (!list.connections.find((c) => c.provider === provider && c.id === id)) return json(res, 403, { error: 'forbidden: connection denies all sprites by default' });
  const echo = (status) => json(res, status, { id: id, method: req.method, path: rest, query: Object.fromEntries(url.searchParams), headers: { 'content-type': req.headers['content-type'] || null, 'x-extra': req.headers['x-extra'] || null }, body: parsed });
  if (provider === 'echo') {
    const status = Number(url.searchParams.get('status') || 200);
    if (url.searchParams.get('text')) { res.setHeader('content-type', 'text/plain'); res.writeHead(status); return res.end('plain ' + rest); }
    return echo(status);
  }
  if (provider === 'custom_api' && id === 'conn_composio') {
    const s = rest.match(/^\\/api\\/v3\\/tool_router\\/session\\/([^/]+)\\/execute_meta$/);
    if (!s || req.method !== 'POST') return json(res, 403, { error: 'forbidden: endpoint not allowed' });
    const session = s[1];
    if (session === 'trs_dead') return json(res, 404, { error: { message: 'Session not found', code: 404, slug: 'SESSION_NOT_FOUND', status: 404 } });
    const slug = parsed.slug, args = parsed.arguments;
    const echo = { session: session, slug: slug, arguments: args };
    if (slug === 'COMPOSIO_SEARCH_TOOLS') return json(res, 200, { result_type: 'completed', log_id: 'log_1', data: { results: [{ primary_tool_slugs: ['GMAIL_SEND_EMAIL'] }], session: { id: 'search_1' }, echo: echo } });
    if (slug === 'COMPOSIO_GET_TOOL_SCHEMAS') return json(res, 200, { result_type: 'completed', log_id: 'log_2', data: { schemas: [{ tool_slug: 'GMAIL_SEND_EMAIL', input_schema: { type: 'object' } }], echo: echo } });
    if (slug === 'COMPOSIO_MULTI_EXECUTE_TOOL') {
      const first = args.tools[0].tool_slug;
      if (first === 'GMAIL_SEND_EMAIL') return json(res, 200, { result_type: 'completed', log_id: 'log_3', data: { results: [{ index: 0, tool_slug: first, response: { sent: true } }], echo: echo } });
      return json(res, 200, { result_type: 'failed', log_id: 'log_4', error: first + ': ' + first.split('_')[0].toLowerCase() + ' is not connected', data: { results: [{ index: 0, tool_slug: first, error: 'not connected' }] } });
    }
    if (slug === 'COMPOSIO_MANAGE_CONNECTIONS') return json(res, 200, { result_type: 'completed', log_id: 'log_5', data: { message: '1 connection initiated', results: { gmail: { redirect_url: 'https://connect.example/gmail' } }, echo: echo } });
    return json(res, 200, { result_type: 'completed', log_id: 'log_6', data: { echo: echo } });
  }
  if (provider === 'custom_api' && id === 'conn_storrito') {
    const p = rest.match(/^\\/api\\/v1\\/(.+)$/);
    if (!p || req.method !== 'POST') return json(res, 404, { errorMessage: 'not found' });
    const name = p[1];
    if (name === 'generate-uuid') return json(res, 200, { uuid: '0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d' });
    if (name === 'status-instagram-story') {
      if (parsed && parsed.storyPostUuid) return json(res, 200, { status: 'scheduled', params: parsed });
      return json(res, 400, { errorMessage: 'invalid params for procedure: status-instagram-story', validationErrorExplanation: { storyPostUuid: ['missing required key'] } });
    }
    if (name === 'flaky-once') {
      if (flaky === 0) { flaky = 1; res.writeHead(503); return res.end('deploying'); }
      return json(res, 200, { ok: true });
    }
    return json(res, 404, { errorMessage: 'unknown procedure' });
  }
  return echo(200);
});
server.listen(0, '127.0.0.1', () => {
  const base = 'http://127.0.0.1:' + server.address().port;
  list = JSON.parse(workerData.list.split('{base}').join(base));
  for (const c of list.connections) { if (!c.gateway_base_url) c.gateway_base_url = base + '/v1/gateway/' + c.provider + '/' + c.id; }
  parentPort.postMessage(base);
});
")

(defn ^:async start!
  "The fake listening on a free port: `{:url :stop}`. `list` is what
   `/v1/gateway/list` answers, `default-list` without; a connected
   entry without `gateway_base_url` gets the fake's."
  [& [list]]
  (let [worker (Worker. (sync-fetch/module-url code)
                        #js {:workerData #js {:list (js/JSON.stringify (clj->js (or list default-list)))
                                              :catalog (js/JSON.stringify (clj->js catalog))}})
        url (await (js/Promise. (fn [resolve reject]
                                  (.once worker "message" resolve)
                                  (.once worker "error" reject))))]
    {:url url
     :stop (fn [] (.terminate worker))}))
