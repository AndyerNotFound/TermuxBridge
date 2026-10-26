#!/usr/bin/env bash
# 打包 tbridge CLI 为 .deb (Termux 包, 多调用 + 符号链接)
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.10.0
PKG=tbridge
ROOT=build/pkg
PREFIX_DIR="$ROOT/data/data/com.termux/files/usr"

rm -rf "$ROOT"
mkdir -p "$ROOT/DEBIAN" "$PREFIX_DIR/bin" "$PREFIX_DIR/share/doc/$PKG" "$PREFIX_DIR/share/man/man1"

install -m 755 pkg/tbridge "$PREFIX_DIR/bin/tbridge"

for c in toast notification vibrate beep tts-speak clipboard-get clipboard-set \
         battery-status net-status wifi-connectioninfo location sensor \
         features feature torch brightness volume share open dial sms-send \
         screenshot tap swipe ui overlay power notif theme app widget ime media role text record stt service ping help; do
  ln -sf tbridge "$PREFIX_DIR/bin/tbridge-$c"
done

install -m 644 README.md "$PREFIX_DIR/share/doc/$PKG/README.md"
install -m 644 pkg/tbridge.1 "$PREFIX_DIR/share/man/man1/tbridge.1"

cat > "$ROOT/DEBIAN/control" <<CTL
Package: $PKG
Version: $VERSION
Architecture: all
Maintainer: AndyerNotFound <AndyerNotFound@users.noreply.github.com>
Installed-Size: 64
Depends: bash, jq, netcat-openbsd | netcat
Section: utils
Priority: optional
Description: TermuxBridge CLI - Termux:API style commands
 Provides tbridge-toast, tbridge-notification, tbridge-clipboard-get/set,
 tbridge-battery-status, tbridge-sensor, tbridge-location, tbridge-torch,
 tbridge-screenshot, tbridge-tap/swipe/ui and more, talking to the
 TermuxBridge app over 127.0.0.1:8021.
CTL
chmod 644 "$ROOT/DEBIAN/control"
find "$ROOT" -type d -exec chmod 755 {} +

mkdir -p dist
dpkg-deb --build --root-owner-group "$ROOT" "dist/${PKG}_${VERSION}_all.deb"
echo "PACKAGE OK: dist/${PKG}_${VERSION}_all.deb ($(stat -c%s dist/${PKG}_${VERSION}_all.deb) bytes)"
