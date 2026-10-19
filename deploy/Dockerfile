# rragent on a Sprite (sprites.dev, 2.0): the bundle on the Sprites base
# image, which brings Node through mise. The agent's folder is
# /home/sprite/rragent/agent with its AGENT.md; `.env` next to the bundle
# holds OPENROUTER_API_KEY and RRAGENT_TOKEN and is written when the
# machine is set up, never into the image. Build from the repository's
# root after `npm run build`: docker build -f deploy/Dockerfile -t rragent .
FROM ghcr.io/superfly/s2-default@sha256:44f9b4af09c4de3726888f7b9146c9b43529ed97f8963ff11e0f61233e2f1614

USER sprite
WORKDIR /home/sprite/rragent
COPY --chown=sprite:sprite dist/ /home/sprite/rragent/dist/
COPY --chown=sprite:sprite --chmod=700 deploy/start.sh /home/sprite/rragent/start.sh
COPY --chown=sprite:sprite deploy/agent/ /home/sprite/rragent/agent/
