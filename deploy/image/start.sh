#!/bin/bash
# rragent as the Sprite's http service: the environment from .env
# (RRAGENT_URL, COMPOSIO_SESSION_ID when the agent has a Composio
# session, RRAGENT_TOKEN only on a sprite without the token label; the
# page's token is the sprite's label `token:<hex>`, the models and the
# integrations come through the sprite's gateway, see the README), the
# port the sprite's URL routes to, the agent's folder, Node through
# mise's shims. Without a readable .env the sprite is not set up: not
# started then, the service's restart tries again. The skills clone
# /home/sprite/skills (the GitHub repository SKILLS_REPO at
# SKILLS_BRANCH from .env, storrito/agentitos at main without; the
# Agent Skills format, one folder per skill with a SKILL.md, which a new
# bot offers) is not in the image: the first start of a machine clones
# it, every later start pulls it, so a machine that wakes offers the
# current skills; a clone or pull that fails, or a pull longer than 20
# seconds, is said on stderr and the start goes on, with the clone that
# stands or without skills until the next start.
# The image holds no bundle. At the first start of a machine, when
# dist/rragent.js is missing, the release's rragent.tar.gz is
# downloaded: the newest release's (releases/latest/download, through
# GitHub's redirect, no API) or the release RRAGENT_UPDATE names in
# .env (v<x.y.z>, or trial-<name> for a pre-release of a branch:
# releases/download/<tag>); its checksum is verified and the tarball
# unpacked into dist/. A download that fails ends the script with 1
# and nothing in place: the service's restart tries again.
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
    || echo "the skills in $1 were not pulled, the clone that stands stays ($(echo "$said" | tail -1))" >&2
}
clone_skills() {
  # the skills clone made at the first start of a machine, one commit
  # deep, from SKILLS_REPO at SKILLS_BRANCH (SKILLS_URL stands in for
  # GitHub in a test); a clone that fails is said and the start goes
  # on without skills, the next start tries again
  local url="${SKILLS_URL:-https://github.com/${SKILLS_REPO:-storrito/agentitos}.git}" said
  said=$(timeout 60 git clone --depth 1 --branch "${SKILLS_BRANCH:-main}" --quiet "$url" "$1" 2>&1) \
    || echo "the skills were not cloned into $1 from $url at ${SKILLS_BRANCH:-main}, the machine starts without them ($(echo "$said" | tail -1))" >&2
}
skills() {
  # the clone pulled when it is there, made when it is not
  if [ -d "$1/.git" ]; then
    pull_skills "$1"
  else
    clone_skills "$1"
  fi
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
release_url() {
  # where the release's files are: the newest release's, or the
  # release's that RRAGENT_UPDATE names (RRAGENT_RELEASES stands in for
  # GitHub in a test)
  local releases="${RRAGENT_RELEASES:-https://github.com/storrito/rragent/releases}"
  case "${RRAGENT_UPDATE:-auto}" in
    auto|off) echo "$releases/latest/download" ;;
    *) echo "$releases/download/$RRAGENT_UPDATE" ;;
  esac
}
bootstrap() {
  # dist/ of the install folder $1 made from the release's tarball when
  # it holds no rragent.js: the download into a temp folder beside it,
  # the checksum verified, the unpack, one move; nothing in place when
  # a step fails
  local install="$1" url tmp
  [ -f "$install/dist/rragent.js" ] && return 0
  url=$(release_url)
  tmp=$(mktemp -d "$install/dist.bootstrap.XXXXXX") || return 1
  echo "no $install/dist/rragent.js: taking the bundle from $url" >&2
  if curl -fsSL --retry 3 -o "$tmp/rragent.tar.gz" "$url/rragent.tar.gz" \
     && curl -fsSL --retry 3 -o "$tmp/rragent.tar.gz.sha256" "$url/rragent.tar.gz.sha256" \
     && (cd "$tmp" && sha256sum --quiet -c rragent.tar.gz.sha256) \
     && mkdir "$tmp/dist" && tar -xzf "$tmp/rragent.tar.gz" -C "$tmp/dist" \
     && [ -f "$tmp/dist/rragent.js" ] \
     && mv "$tmp/dist" "$install/dist"; then
    rm -rf "$tmp"
    # the marker rragent.update reads: a pinned release is in place
    case "${RRAGENT_UPDATE:-auto}" in
      auto|off) ;;
      *) echo "$RRAGENT_UPDATE" > "$install/release" ;;
    esac
  else
    echo "the bundle could not be taken from $url (download, checksum or unpack), not starting" >&2
    rm -rf "$tmp"
    return 1
  fi
}
skills /home/sprite/skills
export PORT=8080
export RRAGENT_HOME=/home/sprite/rragent/agent
export RRAGENT_UPDATE="${RRAGENT_UPDATE:-auto}"
cd /home/sprite/rragent
bootstrap /home/sprite/rragent || exit 1
run_until_updated node dist/rragent.js serve
