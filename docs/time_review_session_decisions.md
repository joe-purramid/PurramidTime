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

<!-- Append new entries below in the same format when a decision deviates from docs/specifications/. -->

