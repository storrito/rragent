#!/bin/bash
# rragent as the Sprite's http service: the environment from .env
# (OPENROUTER_API_KEY, RRAGENT_TOKEN, RRAGENT_URL, and the integrations'
# STORRITO_API_URL, STORRITO_CREDENTIAL, COMPOSIO_URL, COMPOSIO_USER_ID
# when the agent has them, see the README), the port the sprite's URL
# routes to, the agent's folder, Node through mise's shims. Without
# a readable .env there is no token and the page would be open to anyone:
# not started then, the service's restart tries again.
[ -r /home/sprite/rragent/.env ] || { echo "no readable /home/sprite/rragent/.env, not starting without the token"; exit 1; }
set -a
. /home/sprite/rragent/.env
set +a
export PATH=/home/sprite/.local/bin:/usr/local/share/mise/shims:/.sprite/bin:/usr/local/bin:/usr/bin:/bin
export PORT=8080
export RRAGENT_HOME=/home/sprite/rragent/agent
cd /home/sprite/rragent
exec node dist/rragent.js serve
