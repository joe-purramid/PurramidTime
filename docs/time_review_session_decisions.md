# PurramidTime - Implementation Decisions

## Deviations from Specifications
- **Reviewed**: 14 June 2025
- **Architecture**: Instance management
- **Key Decisions**:
  - Do not use AtomicInteger for instance numbering
  - Use thepurramid.instance.InstanceManager.kt

- **Reviewed**: 15 June 2025
- **Architecture**: Service settings Window Implementation
- **Key Decisions**:
  - Settings should open at the center of the screen
  - Use an explosion animation
  - Future efforts may revise this implemenation to have settings open from the settings button in the app-intent window
 
- **Reviewed**: 20 June 2025
- **Architecture**: HiltViewModelFactory is incompatible with LifecycleService
- **Key Decisions**:
  - Use the standard ViewModelProvider with just the factory
  - Use a unique key for each ViewModel instance
  - Add an "initialize()" method to set the instance ID after creation
  - Remove the HiltViewModelFactory usage
 
- **Reviewed**: 18 August 2025
- **Architecture**: TimerService, TimerActivity, Room Database
- **Key Decisions**:
  - Add an icon to the bottom right of the timer layout
  - Use the ic_lap.xml vector image
  - Following new specifications for pre-set time
  - Saved pre-sets are universal and persist across sessions

- **Reviewed**: 20 August 2025
- **Architecture**: Inactive Iconography
- **Key Decisions**:
  - To demonstrate an inactive button, add alpha = 0.5f for disabled state

- **Reviewed**: 14 July 2026
- **Architecture**: Specification review pass for Claude Code (v4/v5) + Android 13/15 best practices
- **Key Decisions**:
  - Foreground-service type corrected from `dataSync` to `specialUse` (`FOREGROUND_SERVICE_SPECIAL_USE`). Rationale: Android 15 (~half the install base) caps `dataSync` FGS at ~6h/24h and then force-stops it, which would kill all-day classroom clocks/timers. **APPLIED (14 July 2026):** `AndroidManifest.xml` now declares `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="persistent_classroom_overlay"` on ClockOverlayService/TimerService/StopwatchService, and requests `FOREGROUND_SERVICE_SPECIAL_USE` instead of `FOREGROUND_SERVICE_DATA_SYNC`. No code change was needed: all three services call the two-arg `startForeground(id, notification)`, so the type comes solely from the manifest (no type constant to keep in sync). Note: Play Console requires a short justification for `specialUse` at submission.
  - Reactive state: specs now direct new ViewModel/Repository code to `StateFlow`/`SharedFlow` (with `repeatOnLifecycle`) rather than `LiveData`; `lifecycle-livedata-ktx` retained only for legacy paths.
  - Timezone data: assets are `cities_timezones.csv` and `time_zones.geojson` (GeoJSON). Boundaries are parsed via JTS and stored as WKT in `TimeZoneBoundaryEntity.polygonWkt`. Corrected the schema doc, which had named non-existent `cities.csv` / `timezone_boundaries.json`.
  - Fixed copy-paste errors carried over from the source project: Stopwatch spec referenced `TimerStateEntity`, "timer background", a non-existent "randomizers app-intent", a bogus "Set countdown time", and INTERNET permission (Stopwatch is beep-only). Timer spec referenced a non-existent "Countdown Timer" mode / "Mode" tap target.
  - Flagged (not removed) vestigial `selectedSoundUri`/`musicUrl`/`recentMusicUrlsJson` columns on `StopwatchStateEntity` as removal candidates for the next migration.
  - Prefer `kotlinx.serialization` (plugin already applied) over Gson for new Room type converters.

- **Reviewed**: 14 July 2026
- **Architecture**: Timer & Stopwatch multi-instance refactor (TimerService, StopwatchService)
- **Key Decisions**:
  - Refactored `TimerService` and `StopwatchService` from single-window (single `overlayView`/`id` fields) to per-instance windows keyed by `instanceId` in a `ConcurrentHashMap`, matching `ClockOverlayService`. This brings both into compliance with the "up to 4 simultaneous windows per app-intent" requirement; previously a second start overwrote the first window's references and leaked it.
  - Both services now restore all persisted instances in `onCreate()` (mirroring `ClockOverlayService.loadAndRestoreClockStates`). Instance allocation happens **only** for `ACTION_START_*`; a `START_STICKY` null-intent restart no longer allocates a fresh id (which previously leaked a slot and drew nothing).
  - Close (X) button now removes only its own window; the service `stopSelf()`s when the last window closes.
  - **Behavior change:** closing a Stopwatch window now deletes that instance's persisted row (consistent with Timer's close = `deleteTimer`). This replaced the previous "preserve the last stopwatch's state on close" logic and the `StopwatchRepository.cleanupOrphanedInstances(activeIds)` call in `onCreate` (which hardcoded `it != 1` and could delete legitimately-persisted rows). `cleanupOrphanedInstances` is left in the repository, now unused.
  - Fixed a latent `ClassCastException`: both overlay layouts declare `closeButton` as `ImageButton`, but the services cached it as `TextView` and called `setTextColor`. Now cached as `ImageView` and tinted via `setColorFilter`.
  - Fixed a `MediaPlayer` leak in `TimerService`: the finish-sound player is now held per-instance and released on completion/error, on window close, and in `onDestroy`.
  - Stopwatch lap rows: removed dead references to `lapTime1..5TextView` (not present in the current `view_floating_stopwatch.xml`, which uses `lapTimesRecyclerView`). Lap tracking + container visibility are preserved; wiring the RecyclerView adapter for individual lap rows is a follow-up UI task.
  - **Pre-existing build blockers cleared to allow verification (all part of the lost-resources issue, flagged as reconstructed placeholders for replacement with originals):** added missing `@color/icon_tint_stateful`, `@drawable/action_button_background`, `@drawable/ic_delete`, `@drawable/dialog_background`, and 10 missing string resources; changed the invalid `android:imageTintList` attribute to `android:tint` across 7 layouts; aligned `ClockSettingsFragment` to the existing `colorPalette` layout id (was referencing non-existent `colorPaletteContainer`).

- **Reviewed**: 15 July 2026
- **Architecture**: Database + release-build quick wins (PurrTimeDatabase, build.gradle.kts, proguard-rules.pro)
- **Key Decisions**:
  - Deleted the dead `MIGRATION_0_1`. It referenced tables that do not exist in this app (`screen_mask_state`, `randomizer_instances` — leftovers from a sibling Purramid project) and re-added columns already declared in the entities. A `Migration(0, 1)` can never run (Room never creates a v0 database), so it was dead code that would only have crashed if somehow invoked.
  - Renamed the database file `purramid_database` → `purramid_time_db` to match `docs/specifications/purramidtime_database_schema.md`. Safe now (pre-release, `versionCode = 1`); any local dev database under the old name is orphaned, not migrated.
  - Set `exportSchema = true` and added `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`. The schema is now exported to `app/schemas/…/1.json` (committed) so future migrations can be validated against it, per the schema doc.
  - Enabled R8 for `release`: `isMinifyEnabled = true` + `isShrinkResources = true`. Added keep rules in `proguard-rules.pro` for the JNI/reflection-heavy libs (Filament, glTF I/O, SceneView, ARCore, JTS) and Gson `TypeToken` generic signatures, since those AARs do not ship reliable consumer rules and the 3D globe / geometry code is reached from native/reflection.
  - **Verification:** `assembleDebug` and `assembleRelease` both BUILD SUCCESSFUL (R8 `minifyReleaseWithR8` + resource shrinking + `lintVitalRelease` pass). **Runtime still needs on-device checking** — R8 stripping of Filament/SceneView shows up only at runtime, so the timezone globe should be exercised on a release build before shipping.

- **Reviewed**: 15 July 2026
- **Architecture**: Stopwatch lap list wired to RecyclerView (StopwatchService, LapTimesAdapter)
- **Key Decisions**:
  - Completed the follow-up left open by the multi-instance refactor: the `lapTimesRecyclerView` in `view_floating_stopwatch.xml` is now driven by a per-instance `LapTimesAdapter` (new, in `stopwatch/ui/`). Added `item_lap_time.xml` for the rows.
  - Laps render **oldest-first** (spec 10.1.3.1.1/10.1.3.1.2). Note this corrects the pre-refactor behavior, which reversed the list (newest on top).
  - Time formatting stays in the service (it owns the Hundredths setting); the adapter receives pre-formatted `LapItem(label, description)` and only applies layout + the current overlay text color. Each row sets a `contentDescription` and is focusable so screen readers expose laps as individual entries (spec 10.1.3.2). New strings: `lap_row_label` ("%1$d.  %2$s") and `lap_row_description` ("Lap %1$d, %2$s").
  - The lap button already goes inactive at 10 laps (`isEnabled = laps.size < MAX_LAPS`), satisfying spec 10.1.3.1.3.
  - Verified with `assembleDebug` (BUILD SUCCESSFUL). Visual/scroll behavior of the list inside the overlay window still wants an on-device check.

- **Reviewed**: 15 July 2026
- **Architecture**: Clock alarm feature completion (ClockAlarmActivity, AlarmScheduler, BootReceiver, AndroidManifest)
- **Key Decisions**:
  - **Root cause of "incomplete":** `ClockAlarmActivity`, `AlarmReceiver`, and `AlarmRingingActivity` were declared **nowhere** in `AndroidManifest.xml`. An undeclared `BroadcastReceiver` never receives the `AlarmManager` PendingIntent, and undeclared activities throw `ActivityNotFoundException` on launch — the feature was fully dead end-to-end. All three are now declared, plus a new `BootReceiver`.
  - Added the missing `android.permission.VIBRATE` permission (the ringing activity vibrates but the permission was never requested).
  - Extracted scheduling into a shared `clock/AlarmScheduler` object so `ClockAlarmActivity` (on save) and `BootReceiver` (reschedule after reboot) build the identical PendingIntent (same request code + extras is required for `cancel()` to match). Added a `canScheduleExactAlarms()` guard per CLAUDE.md with graceful fallback to `setAndAllowWhileIdle` (inexact) plus a snackbar hint when exact alarms are revoked.
  - **Scheduling uses `AlarmManager`, not `AlarmClock`/system alarm app.** This satisfies spec (10.2.2.6.2.2) — the *system alarm app* is not used; all UI/ringing/dismiss behavior lives in-app (`AlarmRingingActivity`). `AlarmManager` is just the OS scheduler primitive.
  - **Background activity start from `AlarmReceiver` relies on `SYSTEM_ALERT_WINDOW`** (which the app already requires) rather than a full-screen-intent notification. Simpler and appropriate for the always-on classroom panel. If Play policy later forces it, switch to a high-priority notification with `setFullScreenIntent` + `USE_FULL_SCREEN_INTENT`.
  - `AlarmRingingActivity` now sets `setShowWhenLocked`/`setTurnScreenOn` (manifest `showOnLockScreen`/`turnScreenOn` too) and populates the previously-blank `timeDisplay`. On dismiss, a fired alarm is marked `isEnabled = false` (one-time semantics; `daysOfWeek` repeat is not yet implemented) — consistent with spec 2.6.1/2.6.2 (alarms persist whether active or not).
  - `BootReceiver` reschedules all `getAllActiveAlarms()` on `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` (AlarmManager forgets alarms across reboot), using `goAsync()` + a Hilt-injected `ClockAlarmDao`. Satisfies spec 2.6.2.
  - Fixed a string-template bug (`"$alarm.label"` interpolated only `$alarm`) and moved alarm user-facing text to string resources (`alarm_invalid_clock`, `alarm_error_saving`, `alarm_set_message[_labeled]`, `alarm_set_inexact`), per the code style guide. Also fixed `saveAlarm` to capture the autogenerated `alarmId` from `insertAlarm` so a freshly-saved alarm can be edited/cancelled by matching request code.
  - **Verification:** `compileDebugKotlin` clean, `processDebugManifest` + `processDebugResources` pass, `dexBuilderDebug` reached UP-TO-DATE. Full `assembleDebug` could **not** be completed due to a pre-existing environmental issue: OneDrive holds file handles on `app/build/intermediates` (and even blocks `clean`), causing `Unable to delete directory` on asset/dex merge tasks — unrelated to the alarm code (no assets touched). Recommend moving the repo out of the OneDrive-synced path (or excluding `app/build`) before a full on-device build/test.

- **Reviewed**: 16 July 2026
- **Architecture**: Clock design-review deltas applied from `docs/design/` (ClockView, clock_overlay_layout, fragment_clock_settings, activity_time_zone_globe, shared color/dimen tokens)
- **Key Decisions**:
  - **AM/PM follows spec (6.1.2.1.1.2.2)/(6.1.2.1.1.2.3): only the active period is drawn.** The design file implies both render (it carries an "inactive AM/PM" token, `#cfcbc3`); review confirmed the spec wins and **`#cfcbc3` is now a dead token** — disregard it. The two labels still occupy a fixed two-line stack (AM slot above the readout's centre, PM below, per 6.1.2.1.1.2.1) and the horizontal offset reserves the wider of the two, so nothing shifts at noon or in locales whose period labels differ in width.
  - **The design's px values are read as ratios, not fixed sizes** (review decision). They exist to fix the *relationship* between elements; since the window is resizable, pinning them would break the design at any other size. `ClockView` scales each from the design reference (340dp-wide window, 220dp face), landing exactly on the design number at reference size and holding the proportion either side. Ratios are named constants in `ClockView.companion object` — e.g. AM/PM is `19f/60f` **of the readout**, not 19 of anything absolute. Note this is a relationship the design file itself does not pin down: `ClockStateEntity.windowWidth/Height` default to `-1` (WRAP_CONTENT), so no default window size exists in code to anchor against.
  - **Colors invert on dark faces to hold contrast** (review decision), implemented as a derivation with an AA guard rather than a light/dark literal pair. The design's greys are quoted against the white face only and do not survive elsewhere: `#6b6b6b` is **1.12:1 on teal**, `#1976D2` is **1.04:1**. So:
    - `secondaryNumeralColor()` dims the ink **34.8% toward the face** — the relationship `#6b6b6b` expresses against `#1c1c1c` ink on white, reproducing `#6b6b6b` exactly there. Where the dimmed value cannot hold 4.5:1 it falls back to full ink and lets the smaller size/lighter weight carry the 13-24 tier alone.
    - `amPmColor()` uses `#1976D2` where it holds 4.5:1 (white and black faces only) and falls back to ink otherwise. Safe because only the active period is drawn, so the accent is decoration, not the state signal.
    - **Verified**: worst contrast across all six `PurramidPalette` faces is now **4.56:1** (AA normal text = 4.5).
  - **Fixed a pre-existing AA failure in `getContrastColor()`.** It hardcoded a per-palette ink map that assigned **white ink to VIOLET (`#EE82EE`) at 2.32:1** — violet is a *light* colour (relative luminance 0.40) and needs dark ink (7.35:1). Replaced the whole map with a contrast comparison between the two inks, which reproduces the other five entries' intent, corrects violet, and keeps custom faces honest. `PurramidPalette` is no longer imported by `ClockView`.
  - Text sizes are `dp` in the overlay (`clock_*` dimens) and `sp` in the settings fragment (`settings_row_label_size`), per `purramid_code_style_guide.md`, rather than the flat px of the design file.
  - Applied the global 32dp/9dp/1.5dp secondary treatment to **`buttonNest`** as well, though the review lists only close/settings/back. Nest is a secondary icon button in the same window and leaving it at 24dp next to a 32dp settings button would read as arbitrary.
  - Fixed an incidental layout bug while re-anchoring the transport row: `clockContainer` was constrained top **and** bottom to parent while `controlButtonsContainer` was constrained below it, which pushed the controls outside the window. The face is now constrained to the top of the transport row. (`clock_motion_scene.xml` is an empty stub, so `MotionLayout` was behaving as a plain `ConstraintLayout` and not overriding these.)
  - `@drawable/control_buttons_background` is now **unused** — the review shows two bare circular transport buttons, not a row chip. Left in place rather than deleted.
  - **Verification:** `assembleDebug` BUILD SUCCESSFUL, `testDebugUnitTest` BUILD SUCCESSFUL. Contrast ratios above were computed against the WCAG 2.2 formula for every `PurramidPalette` face, not eyeballed. **Not checked on device** — the resize behavior, the lone AM/PM label's optical alignment in its slot, and the analog numerals' clearance of the hour ticks all need a real window. The OneDrive file-lock issue noted in the 15 July entry recurs on every incremental resource change; `./gradlew --stop` then `rm -rf app/build` clears it and lets a full `assembleDebug` through.

- **Reviewed**: 16 July 2026
- **Architecture**: Clock aligned to the Timer/Stopwatch service+shim pattern (ClockActivity, activity_clock.xml, clock_overlay_layout.xml, ClockOverlayService, ClockSettingsFragment)
- **Key Decisions**:
  - **Root cause: the clock never finished migrating to the overlay-service architecture.** Applying the design review's close/back sizing surfaced this — the deltas had no target because the controls were on a screen that cannot render. Timer and Stopwatch are coherent and were used as the reference; the Clock was the outlier on three counts, all fixed here.
  - **`activity_clock.xml` was dead markup — stripped to an empty root.** It held a full-screen clock (ClockView + play/pause/reset + close + settings) predating the service. Evidence it never reached the screen: `ClockActivity` is `Theme.Transparent` (`windowIsTranslucent`, transparent background) — the same theme as `TimerActivity`/`StopwatchActivity`, which are launcher shims; it starts the service and `finish()`es on a normal launch; **not one of its buttons had a click listener anywhere**; and `updateUI()` was an empty stub. Now mirrors `activity_timer.xml`: an empty `FrameLayout` root to anchor snackbars. Removed the matching dead Kotlin (`observeClockState()`/`updateUI()`).
    - `clockViewModel.initialize()` **kept deliberately** despite nothing consuming `uiState`: `loadInitialState()` writes a default `ClockState` row when the instance has none. Commented in place so it is not mistaken for more dead code.
  - **The clock's settings screen could never render — fixed.** `ClockSettingsFragment` was a plain `Fragment` committed via `replace(R.id.clock_fragment_container, …)` into a container declared `android:visibility="gone"` that nothing ever made visible, inside a transparent activity. `ClockActivity` also never called `finish()` on that path, leaving an invisible activity on the stack. **Tapping the clock's settings gear did nothing.** Now a `DialogFragment` shown with `.show(fm, TAG)` like `TimerSettingsFragment`/`StopwatchSettingsFragment`, with `onCreateDialog` matching theirs (transparent window, `FLAG_NOT_TOUCH_MODAL`, no dim, centred). `fragment_clock_settings.xml` gains `@drawable/dialog_background` + `minWidth=360dp` (design 1d) since the dialog window itself is transparent.
    - Added `onDismiss { activity?.finish() }` so the shim does not linger. **Timer and Stopwatch have this same gap** — worked around there by forcing window `alpha = 0f` in `TimerActivity.onStart`. Not touched here, but they should get the same treatment.
  - **Close button added to the overlay** (`buttonClose`, spec 13), wired in `ClockOverlayService` to the **already-existing** `removeClockInstance(instanceId)` — mirroring `TimerService:283`. The handler existed all along; only the button was missing.
  - **Removed `buttonNest` from the overlay** to give Close the top-right corner (spec 13 + design 1a/1b both put it there). Justified: Nest is specified as a *Settings* toggle (10.2.2.7) and is already wired at `ClockSettingsFragment:212`; the design shows no nest button in the window; and nest-from-overlay was a **one-way trip** — nesting hides every button including nest itself, so there was no way back out from the window.
  - Removed `enableOverlayButton` from `fragment_clock_settings.xml` (0 references, not in design 1d — another chat-era leftover that would now be visible in the dialog).
  - **Dead layouts confirmed, left in place for now:** `layout_clock_card.xml`, `view_floating_clock{,_analog,_digital}.xml`, `activity_clock_settings.xml`, `tooltip_clock_actions.xml` all have 0 references. The `view_floating_clock*` set looks like an abandoned attempt at the Timer/Stopwatch-style overlay layout. Candidates for deletion once the clock's UI settles.
  - **Verification:** `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL; confirmed `buttonNest`/`clock_fragment_container`/`enableOverlayButton` have no live references left. **None of this is verified on device** — in particular the settings dialog rendering, close actually dismissing a clock, and nest still being reachable via settings all need a real run. This is the highest-risk change in the session and wants a device pass before it is trusted.

- **Reviewed**: 16 July 2026
- **Architecture**: Lingering settings-shim fix carried across to Timer and Stopwatch (TimerSettingsFragment, StopwatchSettingsFragment, TimerActivity)
- **Key Decisions**:
  - Follow-up to the Clock alignment entry above. **Only one of the Clock's three problems existed here** — Timer/Stopwatch were already correct on the other two, and were the reference used to fix the Clock. Checked and found healthy, left alone: `activity_timer.xml`/`activity_stopwatch.xml` are already bare `FrameLayout` roots (no dead UI to strip), both settings fragments are already `DialogFragment`s, and both overlays already have a wired `closeButton`.
  - **The shared bug: neither settings dialog finished its host activity on dismiss.** Added `onDismiss { activity?.finish() }` to `TimerSettingsFragment` and `StopwatchSettingsFragment`, matching `ClockSettingsFragment`. Without it the transparent shim activity stays on top of the overlay after settings closes and, being a full-screen window, **swallows touches meant for the timer/stopwatch beneath it** — the user has to press Back to reach their own timer again.
  - **Removed `TimerActivity.onStart`'s `params.alpha = 0f` hack.** Its comment claimed it stopped the activity blocking the overlay, but window alpha only affects *visibility*, never touch dispatch — so it hid the symptom (a visible shim) while leaving the actual defect (a touch-stealing shim) in place. Redundant now that the activity finishes, and redundant anyway against `Theme.Transparent` + a transparent `windowBackground` + an empty root. `StopwatchActivity` never had the hack.
  - **Safe to finish on dismiss** — verified both `dismiss()` call sites in each fragment are terminal close-settings flows (the close button; and add-another, which issues `startForegroundService` *before* dismissing). The sub-dialogs `TimerSettingsFragment` opens (sound picker, music URL, countdown, presets) all use `childFragmentManager`, so they are torn down with the parent and are not affected.
  - Left `TimerActivity`'s `.add(fragment, TAG)` as-is rather than unifying on `.show(fm, TAG)` like Clock/Stopwatch: for a `DialogFragment` added with no container, `mShowsDialog` resolves true and it displays as a dialog either way. Working code, not worth the churn.
  - **Verification:** `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL; confirmed no `alpha`/`onStart` hacks remain in any of the three activities and all three settings fragments now override `onDismiss`. **Not verified on device** — the payoff (touches reaching the overlay after closing settings) is precisely what a build cannot show.

<!-- Append new entries below in the same format when a decision deviates from docs/specifications/. -->

