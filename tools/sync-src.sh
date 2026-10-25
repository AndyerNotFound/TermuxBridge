#!/usr/bin/env bash
# app/src/main/java is the source of truth; mirror it to src/ for the
# legacy zero-dep syntax-check pipeline. Run after editing Java sources.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf src/lll
cp -r app/src/main/java/lll src/lll
echo "src/ synced from app/src/main/java"
