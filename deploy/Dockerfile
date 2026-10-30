# rragent on a Sprite (sprites.dev, 2.0): the bundle on the Sprites base
# image, which brings Node through mise, without what the templates need
# (deploy/Dockerfile.full has that: the renders answer why there is no
# video here, everything else works). The agent's folder is
# /home/sprite/rragent/agent with the generic AGENT.md every new machine
# starts with (deploy/agent): an assistant that offers the skills of
# /home/sprite/skills, a clone of the GitHub repository SKILLS_REPO at
# SKILLS_BRANCH made here (the Agent Skills format, one folder per skill
# with a SKILL.md), and becomes the one the person picks; start.sh pulls
# the clone when the machine starts. `.env` next to the bundle holds
# RRAGENT_TOKEN (the models come through the sprite's gateway) and is
# written when the machine is set up, never into the image. Build from
# the repository's root after `npm run build`:
#   docker build -f deploy/Dockerfile -t rragent .
# with --build-arg SKILLS_REPO=<owner/name> for another skills repository.
# An image built elsewhere downloads a release instead:
# https://github.com/storrito/rragent/releases/latest/download/rragent.tar.gz
# unpacks to the dist/ folder (rragent.js, public/ and templates/).
# No release publishes this image: the release builds deploy/Dockerfile.full
# (.github/actions/sprite-image); this one is the build for a laptop.
FROM ghcr.io/superfly/s2-default@sha256:44f9b4af09c4de3726888f7b9146c9b43529ed97f8963ff11e0f61233e2f1614

ARG SKILLS_REPO=storrito/agentitos
ARG SKILLS_BRANCH=main
ARG NBB_VERSION=1.6.214

USER sprite
WORKDIR /home/sprite/rragent
SHELL ["/bin/bash", "-lc"]
# nbb runs the programs a bot writes (the README's Starters section)
RUN npm install -g --prefix ~/.local --ignore-scripts --no-fund --no-audit --progress=false nbb@${NBB_VERSION} && nbb --version
RUN git clone --depth 1 --branch "${SKILLS_BRANCH}" "https://github.com/${SKILLS_REPO}.git" /home/sprite/skills
COPY --chown=sprite:sprite dist/ /home/sprite/rragent/dist/
COPY --chown=sprite:sprite --chmod=700 deploy/start.sh /home/sprite/rragent/start.sh
COPY --chown=sprite:sprite deploy/agent/ /home/sprite/rragent/agent/
# the definition names the repository the skills come from
RUN sed -i "s#github.com/SKILLS_REPO#github.com/${SKILLS_REPO}#" /home/sprite/rragent/agent/AGENT.md
