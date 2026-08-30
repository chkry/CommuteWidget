# CommuteWidget health layer reference

This document is the owner's independent-features reference for the v6 health layer and v7 custom pill reminders.
It supersedes the `.sprint0-*.md` scratch reports listed at the end.

## Overview

The health layer adds on-device wellness nudges to the existing commute widget without new Google Maps Platform calls.
Every feature has its own settings toggle (no master switch).
Signals come from Health Connect, UsageStats screen events, calendar context, and MediaSession audiobook detection.
Computation runs locally with safe degradation: missing permissions or failed reads hide only the affected nudge, never the commute route.
Anti-nag rules cap surface density (two health pills, two card chips), forbid water on commute maps, suppress all health UI during audiobook playback, never backfill missed water slots, and keep health elements out of pending or stale route styling.

## Feature-by-toggle table

| Feature | Settings switch name | Default | One-line behavior |
| --- | --- | --- | --- |
| Morning supplements | Morning supplements | ON | One pill marks vitamins and creatine taken during 07:00-10:00. |
| Evening protein | Evening protein | ON | One pill marks protein taken during 18:00-21:00. |
| Water reminders | Water reminders | ON | Up to five dynamic hydration slots per day; tap logs 250 ml to Health Connect. |
| Evening walk | Evening walk | ON | Suggests a calendar-aware walk slot when steps lag goal or the afternoon is sedentary. |
| Sleep prefix | Sleep estimate in morning brief | ON | Prefixes the To Work brief with `Slept ~6h 40m` from UsageStats. |
| Audiobook suppression | Suppress during audiobooks | ON | Hides all health nudges while a configured audiobook app is playing. |
| Sleep-debt brief soften | Soften after short sleep | OFF | Rewrites the brief to `Short sleep · ...` after short sleep plus 3+ meetings. |
| Gym protein priority | Prioritize protein on gym days | OFF | Promotes the Protein pill above water after gym exercise or a matching calendar title. |
| Restless-night shield | Rough-night shield | OFF | Suppresses water and walk until the first event ends or 10:00 after a rough night. |
| Post-Audible walk latch | Post-drive walk | OFF | Starts walk slot search 10 minutes after Audible stops after 17:00. |
| Daylight walk preference | Prefer daylight walks | OFF | Prefers walk slots finishing before locally computed NOAA sunset. |
| Focus gap chip | Focus gap chip | OFF | Shows a `Focus Nm` card chip for meeting-free gaps 45+ minutes (09:00-18:00). |
| Post-gym water pulse | Post-gym water | OFF | One extra water slot within 90 minutes after an exercise session. |
| Morning light line | Morning light | OFF | Adds a morning-light reminder line inside the To Work window. |
| Caffeine cutoff line | Caffeine cutoff | OFF | Shows `Coffee by 2:00 pm` from 90 minutes before the configured cutoff. |

Health Connect and Usage access are permissions, not toggles.
Custom pill reminders are not a health toggle and are configured from **Reminders**.
See the permissions section below.

## Sleep estimation

**Toggle:** Sleep estimate in morning brief (default ON).

**What it does:** Estimates prior-night sleep from UsageStats screen on/off events and prefixes the To Work morning brief when a valid estimate exists.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Overnight search window | 21:00 to 13:00 next day | convention |
| Morning poll | Daily 06:30 via `health_morning` worker, plus lazy backfill on first health compute | convention |
| Minimum screen-off span to seed a candidate | 20 minutes | weak |
| Brief-wake merge tolerance | 10 minutes | weak-moderate |
| Minimum plausible sleep | 180 minutes (3 hours); below this, no prefix is shown | convention |
| Maximum plausible sleep cap | 720 minutes (12 hours) | convention |
| Rolling history | 14 days in DataStore (`HealthHistory`); no stats UI | weak-moderate |
| Display format | `Slept ~6h 40m` prefix on the morning brief | convention |

**Known blind spots (evidence: weak):** phone left charging away from bed (overestimate); fragmented scrolling nights (underestimate); alarm-then-sleep-in splits (underestimate); another person using the phone overnight.

## Evening walk advisor

**Toggle:** Evening walk (default ON).

**What it does:** When steps are behind goal or the afternoon is sedentary, computes a walk duration and searches 18:00-21:30 for a calendar gap, avoiding events and the To Home commute window, finishing at least 60 minutes before typical bedtime.
Shows pill `Walk 7:30` and fires one mutex-guarded notification at walk start.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Step goal | 8000 (settings-configurable) | convention |
| Sedentary trigger | Under 1500 steps since noon, checked from 16:00 | convention |
| Duration formula | Step deficit at 90 steps/min, rounded up to 5 minutes | moderate (cadence), convention (formula) |
| Duration clamp | 10-45 minutes; skip if below floor | convention |
| Search window | 18:00-21:30 (settings-configurable) | convention |
| Bedtime buffer | Finish 60+ minutes before typical bedtime (derived from 14-day sleep history) | moderate |
| Notification | One per day via `health_walk_notify`; deduped by `walkNotified` | convention |

## Water reminders

**Toggle:** Water reminders (default ON).

**What it does:** Plans dynamic hydration slots anchored 07:30-19:30, shifted around calendar meetings, with 90-minute minimum spacing.
Shows one active `Drink water` pill for a 30-minute window.
Tap logs 250 ml to Health Connect and dismisses; write failure keeps the pill for retry.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Reminders per day | 5 default, configurable 3-8 | convention |
| Anchor range | 07:30-19:30 | convention |
| Minimum spacing | 90 minutes between shown or dismissed slots | convention |
| Active window | 30 minutes per slot | convention |
| Cutoff | No slot after 20:00; last anchor 19:30 | weak-moderate |
| Volume per tap | 250 ml | convention |
| Implied tap total | 1250 ml at 5 taps; copy frames this as a supplementary floor, not a daily target | strong reference values, convention framing |
| Backfill | Never | convention (anti-nag) |
| Commute map | Water never shown on commute map | convention (anti-nag) |

## Supplement routines

**Toggles:** Morning supplements (default ON), Evening protein (default ON).

**What it does:** Widget-only supplement nudges with one tap marking taken for the day; midnight reset.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Morning stack | `Vitamins + Cr` (4x2/WIDE), `Vitamins + creatine` (LARGE); one tap marks both | strong (creatine timing), moderate (vitamin-with-food window) |
| Morning window | 07:00-10:00 | moderate (food pairing), convention (clock bounds) |
| Evening protein | `Protein` pill, 18:00-21:00 | strong (protein timing window) |
| Carry-over | All day at 60 percent alpha demotion after the window ends | convention (anti-nag UX) |
| Vitamins cutoff | Hidden at 21:30 (food-pairing anchor) | weak-moderate |
| Protein cutoff | Persists to midnight | moderate (pre-sleep protein) |
| Priority | Supplements outrank water and walk; demoted carry-over drops below active water | convention |

## Custom pill reminders

**Toggle:** None.
Configure reminders from **Reminders**.

**What it does:** Shows user-defined generic reminder pills on the widget at selected daily times and weekdays.
Tap marks only that pill's current slot done for the day with no notification, network request, or Google Maps Platform call.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Maximum reminders | 6 (`CustomPill.MAX_PILLS`) | convention |
| Maximum slots per reminder | 4 (`CustomPill.MAX_SLOTS_PER_PILL`) | convention |
| Reminder name | Up to 12 characters | convention |
| Enabled days | Any subset of Monday=1 through Sunday=7, matching Commute days encoding | convention |
| Active window | 60 minutes default, configurable 15-240 minutes | convention |
| Storage | `custom_pills_json` and `custom_pill_active_window_minutes` in DataStore | convention |
| Dismissal key | `pillId:slotMinute` in `HealthDayState.customPillTakenSlots`, reset at midnight | convention |
| Carry-over | After the active window, remains at 60 percent alpha until tapped, midnight, or the next non-dismissed slot for that reminder | convention |
| Midnight crossing | Active windows truncate at midnight | convention |

**UI rules:** Custom pills render as a horizontal row on WIDE and LARGE maps, stacked 4 dp from the built-in health pill stack, and as chips on cards.
At most three occurrences are shown, followed by inert `+N` overflow when needed.
The 2x2 size shows no custom pill UI.
Custom pills never inherit pending or stale route styling.

**Suppression:** Audiobook playback suppresses custom pills at computation time.
The rough-night shield does not suppress custom pills.

## Audiobook commute detection

**Toggle:** Suppress during audiobooks (default ON).

**What it does:** Uses `MediaSessionManager` via the notification listener service to detect playback from configured packages (default: Audible).
While playing, suppresses ALL health nudges with no queuing or substitution.
Package list is editable in settings.

| Parameter | Value | Evidence |
| --- | --- | --- |
| Detection | Notification listener + MediaSession active sessions | convention |
| Default packages | `com.audible.application` | convention |
| Suppression scope | All health pills, chips, and lines | convention (driving attention) |

## Health Connect integration

**Not a toggle.** Permissions granted from **Access & app info** in settings.

**What it does:** Reads steps and exercise sessions; writes hydration on water tap.
Client version 1.1.0 (`androidx.health.connect:connect-client`).
All calls degrade to null, empty, or false on failure.

| Permission | Use |
| --- | --- |
| READ_STEPS | Step goal deficit, sedentary-afternoon check, history |
| READ_EXERCISE | Gym detection, post-gym water pulse |
| WRITE_HYDRATION | Water tap logging (250 ml) |
| READ_HEALTH_DATA_IN_BACKGROUND | Background step reads for workers |

Samsung Health must sync into Health Connect for step and exercise data to appear.
See permissions setup below.

## Experimental nudges (default OFF)

All live under **Health** > **Experimental nudges** in settings.

### Soften after short sleep

Rewrites the morning brief to `Short sleep · N mtgs · first h:mm` when prior-night sleep is under the 14-day median minus 60 minutes AND today has 3+ meetings.

### Prioritize protein on gym days

After a Health Connect exercise session ending after 12:00, or a calendar title containing the gym substring (default `gym`), promotes Protein to the top health pill slot.

### Rough-night shield

When overnight unlock count exceeds 6 AND sleep is below the 14-day median, suppresses water and walk until the first calendar event ends or 10:00.
Supplements still show.
Applied at computation time; the widget passes `shieldActive = false` to avoid double suppression in the render layer.

### Post-drive walk

When Audible (or configured packages) stops playing after 17:00 and steps remain under goal, starts walk slot search 10 minutes later.

### Prefer daylight walks

When choosing among eligible walk slots, prefers slots finishing before locally computed NOAA sunset from home coordinates.

### Focus gap chip

Card mini-chip `Focus Nm` for meeting-free gaps of 45+ minutes between 09:00 and 18:00.
Tap dismisses for that gap start minute.

### Post-gym water

One extra out-of-schedule water slot within 90 minutes after an exercise session ends, respecting the 90-minute spacing rule and never queueing a second pulse.

### Morning light

Optional line inside the To Work commute window suggesting early outdoor light exposure.

### Caffeine cutoff

Shows `Coffee by h:mm` (default cutoff 2:00 pm, configurable) starting 90 minutes before cutoff.
No caffeine intake logging.

## UI rules summary

- **Map pills:** Max two built-in health pills are stacked 4 dp apart in the map corner diagonally opposite commute pills (leave-by, best departure).
- **Custom pills:** A separate horizontal row, max three visible plus inert `+N` overflow, is stacked 4 dp from the built-in health pill stack on WIDE and LARGE maps.
- **Glyphs:** Checkmark (supplements), droplet (water), walker (walk).
- **Priority:** Supplements, then water, then walk; UX demotion overrides (carry-over at 60 percent alpha; evening protein outranks morning carry-over after 18:00).
- **Water on commute map:** Never.
- **Card chips:** Max two built-in tappable 48 dp mini-chips plus one optional line (morning light, caffeine cutoff), with custom reminder chips in their own row.
- **2x2 size:** Zero health or custom reminder UI (no pills, no chips, no brief sleep prefix).
- **Pending/stale:** Health and custom reminder elements never inherit pending alpha or stale grey; only route ETA does.
- **LARGE size:** Morning supplement label expands to `Vitamins + creatine`.
- **Confirmation:** Successful tap removes the pill or chip on the next settled render; no toast or animation.

## Scheduling and wakeup model

Three health WorkManager chains; zero new Google API calls.

| Worker | Unique name | Pattern | Role |
| --- | --- | --- | --- |
| `HealthMorningWorker` | `health_morning` | Daily 06:30 self-rescheduling chain | Sleep backfill, 14-day history upsert, water-slot plan for the day |
| `HealthBoundaryWorker` | `health_boundary` | One-shot chain to next transition | Recomputes health fields only (no network); wakes at water and custom-reminder slot starts and active-window ends, supplement window edges, 21:30 vitamins cutoff, 18:00 protein transition, walk target, shield end, caffeine lead/cutoff, midnight rollover |
| `HealthWalkNotifyWorker` | `health_walk_notify` | One-shot at walk start | Mutex-guarded post-then-mark against `walkNotified` |

**Reschedule policy:** Workers use `APPEND_OR_REPLACE` when rescheduling their own unique name from inside a running worker; `REPLACE` only from external callers (`ensureScheduled`).

**Typical load:** About 10-14 on-device wakeups per day before custom reminders.
Custom reminders add about two purely local wakes per eligible slot, up to 48 per day at the approved 6 reminders x 4 slots maximum.
No Routes, Static Maps, or Geocoding calls come from health workers.

Route refreshes also call `computeHealthState` synchronously; failures there carry forward previous health fields and never fail the route refresh.

## Permissions setup

Grant all permissions from **Access & app info** in app settings.

### Health Connect

1. Open CommuteWidget settings and select **Access & app info**.
2. Tap **Grant** on the Health Connect row.
3. Approve READ_STEPS, READ_EXERCISE, WRITE_HYDRATION, and READ_HEALTH_DATA_IN_BACKGROUND.

### Usage access (sleep estimation)

1. Tap **Grant** on the Usage access row (or open system Usage access settings).
2. Enable CommuteWidget.

Without Usage access, sleep estimation and the restless-night shield unlock count are unavailable.

### Notification access (audiobook detection)

1. Tap **Grant** on the Notification access row.
2. Enable CommuteWidget's notification listener.

Without notification access, audiobook suppression cannot detect playback.

### Samsung Health to Health Connect sync

Steps and exercise sessions read from Health Connect, not directly from Samsung Health.

1. Open **Samsung Health**.
2. Go to settings (gear icon).
3. Open **Health Connect permissions** (or **Connected apps**) and grant Samsung Health access to share steps and exercise with Health Connect.
4. Enable the **Consents** toggle for Health Connect data sharing if prompted.
5. Enable **Sync with Samsung account** (or equivalent sync toggle) so watch/phone step data reaches Health Connect.

If water tap does not dismiss the pill, Health Connect write permission is missing or the write failed; the pill stays for retry.

## Backlog (proposed, not built)

| Idea | Reason deferred |
| --- | --- |
| Hard-Commute Supplement Triage | Proposed: suppress water/walk on amber/red traffic mornings; not shipped in v6 |
| Screen wind-down nudge | Strong mechanism evidence but low compliance likelihood; deferred to backlog |
| Consistency streak | Evidence weak/mixed; recommend against as default |
| Sedentary micro-break | Overlaps the UX-audit-rejected in-window nag pattern |
| Recovery-aware walk sizing | Rejected by evidence (no support for shrinking walks after short sleep) |
| Best-Departure Step Window | Proposed innovation; not shipped |
| Dense-Day Step Forgiveness | Proposed innovation; not shipped |
| Red-ETA Creatine Carry Whisper | Proposed innovation; not shipped |
| Gym-ready check | Proposed innovation; not shipped |

## Source reports (superseded)

The following scratch reports informed this document and are no longer authoritative:

- `.sprint0-recon.md` (integration map)
- `.sprint0-ux.md` (pill copy, stacking, anti-nag UX)
- `.sprint0-health.md` (parameter evidence labels)
- `.sprint0-innovation.md` (experimental feature proposals)

When behavior conflicts, this file and the shipped code win.
