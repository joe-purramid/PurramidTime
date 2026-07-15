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

<!-- Append new entries below in the same format when a decision deviates from docs/specifications/. -->

