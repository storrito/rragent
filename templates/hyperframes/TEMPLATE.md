---
name: hyperframes
description: A HyperFrames composition, HTML with GSAP in composition/index.html with variables as controls, shown in the HyperFrames player beside the chat; renders an mp4 with the HyperFrames CLI.
renders: mp4
needs: chrome, ffmpeg, hyperframes
entry: serve
---

# A HyperFrames composition

HTML with GSAP, the way the HyperFrames skills describe it. rragent
shows it in the HyperFrames player beside the chat and loads it again
whenever a file of it changes.

- Read first, before any file of this project: the HyperFrames skills,
  the framework's own how-tos, installed on this machine under
  `~/.agents/skills/`, one folder per skill with a `SKILL.md` (a
  `/name` in them is `~/.agents/skills/<name>/`). Begin with
  `~/.agents/skills/hyperframes/SKILL.md`: the contract every
  composition keeps, the work loop, and its table of what to read for
  the kind of video asked for; read those files too, and `/media-use`
  before any music, voice or picture. `hyperframes skills check` says
  whether the skills are there and current, `hyperframes skills`
  installs them. Not a line of the composition before this.
- `composition/` is the project the skills speak of: `composition/index.html`
  the root composition (below), `composition/compositions/` its scenes,
  `composition/assets/` its media and fonts, `SPEC.md` and `REVIEW.md` at
  its root. The CLI of this machine is `hyperframes`, run in
  `composition/`, in place of the skills' `npx hyperframes`, so that the
  installed version runs. The pane beside the chat is the preview: no
  `hyperframes preview`, no desktop app, no `open`; the person sees
  every saved change at once.
- `composition/index.html`: the design. The root carries
  `data-composition-id`, `data-duration` (seconds), `data-width` and
  `data-height` (1080 by 1920 for a story); timed elements are
  `class="clip"` with `data-start` and `data-duration`; one paused GSAP
  timeline registered as `window.__timelines["main"]`. What changes per
  use is a variable: declared on `<html data-composition-variables>`, read
  with `window.__hyperframes.getVariables()`.
- The variables are the controls beside the player: one per declaration,
  `{"id": "title", "type": "string", "label": "Title", "default": "Hello"}`,
  types `string`, `number` (`min`, `max`, `step`), `color` and `boolean`,
  `options` for a choice (`["calm", {"value": "loud", "label": "Loud"}]`).
  What the person sets goes into `variables.json` here; the composition
  gets it as `window.__hfVariables`, the way the render does, so read the
  variables when the composition loads. Write `variables.json` yourself
  to set values; the controls follow.
- Music or a voice: put the file into `composition/` and declare it,
  `<audio id="music" src="music.mp3" data-start="0" data-duration="6"
  data-volume="0.6" data-fade-out="1">`, also `data-media-start` and
  `data-fade-in`. It plays in the preview and is mixed into the video.
  Images go into `composition/` as well.
- The video: the `render` tool with the name of this artifact makes
  `artifacts/<name>.mp4` with `hyperframes render`, with the values of
  `variables.json`; the file appears when the render is done (it is
  made in a hidden folder beside it). The preview plays the design
  live, so render only when the file itself is needed, not after every
  change. An element marked `data-preview-only` shows in the player but
  not in the video: Instagram's stickers that Storrito lays over the
  finished video itself (agentitos' story-stickers), a guide.
- Several compositions, side by side: without `composition/index.html`,
  every folder `composition/<name>/` with its own `index.html` (a whole
  HyperFrames project: its `compositions/`, `assets/`) is shown in a
  player of its own, titled by its `<title>`, without controls. Each
  renders on its own, `hyperframes render` in its folder; the `render`
  tool takes only the single composition.
- `hyperframes lint` in `composition/` finds mistakes in seconds;
  `hyperframes check --json` plays the whole composition and takes
  minutes: once after your last edit, as the skills say, not after
  every change.
- The page beside the chat is served by the template, not by a file of
  this folder: nothing here needs a server.
- `.log` has the output of the process, errors included.
