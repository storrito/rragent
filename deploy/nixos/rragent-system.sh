#!/bin/bash
# rragent-system: how the agent of a NixOS Sprite changes its machine.
# The machine is what /etc/nixos says (flake.nix: the NixOS release and
# rragent's base; flake.lock: the commits both are pinned to; local.nix:
# what the agent added), a git repository in which every change that
# took is a commit.
#
#   rragent-system apply      after an edit of /etc/nixos/local.nix:
#                             build the system, switch to it, keep it
#                             when it works, go back when it does not
#   rragent-system upgrade    the same after moving the pins of
#                             flake.lock to the newest commits: newer
#                             packages, security fixes, a newer base
#   rragent-system rollback   back to the system before the last change
#   rragent-system status     what runs, and what the last changes were
#   rragent-system watch      the unit's (rragent-system-watch, root):
#                             back to the last system rragent answered
#                             on when it does not answer on this one
#
# A build that fails changes nothing: Nix builds the new system beside
# the running one and switches to it in one step. A system that was
# switched to and does not work (node, the browser, rragent's bundle do
# not run) is switched back from here, at once. What this script cannot
# see, a system rragent does not start on at the machine's next start,
# the watch sees: rragent writes the system it started on into
# /run/rragent/started-on, the watch keeps the last one it answered on
# in /var/lib/rragent-system/good and goes back to it.
set -u

config=/etc/nixos
flake="$config#sprite"
profile=/nix/var/nix/profiles/system
state=/var/lib/rragent-system
sw=/run/current-system/sw/bin

say() { echo "rragent-system: $*" >&2; }

current() { readlink -f /run/current-system; }

repo() { git -C "$config" -c user.name=rragent -c user.email=rragent@localhost "$@"; }

answers() {
  # rragent answers on its port, with whatever status
  curl -s -o /dev/null --max-time 5 http://127.0.0.1:8080/
}

check() {
  # what has to run on a system for it to be kept
  local failed=0 said
  "$sw/node" --version >/dev/null 2>&1 || { say "node does not run on the new system"; failed=1; }
  "$sw/git" --version >/dev/null 2>&1 || { say "git does not run on the new system"; failed=1; }
  "$sw/ffmpeg" -version >/dev/null 2>&1 || { say "ffmpeg does not run on the new system"; failed=1; }
  "$sw/chromium" --version >/dev/null 2>&1 || { say "the browser does not run on the new system"; failed=1; }
  "$sw/nix" --version >/dev/null 2>&1 || { say "nix does not run on the new system"; failed=1; }
  if [ -f /home/sprite/rragent/dist/rragent.js ]; then
    said=$(cd /home/sprite/rragent && timeout 60 "$sw/node" dist/rragent.js help 2>&1)
    echo "$said" | grep -q serve || { say "rragent's bundle does not load on the new system: $(echo "$said" | tail -1)"; failed=1; }
  fi
  return "$failed"
}

back() {
  # the system $1 again, the one that ran before
  sudo nix-env -p "$profile" --set "$1" >/dev/null && sudo "$1/bin/switch-to-configuration" switch
}

build_and_switch() {
  # the system of /etc/nixos built and switched to, kept when it works;
  # $1 is the commit's message. 0 kept, 1 not built, 2 switched back
  local before
  before=$(current)
  repo add -A
  say "building the system /etc/nixos describes"
  if ! nixos-rebuild switch --flake "$flake" --sudo; then
    if [ "$(current)" = "$before" ]; then
      say "the build failed and nothing changed: the machine runs what it ran before"
      return 1
    fi
    say "the switch reported a problem (above); checking what runs"
  fi
  if ! check; then
    say "going back to the system before"
    if back "$before"; then
      say "the machine runs what it ran before; the change is still in $config, not committed"
    else
      say "the switch back failed too: $before is the system to return to, 'rragent-system rollback' tries again"
    fi
    return 2
  fi
  if [ "$(current)" = "$before" ]; then
    say "the system is the same as before"
  else
    say "the machine now runs $(current)"
  fi
  if ! repo diff --cached --quiet; then
    repo commit -q -m "$1" && say "committed in $config: $1"
  fi
  return 0
}

apply() {
  build_and_switch "${1:-A change of the machine}"
}

upgrade() {
  local status
  say "moving the pins of $config/flake.lock to the newest commits"
  if ! nix flake update --flake "$config"; then
    say "the newest commits could not be looked up, nothing changed"
    repo checkout -- flake.lock 2>/dev/null
    return 1
  fi
  build_and_switch "Upgrade: the newest packages and base"
  status=$?
  if [ "$status" -ne 0 ]; then
    repo reset -q -- flake.lock 2>/dev/null
    repo checkout -- flake.lock 2>/dev/null
    say "flake.lock is the one from before the upgrade again"
  fi
  return "$status"
}

rollback() {
  local before
  before=$(current)
  if ! sudo nix-env -p "$profile" --rollback; then
    say "there is no system before this one"
    return 1
  fi
  if ! sudo "$profile/bin/switch-to-configuration" switch; then
    say "the switch reported a problem (above); checking what runs"
  fi
  check || say "the system before does not pass the check either"
  if [ "$(current)" = "$before" ]; then
    say "the system is the same as before"
    return 0
  fi
  say "the machine now runs $(current), the system before the last change"
  if repo diff --quiet && repo diff --cached --quiet; then
    repo revert --no-edit HEAD >/dev/null 2>&1 \
      && say "the last commit of $config is reverted, so the folder says what runs" \
      || say "the last commit of $config could not be reverted: the folder is one change ahead of what runs"
  else
    say "$config has changes that are not committed; they are kept, and the folder is ahead of what runs"
  fi
}

status() {
  echo "system:   $(current)"
  echo "base:     rragent-sprite-nixos $(cat /etc/rragent/base-version 2>/dev/null || echo unknown)"
  echo "nixos:    $(cat /run/current-system/nixos-version 2>/dev/null || echo unknown)"
  echo "started:  rragent started on $(cat /run/rragent/started-on 2>/dev/null || echo 'nothing yet')"
  echo "good:     $(cat "$state/good" 2>/dev/null || echo 'none yet: rragent has not answered since this machine was made')"
  [ -f "$state/rolled-back" ] && { echo "rolled back by the watch:"; sed 's/^/  /' "$state/rolled-back"; }
  echo "generations:"
  nix-env -p "$profile" --list-generations 2>/dev/null | tail -5 | sed 's/^/  /'
  echo "changes of $config:"
  repo log --oneline -5 2>/dev/null | sed 's/^/  /'
  if ! repo diff --quiet 2>/dev/null || ! repo diff --cached --quiet 2>/dev/null; then
    echo "  and changes that are not applied yet (rragent-system apply)"
  fi
}

watch() {
  # root's, from the timer. rragent answers: the system it started on
  # is good. It does not, for two and a half minutes, on a system other
  # than the last good one: back to that one.
  local tries=0 started good now
  mkdir -p "$state"
  while [ "$tries" -lt 30 ]; do
    if answers; then
      started=$(cat /run/rragent/started-on 2>/dev/null)
      if [ -n "$started" ] && [ "$started" != "$(cat "$state/good" 2>/dev/null)" ]; then
        echo "$started" > "$state/good"
        say "rragent answers on $started: the system to go back to from now on"
      fi
      return 0
    fi
    tries=$((tries + 1))
    sleep 5
  done
  good=$(cat "$state/good" 2>/dev/null)
  now=$(current)
  if [ -z "$good" ]; then
    say "rragent does not answer, and it never did on this machine: nothing to go back to (no .env yet, or no bundle)"
    return 0
  fi
  if [ "$good" = "$now" ]; then
    say "rragent does not answer on the system it answered on before: not the system's doing, nothing to go back to"
    return 0
  fi
  if [ ! -x "$good/bin/switch-to-configuration" ]; then
    say "rragent does not answer on $now, and $good, where it did, is gone from the store"
    return 1
  fi
  say "rragent does not answer on $now: back to $good"
  nix-env -p "$profile" --set "$good" >/dev/null
  "$good/bin/switch-to-configuration" switch
  systemctl restart rragent
  {
    echo "when: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "left: $now"
    echo "back on: $good"
    echo "why: rragent did not answer on port 8080 for two and a half minutes on the system left"
    echo "next: /etc/nixos still describes the system left. Find what broke it (journalctl -u rragent), fix or revert the change there, then rragent-system apply and remove this file."
  } > "$state/rolled-back"
}

case "${1:-}" in
  apply) shift; apply "$@" ;;
  upgrade) upgrade ;;
  rollback) rollback ;;
  status) status ;;
  watch) watch ;;
  *)
    echo "usage: rragent-system apply [message] | upgrade | rollback | status" >&2
    exit 64
    ;;
esac
