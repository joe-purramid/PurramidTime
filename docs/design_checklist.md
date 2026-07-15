# Design Checklist — Reconstructed Placeholders

These resources and small fixes were created on **14–15 July 2026** to unblock the
build (the module would not compile due to missing resources — the same lost-files
situation as `strings.xml`). Everything below is a **placeholder / minimal
reconstruction**. Review each item, compare against your originals when recovered,
and check it off once confirmed or replaced.

> Legend: `[ ]` = needs review · `[x]` = confirmed/replaced

---

## 1. Drawable & color resources

- [ ] **`app/src/main/res/color/icon_tint_stateful.xml`** — action-button icon tint state list.
  - Values used: `#1976D2` active (`state_activated` / `state_selected`), `#BDBDBD` disabled, `#757575` default.
  - Source of truth: `docs/specifications/purramid_button_implementation_guide.md` (verify these are the exact colors intended).

- [ ] **`app/src/main/res/drawable/action_button_background.xml`** — ripple background for action buttons.
  - Values used: `#E3F2FD` active background (oval), transparent default, ripple color `#E3F2FD`, oval mask.
  - Verify shape (oval vs rounded-rect) and active-background color match the button guide.

- [ ] **`app/src/main/res/drawable/ic_delete.xml`** — delete icon.
  - Standard Material "delete/trash" vector, `24dp`, tinted `?attr/colorControlNormal`.
  - Replace if the original used a custom/brand delete glyph.

- [ ] **`app/src/main/res/drawable/dialog_background.xml`** — settings-dialog surface.
  - White rounded rectangle, `16dp` corner radius, `16dp` padding.
  - Verify corner radius, color (light/dark), and elevation expectations.

## 2. String resources (in `app/src/main/res/values/strings.xml`, "RECONSTRUCTED PLACEHOLDERS" block)

Referenced by layouts but missing. Values are best-guess English; replace with originals.

- [ ] `alarm` → "Alarm"
- [ ] `alarm_label_hint` → "Alarm label"
- [ ] `dismiss` → "Dismiss"
- [ ] `sound` → "Sound"
- [ ] `vibration` → "Vibration"
- [ ] `rotate_left` → "Rotate Left"
- [ ] `rotate_right` → "Rotate Right"
- [ ] `reset_utc` → "Reset to UTC"
- [ ] `time_zone_map` → "Time Zone Map"
- [ ] `data_privacy_text` → "Placeholder" (still literally "Placeholder" — needs real copy)

> Reminder: the wider `strings.xml` still contains many `Placeholder` values from the
> earlier lost-strings issue (e.g. About/EULA/accessibility strings). Those are
> tracked separately from this list — recover the original `strings.xml` when possible.

## 3. Layout / code fixes (not placeholders, but confirm the intent)

- [ ] **`android:imageTintList` → `android:tint`** across 7 layouts:
  `activity_clock.xml`, `clock_overlay_layout.xml`, `layout_clock_card.xml`,
  `view_floating_clock_analog.xml`, `view_floating_clock_digital.xml`,
  `view_floating_stopwatch.xml`, `view_floating_timer.xml`.
  - `android:imageTintList` is **not** a valid framework attribute (blocked every build).
    `android:tint` is the ImageView equivalent. If you prefer AppCompat semantics,
    switch these to `app:tint` instead.

- [ ] **`ClockSettingsFragment`** now references `binding.colorPalette` (was the
  non-existent `colorPaletteContainer`). The layout id in
  `fragment_clock_settings.xml` is `colorPalette` — confirm that's the correct
  swatch container.

---

## Visual review pass (once the app runs)

- [ ] Action buttons show the correct active/inactive/disabled tint states.
- [ ] Active buttons show the `#E3F2FD` background highlight per the button guide.
- [ ] Settings dialog surface has the expected shape/corners.
- [ ] All reconstructed strings read correctly in-context (alarm screen, time-zone globe, stopwatch/timer settings).
