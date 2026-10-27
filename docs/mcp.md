# MCP servers

A service with an [MCP](https://modelcontextprotocol.io) server (a
calendar, a file store, a database, Storrito's own agent endpoint)
becomes a namespace of the bots' REPL: every tool of the server is a
function `mcp.<server>/<tool>` that takes a map and answers data. The
client is [`@earendil-works/pi-mcp`](https://github.com/earendil-works/pi/tree/main/packages/mcp);
rragent's side is `rragent.mcp` (the connections), `rragent.codemode`
(the functions) and `rragent.codemode-worker` (the bridge between them).

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
| `timeoutMs` | How long one request may take, 60000 without; every progress the server reports starts it over. |

A name has letters, digits, `-` and `_`. `${NAME}` in `url`, `headers`,
`command`, `args`, `env` and `cwd` is the environment variable `NAME`,
so that a token lives in the service's environment and not in the file;
a variable that is not set makes the server fail with its name.

A server behind a [connector](https://fly.io/docs/sprites/concepts/connectors)
needs no token at all: its gateway URL as `url`
(`http://sprite.internal/v1/gateway/<provider>/<connection id>/mcp`),
no `headers`, and the gateway adds the credential, as for `call`.

OAuth is not wired up: pi-mcp has it (`@earendil-works/pi-mcp/oauth`),
rragent passes static headers only.

The file is read again before every program when it changed: a new
server is there for the next program, a changed one is connected anew,
a removed one closed. No restart.

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
```

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

`test/rragent/mcp_test.cljs` and the MCP tests of
`codemode_test.cljs` and `codemode_worker_test.cljs` run against a fake
server on pi-mcp's in-memory transport pair
(`@earendil-works/pi-mcp/testing`, `test/rragent/fake_mcp.cljs`), and
against a real program over stdio and a real HTTP server with a bearer
token.
