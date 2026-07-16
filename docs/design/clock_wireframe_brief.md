# Clock App-Intent — Wireframe Brief

Derived from `docs/specifications/purramidtime_specifications_clock.md` for use as a prompt in Claude Design. Each `##` section below is a separate screen/frame.

## Global notes (apply to every screen)
- Overlay windows are free-form, resizable, draggable — draw a resize handle/corner affordance on any window frame.
- Icon button states: **inactive** fill `#757575`; **active** fill `#1976D2` with `#E3F2FD` active background chip behind the icon. Some secondary icons instead go to `#808080` on activation (noted per-button below) — no blue state for those.
- Long-press on any icon shows a tooltip with a pointer/tail aimed at the icon (dismiss on tap-elsewhere) — show one example tooltip callout in the wireframe.
- All settings/menu windows open centered on screen with an "explosion" (radial expand) transition — annotate with a burst/expand arrow motif from the triggering button.

## Screen 1 — Clock Window: Digital (default mode)
Freeform overlay window, resizable, default size, white background (default clock-face color).

- **Top-right corner:** Close button (X icon, `ic_close`), inactive/active states per global icon rule.
- **Bottom-left corner:** Settings button (gear icon, `ic_settings`), inactive/active states per global icon rule.
- **Center of window:** Digital time display, large monospace-style numerals, format `HH:MM:SS`.
  - Below/beside the seconds: stacked **AM** (top) / **PM** (bottom) indicator, only one visible depending on current period (12-hour mode only). In 24-hour mode, no AM/PM, hours 00–23, seconds still HH:MM:SS.
- **Below the clock face, centered, side by side:**
  - Left: Play/Pause button (`ic_pause` default / `ic_play` when paused)
  - Right: Reset button (`ic_reset` default; active state = default icon shape tinted `#808080`, no blue state)
  - (Note: order flips — Reset left, Play/Pause right — in RTL layout; annotate a mirrored variant if useful.)

Annotate: "time display animates — numerals count upward when playing."

## Screen 1b — Clock Window: Analog mode
Same window chrome (Close top-right, Settings bottom-left, Play/Pause + Reset row below face) but the center face is a circular analog clock:
- Numbers 1–12 arranged around the interior circumference.
- Optional inner ring of 13–24 (toggle-dependent) — show as a fainter/smaller secondary number ring just inside the 1–12 ring.
- Tick marks: 1 large tick aligned with each number, 4 small ticks between each pair of numbers.
- Three hands: hour (shortest/thickest), minute (medium), second (thinnest, longest) — second hand can be hidden via settings, show both variants.
- Face background fills with the user's chosen palette color; hands/numbers render in black or white depending on contrast against that background.

Annotate: "hands rotate continuously when playing; when paused, hands are drag-manipulable."

## Screen 2 — Nested Clock
A miniature version pinned to the **top-right corner of the screen** (not the window — the screen), 20px padding from the edges.
- Analog nested size: 75×75px square.
- Digital nested size: 75×50px rectangle.
- No Play/Pause/Reset/Settings/Close buttons visible — just the bare clock face.
- Still draggable/pinch-resizable like a normal window.
- If multiple nested clocks exist, stack them vertically in that corner, newest on the bottom.

Show 2–3 stacked nested clocks (mix of analog/digital) to illustrate the stacking order.

## Screen 3 — Settings Window
Centered modal/panel opened from the Settings button, entrance via explosion transition. Layout: a vertical list of setting rows, each row = a label/string on the left and a control on the right.

Rows top to bottom:
1. **Mode** — analog icon + digital icon side by side; the active one is tinted `#808080` with a thick blue bar underneath it; default/active-on-launch = Digital.
2. **Colors** — label "Colors" + a color swatch square showing the current face color (default: white). Tapping label or swatch opens a color picker with the 6 `PurramidPalette` swatches.
3. **24-Hour** — label "24-Hour" + On/Off toggle (text labels "On"/"Off" on the toggle itself), default Off.
4. **Set Time Zone** — label + edit-pencil icon (`ic_edit`, default/active `#808080`). Tapping opens the Time Zone Globe screen (Screen 4).
5. **Seconds** — label + On/Off toggle, default On.
6. **Set Alarm** — label + edit-pencil icon. Tapping opens the Set Alarm screen (Screen 5).
7. **Nest Clock** — label + On/Off toggle, default Off.
8. **Add Another** — label + add-circle icon (`ic_add_circle`, default/active `#808080`). Disabled/grayed state when 4 clock windows already exist (show this disabled variant too).

No close/back chrome specified for this window itself beyond it being a settings panel — keep it simple, single column, rows separated by dividers.

## Screen 4 — Time Zone Globe window
Fixed initial size **750×750px**, resizable like the main clock window.
- **Top-left:** back arrow (`ic_back`) — closes this window, returns to Settings (Screen 3).
- **Top-right:** close button (`ic_close`) — closes *all* settings-related windows.
- **Below the top bar:** two city listings (stacked or side by side) showing City name, Country, UTC offset — representing the currently centered/active time zone. (Only one shown if the zone has just one city; zone hidden entirely from the globe if it has none.)
- **Center:** large 3D globe sphere, divided into colored time-zone overlay regions (40% opacity color fill over the sphere), current active time zone left uncolored/transparent. Add a couple of small city pin markers on the globe surface (northern + southern hemisphere examples). Optionally indicate a striped-overlay pattern on one region to represent a half/45-minute-offset zone.
- **Below the globe, centered row of three controls:**
  - Left-facing arrow (`ic_arrow_left`, default/active `#808080`) — steps to previous time zone by UTC offset.
  - Circle button (`ic_circle`, same default fill as the arrows, active `#808080`) — recenters globe on current time zone.
  - Right-facing arrow (`ic_arrow_right`, same state rule) — steps to next time zone by UTC offset.

Annotate: "globe is drag-rotatable in any direction (press-hold-drag); poles converge all time zones."

## Screen 5 — Set Alarm window
Opened from Settings row 6 ("Set Alarm"). Can visually resemble the Android system alarm UI, but with app-specific chrome:
- **Top-left:** back arrow (`ic_back`) — returns to Settings window.
- **Top-right:** close button (`ic_close`) — closes all settings-related windows.
- **Body:** standard alarm-creation UI — time picker, repeat-day selector, alarm label field, save/list of existing alarms — styled like Android's native alarm screen, but entirely self-contained (no dependency on the system Clock app).

Keep this screen lower-fidelity/generic since the spec defers to "match Android system UI" — a rough placeholder alarm list + time-picker is sufficient for the wireframe.
