# TermuxBridge

A bridge between [Termux](https://termux.dev) and Android system capabilities that a pure CLI cannot reach: a foreground service + a local TCP JSON interface (127.0.0.1:8021) + a CLI client `tb`.

One line of JSON in, one line of JSON out — screenshots, notifications, clipboard, sensors, TTS/STT, touch injection, UI tree dumps, overlay windows and more.

- Package: `lll.bridge.termux`
- Default port: `8021` (configurable in the app; the listener restarts on change)
- Bind address: `127.0.0.1` only
- Requirements: Android 9+ (minSdk 28); verified on Android 16, arm64
- License: AGPL-3.0

## Download

Grab `TermuxBridge.apk` and `tbridge_*_all.deb` from [Releases](https://github.com/AndyerNotFound/TermuxBridge/releases). Verify with `SHA256SUMS`.

## Install

```bash
# 1. App (via Shizuku, or open the APK manually)
cat TermuxBridge.apk | ~/Shizuku/rish -c "pm install -r -S $(stat -c%s TermuxBridge.apk)"

# 2. CLI package
pkg install jq netcat-openbsd
dpkg -i tbridge_*_all.deb

# 3. Start the service (any one of)
tbridge-service start
am start-foreground-service -n lll.bridge.termux/.BridgeService -a lll.bridge.START
# or open the app and tap "Start service"
```

Accessibility features (screenshot / touch injection / UI tree) require a one-time grant:

```bash
tb a11y-on    # opens Settings → Accessibility → TermuxBridge
```

Reinstalling over an existing build disables the accessibility service; re-enable it in Settings or:

```bash
rish -c "settings put secure enabled_accessibility_services lll.bridge.termux/lll.bridge.termux.BridgeA11yService; settings put secure accessibility_enabled 1"
```

## Protocol

One JSON request line → one JSON response line, one connection per request.

```bash
echo '{"cmd":"battery","token":"optional"}' | nc -w 5 127.0.0.1 8021
# {"ok":true,"level":22,"charging":false,"status":"discharging","temp":39.3,"voltage":3.42}
```

Errors are always `{"ok":false,"error":"..."}`.

### Command overview

| Category | Commands |
|---|---|
| Meta | `ping` `features` `feature` `proto` |
| Messaging | `toast` `notify` `vibrate` `beep` |
| Clipboard | `clip.read` `clip.write` (read requires focus, via `ClipCatchActivity`) |
| Status | `battery` `net` `bright` `volume` |
| Hardware | `torch` |
| Location/sensors | `location` `sensor.list` `sensor.read` |
| Voice | `tts` `record` (PCM→WAV) `stt.poll` |
| Intents | `share` `open` `dial` `sms` |
| Screen | `shot` (accessibility) `shot.poll` (MediaProjection) |
| Touch/UI | `tap` `swipe` `ui` `a11y` |
| Overlay | `overlay.*` — see [Overlay windows](#overlay-windows) |
| Apps | `app.list` `app.launch` `app.info` `app.settings` |
| Notifications | `notif.*` — see [Notifications](#notifications) |
| IME | `ime.*` — see [IME](#ime) |
| Misc | `power.*` `widget.*` `theme.*` `text.*` `media.*` `role.*` `acc.*` |

## CLI client

31 `tbridge-*` commands (Termux:API style, deliberately not colliding with `termux-*`), plus the short form `tb`:

| Command | Purpose |
|---|---|
| `tbridge-toast TEXT` | Toast (`-d long`) |
| `tbridge-notification -t TITLE -c BODY` | Notification (`-i id` to overwrite) |
| `tbridge-clipboard-get` / `tbridge-clipboard-set` | Clipboard (auto focus capture; stdin supported) |
| `tbridge-battery-status` / `tbridge-net-status` / `tbridge-wifi-connectioninfo` | Status |
| `tbridge-location` | Location |
| `tbridge-sensor -l` / `-s NAME` | Sensor list / single sample (CSV) |
| `tbridge-torch on\|off`, `tbridge-brightness [0-255]`, `tbridge-volume [STREAM [0-MAX]]` | Hardware |
| `tbridge-tts-speak TEXT` | Speak |
| `tbridge-share [TEXT\|-]` / `tbridge-open URL` / `tbridge-dial NUM` / `tbridge-sms-send NUM [TEXT]` | Intents |
| `tbridge-screenshot FILE` | Screenshot (accessibility first, MediaProjection fallback) |
| `tbridge-tap X Y` / `tbridge-swipe X1 Y1 X2 Y2 [MS]` / `tbridge-ui [DEPTH]` | Touch / UI tree |
| `tbridge-record SECONDS [FILE]` / `tbridge-stt` | Recording / speech recognition |
| `tbridge-features` / `tbridge-feature on\|off NAME` | Module toggles |
| `tbridge-service start\|stop\|status` / `tbridge-ping` | Service control |
| `tbridge-overlay ...` | Overlay windows, gestures, events |
| `tbridge-notif status\|recent [N]\|clear\|perm\|dismiss KEY` | Notification listener |
| `tbridge-ime status\|commit TEXT\|show\|hide\|pick` | IME |
| `tbridge-media image\|video [N]` | Gallery listing |
| `tbridge-text TEXT` / `-g` | Write/read the focused input field (accessibility `ACTION_SET_TEXT`) |

Any command accepts `--json` for raw JSON output. Environment: `TBRIDGE_PORT` `TBRIDGE_HOST` `TBRIDGE_TOKEN` `TBRIDGE_TIMEOUT`.

```bash
tb ping
tb toast "build done"
tb notify "backup" "finished $(date +%H:%M)"
tb clip                      # read clipboard (auto focus capture)
tb battery | jq .level
tb shot ~/screen.png         # accessibility, no prompt
tb shot --mp ~/screen.png    # MediaProjection, one-time grant
tb ui 8 | jq -r '.nodes[] | select(.clickable) | "\(.text) \(.bounds)"'
tb tap 640 1600
```

## Overlay windows

Describe layouts in JSON; the app builds the View tree and overlays it on screen. Requires "Display over other apps" (`appops set lll.bridge.termux SYSTEM_ALERT_WINDOW allow` via Shizuku works too).

```bash
tbridge-overlay show card.json     # tbridge-overlay demo for an example
tbridge-overlay update card.json   # in-place diff update
tbridge-overlay bounds             # real screen coordinates
tbridge-overlay events --since N --wait 20   # long-poll events (max 25 s)
tbridge-overlay values <id>        # input field contents
tbridge-overlay tree demo          # view tree snapshot (debug)
tbridge-overlay hide [id|all]
```

Node types: `col`/`row` (containers), `text`, `button`, `input`, `progress`, `divider`, `spacer`. Common props: `w`/`h` (number = px, or `wrap`/`match`), `m`, `pad`, `bg`, `radius`, `alpha`, `weight`, `gravity` (position within container), `align` (position of text within its own box).

Gestures on nodes with an `id`/`on`: `click` / `double` / `triple` / `long` / `long_hold` / `drag:start|move|end|cancel` / `swipe:*`. All thresholds are per-node configurable (`tap_ms` 280, `hold_ms` 500, `slop` 20dp, `swipe` 56dp). Events carry `dev` (`-1` = injected, positive = real touchscreen).

Attribution rules: only nodes declaring `on` handle gestures (others bubble touches to the parent); a root node with `on` takes priority over `drag_window`. `overlay.show` accepts `"trace":true` to log `win:down/move/up` into the event stream.

In-place updates (`overlay.update`) diff the view tree and mutate it without rebuilding: measured 4/1/1/0 ms vs. 29/12/20/19 ms for a full rebuild on the same 3-node card. Drag performance: `updateViewLayout` averages 0.06 ms; commit interval p50 = 16.6 ms (steady 60 fps, the window-repositioning ceiling). `tbridge overlay stats` reports live counters.

## Notifications

```json
{"cmd":"notify","id":9001,"channel":"live","channel_name":"progress",
 "title":"backup","text":"6/10","ongoing":true,
 "progress":{"value":60,"max":100,"live":true,"segments":["FF6750A4"]},
 "actions":[{"label":"pause","json":"{\"cmd\":\"toast\",\"text\":\"paused\"}"}]}
```

- Channel management: `notify.channels`, `notify.channel` (attributes immutable once created).
- `progress.live=true` uses `Notification.ProgressStyle` and requests promotion to a Live Update (Android 16). Requires `appops set lll.bridge.termux POST_PROMOTED_NOTIFICATIONS allow`.
- `actions[].json` executes an arbitrary bridge command on tap.

The notification listener (`BridgeNotifListener`) caches the last 80 notifications. Grant without dialogs: `cmd notification allow_listener lll.bridge.termux/lll.bridge.termux.BridgeNotifListener`.

## IME

`BridgeImeService` is a minimal IME (built-in QWERTY + numeric keypad) that also accepts text injected from Termux:

```bash
rish -c "ime enable lll.bridge.termux/.BridgeImeService"
rish -c "ime set lll.bridge.termux/.BridgeImeService"
tbridge-ime commit "text"
```

Note: writing to input fields usually does **not** need the IME at all — `tbridge-text` uses accessibility `ACTION_SET_TEXT` and works with any keyboard.

## Battery / background behavior

- `tbridge-power request` — battery optimization exemption (or `cmd deviceidle whitelist +lll.bridge.termux` via Shizuku).
- The accessibility service subscribes to window-state changes only by default; `tbridge acc.events both` opts into content-change events, `acc.stats` shows counters.
- Event consumers should long-poll (`overlay events --since N --wait 20`); the daemon fully sleeps while idle.
- Measured footprint: PSS 18.5 MB, idle CPU 0.0%. (RSS ~108 MB is almost entirely shared `.art`/`.oat` pages.)

## Security

- Listens on `127.0.0.1` only; never exposed to the LAN.
- Optional token auth (`TBRIDGE_TOKEN` in the app; requests must then carry `"token"`).
- The broadcast fallback channel only accepts commands with no UI dependency.
- No network uploads, no third-party SDKs or dependencies.

## Build from source

Build requires only local tools — `javac`, `aapt2`, `d8`, `zipalign`, `apksigner`, `keytool` (Termux: `pkg install openjdk-21 aapt aapt2 apksigner zipalign d8`). Only `android.jar` is needed from the SDK.

```bash
export ANDROID_SDK=$HOME/android-sdk        # with platforms/android-36/android.jar
export TBRIDGE_KS_PASS=your-pass            # generates ~/.android/bridge.keystore on first run
./build.sh                                  # → build/TermuxBridge.apk
./package.sh                                # → dist/tbridge_*_all.deb
```

The APK is self-signed. Keep the same keystore across releases or upgrades will require uninstall/reinstall.

## Compatibility notes

- Accessibility screenshots use the accessibility API (Android 11+, no prompt); MediaProjection is the fallback.
- Clipboard read/write requires window focus → transparent capture activity (Android 10+ system restriction).
- Background service starts are restricted on Android 12+ → use Shizuku or start from the app.
- `APPLICATION_OVERLAY` coordinates already start below the status bar; negative `x/y` are clamped to the visible area.

## Not implemented (by design)

| Capability | Reason |
|---|---|
| TTS **engine** provider | Needs a real synthesizer; `tbridge-tts-speak` is a client of the system engine. |
| Installer provider | `rish`/`pm install` already covers this. |
| NFC/HCE card emulation | Requires card-network-certified AIDs and keys; a fraud/compliance red line. NFC tag reading would be feasible. |
| Default role (browser/dialer/SMS) | `createRequestRoleIntent` only fires for apps with matching intent filters; becoming a default app means implementing that app. |

## Project layout

```
src/lll/bridge/termux/
  BridgeService.java        Foreground service + TCP server
  Dispatcher.java           Command routing + feature toggles
  Cmds*.java                Feature modules
  BridgeA11yService.java    Accessibility: screenshots / gestures / UI tree
  *Activity.java            Transparent activities (clipboard focus / projection grant / STT)
  BridgeReceiver.java       Broadcast fallback channel
```

## License

[AGPL-3.0](LICENSE). Note AGPL §13: if you offer this software as a network service, you must provide the complete corresponding source code to its users.

```
Copyright (C) 2026 AndyerNotFound

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published
by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
