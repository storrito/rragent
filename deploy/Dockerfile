# rragent on a Sprite (sprites.dev, 2.0): the bundle on the Sprites base
# image, which brings Node through mise, without what the templates need
# (deploy/Dockerfile.full has that: the renders answer why there is no
# video here, everything else works). The agent's folder is
# /home/sprite/rragent/agent with its AGENT.md; `.env` next to the bundle
# holds RRAGENT_TOKEN (the models come through the sprite's gateway) and
# is written when the machine is set up, never into the image. Build from the repository's
# root after `npm run build`: docker build -f deploy/Dockerfile -t rragent .
# An image built elsewhere downloads a release instead:
# https://github.com/storrito/rragent/releases/latest/download/rragent.tar.gz
# unpacks to the dist/ folder (rragent.js, public/ and templates/).
# The release workflow pushes it as
# ghcr.io/storrito/rragent-sprite:<version>-plain (.github/actions/sprite-image).
FROM ghcr.io/superfly/s2-default@sha256:44f9b4af09c4de3726888f7b9146c9b43529ed97f8963ff11e0f61233e2f1614

USER sprite
WORKDIR /home/sprite/rragent
COPY --chown=sprite:sprite dist/ /home/sprite/rragent/dist/
COPY --chown=sprite:sprite --chmod=700 deploy/start.sh /home/sprite/rragent/start.sh
COPY --chown=sprite:sprite deploy/agent/ /home/sprite/rragent/agent/
