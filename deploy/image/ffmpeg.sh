#!/bin/bash
# The ffmpeg of an rragent machine: ffmpeg and ffprobe of one static
# build of FFmpeg's newest release branch (9.0), from
# github.com/BtbN/FFmpeg-Builds, put into the folder $1. The image
# takes it into /usr/local/bin (deploy/image/Dockerfile), which comes
# before the distribution's /usr/bin on a machine's PATH; the Tests and
# Release workflows take the same build for their runner, so a filter or
# a loudness number is the same in a test as on a machine. This file is
# the one place that names the build: the Dockerfile and the workflows
# run it.
#
# The build is a month's last one (autobuild-<yyyy>-<mm>-<last day>):
# BtbN keeps those for about two years and deletes a day's build after
# about two weeks, so a pin to any other day stops downloading (the
# workflows' earlier pin, autobuild-2026-10-22, answered 404 a month
# later and ran from the cache alone). The download is checked against
# the checksum the release listed for it, which is written here, not
# fetched: a file changed later is refused.
#
# To move to a newer build: BUILD, NAME and SHA256 from the release's
# page and its checksums.sha256, and the image's version in the
# Dockerfile bumped, as with every change in deploy/image/.
#
# A machine made from an older image gets the same ffmpeg by running
# this file as root: bash ffmpeg.sh /usr/local/bin
set -euo pipefail

BUILD=autobuild-2026-10-31-12-59
NAME=ffmpeg-n9.0.2-39-g7adfbe40dc-linux64-gpl-9.0
SHA256=73e29a56974ae5a651133a04584c7483b21afe941e17dc91d79814a758b608d3

dest="${1:?usage: ffmpeg.sh <the folder ffmpeg and ffprobe go into>}"
[ "$(uname -m)" = x86_64 ] || { echo "this build of ffmpeg is for x86_64, the machine is $(uname -m): nothing installed" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp/ffmpeg.tar.xz" \
  "https://github.com/BtbN/FFmpeg-Builds/releases/download/$BUILD/$NAME.tar.xz"
echo "$SHA256  $tmp/ffmpeg.tar.xz" | sha256sum --quiet -c -
tar -xJf "$tmp/ffmpeg.tar.xz" -C "$tmp" "$NAME/bin/ffmpeg" "$NAME/bin/ffprobe"
mkdir -p "$dest"
install -m 755 "$tmp/$NAME/bin/ffmpeg" "$tmp/$NAME/bin/ffprobe" "$dest/"
"$dest/ffmpeg" -hide_banner -version | head -1
