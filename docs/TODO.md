# PurramidTime - Open TODOs

Actionable follow-ups that aren't tied to a single spec file. For behavioral
deviations from `docs/specifications/`, use `docs/time_review_session_decisions.md`
instead.

## Play Console: justify the `specialUse` foreground service (REQUIRED before release)

**Context:** `ClockOverlayService`, `TimerService`, and `StopwatchService` declare
`foregroundServiceType="specialUse"` (permission `FOREGROUND_SERVICE_SPECIAL_USE`,
subtype `persistent_classroom_overlay`). Google Play requires a written justification
for every `specialUse` FGS at upload time, reviewed manually. Without it the release
is blocked.

**Action:** In the Play Console app-content / declarations flow, provide a
justification along these lines:

> PurramidTime is a classroom tool for interactive flat panels. The Clock, Timer,
> and Stopwatch draw a persistent, user-visible on-screen overlay (via
> SYSTEM_ALERT_WINDOW) that must keep running and updating for the full duration of
> a lesson (potentially many hours). None of the standard foreground-service types
> fit: it is not media playback, location, data sync, camera, microphone, phone
> call, or connected-device work. It is a long-lived interactive UI overlay, which
> is exactly what `specialUse` is for.

Keep the wording in sync with the manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value.

### Fallback if Google rejects the `specialUse` justification

Do **not** simply revert to `dataSync` — on Android 15 (~half the install base) a
`dataSync` FGS is force-stopped after a cumulative ~6h/24h, which is the exact
failure this change was made to avoid. Evaluate the fallbacks in this order:

1. **Device-owner / managed deployment → `systemExempted`.** These run on
   school-managed interactive panels, which are frequently MDM / device-owner
   provisioned. A device-owner-managed app can use
   `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` legitimately. If the target deployment is
   managed, switch to `systemExempted` and document the management assumption. This
   is the cleanest fallback for the classroom-panel use case.

2. **Split by activity: `mediaPlayback` only while audio plays.** The alarm/timer
   audio is genuine media playback, so declare `mediaPlayback` for those windows.
   For the (majority) silent overlay time, drop the foreground service entirely and
   rely on the `TYPE_APPLICATION_OVERLAY` window, which persists as long as the app
   process is alive and the overlay permission is held. Risk: the process can be
   killed under memory pressure with no FGS keeping it warm — persist window/instance
   state (already done via Room + `uuid`) and restore on next launch.

3. **Keep `dataSync` but handle the Android 15 timeout.** Last resort. Implement
   `Service.onTimeout()` (API 35): persist state, stop gracefully, and re-establish
   the foreground service on the next user interaction or a scheduled re-arm. Accept
   that an untouched all-day overlay may pause after ~6h until the teacher taps it.

Whichever fallback is chosen, update the manifest (type + permission + property),
`docs/specifications/purramidtime_architecture_decisions.md` (the "Foreground-service
type" note), and `docs/time_review_session_decisions.md`.
