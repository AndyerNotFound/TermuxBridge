#!/usr/bin/env bash
# Build the Termux-side rime daemon. Run inside Termux:
#   ./pkg/rime/build.sh            # build + install to ~/rime/daemon
set -euo pipefail
cd "$(dirname "$0")"
clang -O2 rime-daemon.c -lrime -o rime-daemon
mkdir -p ~/rime/daemon
cp rime-daemon ~/rime/daemon/
echo "installed: ~/rime/daemon/rime-daemon"
