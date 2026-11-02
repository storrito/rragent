# Costs: what OpenRouter charges, not what a catalog says

*Decided 2026-11-02 (Max): the page showed far more than OpenRouter
billed.*

## The problem

Every answer's cost on the page came from pi-ai: it read the token
counts OpenRouter sent and priced them from its own static catalog
(`calculateCost`), per million tokens in, out, cache read, cache
write, with tiers. The catalog is frozen at pi-ai's release, so a
price drop at OpenRouter, a cache discount, a cheaper route never
reached the page. The media tools printed their charge in a sentence
and nothing summed it; dictation and its correction were separate
requests priced the same way.

OpenRouter puts the real charge into every answer: `usage.cost` in
dollars, on the chunk of the stream that carries the usage, with
`cost_details`, cached and reasoning tokens beside it. Nothing has to
be requested; the old `usage: {include: true}` is deprecated. pi-ai
reads the token counts from that chunk and throws the `cost` away.

## The decisions

🟢 recommended, 🟡 workable, 🔴 rejected. Max's call in bold.

| # | Decision | Options | Why |
|---|---|---|---|
| 1 | Source of the charge | **🟢 `usage.cost` from the answer, caught in the fetch wrapper, matched by generation id** · 🟡 `GET /v1/generation?id=` after each answer · 🔴 the key's usage, the Activity API | No extra request, exact per answer, per conversation. The generation endpoint is exact too, BYOK upstream cost included, but one more request, a lag before it answers, retries. The key's usage is the organization's, no per-bot number; Storrito's metering reads it already. |
| 2 | An answer without a charge | **🟢 pi-ai's estimate stays for that answer** · 🟡 nothing shown | Another provider on a laptop, a stream without the chunk: better a number than none. |
| 3 | Pictures, voices, music | **🟢 the tool answers its charge as the usage of its result, into the ledger's tools** · 🟡 a sentence only | pi-durable adds a tool result's `usage` to `pi.usage`, and the page summed that bucket already. The voice service answers no charge: its estimate per character stands in. |
| 4 | Dictation and its correction | **🟢 left out of the sum** · 🟡 into the ledger as a tool | Small, separate requests; the correction's log line carries the real charge now. |
| 5 | What the page shows | **🟢 the charge times `RRAGENT_COST_FACTOR`, 1 without it** · 🟡 the charge as it is | Storrito bills the organization OpenRouter's use times its markup; the provisioning sets the factor so that the bot's page and the wallet say the same. A laptop sees the cost. |
| 6 | Old sums | **🟢 no migration** · 🔴 rebuilt from the entries | `pi.usage` is a running total; what was estimated stays estimated. |
| 7 | List prices in the model switch | **🟢 pi-ai's catalog, as before** · 🟡 refreshed from `GET /v1/models` through the gateway | The only source of a price before the first answer. |

## How it is built

- `rragent.costs`: `charge-of-chunk` reads one `data:` line for an
  `id` and a numeric `usage.cost`; `tee` splits a response's body, one
  branch read here line by line (`scan!`, `remember!`), the other
  handed on in a response like it; `charges` keeps a charge by
  generation id for ten minutes; `adopt!` puts the charge into pi-ai's
  message usage (the parts scaled to it, the total replaced; without
  an estimate the whole charge as input); `extension` is pi-durable's
  `afterResponse` hook doing that before `appendAssistant` writes the
  usage into `pi.usage`, with a line on stdout that names the estimate
  beside the charge, so that the drift can be read off the logs for a
  while; `tool-usage` is the usage a tool answers for a charge of its
  own; `factor` and `priced` are the page's.
- `rragent.model-requests/wrap`: a good answer to a chat completion
  goes through `costs/tee`.
- `rragent.harness`: the extension installed beside the redaction.
- `rragent.tools.media`: `image!`, `voice!` and `music!` answer
  `{:line :cost}`; the tools put the cost into their result's `usage`.
- `rragent.correction`: `adopt!` before the log line.
- `rragent.view`: `context-usage` and the header's spend times
  `priced`.
- `deploy/agent/AGENT.md` is unchanged.

What is not in the page's number: dictation and its correction, and
anything a program of the REPL pays for itself.
