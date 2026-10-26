#!/usr/bin/env bash
# Local Termux build entry (v1.7.0+). CI equivalent: .github/workflows/build.yml
# Usage: TBRIDGE_KS_PASS=... ./build-apk.sh [extra gradle args]
#   or:  echo -n 'pass' > ~/.android/ks-pass && chmod 600 ~/.android/ks-pass
set -euo pipefail
cd "$(dirname "$0")"

GRADLE="${GRADLE:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | sort -V | tail -1)}"
[ -n "$GRADLE" ] || { echo "gradle not found in ~/.gradle/wrapper/dists" >&2; exit 1; }

if [ -z "${TBRIDGE_KS_PASS:-}" ] && [ -f "$HOME/.android/ks-pass" ]; then
  export TBRIDGE_KS_PASS="$(cat "$HOME/.android/ks-pass")"
fi
[ -n "${TBRIDGE_KS_PASS:-}" ] || { echo "TBRIDGE_KS_PASS not set (env or ~/.android/ks-pass)" >&2; exit 1; }

export TBRIDGE_SKIP_LINT=1   # Termux cmdline-tools SDK XML breaks AGP lint
exec "$GRADLE" :app:assembleRelease --no-daemon "$@"
