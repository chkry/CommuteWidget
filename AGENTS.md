# CommuteWidget - agent guide and decision memory

This file is the entry point for any coding agent working in this repo.
It records how to build, how the app behaves, every major decision with its reasoning, and the exact current state.
Read this before touching code; the owner's approved decisions below are binding unless the owner reverses them.

## What this is

A personal Android home-screen widget app for the owner's Samsung S24 Ultra (One UI, Android 14+), sideloaded via adb, never published.
It shows commute ETA with live traffic and a route map (Google Maps Platform), calendar-event routing, leave-by advisors, and several on-device routine automations.
Single user, single device; there is no backward-compatibility audience beyond the owner's own installed build and stored data.

## Build, verify, ship

- Toolchain env: `source ./env.sh` from the repo root before any Gradle command (sets JAVA_HOME, ANDROID_HOME, PATH).
- Verify: `./gradlew assembleDebug test lint` must be green before any commit; current suite is 622 JUnit4 unit tests, lint has 0 errors (pinned-version advisories are accepted).
- Tests are plain JVM JUnit4; there is no Robolectric and no instrumentation - test pure functions, extract logic to make it pure.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (owner's phone is often connected; installing after shipping is established practice).
- Git: repo-local identity `chkry <chkryreddy@gmail.com>`; remote `origin` uses the `github-personal` SSH alias (personal key), never the machine's default work identity.
- Ship pattern per change: build + test + lint green, commit with a why-focused message, push, install to the phone.
- Versions are pinned: Gradle 9.5, AGP 9.3.0 (built-in Kotlin, NO org.jetbrains.kotlin.android plugin), Kotlin 2.3.20, compileSdk/targetSdk 37, minSdk 34, Glance 1.1.1, Compose BOM 2026.08.00. Do not bump to silence lint advisories.

## Architecture map

- `CommuteWidget.kt`: the entire Glance widget UI (three responsive sizes: SMALL 2x2, WIDE 4x2 which is the owner's size, LARGE 4x4), actions (RefreshAction, NavigateAction), all pure formatting helpers.
- `engine/CommuteRefresher.kt`: the refresh pipeline; `engine/WidgetMode.kt`: window-model resolution; `engine/BestDepartureAdvisor.kt`: predicted-traffic sampling.
- `api/`: Google clients (RoutesClient with optional future departureTime, StaticMapUrl with traffic-colored segments, GeocodingClient, MapImageFetcher, Polylines).
- `calendar/CalendarReader.kt`: content-provider reads with pure, tested selection functions.
- `data/`: Preferences DataStore repository (singleton), AppSettings, CustomPill, CommuteSnapshot (the cache the widget renders from, including the resolved, deliberately uncapped `customPillOccurrences` list), HealthModels/HealthDayState/HealthHistory, codecs with safe fallbacks.
- `health/`: platform wrappers (HealthConnectFacade, ScreenEventsReader, CommuteAudioDetector, HealthNotificationListener).
- `engine/health/`: pure health logic (SleepLogic, WaterLogic, WalkAndSunsetLogic, NudgeLogic, HealthParams, CustomPillLogic).
- `engine/HealthNudgeComputer.kt`: orchestrates health reads and nudge computation into snapshot health fields.
- `HealthWidgetUi.kt` + `HealthActions.kt`: health pill/chip rendering and idempotent tap actions.
- `schedule/`: WorkManager workers (WindowBoundaryWorker, CalendarTickWorker, CalendarChangeWorker, CommuteLeaveByWorker, EventLeaveByWorker, HealthMorningWorker, HealthBoundaryWorker, HealthWalkNotifyWorker) and CommuteScheduler.ensureScheduled.
- `ui/SettingsScreen.kt` + MainActivity: hand-rolled, single-activity Settings-style home menu with eight categories and saveable screen-state navigation.
- `ui/SettingsComponents.kt`, `SettingsSummaries.kt`, `DayLabels.kt`, `RemindersHelpers.kt`, and category screens: reusable settings UI, pure summaries/day labels/reminder helpers, and focused Compose screens for commute setup, places/maps, alerts/timing, calendar, reminders, health/experimental nudges, widget appearance, and access/app info.

## Behavioral model (as approved by the owner)

- Window model: `commuteDays` plus a To Work window (default 7:00-10:00) and To Home window (default 17:00-20:00) drive everything; inside a window the widget shows that commute; outside (and on non-commute days) it is calendar mode.
- Calendar mode shows the next event remaining today (query extends past midnight by `eventTakeoverMinutes` so imminent after-midnight events still route); located events route from current location with a map; unlocated events render a full-width card; no events renders the wind-down card.
- Event takeover: a located event starting within `eventTakeoverMinutes` (default 120) replaces the commute view even inside windows.
- Leave-by advisors: commute (arrive-by targets, live-traffic based, precise one-shot alarm notification) and event (predicted traffic beyond `eventRealtimeThresholdMinutes`, buffer `eventLeaveByBufferMinutes`, one notification per event).
- Best departure: samples predicted traffic at 30-minute steps across the CURRENT commute window (morning = home to work, evening = work to home, auto-switching), once per window per day, shown as a map pill "Best: h:mm"; hidden while a calendar event is displayed.
- Refresh triggers: TAP (user), AUTO (window boundaries, calendar changes), TICK (20-minute event-freshness tick, event-driven chain with zero idle wakeups).
- A WorkManager content-URI observer on the calendar provider refreshes within about a minute of any calendar change.
- Routine automations (all on-device): wind-down card (tomorrow's first event + next clock-app alarm), countdown captions ("in 1h 45m" / "Free for 2h 10m"), morning brief ("3 meetings - first 10:00 am"), alarm line on the empty card.
- Widget appearance: owner-configurable background opacity (One UI translucency), text scale, and map-pill corner; ETA is traffic-colored (green/amber/red) with a traffic dot; pending renders as 45 percent alpha of the accent; stale (over 10 minutes) renders grey.
- Health layer (v6): six core features (sleep estimation via UsageStats with 14-day rolling history as nudge input only, evening walk advisor, water reminders with Health Connect hydration writes, supplement routines, audiobook commute detection via MediaSession, Health Connect 1.1.0 for steps/exercise) plus nine experimental toggles default OFF under **Experimental nudges** (sleep-debt brief soften, gym-day protein priority, restless-night focus shield, post-Audible walk latch, daylight walk preference, focus gap chip, post-gym water pulse, morning-light line, caffeine-cutoff line).
- Health suppression hierarchy: audiobook playback suppresses ALL health nudges; restless-night shield suppresses water and walk until first event ends or 10:00; water never renders on commute maps; max two health pills on map, max two card chips; 2x2 renders zero health UI; health elements never inherit pending alpha or stale grey.
- Custom pill reminders (v7): up to six user-defined, widget-only reminders with up to four selected weekday slots each; their shared active window defaults to 60 minutes and is configurable from 15-240 minutes; an active occurrence carries over at 60 percent alpha until dismissed, midnight, or the next non-dismissed slot for that pill; taps dismiss only that `pillId:slotMinute` occurrence locally with no notification or network work; a "Reset dismissed reminders" action in the Reminders screen clears today's `customPillTakenSlots` only, preserving all other day state.
- Settings use an Android-Settings-style eight-category home menu with live summaries: Commute setup, Places & Maps, Alerts & timing, Calendar, Reminders, Health, Widget appearance, and Access & app info; all permission grants are centralized in Access & app info while feature screens retain status links and automatic prompts.

## Decision log (dated, with the why)

- 2026-08-25 v1: Google Maps Platform chosen over Mapbox/HERE for India traffic quality; CLI-only toolchain (no Android Studio); API key pasted in settings and stored on-device only, excluded from backups.
- 2026-08-25 v2: favourites, leave-by advisor, calendar-on-tap, slot-based history added (favourites and history later removed - see the audit).
- 2026-08-26 v3: window model replaced the single 2pm switch time; windows are pure commute; owner explicitly removed the "Updating..." text state - NEVER reintroduce text-based loading copy; non-text pending treatments only.
- 2026-08-26 v4: event leave-by uses a future departureTime on the same Routes call (predicted traffic) beyond a threshold, live traffic within it; one notification per event via a zero-API one-shot work.
- 2026-08-26 UX audit (see UX-AUDIT.md): three independent specialist reports plus a moderated debate produced the v5 simplification; owner decisions: favourites reduced to saved-places-with-navigate in settings (no widget chips, no override pipeline), stats screen and history store deleted entirely, in-window 10-minute sampling deleted, calendar staleness tick accepted at 20 minutes.
- 2026-08-26 v5: Variant A widget hierarchy (destination, one large ETA, leave-by always present); pre-fetch render scoped to TAP only; location cache warm-up at window boundaries; day chips renamed "Commute days" (they always gated window existence - the stored DataStore key remains `history_days_json` deliberately so device data survives).
- 2026-08-26 post-audit latency: map render-hash cache (SHA-256 over polyline + traffic segments + endpoints + dimensions - anything less shows stale congestion colors); Static Maps requested at 600@2x so the downsample step no-ops; calendar-event geocode cached by location text and run concurrently with the location fix.
- 2026-08-26 pills: leave-by and best-departure render as opaque pills on the map (the 4x2 info panel cannot fit them), side by side, in an owner-configurable corner; ETA hour format is compact ("1h 15m").
- 2026-08-26 phantom alarm: the alarm line filters `getNextAlarmClock` by the show intent's creator package against a clock-app allowlist, because Samsung Modes and Routines registers schedule triggers as system alarm clocks.
- 2026-08-31 health layer v6: Health Connect only over Samsung SDK (boring/stable, goals set in-app); UsageStats sleep estimation (no wearable); MediaSession audiobook commute detection; 14-day rolling history allowed back as nudge input only with no stats UI; 8 extras built default-off, triage + wind-down nudge to backlog; walk notification is the only new notification; ~10-14 on-device wakeups/day approved; zero new Google API calls.
- 2026-08-31 v7 custom pills and settings IA: generic widget-only reminders use per-slot dismissal, caps of 6 pills x 4 slots, a global active window, and silent local transitions with about 2 approved local wakeups per slot; research-informed, owner-approved Settings-style IA separates eight categories with live summaries, and uses emoji icons because no icon artifact is on the classpath and new dependencies are forbidden.

## Hard-won invariants (violating these reintroduces shipped bugs)

- WorkManager: a worker rescheduling its own unique name must use APPEND_OR_REPLACE; REPLACE from inside a running worker cancels itself. REPLACE is correct only from external callers (ensureScheduled).
- Coroutine cleanup that must run on cancellation (clearing the refreshing flag, final widget render) must be wrapped in `withContext(NonCancellable)`; Glance action coroutines are cancelled after roughly 10 seconds and a cancelled coroutine cannot suspend.
- Notification post-then-mark must go through a shared mutex-guarded helper; separate check-then-act paths double-notify.
- CommuteSnapshot changes must be additive with defaults, and every addition needs a backward-compat decode test against a hardcoded old-format JSON string.
- Cross-package refactors follow deprecate-then-delete: keep old symbols compiling as inert @Deprecated stubs until every consumer is updated, then a final pass deletes them.
- Failure snapshots must not leak fields across mode/target transitions (same-target failures preserve stale data; target changes build clean snapshots).
- The engine's mutex serializes refreshes; the 5-second cooldown skip must not touch the refreshing flag except the deliberate 250ms tap-feedback flash.
- Any new background fetching beyond taps, window boundaries, and the accepted tick is a cost decision the owner must approve explicitly; it is never a silent default.
- Glance renders exactly two static compositions (pending, settled); no animation, no shimmer, no looping.
- ensureScheduled cancels legacy unique work names (v2 fixed refreshes, v4 slot chain) as device-migration hygiene; keep those cancellations.
- Health computation must never fail a route refresh: `computeHealthFieldsSafely` wraps `computeHealthState` in runCatching and carries previous health fields on exception.
- Failure snapshots must carry through `healthNudges`, `sleepEstimateMinutes`, and `shortSleepDay` from the previous snapshot (same-target failures preserve stale health data).
- Health tap actions are idempotent local writes with `NonCancellable` + `updateAll` and never trigger network; water tap records only on confirmed Health Connect write (write failure keeps the pill).
- Restless-night shield is applied at computation time; the widget render path always passes `shieldActive = false` to avoid double suppression.
- `HealthDayState`/`HealthHistory` additions follow the same additive-with-decode-test rule as `CommuteSnapshot`.
- Custom pill occurrences are fully resolved at computation time but stored UNCAPPED; the render layer re-filters against the live taken set for dismissal (never for suppression) and only then applies the max-3 cap and derives the `+N` overflow - capping before the dismissal filter strands stale or lone overflow indicators after taps.
- The boundary chain gate is `healthBoundaryNeeded` (health toggles OR any custom pill), not `anyHealthFeatureEnabled` alone; a pills-only configuration must keep the chain armed, while `health_morning` stays health-toggle-gated.
- Every non-route health rewrite of the snapshot goes through `withHealthComputation`; hand-copying health fields in a worker is how custom pill fields silently went missing once.
- Custom pill day-state and snapshot additions follow the same additive-with-decode-test rule as `CommuteSnapshot`.

## Known edges (flagged, accepted, not bugs)

- Cross-midnight leave-by times compare minute-of-day within one day, so the late-red state can be wrong for after-midnight events.
- A TICK refresh that fails on transient network ends the tick chain until the next tap or boundary (deliberate zero-idle-wakeup design).
- A buffer larger than the realtime threshold silently downgrades the event probe to live traffic (arithmetic stays correct).
- Some launchers flatten background alpha to opaque; One UI renders it correctly.

## API cost profile

- One key, three APIs: Routes (Pro SKU, 5000 free calls per month - the binding constraint), Static Maps and Geocoding (10000 free each).
- Typical day: about 4 boundary refreshes, taps, the event tick while a routed event shows, up to about 14 best-departure sampling calls; comfortably inside free tiers.
- Per-minute quota caps are set low in the Google Cloud console as the abuse guard; per-day caps do not exist for these APIs.

## Where to look next

- UX-AUDIT.md: the full audit, debate rulings, and the prioritized fix list (FIX-10..15 and 17 partially implemented since; FIX-14/15 and unified scheduling remain unimplemented candidates).
- HEALTH-FEATURES.md: owner-facing health layer reference (toggles, parameters, evidence labels, scheduling, permissions).
- README.md: owner-facing setup, behavior, and troubleshooting; keep it updated when behavior changes.
- The owner drives work through an interview-then-plan flow with approval gates; propose, get approval, then build in verifiable increments.
