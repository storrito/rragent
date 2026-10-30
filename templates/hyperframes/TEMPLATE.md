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
  `hyperframes check` plays the whole composition and takes minutes, so
  leave it to the render.
- The page beside the chat is served by the template, not by a file of
  this folder: nothing here needs a server. The `eval` tool does not
  reach into this page.
- `.log` has the output of the process, errors included.
