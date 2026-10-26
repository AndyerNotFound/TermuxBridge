# TermuxBridge — Overlay protocol reference

Wire format (all commands): one JSON request line → one JSON response line over
`127.0.0.1:8021`, one connection per request. Errors are always
`{"ok":false,"error":"..."}` — error strings are machine-readable and stay
English-only regardless of system locale. Command overview: see
[README](../README.md); this page documents the overlay event stream and the
text-input editor flow in detail.

## Overlay commands

| Command | Params | Response |
|---|---|---|
| `overlay.show` | `id`, `x`, `y`, `w`, `h`, `view`, `drag_window`, `trace` | `ok` |
| `overlay.update` | same subset to change; diffs in place | `ok` |
| `overlay.hide` | `id` or `"all"` | `ok` |
| `overlay.list` | — | `windows: [id]` |
| `overlay.bounds` | — | `windows: [{id,x,y,w,h}]` (real px) |
| `overlay.tree` | `id` | `nodes: [{depth,cls,id,x,y,w,h,text}]` |
| `overlay.values` | `id` | `values: {nodeId: value}` |
| `overlay.events` | `since`, `clear`, `wait` | `events: [...]`, `last_seq` |
| `overlay.stats` | — | commit/move counters, frame-gap percentiles |
| `overlay.perm` | `request` | `granted`, `hint` |

`overlay.events` semantics: without `since` the queue is read-and-cleared;
with `since` events are retained (re-readable). `wait` long-polls up to 25 s.
The buffer holds the last 500 events; `seq` is monotonic per service lifetime.

## Panic brake

| Command | Params | Response |
|---|---|---|
| `panic.stop` | — | cuts everything at once: overlay windows removed, a11y `disableSelf()` (system clears enabled_accessibility_services), notif listener unbound, TCP accept-loop gated, foreground service stopped, START_STICKY broken, boot auto-start suppressed |
| `panic.status` | — | `panicked`, `a11y`, `notif`, `service_up` |

While panicked the TCP accept-loop still accepts one connection per request and
answers `{"ok":false,"error":"panicked - restart service to recover"}`-style
status replies; the broadcast channel refuses everything. Recovery: `am start-
foreground-service -n lll.bridge.termux/.BridgeService -a lll.bridge.START`
(clears the flag) or the MainActivity PANIC-adjacent start button.

## Node styling (MD3)

| Node | New params | Values / notes |
|---|---|---|
| `button` | `style` | `filled` (default) / `tonal` / `elevated` / `outlined` / `text` — real Material 3 button styles; changing `style` via `overlay.update` rebuilds the view |
| `input`, `textinput` | `style` | `outlined` (default) / `filled` — MD3 text field box styles |
| `switch` | — | now a true MD3 `MaterialSwitch` |
| `slider` | `label` | `float` (default) / `none` / `inside` — value label behavior |
| `col`, `row`, `text`, `card`, `chip` | — | declaring `on` now adds an MD3 state-layer ripple (12% primary) |

Inline text editing (input/textinput, default unless `mode:"dialog"`): tapping
the field transiently makes the overlay window focusable, shows the IME; DONE,
BACK on the field, or a tap outside the window (events still pass through to
apps below) re-seals it NOT_FOCUSABLE and emits `change {window,id,value}`.

## Event stream

All events carry `seq`. Gesture/input events carry `window` (overlay id) and
`id` (node id). `dev` is `-1` for injected/synthetic input, a positive device
id for a real touchscreen.

| `type` | Extra fields | Trigger |
|---|---|---|
| `click` | `text`, `dev`, `ts` | tap on a node declaring `on` (tap ≤ `tap_ms`, movement ≤ `slop`) |
| `gesture` | `gesture`, `text`, `dev`, `ts` (+ `dx`,`dy`,`ms` for drag/swipe) | `double`, `triple`, `long`, `long_hold`, `drag:start/move/end/cancel`, `swipe:up/down/left/right` |
| `change` | `value`, `dev:-1` | `switch` → boolean; `slider` → float; `textinput`/`input` → committed string (see editor flow) |
| `edit_request` | `window`, `id` | ACTION_DOWN on an `input`/`textinput` field, immediately before the editor activity launches |
| `cancel` | `window`, `id`, `dev:-1` | editor dismissed without submitting |
| `edit_failed` | `window`, `id`, `error` | editor activity could not start (e.g. background-start restriction); `error` is the exception string |
| `win:down/move/up/...` | `id`, `dev`, `src`, `tool`, `p`, `x`, `y` | every raw touch on the window root — only when the window was shown with `"trace":true` |

A fast flick that never exceeds `swipe` threshold without a preceding MOVE
still emits `swipe:<dir>`; a drag ending within 250 ms above the swipe
threshold emits `drag:cancel` followed by `swipe:<dir>` instead of
`drag:end`.

## Text input editor flow

Overlay windows are never focusable (a focused overlay would swallow system
back and other IME interactions), so `input` and `textinput` fields do not
take direct focus. Tapping a field runs:

```
tap on field
  → event edit_request {window, id}
  → TextInputActivity opens (transparent, focused; preset = current field text)
      OK button / IME DONE → event change {window, id, value}   # value also written into the overlay field
      Cancel button / tap outside card / back key → event cancel {window, id}
  → overlay.values reflects the committed value
```

Rules and edge cases:

- Node props for `textinput`: `hint`, `text` (preset), `title` (editor dialog
  title; defaults to the app's localized "Input" label), `multiline`, `size`,
  plus the common props. Plain `input` supports the same `title`.
- Node `id` defaults to `input<N>` when omitted.
- Back key: first press dismisses the IME, second press cancels. On API 33+
  this routes through `OnBackInvokedDispatcher`; the system back gesture is
  never hijacked.
- Taps on the card itself never cancel; taps outside the card cancel, with a
  300 ms grace window after launch so the tap that opened the editor is
  ignored.
- One editor at a time: a repeat tap on the same `window/node` within 700 ms
  is dropped. The guard clears when the editor activity is destroyed, so a
  backgrounded editor can never lock the feature.
- If the editor activity cannot start, `edit_failed` is emitted with the
  exception text and the overlay stays usable.

## CLI mapping

```bash
tbridge-overlay show card.json          # overlay.show
tbridge-overlay events 0 false          # overlay.events since=0 clear=false
tbridge-overlay values demo             # overlay.values
tbridge-overlay tree demo               # overlay.tree
tbridge-overlay bounds                  # overlay.bounds
```
