---
name: motion
description: A motion design in ClojureScript, the frame as a function of time with controls over its data, played live beside the chat; renders an mp4.
renders: mp4
needs: chrome, ffmpeg
entry: serve
---

# A motion design

A frame as a function of time. rragent runs the project and shows its
page beside the chat; the person sees every saved change at once.

- `design.cljs`: the namespace `design` with `size` (width and height of
  the video in pixels, 1080 by 1920 for a story), `duration` in seconds,
  `design-view` (the frame at time `t`, a pure function of the design
  data and the time, hiccup with inline style maps that fills the stage,
  a fragment is a seq, never a vector of vectors, no functions in it),
  `controls` (sliders, color pickers, text fields over the design with
  `(player/setter [:key])`) and `styles` (CSS for the page and the video).
  Save the file and the running process loads it again; an error in it
  shows on the page.
- `design.edn`: the data of the design, what changes per use. It is
  written on every change of a control and read at start, so it survives
  restarts; write it yourself to change the data.
- Live coding: the `eval` tool with `project` evaluates in the namespace
  of `design.cljs` as it runs: `(defn design-view ...)` or `(def duration
  3)` shows on the page at once, `(design-view @data 1.5)` is a look at a
  frame. What you keep goes into the file; a saved file starts over.
- Music or a voice: put the audio file into this directory and add it
  to `:audio` in `design.edn`: `{:src "music.mp3" :volume 0.6 :fade-out
  1}`, with `:start` (seconds into the video, 0), `:duration` (to the
  end), `:media-start` (where in the file it begins), `:fade-in`, `:loop?
  true` as needed. It plays in the preview and is mixed into the video.
  An image a frame shows by its file name lives here as well.
- The video: the `render` tool with the name of this artifact makes
  `artifacts/<name>.mp4`, thirty frames a second at `size`. Every frame
  is screenshot by a headless Chrome, ffmpeg encodes them. The preview
  plays the design live, so render only when the file itself is needed
  (the person asks for it, or it is posted somewhere), not after every
  change.
- fnmotion is at hand as `fm` (interpolate, progress, lerp, spring,
  easings such as cubic-out, px, pct), `tl` (scene, still, sequence,
  parallel, at) and `captions` (captions, at, word-at over words with
  :start :end :text in seconds). The design runs in ClojureScript (SCI):
  what JavaScript has is reachable as `js/Math` and the like.
- `.log` has the output of the process, errors included.
