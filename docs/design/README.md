# Handoff: Clock App — Window, Settings, Globe & Alarm

## Overview
This package covers the wireframes for a classroom-oriented Clock app: a resizable/draggable Clock Window (digital + analog modes), a nested bare-face mode, a Settings window, a Time Zone Globe picker, and a Set Alarm screen. It captures the structure, iconography, sizing, and behavior refined during design review — including accessibility (WCAG 2.2 AA) contrast fixes and resize rules.

## About the Design Files
The file in this bundle (`Clock Wireframes.dc.html`) is a **design reference created in HTML** — a low-fidelity wireframe showing intended structure, layout, iconography, and behavior. It is **not production code to copy directly**. The task is to **recreate these designs in the target codebase's existing environment** (React, SwiftUI, native Android, etc.) using its established patterns, components, and design system. If no environment exists yet, choose the most appropriate framework and implement there.

The `.dc.html` file uses a lightweight in-house component runtime (`support.js`); ignore that mechanism — it is only there to render the wireframe. Read it for layout/measurements/behavior, not for implementation architecture.

## Fidelity
**Low-fidelity (lofi).** These are wireframes showing structure, sizing relationships, icon placement, states, and interaction behavior. Use them as the source of truth for **layout, control sizing, iconography, copy, contrast, and behavior**, but apply the **codebase's existing design system** for final styling (fonts, palette, elevation). Where specific values are given below (icon box sizes, contrast-safe colors, font sizes), treat those as **intentional decisions from review** and preserve them.

The orange handwritten notes in the wireframe are **behavior annotations**, not UI — do not render them.

## Screens / Views

### 1a — Clock Window, Digital (default)
- **Purpose**: Primary clock display; a stopwatch-style timer that counts upward when playing.
- **Layout**: Freeform window, ~340×230px (resizable). White fill, 1.5px #333 border, 6px radius.
- **Components**:
  - **Time readout** (dominant element): monospace, **60px / weight 600**, color #1c1c1c, format `HH:MM:SS`. Centered on the card. Must be the visually dominant feature (readable from the back of a classroom).
  - **AM/PM indicator**: stacked vertically to the right of the time, monospace **19px / weight 700**. Active period lit in **#1976D2**, inactive in #cfcbc3. In 24-hour mode this is hidden and hours run 00–23.
  - **ic_close**: top-right, **32×32px, 9px radius**, 1.5px #757575 border. Secondary control (smaller than the play/reset buttons).
  - **Play/Pause + Reset**: two 44×44px circular buttons, 1.5px #757575 border, centered horizontally, anchored **42px from the bottom edge**, 22px gap between them.
  - **ic_settings**: bottom-left, **32×32px, 9px radius**, 1.5px #757575 border.
  - **Resize handle**: hatched corner, bottom-right.
- **States**: Inactive icon controls use grey **#757575**; active state uses a blue (#1976D2) treatment (see global rule below).
- **Behavior notes**: Numerals count upward while playing. On resize, the time stays centered but keeps a **minimum 10px gap above the buttons**; buttons stay 42px from the bottom and can compress that gap to **30px** before the card reaches its minimum height. **RTL variant**: Reset ↔ Play/Pause order flips.

### 1b — Clock Window, Analog mode
- **Purpose**: Same window in analog display mode.
- **Layout**: ~340×325px, resizable (height changes are taken from the bottom; bottom-anchored controls keep their position relative to the card edges).
- **Components**:
  - **Analog face**: 220×220px circle, 2px #333 border, centered.
  - **Numerals 1–12**: system-ui **20px / weight 600**, #1c1c1c, positioned on a radius that clears the bold hour ticks (numbers must not touch tick marks).
  - **Numerals 13–24** (inner ring, toggleable): system-ui **12px / weight 500**, color **#6b6b6b** (darkened to meet WCAG AA on white while staying lighter than the 1–12 numerals).
  - **Ticks**: bold hour ticks at each numeral; 4 thin minute ticks between each.
  - **Hands**: hour (6px), minute (4px) in #1c1c1c; second hand (thin, 2px) in accent #c1440e — second hand hideable via Settings.
  - **ic_close**: top-right, 32×32px / 9px radius (matches 1a).
  - **Play/Pause + Reset**, **ic_settings**, **resize handle**: same treatment as 1a.
- **Behavior notes**: Playing → hands rotate. Paused → hands are drag-manipulable. The 13–24 inner ring and the second hand are both Settings toggles.

### 1c — Nested Clocks (screen corner)
- **Purpose**: Compact bare clock faces pinned to the device screen (not inside a window).
- **Layout**: Pinned to the **top-right of the screen**, 20px padding from screen edges, stacked vertically with 10px gap. Newest clock stacks on the bottom.
- **Components**: Analog nested face **75×75px**; digital nested **75×50px**. Bare faces only — **no Close / Settings / Play / Reset chrome**. Still draggable and pinch-resizable.

### 1d — Settings Window
- **Purpose**: Per-clock configuration.
- **Layout**: Centered window, ~360px wide, white, 8px radius, single column; rows divided by 1px #eee hairlines.
- **Components (rows)**: label text at **18px / weight 600**, control right-aligned per row:
  - **Mode** — icon toggle between analog and digital (default active = Digital, indicated by a #1976D2 underline bar).
  - **Colors** — color swatch.
  - **24-Hour** — toggle (default off).
  - **Set Time Zone** — pencil affordance (opens the Globe, 1e).
  - **Seconds** — toggle (default on).
  - **Set Alarm** — pencil affordance (opens Alarm, 1f).
  - **Nest Clock** — toggle (default off; enables 1c behavior).
  - **Add Another** — add-clock action; **disabled at 4 clocks**.
- **Behavior notes**: Opens centered via an "explosion" (radial expand) burst originating from the Settings button.

### 1e — Time Zone Globe (750×750, resizable)
- **Purpose**: Pick a time zone by rotating a globe.
- **Layout**: Windowed. Top bar with back (left) + close (right); city listings below; centered globe; rotation controls at the bottom.
- **Components**:
  - **ic_back** and **ic_close**: **32×32px, 9px radius** (matches 1a/1b). Back → returns to Settings; Close → closes all settings windows.
  - **City listings**: city — country in weight 600; **UTC offset label in #595959** (darkened to meet WCAG 2.2 AA on white).
  - **Globe**: circular, with colored longitudinal time-zone bands, meridian grid lines, and location pins in #c1440e. The **active zone is left uncoloured**; a **striped band** denotes a 30/45-minute half-offset zone.
  - **Controls**: prev-zone (←), recenter (○), next-zone (→) — 42px circular buttons.
  - **Resize handle**: bottom-right.
- **Behavior notes**: Globe is press-hold-drag rotatable in any direction; poles converge all zones.

### 1f — Set Alarm (Android-style placeholder)
- **Purpose**: Create/manage alarms. Intentionally lower fidelity — mirrors the native Android alarm layout.
- **Layout**: Windowed, ~360×470px. Top bar (back + close), time picker, repeat-day selector, label field, existing-alarms list, save button.
- **Components**:
  - **ic_back** / **ic_close**: **32×32px, 9px radius** (matches 1a/1b). Back → Settings; Close → close all settings windows.
  - **Time picker**: large monospace 52px readout with AM/PM (scroll/tap).
  - **Repeat days**: 7 circular day chips; selected = #1976D2 filled white text, unselected = 1.5px #ccc border grey text.
  - **Label field**: single underlined text input.
  - **Existing alarms list**: each row shows time + repeat summary + an on/off toggle (#1976D2 on, #ccc off).
  - **Save alarm**: full-width pill button, #1976D2, white text.

## Interactions & Behavior
- **Play/Pause/Reset**: control the count-up timer (1a/1b).
- **Settings open**: radial "explosion" animation from the settings button.
- **Globe**: press-hold-drag rotation; prev/next step through zones; recenter resets orientation.
- **Windows**: draggable; resizable via bottom-right handle; nested faces are pinch-resizable.
- **Resize rule (1a)**: time stays centered, min 10px gap above controls, controls fixed 42px from bottom, gap compresses to 30px min before hitting min height.
- **RTL**: mirrors control order (Reset ↔ Play/Pause).

## State Management
- Per-clock: mode (digital/analog), 24-hour flag, seconds-visible flag, color, time zone, nested flag, running/paused state, elapsed time.
- App-level: list of clocks (max 4), list of alarms (time, repeat days, label, enabled).
- Globe: current rotation/orientation, selected zone.

## Design Tokens
- **Colors**:
  - Text primary: `#1c1c1c`
  - Icon/border inactive grey: `#757575` (also `#808080` on globe controls)
  - Accent blue (active/selected): `#1976D2`
  - Accent orange (second hand, pins): `#c1440e`
  - Inner-ring numerals 13–24 (AA-safe): `#6b6b6b`
  - UTC offset text (AA-safe): `#595959`
  - Inactive AM/PM: `#cfcbc3`
  - Hairline divider: `#eee`
  - Window border: `#333`
- **Typography**:
  - Digital time: monospace 60px / 600 (1a); 52px / 600 (alarm picker)
  - AM/PM: monospace 19px / 700
  - Analog numerals 1–12: system-ui 20px / 600
  - Analog numerals 13–24: system-ui 12px / 500
  - Settings row labels: 18px / 600
- **Control sizing**:
  - Secondary icon buttons (close/settings/back): 32×32px, 9px radius, 1.5px border
  - Primary transport buttons (play/reset): 44×44px circular
  - Globe rotation controls: 42px circular
- **Radius**: window 6px, settings window 8px, icon buttons 9px
- **Spacing**: transport buttons 42px from bottom; nested faces 20px from screen edges, 10px stack gap

## Assets
No raster assets. Icons are placeholders (ic_close, ic_settings, ic_pause, ic_reset, ic_back, recenter, prev/next). Replace with the codebase's icon set. The globe is a schematic placeholder — implement with the app's mapping/geo rendering approach.

## Files
- `Clock Wireframes.dc.html` — the full wireframe (options 1a–1f on one canvas).
