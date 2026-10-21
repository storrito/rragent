#!/bin/bash
# rragent as the Sprite's http service: the environment from .env
# (RRAGENT_URL, COMPOSIO_SESSION_ID when the agent has a Composio
# session, RRAGENT_TOKEN only on a sprite without the token label; the
# page's token is the sprite's label `token:<hex>`, the models and the
# integrations come through the sprite's gateway, see the README), the
# port the sprite's URL routes to, the agent's folder, Node through
# mise's shims. Without a readable .env the sprite is not set up: not
# started then, the service's restart tries again. The skills clone of
# the image (/home/sprite/skills, see deploy/Dockerfile) is pulled first,
# so a machine that wakes offers the current skills; a pull that fails
# or takes longer than 20 seconds is said on stderr and the clone from
# the image stands.
# rragent swaps dist/ for a newer release when the person takes an
# update, or by itself once the agent is idle (RRAGENT_UPDATE=auto, set
# below, see rragent.update), and ends with code 75: run_until_updated
# starts it again then, from the new dist/, without the pull and
# without the service's restart. Any other code ends the script with
# it, and the service's restart does what it did before.
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
run_until_updated() {
  local status
  while :; do
    "$@"
    status=$?
    [ "$status" -eq 75 ] || return "$status"
    echo "rragent swapped in a newer release and ended with 75: starting it again" >&2
  done
}
pull_skills /home/sprite/skills
export PORT=8080
export RRAGENT_HOME=/home/sprite/rragent/agent
export RRAGENT_UPDATE="${RRAGENT_UPDATE:-auto}"
cd /home/sprite/rragent
run_until_updated node dist/rragent.js serve
