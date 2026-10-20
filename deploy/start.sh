#!/bin/bash
# rragent as the Sprite's http service: the environment from .env
# (RRAGENT_TOKEN, RRAGENT_URL, COMPOSIO_SESSION_ID when the agent has a
# Composio session; the models and the integrations come through the
# sprite's gateway, see the README), the port the sprite's URL routes
# to, the agent's folder, Node through mise's shims. Without a readable
# .env there is no token and the page would be open to anyone: not
# started then, the service's restart tries again. The skills clone of
# the image (/home/sprite/skills, see deploy/Dockerfile) is pulled first,
# so a machine that wakes offers the current skills; a pull that fails
# or takes longer than 20 seconds is said on stderr and the clone from
# the image stands.
[ -r /home/sprite/rragent/.env ] || { echo "no readable /home/sprite/rragent/.env, not starting without the token"; exit 1; }
set -a
. /home/sprite/rragent/.env
set +a
export PATH=/home/sprite/.local/bin:/usr/local/share/mise/shims:/.sprite/bin:/usr/local/bin:/usr/bin:/bin
pull_skills() {
  local said
  said=$(timeout 20 git -C "$1" pull --ff-only --quiet 2>&1) \
    || echo "the skills in $1 were not pulled, the clone from the image stands ($(echo "$said" | tail -1))" >&2
}
pull_skills /home/sprite/skills
export PORT=8080
export RRAGENT_HOME=/home/sprite/rragent/agent
cd /home/sprite/rragent
exec node dist/rragent.js serve
