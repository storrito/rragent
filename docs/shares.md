# Shares: a folder behind a public link

*Decided 2026-10-31 (Max), after a bot on a customer machine spent a
run looking for a way to hand its customer a gallery, and wrote a
six-point handoff for one.*

## The problem

A bot builds a customer gallery under `artifacts/`: an `index.html`,
pictures, clips. The person wants to send their customer a link.
Everything the page serves sits behind the machine's cookie
(`rragent.auth`): the person's own token would let the customer into
the whole machine, and nothing else on the machine is public. The bot
said so and fell back to a ZIP. For a non-technical person the natural
end of many jobs is "send them the link", and it was missing.

## The shape

The bot gets one more tool, `share`: a folder under `artifacts/`
becomes reachable at `/s/<token>/` on the machine's own address,
without the cookie, until the bot stops the share or until the moment
the share names. Nothing new on the page; the link lives in the chat,
where the person asked for it.

```
person: Send the Müllers the gallery.
bot:    Here is the link for them: https://<machine>/s/Y4…/ — anyone
        with it sees the gallery, as it changes.
```

## The decisions

🟢 recommended, 🟡 workable, 🔴 rejected. Max's call in bold.

| # | Decision | Options | Why |
|---|---|---|---|
| 1 | Who makes the link | **🟡 the bot's tool only, the link in the chat** · 🟢 tool and a Share entry in a folder row's menu · 🟡 the menu only | The person asks for the link where they asked for the gallery. No new control in the pane for now. |
| 2 | Where the files are served | **🟢 the sprite itself**, `/s/<token>/<path>` outside the cookie · 🟡 a Tigris bucket per machine · 🔴 Storrito hosts it | No new infrastructure, no credentials on the machine. A sleeping sprite wakes for the visitor (the first load takes a few seconds); the link dies with the machine. Accepted. |
| 3 | What a link covers | **🟢 one folder, everything in it** · 🟡 a single file · 🔴 a manifest of allowed files | People think in folders. A manifest is one more thing to keep right; a stale one breaks the page. |
| 4 | Live or frozen | **🟢 live**, the folder as it is · 🟡 a frozen copy, re-shared to update | "Fix the typo" just works. Drafts belong in a folder of their own, which `AGENT.md` says already. |
| 5 | Expiry | **🟢 none**, an optional `until` · 🟡 30 days by default | Customers hit dead links and the person wonders why. A moment when the person names one. |
| 6 | Scripts in a shared page | **🟡 unrestricted**, served as is · 🟢 allowed inside a CSP sandbox · 🟡 blocked | What the pane serves the owner today. A sandbox can come when a shared page ever carries a stranger's words. |
| 7 | Root without `index.html` | **🟡 nothing** (404), files by their paths · 🟢 a generated file list | The bot writes the page; a folder is not a listing. |
| 8 | The token at rest | **🟢 plain**, like a hook's secret · 🟡 hashed | Whoever reads the machine's storage owns the machine. `share list` can show the links again. |
| 9 | "Your customer opened it" | **🟢 later** · 🟡 count the opens now | Shares stay a plain file route. |
| 10 | A rule in `AGENT.md` against reading rragent's own bundle | **🟡 leave it** · 🟢 add the rule | The tool's description carries what a bot needs to know. |

## How it is built

- `rragent.shares`: the session document `rragent.shares`, by token
  (16 random bytes, base64url), each entry its bot, its folder under
  `artifacts/`, when it was made and its moment. A folder is shareable
  when it lies inside the artifacts, is neither hidden nor the notes,
  and is a directory. Sharing a folder that has a share answers the
  same link. The handler serves `GET` and `HEAD` of `/s/<token>/<path>`:
  the folder's `index.html` for the bare link and for a path ending in
  a slash, a file by its path, never a hidden one or a note, nothing
  outside the folder (realpath containment, so a symlink out of it is
  nothing too); `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy:
  no-referrer`, `X-Content-Type-Options: nosniff` on every file. The
  bare `/s/<token>` is sent to its slash so that relative links hold.
  Anything else is a 404 page, "Nothing here".
- `rragent.tools.share`: `create`, `stop`, `list`, with `folder` (with
  or without `artifacts/` in front) and `until` (an ISO date or
  date-time). A run shares as the bot would: the folder is the bot's.
- `rragent.rframes.http`: `file-response` answers a `HEAD` with the
  headers alone and a range the file has no bytes for with a 416
  (`unsatisfiable?`); a malformed `Range` is ignored, as before.
- `rragent.page`: the two routes in `open-register`, beside the hooks,
  Composio's events and the phone's pairing. `POST` to `/s/…` stays
  behind the token, so a 403.
- `deploy/agent/AGENT.md` is unchanged: the tool's description says
  when to share and what a shared folder must not hold.
