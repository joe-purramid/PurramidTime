# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

PurramidTime is a native Android (Kotlin) classroom management app for interactive flat panels (Android 13+, large-screen tablets 55"+). It presents four "app-intents" launched from a floating launcher icon: Clock, Timer, Stopwatch, and About.

This project was previously developed through chat-driven sessions with Claude and Gemini, one app-intent per conversation. That workflow is retired — **`docs/specifications/` is now the source of truth for feature behavior** and should be read directly rather than re-derived from code or re-explained in prompts. `docs/time_review_session_decisions.md` is a running log of decisions that intentionally deviate from those specs (e.g. instance-numbering approach, a `HiltViewModelFactory` incompatibility workaround for `LifecycleService`); check it before assuming a spec is followed literally, and append new entries to it in the same format whenever an implementation choice deviates from `docs/specifications/`.

Do not use `docs/specifications/deprecated/` — those are superseded drafts kept for history only.

### Spec index and reading order

Filenames split into two groups — read the `purramid_*` group first, it's the shared foundation every feature spec assumes:

| Read | File | Covers |
|---|---|---|
| 1 | `purramidtime_architecture_decisions.md` | MVVM+Repository/Hilt/Room patterns mandatory across the whole app |
| 2 | `purramid_code_style_guide.md` | Naming, `dp`/`sp`, snackbars, localization |
| 3 | `purramid_button_implementation_guide.md` | Active/inactive icon state implementation (referenced by every feature spec) |
| 4 | `purramid_accessible_navigation.md` | Keyboard/switch-access behavior, applies app-wide |
| 5 | `purramid_audio_requirements.md` | Music/alarm/beep playback, applies to Clock/Timer/Stopwatch |
| 6 | `purramidtime_database_schema.md` | Target Room schema shared by Clock/Timer/Stopwatch |
| 7 | `purramidtime_specifications_main.md` | Launcher (`MainActivity`) behavior |
| 8 | `purramidtime_specifications_clock.md` | Clock app-intent (feature-specific) |
| 9 | `purramidtime_specifications_timer.md` | Timer app-intent (feature-specific) |
| 10 | `purramidtime_specifications_stopwatch.md` | Stopwatch app-intent (feature-specific) |
| 11 | `purramidtime_specifications_about.md` | About app-intent (feature-specific) |

Each feature spec uses numbered clauses (`(9.2.2.4.2)` etc.) as stable IDs — cite these instead of paraphrasing when discussing a specific requirement.

## Commands

Single Gradle module (`:app`). Standard wrapper commands:

```
./gradlew assembleDebug              # build debug APK
./gradlew installDebug                # build + install on connected device/emulator
./gradlew test                        # run all JVM unit tests (app/src/test)
./gradlew testDebugUnitTest --tests "com.example.purramid.purramidtime.SomeTest"   # single test class
./gradlew testDebugUnitTest --tests "com.example.purramid.purramidtime.SomeTest.someMethod"  # single test method
./gradlew connectedAndroidTest        # instrumented tests (app/src/androidTest), needs a device/emulator
./gradlew lint                        # Android Lint
```

On Windows use `gradlew.bat` in place of `./gradlew`. There is no Compose UI (layouts use `viewBinding`), so there's no Compose preview/tooling to run.

## Architecture

**Pattern**: MVVM + Repository, per `docs/specifications/purramidtime_architecture_decisions.md`. Activities own UI lifecycle only; ViewModels hold state/logic (`viewModelScope`); Repositories are the single source of truth over Room; Hilt provides DI (`@AndroidEntryPoint`, `@HiltViewModel`-style modules). Source lives under `app/src/main/kotlin/com/example/purramid/purramidtime/` (note: `kotlin/`, not `java/`), one package per app-intent (`clock/`, `timer/`, `stopwatch/`) plus shared `data/db/`, `di/`, `instance/`, `ui/`, `util/`.

**Service + Activity pairing per app-intent**: each of Clock/Timer/Stopwatch is a foreground `Service` (e.g. `ClockOverlayService`, `TimerService`, `StopwatchService`) that owns and draws a `WindowManager` overlay window, paired with a settings `Activity` (e.g. `ClockActivity`) launched from the manifest. The service, not the activity, is the long-lived owner of the overlay's lifecycle and state; the activity binds to it. `MainActivity` is only a launcher that unfolds a curved list of the four app-intents; for Clock/Timer/Stopwatch it starts the settings `Activity`, which in turn starts and binds the Service (it does not start the Service directly). The three services declare `foregroundServiceType="specialUse"` (not `dataSync`) with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` — `dataSync` is capped at ~6h/24h on Android 15 and would be force-stopped mid-lesson. They call the two-arg `startForeground(id, notification)`, so the FGS type comes solely from the manifest.

**Multi-instance windows**: each app-intent supports up to 4 simultaneous windows. `instance/InstanceManager.kt` allocates the standardized `instanceId` (1-4) used as the Room primary key and for window tracking; a `uuid` field on each state entity supports crash recovery. Do not use `AtomicInteger` for instance numbering — see the deviation log.

**Shared Room database**: one database (`purramid_time_db`) is shared across Clock, Timer, and Stopwatch — see `docs/specifications/purramidtime_database_schema.md` for the target entity/DAO shapes (`ClockStateEntity`, `ClockAlarmEntity`, `TimerStateEntity`, `StopwatchStateEntity`, `CityEntity`, `TimeZoneBoundaryEntity`). Treat that doc as the target schema, not a guarantee of current code — e.g. `timer/TimerState.kt` is currently a plain data class, not yet wired as a Room `@Entity`; check the actual `data/db/` files before assuming persistence is fully implemented for a given field.

**Clock's 3D timezone globe**: `clock/ui/TimeZoneGlobeActivity.kt` renders an interactive 3D globe (`scene.gltf`) for timezone selection using Filament/SceneView/ARCore, with timezone boundary polygons overlaid and city data from `CityEntity`. The on-disk assets are `app/src/main/assets/cities_timezones.csv` and `app/src/main/assets/time_zones.geojson` (**GeoJSON**); the loader parses the GeoJSON to JTS geometry and stores each boundary's **WKT** form in `TimeZoneBoundaryEntity.polygonWkt`, so runtime code round-trips through JTS (`WKTReader`/`WKTWriter`) rather than re-parsing GeoJSON. This is the most architecturally distinct corner of the app — see `docs/specifications/purramidtime_specifications_clock.md` section 9 before touching it.

**No microphone/audio-recording feature.** The manifest requests `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `INTERNET` (music URL streaming only — Clock alarm and Timer; the Stopwatch is beep-only and needs no network), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`. `USE_EXACT_ALARM` backs the Clock alarm; `SCHEDULE_EXACT_ALARM` is user-revocable on Android 13, so check `AlarmManager.canScheduleExactAlarms()` before scheduling. Do not add `RECORD_AUDIO` or decibel-metering code — that belongs to a different, unrelated Purramid app and has shown up as copy-paste leftovers in older spec drafts.

## Project-specific conventions

From `docs/specifications/purramid_code_style_guide.md` and `purramid_button_implementation_guide.md` (full detail there; highlights that aren't obvious):

- Package root is `com.example.purramid.purramidtime.*`; "Purramid" is never translated/localized, and logs may be English-only, but all other user-facing text must be a string resource.
- Use `dp` for text in fixed-size overlay windows where layout must not shift (clock face, timer/stopwatch display); use `sp` in standard Activities/settings screens where font-scaling accessibility applies. Document the choice in a layout XML comment when using `dp`.
- Action button icon states: `#757575` inactive, `#1976D2` active, `#E3F2FD` active background, driven by the `isActivated` property (see the button implementation guide for the full color-state-list/ripple pattern). Long-press shows a tooltip with a tail pointing at the icon, dismissed only by tapping elsewhere.
- User-facing messages are snackbars (opened near the triggering button), never toasts.
- New ViewModel/Repository code exposes state as `StateFlow`/`SharedFlow` (collected with `repeatOnLifecycle`), not `LiveData` — `lifecycle-livedata-ktx` is retained only for legacy paths. Prefer `kotlinx.serialization` (plugin already applied) over Gson for new Room type converters / JSON, even though Gson is still a dependency.
