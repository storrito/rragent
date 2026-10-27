# MCP servers

A service with an [MCP](https://modelcontextprotocol.io) server (a
calendar, a file store, a database, Storrito's own agent endpoint)
becomes a namespace of the bots' REPL: every tool of the server is a
function `mcp.<server>/<tool>` that takes a map and answers data. The
client is [`@earendil-works/pi-mcp`](https://github.com/earendil-works/pi/tree/main/packages/mcp);
rragent's side is `rragent.mcp` (the connections), `rragent.codemode`
(the functions) and `rragent.codemode-worker` (the bridge between them).

## Connecting a service

The person does not have to write any of this. Settings → MCP servers
lists the servers with their state (connected and how many tools,
waiting for a secret, failed, off), Test connection, Turn off and
Remove, and **Connect a service …**, which opens a new bot made for
the job (`rragent.mcp-setup`): it asks which service, reads the
service's documentation, connects it with `mcp/add!`, asks for the
token with a password field and tests the connection. Any other bot
can do the same when asked.

A token never goes through the conversation:

1. `mcp/add!` writes the entry with the token as `${NAME}`
   (`:token "GITHUB_TOKEN"` is `Authorization: Bearer ${GITHUB_TOKEN}`)
   and answers `:needs ["GITHUB_TOKEN"]`; a credential given as its
   value is refused.
2. The bot puts `[:secret {:name "GITHUB_TOKEN" :label "…"}]` into a
   ```` ```hiccup ```` block of its answer, which the page shows as a
   password field (the MCP settings show one too for every secret a
   server waits for).
3. What the person types there goes to the server as a command, not a
   message, into `secrets.json` of the machine's folder (mode 600);
   the bot is told "I saved the secret GITHUB_TOKEN", the servers that
   use it are connected again.

`${NAME}` is looked up in `secrets.json` first, then in the
environment. Before a tool's result is committed, every known secret
value in it (`secrets.json` and the environment's `…_KEY`, `…_TOKEN`,
`…_SECRET`, `…_PASSWORD`) is replaced by `«NAME»` (an `afterTool`
hook, `rragent.harness/redaction`), so a `cat secrets.json` or a server
that echoes its token shows the model nothing. That stops accidents,
not a bot set on it: the bots' bash runs as the same user and could
still encode a value.

## Signing in with OAuth

Most hosted MCP servers (Linear, Notion, Atlassian, Sentry …) have no
token to copy: the person signs in with their account at the service.
Such an entry says `"oauth": true`:

```json
{ "mcpServers": { "linear": { "url": "https://mcp.linear.app/mcp", "oauth": true } } }
```

(`(mcp/add! "linear" {:url "https://mcp.linear.app/mcp" :oauth true})`
from the REPL.) Until the person signed in, the server waits for it
(`:sign-in true`, "Waiting for a sign-in" in the settings) and is not
tried by itself. The sign-in:

1. The person clicks **Sign in …** in the MCP settings, or the link a
   bot puts in its answer, `[:sign-in {:server "linear"}]` in a
   ```` ```hiccup ```` block. Both open `/mcp/oauth/start/<server>` of
   the page in a new tab.
2. rragent discovers the service's authorization server, registers
   itself there the first time (dynamic client registration) and sends
   the browser to the service's sign-in page, with PKCE and a state of
   its own (`rragent.mcp/sign-in!`, pi-mcp's `authorizeMcp`).
3. The service sends the browser back to `/mcp/oauth/callback` of the
   page: the code is exchanged for tokens, the server connected, and
   the bot whose link it was is told "I signed in to linear"; the tab
   says how it went.

Both routes are behind the page's token: the redirect back from the
service carries the cookie (`SameSite=Lax`). The service sends the
browser back to `RRAGENT_URL`, else to the address the sign-in was
started at. The tokens, the client registration and the state of a
sign-in under way are kept per server in `mcp-oauth.json` of the
machine's folder (mode 600); an access token that expires is refreshed
by itself, a service that refuses the refresh makes the server wait for
a sign-in again. **Sign out** in the settings forgets the server's
tokens, Remove forgets them with the server. The access and refresh
tokens are redacted from tool results like the secrets.

A service that does not register clients by itself gives a client id
and secret in its docs: `"oauth": {"clientId": "…", "clientSecret":
"${LINEAR_CLIENT_SECRET}", "scope": "read"}`, the secret saved like a
token (`:oauth {:client-id … :client-secret "${NAME}"}` in `mcp/add!`).

## Configuring

The servers are named in `mcp.json` in the machine's folder
(`RRAGENT_HOME`, `./agent` by default), the same `mcpServers` file pi,
pchat and most MCP clients read. Every bot of the machine has them.

```json
{
  "mcpServers": {
    "storrito": {
      "url": "https://acme.storrito.com/mcp",
      "headers": { "Authorization": "Bearer ${STORRITO_TOKEN}" }
    },
    "storrito-agent": {
      "url": "https://acme.storrito.com/agent/mcp",
      "headers": { "Authorization": "Bearer ${STORRITO_AGENT_TOKEN}" },
      "timeoutMs": 120000
    },
    "files": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
      "cwd": "bots/1/work"
    },
    "old": { "url": "https://old.example.com/mcp", "enabled": false }
  }
}
```

| Key | |
| --- | --- |
| `url` | A Streamable HTTP server (`http://` or `https://`). The legacy HTTP+SSE transport (`"type": "sse"`) is not supported. |
| `headers` | Sent with every request of a `url` server, a bearer token as a rule. |
| `command`, `args` | A server that is a program, spoken to over stdin and stdout. |
| `env` | More environment for the program; it has the agent's environment besides. |
| `cwd` | The program's working directory, relative to the machine's folder; the machine's folder without. |
| `enabled` | `false` keeps the entry without connecting it. |
| `oauth` | `true` when the person signs in at the service (OAuth), or `{clientId, clientSecret, scope}`; see above. |
| `timeoutMs` | How long one request may take, 60000 without; every progress the server reports starts it over. |

A name has letters, digits, `-` and `_`. `${NAME}` in `url`, `headers`,
`command`, `args`, `env` and `cwd` is the secret `NAME` of
`secrets.json`, else the environment variable, so that a token is not
in the file; one that has neither leaves the server waiting for it
(`:needs`), not connected.

A server behind a [connector](https://fly.io/docs/sprites/concepts/connectors)
needs no token at all: its gateway URL as `url`
(`http://sprite.internal/v1/gateway/<provider>/<connection id>/mcp`),
no `headers`, and the gateway adds the credential, as for `call`.

A server can also sign in with OAuth, `"oauth": true` (see above).

The file and `secrets.json` are read again before every program when
one changed: a new server is there for the next program, one whose
entry or secret changed is connected anew, a removed one closed. No
restart.

## In the REPL

```clojure
(mcp/servers)
;; => [{:name "storrito" :ns "mcp.storrito" :tools 12 :connected true
;;      :instructions "..."} ...]

(mcp/tools "storrito")
;; => [{:name "list_stories" :fn mcp.storrito/list_stories :description "..."} ...]

(doc mcp.storrito/list_stories)        ; the description and the arguments from the schema

(->> (mcp.storrito/list_stories {:limit 50})
     :stories
     (filter #(= "draft" (:status %)))
     (map :title))

(mcp/call "files" "read.file" {:path "notes.md"})   ; by name, for a name that is no symbol

(mcp/add! "github" {:url "https://api.githubcopilot.com/mcp/" :token "GITHUB_TOKEN"})
;; => {:name "github" :connected false :tools [] :needs ["GITHUB_TOKEN"] :error "needs the secret …"}
(mcp/test "github")          ; connected anew: {:connected true :tools ["get_me" …]} or the :error
(mcp/enable! "github" false) ; off, the entry kept
(mcp/remove! "github")
```

The four that change the servers are done by the main thread, which
owns `mcp.json`; a new server's `mcp.<server>/…` functions are there
from the next program on, `mcp/call` reaches them at once.

- A tool's symbol is its name with anything but letters, digits, `_`
  and `-` replaced by `-` (`files.read` is `mcp.files/files-read`).
- The value is the tool's structured content when it gives one, else
  its text, as data when the text is a JSON object or array (keys as
  keywords, as `call` reads a body), several content blocks a vector,
  none `nil`.
- A picture in the answer is shown to the model after the program's
  answer (up to four per call, eight per program); the value has a line
  in its place.
- A tool that fails (`isError` in its result), an unknown tool, a
  server that cannot be reached, a timeout: the call throws with what
  went wrong, which a program can catch.
- Progress the server reports shows as the eval call's running output.
- `(help)` lists the servers, what each has and what failed.

## How it runs

The connections live in the agent's main thread, one per server for all
bots, made when a program first needs the list of tools: the program
waits up to fifteen seconds for the servers, a slower one goes on
connecting and its tools come with a later program. A server that
failed is tried again after thirty seconds, one whose connection closed
(a program that ended, an HTTP session the server forgot) at the next
call; a call whose session had expired is made again on the new one,
the server having taken nothing of it.

SCI evaluates a program synchronously in the bot's worker thread, so a
call cannot await: the worker posts it on a port of its own and blocks
on a shared flag (`Atomics.wait`), the main thread calls the tool with
pi-mcp, posts the answer and raises the flag. The connections outlive
the worker. Stop and the program's timeout end the worker as before;
the calls it waited for are aborted, and pi-mcp tells the server
(`notifications/cancelled`).

## Tests

`test/rragent/mcp_test.cljs` (with the secrets, the redaction,
`add!`/`test!`/`enable!`/`remove!` and the OAuth sign-in, refresh and
sign-out against a fake service with a real authorization server,
`test/rragent/fake_oauth.cljs`), `tools_test.cljs`'s redaction of a
real bash call, `page_test.cljs`'s MCP settings and secret field, and
the MCP tests of
`codemode_test.cljs` and `codemode_worker_test.cljs` run against a fake
server on pi-mcp's in-memory transport pair
(`@earendil-works/pi-mcp/testing`, `test/rragent/fake_mcp.cljs`), and
against a real program over stdio and a real HTTP server with a bearer
token.
