#!/bin/bash
# The voice denoiser of an rragent machine: DPDFNet-2 at 48 kHz, a small
# neural network that takes noise out of a spoken voice on the CPU, run
# by sherpa-onnx's offline denoiser. Three files into the prefix $1
# (/opt/sherpa-onnx in the image, the program linked into
# /usr/local/bin):
#   <prefix>/bin/sherpa-onnx-offline-denoiser   the program (k2-fsa/sherpa-onnx, Apache-2.0)
#   <prefix>/lib/libonnxruntime.so              what runs the network (onnxruntime 1.28.2, MIT)
#   <prefix>/models/dpdfnet2_48khz_hr.onnx      the network (Ceva-IP/DPDFNet, Apache-2.0)
# The program looks for the library beside itself ($ORIGIN/../lib), so
# nothing goes into the system's library folders, and it needs no
# network and no graphics card. Nothing of it runs when a machine
# starts: agentitos' voice-sound skill starts it for a recording whose
# pauses it measured as noisy, and works with ffmpeg alone where it is
# not there.
#
# Why it is on the machine: ffmpeg's own noise filters learn one steady
# noise and cannot follow one that changes. On twelve real recordings
# with a café or a keyboard behind the voice the background's score
# (DNSMOS, 1 to 5) moved from 2.72 to 2.74 with ffmpeg and to 3.56 with
# this in front (agentitos-lab, 2026-11-19); it takes about a seventh of
# a recording's length on one core and holds the recording in memory,
# about 80 MB a minute.
#
# The downloads are checked against checksums written here, not fetched:
# the model's release (speech-enhancement-models) is one whose files can
# be replaced. To move to a newer release: the five values below, and
# the image's version in the Dockerfile bumped.
set -euo pipefail

VERSION=v2.0.2
ARCHIVE=sherpa-onnx-v2.0.2-linux-x64-shared-no-tts
ARCHIVE_SHA256=c6725b8994a5e559678f72ddffbf1c14b41fc3f6f650618b98eeb61581b63da2
MODEL=dpdfnet2_48khz_hr.onnx
MODEL_SHA256=0b399f8a58dc4d70d8cd97541f5c39869406145193b957d00a03b66070944928

prefix="${1:?usage: denoiser.sh <the prefix its bin, lib and models folders go into>}"
[ "$(uname -m)" = x86_64 ] || { echo "this build of the denoiser is for x86_64, the machine is $(uname -m): nothing installed" >&2; exit 1; }

releases=https://github.com/k2-fsa/sherpa-onnx/releases/download
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp/sherpa.tar.bz2" "$releases/$VERSION/$ARCHIVE.tar.bz2"
echo "$ARCHIVE_SHA256  $tmp/sherpa.tar.bz2" | sha256sum --quiet -c -
curl -fsSL --retry 3 -o "$tmp/$MODEL" "$releases/speech-enhancement-models/$MODEL"
echo "$MODEL_SHA256  $tmp/$MODEL" | sha256sum --quiet -c -
tar -xjf "$tmp/sherpa.tar.bz2" -C "$tmp" "$ARCHIVE/bin/sherpa-onnx-offline-denoiser" "$ARCHIVE/lib/libonnxruntime.so"

# each file written beside its place and moved there in one step
mkdir -p "$prefix/bin" "$prefix/lib" "$prefix/models"
install -m 755 "$tmp/$ARCHIVE/bin/sherpa-onnx-offline-denoiser" "$prefix/bin/.denoiser.new"
install -m 644 "$tmp/$ARCHIVE/lib/libonnxruntime.so" "$prefix/lib/.onnxruntime.new"
install -m 644 "$tmp/$MODEL" "$prefix/models/.model.new"
mv -f "$prefix/lib/.onnxruntime.new" "$prefix/lib/libonnxruntime.so"
mv -f "$prefix/models/.model.new" "$prefix/models/$MODEL"
mv -f "$prefix/bin/.denoiser.new" "$prefix/bin/sherpa-onnx-offline-denoiser"
echo "$prefix/bin/sherpa-onnx-offline-denoiser with $prefix/models/$MODEL"
