# rragent

One durable agent on one machine. A Replicant chat on the left, the live
artifacts on the right, pi-durable underneath, written in ClojureScript and
compiled by Clojurific for Node.js. "rr" is Replicant and REPL.

## The idea

Agents should be plenty, and each should have one job. An rragent is one
conversation that never ends: what the agent did is its memory, a job and a
chat are the same thing, and the machine it runs on belongs to it alone. No
list of chats, no second agent on the same machine, nothing an earlier
conversation left behind for a later one to stumble over.

The runtime is [pi-durable](https://github.com/earendil-works/pi/tree/main/packages/durable):
every turn, tool call and piece of state is committed to SQLite before it is
shown, and a restarted process picks up where it stopped. That fits a machine
that sleeps when nothing happens and restarts its processes when it wakes.

The agent's definition is a folder with an `AGENT.md` (`SKILL.md` works too):
its instructions, its tools, its schedule. The agent runs on
[OpenRouter](https://openrouter.ai) with a key of its own. It reaches the
outside world through typed tools, a schedule it writes itself as a cron line,
and named hooks that other systems call.

## Status

Being set up. The toolchain works (Clojurific, Vite, Node 26, pi-durable on
SQLite); the harness, the page and the tools follow.

## Development

Node 26 (`mise install`), Java 17 or later for the compiler.

    npm ci
    npm run dev      # the dev server runs the entry, hot reloads, nREPL in .nrepl-port
    npm test         # the ClojureScript tests under Node
    npm run build    # dist/rragent.js, one file, run with node

An `.env` next to the project is never committed; `OPENROUTER_API_KEY` is
the one variable the agent needs to talk to a model.

## License

MIT, see [LICENSE](LICENSE).
