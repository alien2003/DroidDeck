# Pen and stylus input

This document describes how DroidDeck turns an Android pen into a graphics tablet for Linux programs:
pressure, tilt, hover, eraser, side buttons and palm rejection, with a mouse fallback for programs and
sessions that cannot take tablet input. It records the design, the measurements it rests on, the test plan
and the open risks. The validation report for the implementation is added at the end.

Status: design. Nothing described below exists in the tree yet.

## Today

There is no pen code in the app, the compositor or the guest patches. A pen is handled as whatever its
Android source looks like:

- A built-in pen (`SOURCE_TOUCHSCREEN | SOURCE_STYLUS`: S Pen, OPPO Pen, USI pens) takes the finger route of
  the current touch mode. In the desktop with touch mode Auto it drives the trackpad, so the cursor moves
  relative to where the pen lands; in Steam it is a direct touch with the hold-to-right-click delay. Hover
  events reach `SessionActivity.dispatchGenericMotionEvent`, are neither a controller nor a mouse, and are
  dropped. Pressure, tilt, buttons, the eraser and historical samples are discarded.
- An external drawing tablet (`SOURCE_MOUSE | SOURCE_STYLUS`, Android's POINTER mode) is taken by `onMouse`.
  Hover moves the cursor, but the tip never clicks because `onMouse` ignores `ACTION_DOWN/UP`. It also counts as
  a hardware mouse, so DroidDeck asks for pointer capture, which disables a POINTER-mode touch device (Android
  15 and 16) or turns it into an unscaled touchpad that DroidDeck then ignores (Android 13 and 14).
- KWin's nested Wayland backend has no tablet code (`WaylandInputDevice::isTabletTool()` returns false and
  `zwp_tablet_manager_v2` is never bound), so a tablet could not reach Plasma apps even if the app compositor
  offered one.

The only request upstream is [#318](https://github.com/Droid-Deck/DroidDeck/issues/318): hover moves the cursor,
the tip holds the left button, the side button right-clicks, and taps must not stay stuck as held clicks after
switching touch modes. Two users mention card games (Slay the Spire 2, Balatro) where hover shows the card text.
That is this design's Mouse behaviour.

## Measured hardware

Device: OPPO Find N6 (CPH2765), Android 16, ColorOS 16.0.10.500, OPPO AI Pen. Captured with `getevent -lp`,
`dumpsys input` and a probe app that logs every `MotionEvent` and `KeyEvent`.

| | Inner screen | Cover screen |
|---|---|---|
| evdev device | `touchpanel_pen` | `touchpanel_pen1` |
| Position | `ABS_X` 0..35967, `ABS_Y` 0..39679 (16 per pixel of 2248×2480) | 0..18239 × 0..41855 (16 per pixel of 1140×2616) |
| Pressure | `ABS_PRESSURE` 0..4095 → `AXIS_PRESSURE` 0..1 | same |
| Tilt | `ABS_TILT_X/Y` −60..60 → `AXIS_TILT` 0..π/2, `AXIS_ORIENTATION` −π..π | same |
| Distance | `ABS_DISTANCE` 0..1: 1.0 while hovering, 0.0 in contact | same |
| Tool | `BTN_DIGI` (pen); no `BTN_TOOL_RUBBER` | same |
| Barrel bits | `BTN_STYLUS`, `BTN_STYLUS2` declared, never sent | same |

- **The side button is not a digitizer button.** The pen's only button arrives as `KEYCODE_F21` (scan code 191)
  from a separate Bluetooth HID keyboard named `OPPO AI Pen`. The key reaches the focused app and repeats while
  held. ColorOS also opens its Stylus Palette for it unless the button action is turned off in the system's
  stylus settings.
- **Capabilities must be read late.** While the screen is off, `InputDevice.getMotionRanges()` is empty for
  both pen devices, so they are read at the first pen event.
- **Tilt drops out at proximity edges.** The first hover sample after `HOVER_ENTER`, and a few around the edges
  of hover range, report `AXIS_TILT == 0` and `AXIS_ORIENTATION == 0` exactly (37 of 1860 hover samples, 2 of
  1404 contact samples). Sent as "pen upright", they make brushes jump; such samples carry no tilt.
- **The device changes with the screen.** Folding moves the pen from one evdev device to the other. Pen state is
  not keyed on the device id.
- **Palm.** A palm that lands while the pen is out of hover range arrives as an ordinary finger. When the pen
  then enters hover, Android cancels that finger stream (`ACTION_CANCEL` with `FLAG_CANCELED`) just before
  `HOVER_ENTER`.

Timing in the probe app:

| | Buffered dispatch | Unbuffered dispatch |
|---|---|---|
| Contact | 90 Hz, ~3.8 samples per event, 2 ms median delay | 360 Hz, 1 sample per event, 1 ms median delay |
| Hover | 60 Hz, ~1.9 samples per event, 8 ms median (13 ms p90) | 360 Hz, 1 sample per event, 1 ms median (2 ms p90) |

DroidDeck already requests unbuffered dispatch for `SOURCE_CLASS_POINTER` in its main window when Low latency
mode is on (the default), and a stylus is a pointer-class source, so the pen arrives at full rate without a new
setting. The lower-screen windows get the same request. Hover still looked heavier than contact in the probe: the
display reports `frameRateCategoryRate {normal=60, high=90}`, and Android raised the refresh rate for contact
(events every 11.1 ms) but not for hover (every 16.6 ms). Validation checks whether a DroidDeck session keeps
its own refresh rate while a pen hovers; if not, the session votes for a high frame rate while a pen is in
proximity.

A synthetic pen works without root. The ADB shell user is in the `uhid` group, and `/dev/uinput` is
`uhid:uhid 0660`, so `adb shell uinput -` can register a direct pen with pressure, distance, tilt,
`BTN_TOOL_PEN/RUBBER` and `BTN_STYLUS/2`. Android classifies it like the built-in pen (source 0x5002, tool type
stylus). A barrel button pressed on it produces no `ACTION_BUTTON_PRESS` and no key event; it only appears as
`BUTTON_STYLUS_PRIMARY` in the `buttonState` of the following motion events. `KEYCODE_STYLUS_BUTTON_*` key events
are consumed by Android 14+ and never reach apps.

## Architecture

```
MotionEvent / KeyEvent (main thread, unbuffered)
  SessionActivity.dispatchTouchEvent ── PalmGate (fingers only) ──▶ view tree as today
  SessionActivity.onTouchEvent / dispatchGenericMotionEvent / dispatchKeyEvent / onBottomGameTouch
        │ pen pointers: tool type STYLUS or ERASER, whatever the source
        ▼
  PenInput  samples with history and time, OUT → HOVER → CONTACT, button diff, tilt, pressure curve
  PenRouter.decide(snapshot) → BLOCKED | PEN(wantTablet) | RELATIVE | TOUCH
        │ PEN: one input_msg per sample             │ RELATIVE, TOUCH: existing pointer and finger routes
        ▼                                           ▼
  nativeSendPen ──pipe──▶ compositor thread: deliver_pen picks, per stroke,
                            TABLET  → wl_tablet.c (tablet-v2)            when wantTablet and the client bound it
                            POINTER → pointer emulation                   otherwise
                            POINTER_LOCKED → buttons only                 under a program's pointer lock
                                │
        nested KWin + patch: host tablet-v2 → KWin tablet InputDevice → KWin's own tablet pipeline
                                ├─ Wayland clients: tablet-v2 (Krita, GTK, Qt, Firefox)
                                ├─ X11 clients: Xwayland's "xwayland-tablet stylus/eraser" XI2 devices (GIMP, Wine)
                                └─ clients without tablet-v2: KWin's pointer emulation
        gamescope (no tablet-v2): pointer emulation from the host compositor
```

Each layer decides what only it can see:

- **Android** decides whether a pen event belongs to the pen at all: UI on top, the pen mode, the session kind,
  the screen (phone, glasses, lower screen), palm rejection. It extracts every sample and keeps the pen's
  proximity, contact and button state.
- **The app compositor** decides per stroke whether the target client gets tablet-v2 or a pointer, because only
  it knows which client is under the pen, what that client bound, and whether a program holds a pointer lock. It
  never turns a pen into touch and never upgrades a mouse stroke to a tablet stroke.
- **KWin** turns the host tablet into an ordinary KWin tablet device. Focus, implicit grabs, tablet-v2 for Plasma
  apps, Xwayland's devices and the tool cursor are KWin's own code.

Both sides freeze their choice for a stroke, from tip down or the first button press to the last release, so a
stroke is never delivered half as tablet and half as pointer. They reconcile between strokes; Kotlin always
closes the old channel before opening a new one, on the same thread and through the same pipe.

## Pen modes

Stored as `SessionPrefs` key `"pen"`, global like `"touch"`, default `auto`.

| Mode | Desktop (KWin) | Steam, Big Picture or desktop UI | Program in gamescope |
|---|---|---|---|
| **Auto** (default) | Tablet; programs without tablet input get a pointer | Mouse | Mouse |
| **Mouse** | Absolute pointer: hover moves it, tip = left button, side button = right button | same | same |
| **Touch** | The pen is a finger in the current touch mode, as today | same | same |

- **No separate "Pen" mode.** A tablet can only reach a client that bound tablet-v2, and the compositor makes
  that choice per stroke, so a forced tablet mode would behave exactly like Auto in every session: tablet in
  KWin, pointer in gamescope, which binds no tablet-v2.
- **Mouse** is for programs that take tablet input but behave worse with it than with a mouse (Moonlight's
  #1317 is the classic case), and for anyone who wants a real right click everywhere.
- **Touch** keeps today's behaviour, including the touch modes' gestures.
- **Auto in Steam is Mouse**, not Touch: #318 asks for hover and tooltips in games running in Steam, and the
  pen has its own right-click button, so it does not need Steam's per-game touch-click modes.
- **Program pointer locks.** When a program in gamescope locks the pointer (camera control), Auto and Mouse
  route the pen like the trackpad's locked swipe: a tap clicks, a drag is relative motion, hover does nothing.
  KWin's own desktop grab (Right Ctrl) is not a program lock and changes nothing for a tablet.
- **Glasses.** When the picture is on glasses, the phone is a trackpad; the pen moves the pointer relatively.
- In Auto and Mouse the pen never goes through the touchpad or gesture recognisers, so it cannot leave a
  touchpad drag held when the touch mode changes, the failure reported on #318.
- The drawer shows Auto with its resolved route, like the touch mode: "Auto (tablet)" or "Auto (mouse)". The
  compositor publishes the route the pen would get now in its query snapshot: tablet when the client under the pen's
  last position (before the first pen sample, the client with keyboard focus or under the pointer) bound tablet-v2
  and is not gamescope. Steam, glasses and a program's pointer lock show "Auto (mouse)". The launcher's Controls tab
  shows plain Auto, since no session is running there.

## Settings

The settings sit next to the touch mode: the Steam and Desktop page sheet, **Controls** tab, and the session
drawer, **Controller** page, as a "Pen" group after "Touchscreen". The group is shown only when a stylus device
exists or a pen event was seen; the first of either sets `penSeen`, which a settings reset keeps. Before Android 14
every touchscreen whose driver reports a tool type has the stylus source, pen or not, so there a touchscreen counts
only with its first pen event. Side button is hidden in Touch, and Pressure is shown only for Auto in the desktop,
the one place a tablet gets it. Every key in the table is in `SettingsReset.SESSION_KEYS`; the device report gets
a line for each.

| Setting | Key | Values | Default | Scenario |
|---|---|---|---|---|
| Pen | `pen` | Auto, Mouse, Touch | Auto | the modes above |
| Palm rejection | `penPalm` | While the pen is near, Fingers only scroll, Off | While the pen is near | a hand resting on the screen while writing |
| Side button | `penButton` | Right click, Middle click, Eraser while held, Off | Right click | single-button pens; pens without an eraser end |
| Pressure | `penPressure` | Soft, Normal, Firm | Normal | pens that never reach full pressure in normal strokes; apps without their own curve |

- **Palm rejection** is described below.
- **Side button.** It applies to the pen's first barrel button and to a Bluetooth pen key. Right click sends
  `BTN_STYLUS2` to tablet clients (GTK, Qt, Xwayland and Krita read it as the right button) and `BTN_RIGHT` as a
  pointer. Middle click sends `BTN_STYLUS` / `BTN_MIDDLE`. A second barrel button, where a pen has one, sends the
  other click: middle when the side button is Right click, right otherwise. "Eraser while held" switches the tool to
  the eraser: while hovering, at once (on the tablet route the pen tool's `proximity_out`, then the eraser's
  `proximity_in`, which is not the pen leaving; see Android capture); during contact the switch waits until the tip
  lifts, and a press that comes with the tip's `DOWN` starts the stroke as the eraser. Off sends nothing, and a
  Bluetooth pen key is still consumed. The raw mapping (primary = `BTN_STYLUS`, read as middle) is one choice away.
  The mapping is done in Kotlin; the compositor renders the right and middle bits it gets.
- **Eraser end.** A real eraser is a separate tablet-v2 tool. As a pointer, eraser contact is a right click, as in
  Moonlight and GameNative; hover never holds a button.
- **Pressure.** Soft, Normal and Firm are cubic Bézier curves from (0,0) to (1,1), the representation KWin and
  GNOME use. In KWin's format the control points are Soft `0,0.25;0.75,1;`, Normal `0,0;1,1;` (the identity, KWin's
  default) and Firm `0.25,0;1,0.75;`, which is GNOME's mapping of its feel slider at 0.375, 0.5 and 0.625. Half
  pressure becomes 0.68 with Soft and 0.32 with Firm; Firm is Soft mirrored. Kotlin applies the curve once, to every
  pen record after clamping to 0..1 and before quantising to 0..65535; the pointer routes ignore pressure. The curve
  is a table of 1025 points at even steps of the Bézier parameter, built when the setting changes, and follows the
  exact curve within 0.00004. Even steps of pressure would be off by up to 0.004 at the end where Soft and Firm rise
  like a square root. KWin applies its own curve only to libinput devices, so a nested tablet gets no second curve,
  and the Plasma Drawing Tablet page lists only libinput devices and does not show the DroidDeck pen. A program's
  own curve, such as Krita's, applies on top. DroidDeck never writes Plasma's `[ButtonRebinds]`.
- **No cursor setting.** In tablet mode KWin draws the tool cursor the app asks for (Krita's brush outline, a cross
  by default); as a pointer the pen moves the normal cursor.

## Palm rejection

One gate, `PalmGate`, in Kotlin at the window level: `SessionActivity.dispatchTouchEvent` and a new
`BottomGameScreen.dispatchTouchEvent`, before the view tree. Only Kotlin can cover every finger consumer
(trackpad, gestures, scroll, direct touch, on-screen controls, Steam touch controls); the compositor sees only
direct touches.

- **Armed** while a direct pen (tool type stylus or eraser on the phone's own screen) hovers or touches, and for
  400 ms after its last presence. External tablets never arm it: a pen that reports a mouse source arms only when
  its device is built in (`InputDevice.isExternal` is false, Android 10 and later), while a plain stylus source, as
  `adb shell input stylus` sends, counts as direct. The gate is global, so a pen on either screen arms it for both.
- **While the pen is near** (default): a finger that goes down while the gate is armed is rejected until it lifts,
  even after the pen leaves, and, as in Android, so is any finger that joins a stream still holding a rejected one.
  When the gate goes from disarmed to armed, fingers already down are cancelled with one `ACTION_CANCEL` through
  the view tree. A pen that lands in a finger stream (a pen and fingers on one device) starts its own stream there.
- **Fingers only scroll**: as above, and whenever the pen mode is not Touch, one-finger drags scroll and finger
  taps and long presses do nothing, so a hand on the screen can never click. It takes over every touch mode except
  Off.
- **Off**: fingers are never filtered by the app. From Android 14 QPR2 the dispatcher itself drops fingers from
  another device while a pen hovers over or touches the window, and a pen that comes back cancels a finger that
  slipped in (`InputState` stylus arbitration), so on those releases Off only matters after the pen has left.
  Measured on the Find N6 (Android 16): taps during a steady hover never reach the app.
- **Always, whatever the setting**: `ACTION_CANCEL`, `UP` with `FLAG_CANCELED` and `POINTER_UP` with
  `FLAG_CANCELED` are Android's own palm decisions and cancel the finger stream without a click; the rest of that
  stream is dropped, because `wl_touch` cannot cancel one touch point. On-screen controls release only the
  cancelled pointer, without its action, so a grip palm does not let go of a stick. `pointerSubset`, which splits
  events for on-screen controls, keeps cancel and history: a cancelled `POINTER_UP` that is the only pointer left
  becomes `ACTION_CANCEL`, as in Android's own split.
- **A hover that stops reporting** for 2 s counts as gone; the pen state machine sends proximity-out with the
  same rule, from a timer, while the gate checks it at the next event. An explicit `HOVER_EXIT` is never
  backdated, because a palm on a same-device digitizer ends the hover with a `HOVER_EXIT` that has the finger's own
  time. Lifecycle cancels force the pen out, and so does removing its device.
- Pen and mouse pointers are never filtered. Without a pen there is no change, and the gate does not allocate.

## Axes and buttons

| Android | Compositor message | tablet-v2 (host → KWin) | KWin | Xwayland | Pointer emulation |
|---|---|---|---|---|---|
| x, y (view px) | input space × 256 (24.8 fixed) | `motion`, surface-local `wl_fixed` | `mapToOutput` (desktop scale of patch 0001) | x/y valuators | `wl_pointer.motion` |
| `AXIS_PRESSURE` 0..1 | curve applied, 0..65535 | `pressure` 0..65535 | 0..1 | pressure 0..65535 | contact ⇒ `BTN_LEFT` |
| `AXIS_TILT` θ, `AXIS_ORIENTATION` φ | tilt x/y in 1/100° | `tilt` in degrees | degrees | tilt −64..63 | — |
| `AXIS_DISTANCE` | 0..65535 of the device range | `distance`, only for devices with a real range | 0..1 | — | — |
| `HOVER_ENTER`, `HOVER_EXIT` | in proximity | `proximity_in`, `proximity_out` | proximity | in/out | motion only |
| `DOWN`, `UP` | tip | `down`, `up` | tip | button 1 | `BTN_LEFT` |
| tool type `ERASER` | eraser | tool `eraser` (0x141) | eraser | `xwayland-tablet eraser` | `BTN_RIGHT` |
| `BUTTON_STYLUS_PRIMARY` | right or middle, as Side button says; eraser for Eraser while held | `BTN_STYLUS2` (Right click) or `BTN_STYLUS` | button | 3 or 2 | `BTN_RIGHT` or `BTN_MIDDLE` |
| `BUTTON_STYLUS_SECONDARY` | the other of right and middle | the other of the two | button | | the other |
| Bluetooth pen key (`KEYCODE_F21` on OPPO) | as primary, while in proximity | as primary | | | as primary |
| event time, historical times | ms per sample | `frame(time)` per sample | event time | | event time |

- **Tilt.** Android cooks the raw `ABS_TILT_X/Y` into θ and φ (`orientation = atan2(−sin tx, sin ty)`,
  `tilt = acos(cos tx · cos ty)`). The exact inverse gives tablet-v2's tilt, whose sign convention (top of the pen
  towards +x positive) is evdev's and libinput's:
  `s = sin φ, c = cos φ, k = s²c²; r² = sin²θ if k = 0, else (1 − √(1 − 4k·sin²θ)) / 2k;
  tilt_x = asin(−r·s), tilt_y = asin(r·c)`. On a synthetic pen with known raw tilt over −50..50° × −30..30° it
  returns the raw values within 1°. The projection inverse used by Chromium, Gecko and Sunshine is off by up to
  16° on diagonals. φ is in window coordinates, so display rotation is already applied. Samples with θ = 0 and
  φ = 0 together carry no tilt and send no tilt event.
- **Rotation, slider, wheel** are never advertised. Android has no barrel-rotation axis, and `AXIS_ORIENTATION`
  is the tilt azimuth, not a rotation (Termux:X11 sends it as rotation, which is the mistake to avoid).
- **One virtual tablet.** Each compositor instance has one `zwp_tablet_v2` named "DroidDeck Pen" (fixed, not
  translated), bustype `virtual`, no vendor id and no path, announced at start when the device has stylus
  hardware and otherwise at the first pen sample, and never removed while the compositor runs. Xwayland creates
  its X devices when the tablet appears, and Wine reads them once per process, so the tablet must exist before
  programs start. All Android pen devices go through it: the Find N6 has one digitizer per screen and the pen
  moves between them when the phone folds. The stable name is also what KWin keys `[ButtonRebinds]` on.
- **Tools** are created on first use: one pen and one eraser, without hardware serial, kept until the compositor
  exits. Capabilities come from the first pen device's motion ranges: pressure always, tilt if `AXIS_TILT` exists,
  distance only if `AXIS_DISTANCE` has more than two values (the OPPO Pen reports only hovering or touching). A
  later device with more axes keeps the first tool's capabilities until the session restarts, because KWin cannot
  re-announce a tool.

## Android capture

- **Entry points.** Pen contact is taken at the top of `SessionActivity.onTouchEvent`, after the Compose UI, the
  HUD and on-screen controls declined it, so the pen still works in the drawer and presses on-screen buttons like
  a finger. Hover is taken in `dispatchGenericMotionEvent` before the mouse branch, with the mouse's "app UI owns
  the pointer" rule. Pens are recognised by tool type first, not by source: some devices report pens as
  `SOURCE_MOUSE | SOURCE_STYLUS`, and `adb shell input stylus` sends plain `SOURCE_STYLUS`. The lower-screen game
  (`onBottomGameTouch`, `BottomGameScreen`) and the surface touch listener accept stylus pointers. Events that mix a
  finger and a pen are split by tool type.
- **Samples.** Every historical sample is forwarded with its own event time, then the current one. Miss forwarding
  from `InputSurfaceView` and `SteamTouchControls` keeps history when it rebuilds an event. No touch slop applies
  to a pen stroke.
- **State.** `OUT → HOVER → CONTACT` per tool, tracked at the activity because the dispatcher and view groups add
  their own hover exits. A `HOVER_EXIT` followed within 50 ms by a hover or `DOWN` is not a proximity change:
  Android splits a landing into an exit and a down with the same time, and the OPPO digitizer flickers out and in
  at the top of its hover range (median gap 14 ms, 214 of 227 gaps under 20 ms in one device capture). `UP`
  with `FLAG_CANCELED` or `ACTION_CANCEL` lifts the tip without a click. A pen/eraser switch, from the tool type
  or from Eraser while held, is not the pen leaving: the pen stays in proximity and its next record carries the
  other tool, so the switch does not move the pointer, bring back the cursor overlay or disarm palm rejection. A
  `HOVER_EXIT` followed within those 50 ms by a hover of the other tool is such a switch. During contact the presented
  tool is latched until the tip lifts, as Windows Pen States require for eraser buttons. Button presses are the
  difference between consecutive `buttonState`s.
- **Bluetooth pen buttons.** A key event is a pen button when it comes from an external device whose only source is
  keyboard and whose name contains "pen" or "stylus", while a pen is in proximity. It is consumed and folded into
  the button state; any other key stays a key. A key held while the pen goes away comes back only as repeats, so
  the folded key's down and up are still followed then: the out record carries no button, and the pen comes back
  with the key held (as the eraser, for Eraser while held). Removing the key's device forgets it, and so do pause,
  focus loss and pointer-capture loss, where its up may go elsewhere; a repeat while the pen is near counts it again.
- **Cancel points.** Everything that cancels touches today also ends the pen: mode changes in `readPrefs()`,
  touch-route changes, configuration changes (rotation, folding), surface resize and destruction, PiP, pause, focus
  loss, device removal, pointer-capture loss. Ending the pen lifts the tip, releases buttons and leaves proximity.
- **External tablets.** Devices with `SOURCE_STYLUS` no longer count as hardware mice for pointer capture or for
  the relative-mouse Auto rule, so a drawing tablet keeps working.
- **Stylus pointer icon.** On Android 15+ the system draws its own stylus hover icon unless
  `onResolvePointerIcon` returns `TYPE_NULL` for stylus pointers; the session's views do that wherever they already
  hide the mouse pointer.
- **Second display.** The lower-screen desktop runs its own compositor in the `:bottom_desktop` process and gets
  input over a `Messenger`; pens there use a `MSG_PEN` carrying the same samples in a `Bundle`.

## App compositor

- **Message.** `struct input_msg` grows from five to nine ints so one record carries a whole sample: 36 bytes,
  far below `PIPE_BUF`, so records from different threads never interleave. Existing writers keep their positional
  initialisers. Type 13 is a pen sample: flags (tool, in proximity, tip, buttons, wants tablet, cancel, tilt
  present), x and y in input space × 256, pressure, distance, tilt x and y in 1/100°, event time. Each record is a
  full state snapshot, so a lost record cannot leave a button held, and the writer keeps the newest pen record aside
  when the pipe is full so the compositor applies it after draining. Type 14 announces the tablet and the tools'
  capabilities. The pipe is enlarged with `F_SETPIPE_SZ`, since a 360 Hz pen writes 360 records a second.
- **Times.** Pen frames carry the Android event time (`CLOCK_MONOTONIC` milliseconds, the clock `now_ms()` already
  uses), so historical samples keep their spacing. The clock-pause preload shifts `CLOCK_MONOTONIC` for some guest
  processes, and open PR #2393 adds a guest clock domain to the compositor; pen times must follow whatever that PR
  settles.
- **`src/wl_tablet.c`.** `zwp_tablet_manager_v2` version 2, the one tablet, lazy tools, proximity, frames, and
  `set_cursor` checked against the proximity serial. Like `wl_text_input.c`, it includes only `droiddeck_ext.h` and
  its generated header, so it builds on a PC against libwayland for the harness. The generated code comes from the
  stable `tablet-v2.xml` with wayland-scanner 1.26.0 in `private-code` mode, like the other protocols.
- **Routing.** `deliver_pen` picks the target in the pointer's order (constraint surface, desktop surface, implicit
  grab, topmost toplevel) and freezes target and route while the tip or a button is down. A record whose tool
  differs from the last one keeps proximity: on the tablet route `wl_tablet.c` releases the old tool's buttons and
  sends its `proximity_out` in one frame, then the new tool's `proximity_in` with the full state in the next; a
  tool first used while a button is held is announced then. A client named
  `gamescope` always gets the pointer, even when SDL2 inside it bound tablet-v2: SDL2 turns tablet events into mouse
  events anyway, and under a game's pointer lock gamescope would turn the pen's absolute hover motion into camera
  motion.
- **Pointer emulation**, modelled on the touch `pointer_fallback`: hover is motion, the tip is `BTN_LEFT`, buttons
  map as in the table; leaving proximity, cancel and target destruction release every emulated button. Emulation
  moves the shared pointer position, resets the relative-motion origin and publishes the cursor position for the
  Android cursor overlay. Under a program's pointer lock the compositor sends buttons only, which closes the gap
  before Kotlin hears about the lock.
- **Tablet route and the pointer.** While the pen is near, the tablet route does not move the host pointer:
  tablet focus is independent of the pointer in the protocol and in KWin, and every host user of the pointer
  position assumes it is the client's `wl_pointer` position. The compositor keeps the pen position separately.
  When the pen leaves (a tool switch is not leaving), the compositor moves the pointer to where the pen left,
  through the normal pointer motion path (not under a pointer lock). This matches a desktop graphics tablet, where
  the pen moves the one cursor; on the device, the cursor otherwise came back where the trackpad had left it.
  `PenCursorPolicy` hides the Android cursor overlay while a pen is near on the tablet route (KWin draws the tool
  cursor). When the pen leaves, it restores the overlay's visibility as it was or as other input left it meanwhile,
  at the pen's last position. On the pointer route the overlay follows the pen as before. A sample that changes
  nothing for a client sends that client no frame.
- **Lifecycle.** Surface destruction calls `droiddeck_tablet_surface_gone`, which sends `proximity_out` while the
  surface still exists. Tablet cleanup runs in resource destroy handlers, because libwayland runs a client's
  destroy listener before destroying its resources.

## KWin

The live desktop is the Ubuntu runtime's KWin 6.6.6 (`tools/ubuntu/patches/kwin/`); the Arch desktop package
(`tools/desktop-kde/patches/`, KWin 6.8) is the fallback. A patch for one does not apply to the other, so the
change is carried twice: `0005-forward-nested-tablet-input.patch` for Ubuntu and
`0004-forward-nested-tablet-input.patch` for Arch, from one design, each followed by two small fixes to KWin's own
tablet handling (Ubuntu `0006`/`0007`, Arch `0005`/`0006`).

- **What it adds.** In `src/backends/wayland`, a `WaylandTabletSeat` binds the host `zwp_tablet_manager_v2` (KWin's
  build already generates the client header) and creates a `WaylandTabletDevice` (`InputDevice` with
  `isTabletTool()`) per host tablet and a cached `InputDeviceTabletTool` per host tool. Host events are collected
  per `frame` and emitted as KWin's tablet signals in libinput's order: proximity in, axes, tip down, buttons, tip
  up, proximity out. A proximity out followed by in within one frame (moving between KWin's subsurfaces) is a
  surface change, not a proximity cycle. Positions go through the `mapToOutput` of patch 0001, so the desktop
  scale applies to the pen as to the pointer and touch. On 6.6, KWin stores the position of a proximity-out and of
  a tip-up as the tool's position, so those events carry the last real position.
- **Gate.** The host tablet is bound only with `DROIDDECK_NESTED_TABLET=1`, which `droiddeck-desktop` exports next
  to `DROIDDECK_NESTED_SCALE`. Without it nothing in the patch runs.
- **Cursor.** The nested backend shows KWin's cursor through the host `wl_pointer`, which does not follow the pen.
  While a tool is in proximity, the output uses a software cursor (the mechanism pointer lock already uses), and the
  host tool cursor is hidden with `set_cursor(NULL)`. This repaints KWin's output as the pen hovers; if validation
  shows the cost, the follow-up is to hand KWin's cursor image to the host as the tool's cursor surface.
- **Programs without tablet input.** KWin delivers tablet events only to clients that bound tablet-v2.
  `droiddeck-desktop` also exports `KWIN_WAYLAND_EMULATE_TABLET=1`, so KWin turns pen input into pointer input for
  the rest (SDL and GLFW programs, Wine on Wayland); that emulation drops side buttons, and Mouse mode is the answer
  for those programs. X11 programs get the pen from Xwayland, which binds tablet-v2 and creates
  `xwayland-tablet stylus/eraser` devices attached to the core pointer.
- **Xwayland.** Ubuntu's Xwayland 24.1.10 has a regression: tablet events go to the root window unless the
  `wl_pointer` is over an X11 surface, so X11 programs get only raw events. Upstream fixed it in 24.1.11 with
  0ca92fb4; the runtime carries it as `tools/ubuntu/patches/xwayland/0002`, after 03228e00 (`0001`, the revert of
  a pointer-only check that KWin tripped over) so that it applies as upstream has it. Separately, a tablet tool
  can keep a hidden cursor over X11 windows: on `proximity_in` the tool gets the seat's current X cursor, which is
  unset when the window's cursor became the sprite's cursor before any Xwayland input device had sent an event
  (Xwayland ignores it then, while the server marks it as shown), and motion over windows with the same cursor
  changes nothing. Patch 0003, DroidDeck's own, invalidates the master's sprite on tablet `proximity_in`, as
  Xwayland already does on pointer enter. On the device, Kate under X11 shows the arrow and the I-beam for the pen,
  takes the caret, a selection and the side-button menu, and `xinput test-xi2` gets `Motion` and `ButtonPress`
  from the tablet device.
- **KWin's tablet fixes.** A side button is followed by a `frame`, so clients apply it at once; a tool at (0,0)
  keeps its focus; the tip release goes to the window that got the press before focus moves; a tool that moves onto
  a title bar, an internal window or the empty desktop leaves the client surface, and one that comes back gets
  `proximity_in` again. All four reproduce on unpatched 6.6.6 and are fixed with the patches (x86 test rig).
- **ABI.** The tablet seat stays inside `src/backends/wayland` and keeps `WaylandBackend`'s layout; the fixes touch
  `src/input.cpp` and `src/tablet_input.*` and add a private member to `TabletInputRedirection`, which plugins do not
  use. The Arch package replaces only `kwin_wayland` and `libkwin.so` and keeps Arch-built plugins.
- **Rotated KWin outputs.** KWin 6.6's nested backend does not draw a rotated output correctly (fixed upstream in
  6.8), so a pen on a KWin output rotated in Plasma's display settings is not covered on the live runtime. Rotating
  the phone is handled by Android and is covered.
- **Release.** A new KWin reaches users only through a runtime or desktop package release, which maintainers
  publish. Until then, the pen works in the desktop through the compositor's pointer emulation, because the
  unpatched KWin binds no tablet.

## gamescope

gamescope 3.16.29 does not expose tablet-v2. Its SDL backend (PowerVR) binds it through SDL2, but the app compositor
never sends tablet-v2 to gamescope, so Steam and programs in gamescope get pointer emulation in Auto and Mouse. A
tablet through gamescope (a host tablet-v2 client in its Wayland backend and a `wlr_tablet_v2` server for its
Xwayland) would give Proton drawing programs Wintab pressure; it is left out of this change.

## Threads and races

- All `MotionEvent` and `KeyEvent` handling, pen state, palm state and routing run on the main thread. The agent
  bridge posts synthetic pen events to the main thread rather than calling the compositor from its binder thread.
- Settings are resolved in `readPrefs()`, never during dispatch. The only timers are the palm grace and the hover
  watchdog, both evaluated lazily from event times.
- The compositor owns all tablet and emulation state on its thread. Kotlin reads the effective route from the query
  snapshot, under its existing lock.
- Pen and touch records share one pipe, so their order is the order of `write()` calls on the main thread. A mode
  change writes a pen cancel before any record of the new mode.
- Bottom-screen pens are serialised by the `:bottom_desktop` main looper like its touches.

## Agent tooling

- `droiddeckctl input pen hover|tap|stroke|button` builds pen samples (position, pressure, tilt, tool, buttons).
  The bridge turns them into `MotionEvent`s on the main thread and feeds them to `PenInput`, so modes, palm
  rejection and button mapping are exercised. `--via uinput` instead registers a virtual pen with
  `adb shell uinput -`, which tests Android's own mapping (hover, tilt, buttons, eraser, batching) end to end.
- A `droiddeck-scenario` scenario draws strokes in the desktop and checks them with `wev` and `xinput test-xi2`
  run inside the session. The compositor's `test-input` FIFO is not used: `droiddeck-desktop` removes it.

## Prior art

| Project | What it does with pens | Taken from it |
|---|---|---|
| Termux:X11 (GPL-3.0) | Pen and eraser X devices with pressure and tilt; options for mouse mode and which button contact sends | Pen modes, button choice. Not taken: tilt scaled in radians, orientation sent as rotation, no history |
| Moonlight (GPL-3.0) | Pen protocol to Sunshine; mouse fallback with eraser = right click | Mouse fallback mapping, the need for a Mouse mode (#1317), unbuffered dispatch |
| AVNC (GPL-3.0) | Stylus handled before mouse; hover moves | Tool type before source; no touch slop for pen strokes (#63) |
| Weylus (AGPL-3.0) | Browser pointer events to uinput | A watchdog for hovers without a leave (#263) |
| GameNative (GPL-3.0) | Hover cursor, side button or eraser = right click | The behaviour #318 asks for |
| Infinite Painter, Clip Studio | Pen paints, fingers navigate; palm rejection options | "Fingers only scroll" |

## Test plan

Automated:

- JVM: `PenRouter` decisions for every mode, session, lock and screen; the tilt inverse and pressure curves; the pen
  state machine (hover exit before down, cancel, tool switch, button diff, Bluetooth pen keys); `PalmGate` timing and
  cancel; a pen on an on-screen control presses it and a miss keeps its history; settings reset.
- Host harness `tools/tests/tablet_v2_harness.c` with `test_tablet_v2.py`: `wl_tablet.c` and the generated code
  against the PC's libwayland, server and client in one process over a socket pair: binding, tool announcement,
  hover, down with pressure and tilt, buttons, implicit grab, surface destruction while down, client disconnect,
  late binding. The test skips without libwayland, and the CI test job installs `libwayland-dev`.
- Routing harness extracted from `compositor.c` in the existing style: tablet versus emulation per stroke, button
  balance, cancel, locks. `test_output_refresh.py` learns the new message type.
- KWin patch logic extracted from the patch and compiled on the PC, like the gamescope pointer-hold test: frame
  assembly, proximity edge cases, tilt under output transforms.
- `droiddeckctl` tests for the new input command.

On the device (validation report below):

- Krita (Wayland and X11), Xournal++, GIMP, Firefox pointer events, `wev`, `xinput test-xi2`; pressure over the
  full range, tilt, hover, eraser button, side button, fast strokes, screen edges, phone rotation, desktop scale not
  100 %, folding during a stroke, a palm while writing.
- Steam: the pen drives the pointer; Big Picture and a game still work.
- Regressions: fingers in every touch mode, mouse, trackpad mode, on-screen controls, PiP.
- Latency from Android event time to the client's event time, pen and finger, and the refresh rate while hovering.

## Risks

- **The patched KWin ships in a runtime release.** Until maintainers publish one, users get pointer emulation in
  the desktop. The Ubuntu archive is not pinned, and 6.6.7 is in proposed; the patch applies to both 6.6.6 and
  6.6.7 without fuzz.
- **KWin tablet bugs.** KWin 6.6 and 6.8 have open tablet fixes upstream (merge requests 9785 and 9883). DroidDeck
  carries its own minimal fixes for the cases the pen hits; they need a rebase when those merge requests land. The
  host never sends a down or a button without its release.
- **Xwayland patches.** Patches 0001-0002 can go when Ubuntu ships 24.1.11. Patch 0003 is DroidDeck's own and is in
  no Xwayland release, so the runtime rebuilds Xwayland until it is upstream and released.
- **Programs without tablet-v2 in the desktop** depend on KWin's deprecated emulation, which drops side buttons.
  Mouse mode is the workaround.
- **Hover cursor cost**: KWin repaints its output while a pen hovers.
- **Hover refresh rate** may fall to 60 Hz on devices that only boost for contact.
- **OEM pen buttons** differ: OPPO sends a Bluetooth key that ColorOS also handles; Samsung may consume the S Pen
  button for Air actions; a user can turn stylus buttons off system-wide. Only buttons that reach the app can be
  mapped.
- **Tilt math** assumes Android's own conversion. A vendor that reports physically correct tilt and orientation
  would get skewed diagonals; the synthetic pen check catches that per device.
- **Open PRs** touch the same code: #2162 adds stylus routing rules to the main surface, #2394 adds a protocol to the
  compositor, #2393 changes compositor event times, #2465 and #2466 change touch-to-pointer behaviour.
- **Second display** cannot be checked on the Find N6, which has no lower screen.

## Commits

1. `feat(compositor): ...` tablet-v2 server, pen message, pointer emulation, harness tests.
2. `feat(input): ...` pen capture, modes, palm rejection, side buttons, settings rows, strings in all locales,
   JVM tests.
3. `feat(desktop): ...` KWin patch in both series, `droiddeck-desktop` variables, README.
4. `feat(ctl): ...` synthetic pen in `droiddeckctl`, scenario, `docs/agent-control.md`.
5. `docs: ...` this document with the validation report, credits.

## References

- tablet-v2 from [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols) (MIT), generated
  with wayland-scanner (MIT).
- Event order and lifetimes follow [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) `types/tablet_v2` and
  `backend/wayland/tablet_v2.c` (MIT) and weston's `libweston/input.c` (MIT); no code was copied.
- The KWin patch builds on KWin's tablet pipeline and libinput backend (GPL-2.0-or-later).
- The tilt inverse is derived from AOSP's `TouchInputMapper.cpp` (Apache-2.0).
- Behaviour, not code, was compared with Termux:X11, Moonlight, AVNC, Weylus and GameNative (licences above).
