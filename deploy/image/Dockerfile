# rragent on a Sprite (sprites.dev, 2.0): what the machine needs around
# the bundle, without the bundle. The image has a version of its own,
# the label org.opencontainers.image.version below, and is pushed as
# ghcr.io/storrito/rragent-sprite:<version> (.github/workflows/image.yml,
# on a push to main that touches deploy/image/). That is the convention
# a provisioner makes a machine by: Storrito lists the package's tags and
# takes the highest version. So every change in deploy/image/, this
# file's or start.sh's, bumps the version with it; the workflow refuses
# to build a version that exists, so a forgotten bump fails loudly
# instead of shipping nothing. A branch's build (the Trial workflow, or
# the Image workflow run by hand) is <version>-trial.<sha>, a pre-release
# version the provisioner passes over.
#
# The bundle (dist/: rragent.js, public/ and templates/) is not in the
# image: start.sh downloads the newest release's rragent.tar.gz at the
# first start of a machine, or the release RRAGENT_UPDATE names, and
# rragent.update swaps in newer releases later. What is here are the
# `needs` of templates/*/TEMPLATE.md, which rragent.programs looks for
# on the machine: ffmpeg (with the fonts), Google Chrome and the
# HyperFrames CLI for the hyperframes template, with the HyperFrames
# skills the template's notes have the agent read before it writes a
# composition; and nbb, which runs the programs a bot writes (the
# README's Starters section). A template added later brings its needs
# to this list. The skills clone /home/sprite/skills (the GitHub
# repository SKILLS_REPO at SKILLS_BRANCH, the Agent Skills format,
# one folder per skill with a SKILL.md, which a new bot offers) is not
# in the image either: start.sh clones it at the first start of a
# machine and pulls it at every start. The agent's folder
# /home/sprite/rragent/agent is empty in the image: the first start
# makes the first bot in it. `.env` next to the bundle holds the
# sprite's settings (the README's Deploy section) and is written when
# the machine is set up, never into the image. Build from the
# repository's root:
#   docker build -f deploy/image/Dockerfile -t rragent-sprite:dev deploy/image
FROM ghcr.io/superfly/s2-default@sha256:44f9b4af09c4de3726888f7b9146c9b43529ed97f8963ff11e0f61233e2f1614

# the image's version: bump it with every change in deploy/image/
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.source="https://github.com/storrito/rragent"

ARG HYPERFRAMES_VERSION=0.8.221
ARG NBB_VERSION=1.6.214

USER root
RUN apt-get update -qq \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ffmpeg fonts-noto-core fonts-noto-color-emoji fonts-liberation >/dev/null \
    && curl -fsSL -o /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq /tmp/chrome.deb >/dev/null \
    && rm -f /tmp/chrome.deb && rm -rf /var/lib/apt/lists/*

USER sprite
WORKDIR /home/sprite/rragent
SHELL ["/bin/bash", "-lc"]
RUN PUPPETEER_SKIP_DOWNLOAD=1 npm install -g --prefix ~/.local --ignore-scripts --no-fund --no-audit --progress=false \
      hyperframes@${HYPERFRAMES_VERSION} @hyperframes/player@${HYPERFRAMES_VERSION} \
      nbb@${NBB_VERSION} \
    && hyperframes --version && nbb --version && ffmpeg -version | head -1 && google-chrome --version
# the HyperFrames skills, the framework's own how-tos: the CLI installs the
# published set (github.com/heygen-com/hyperframes, through npx skills) under
# ~/.agents/skills and ~/.claude/skills, where templates/hyperframes/TEMPLATE.md
# sends the agent; check fails the build when the set is incomplete
RUN hyperframes skills && hyperframes skills check
COPY --chown=sprite:sprite --chmod=700 start.sh /home/sprite/rragent/start.sh
