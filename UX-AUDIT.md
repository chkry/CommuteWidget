# CommuteWidget UX Audit - Moderator Synthesis

Date: Wednesday, August 26, 2026

## How this audit was made

A principal UX researcher, a principal UI designer, and a principal performance engineer each independently audited the CommuteWidget codebase and wrote a full report.
This document is a moderator synthesis of those three reports, produced by staging their disagreements as explicit debates and issuing a ruling on each, then merging their overlapping findings into one prioritized fix list.
Every mandatory debate item was checked against the actual app source before a ruling was issued, including the widget layout constants and the F10 leave-by gap, rather than taken on the reports' word alone.

## Debate rulings

### a. Early `updateAll`: remove it, but only for background triggers

Performance wants the early `updateAll` in `CommuteRefresher.refreshNow` removed outright, citing 50-300 ms saved per refresh and 38-42 redundant background renders per day.
The designer's pending-state treatments need a render to happen before the fetch starts, so a naive removal would break every perceived-latency fix in the same report.
Verified in code: `refreshNow` calls `CommuteWidget().updateAll(appContext)` once immediately after acquiring the mutex and again in the `finally` block, for every trigger (TAP, AUTO, SLOT) with no trigger-based gating today.
Ruling: keep the pre-fetch render only for `RefreshTrigger.TAP` (which also covers `FavouriteAction`, itself dispatched as TAP), and drop it unconditionally for AUTO and SLOT.
Nobody is looking at the widget the instant a background worker fires, so a background pending frame buys nothing; a tap is the one case where a human is watching and the frame has a job to do.
This reconciles the two positions exactly as the task's suggested resolution describes and delivers the performance team's savings on 38-42 of the roughly 42 daily executions while preserving the one pending frame the designer's treatments require.

### b. ETA vs leave-by as the 4x2 hero

The designer's contested position 1 holds that ETA is the unique hero and leave-by is a 12sp secondary.
The researcher's F10 finding and the owner's ground truth (leave-by advisor used daily, alongside ETA and map) cut the other way: leave-by is not a secondary glance, it is a second daily decision input, and F10 shows it can vanish outright.
Ruling: ETA stays the largest visual element, because a single large number is still the fastest thing to read in a half-second glance and the owner's top stated priority is glanceable clarity.
Leave-by is promoted from "overlay pill that may not render" to a guaranteed second line in the info column, always present whenever leave-by is enabled and computed, never conditional on whether a map bitmap loaded.
This is not a size inversion, it is a reliability fix: the owner's actual behavior (checking leave-by daily) requires that the field always exist somewhere, even if ETA keeps the bigger font.

### c. The standing "updated Xm ago" line

The designer wants it removed: it is static chrome that never changes mid-fetch and trains the owner to stop reading it, while spending a row ETA should own.
The researcher's implicit trust argument (drawn from F2 and F6) is that staleness is sometimes real and unbounded, particularly in calendar mode outside windows, so removing all timing signal removes the owner's only way to notice a stuck state.
Ruling: cut the standing text caption from the layout (the designer wins the row back), but keep a non-text staleness signal, specifically the designer's own proposed treatment of dimming the ETA once `fetchedAt` is older than about 10 minutes.
This gives the owner a real signal exactly when data is actually stale, costs no dedicated row, and is reinforced by fixing F2 (debate item e) so the dimmed state becomes rare rather than the calendar-mode norm.
The owner's standing decision against text-based loading copy is respected throughout: the dim treatment is a color/alpha change, never new text.

### d. Favourite chips on 4x2

Both specialists lean toward removing favourite chips from the widget; the researcher additionally wants the saved-places capability preserved somewhere, since the owner does not use daily-glance chips but a rare-trip lookup still has value.
Ruling: stop rendering `FavouriteChipRow` on the widget surface (both WIDE and LARGE layouts) and default `showFavouriteChips` to false, which reclaims the WIDE panel space leave-by needs per ruling (b).
Whether to delete the favourites subsystem entirely (settings section, `performFavouriteRefresh` pipeline, `ActiveFavourite` expiry state) or keep a minimal "saved places + route there" action in settings is a removal question, not a design question, and is deferred to Owner Decisions below.
The moderator's recommendation there is to keep a lightweight list-and-navigate action and drop the parallel refresh pipeline and the widget-facing expiry state entirely, since that state is also the direct cause of bug F6.

### e. Calendar-mode periodic tick vs the owner's minimal-cost stance

The researcher's F2 finds calendar mode has zero periodic refresh, so "updated 3h ago" is the expected steady state outside windows, and proposes a coarse 15-20 minute tick scoped to when a located event is shown.
The owner's standing decision requires any new background fetching to be flagged as a cost-adding proposal, never a silent default, and the performance report's waste findings show every located-event refresh already costs a geocode, a route call, and a full map fetch.
Ruling: this is not the moderator's call to make by design, per the owner's own constraint, so it is built as an opt-in, default-off setting (see FIX-15) with settings copy that names the added battery and data cost, and it is also carried into Owner Decisions as a cost decision the owner must actively accept.
The moderator's opinion, offered for the owner's consideration only: since calendar mode is used daily and the current staleness is unbounded and hours-long, the tick likely earns its cost for this specific owner, but the decision is the owner's per the standing constraint.

### f. The 30-minute located-event preference

F8 flags that `selectTodayEvent` can silently reorder "what's next" by preferring a later located event over an earlier unlocated one within a 30-minute gap, and asks whether strict chronology should win instead.
The designer's frame (contested position 7 and the code's own comment, "a route we can draw is more actionable than a bare reminder") argues the preference is correct given what the app is for: a routing widget, not a calendar viewer.
Ruling: keep the preference; the app's entire reason for existing is to answer "how do I get there and when do I leave," and a bare reminder without a route does not serve that job as well as a routable event nearby in time.
Fix the actual complaint instead of the mechanism: add a small caption (for example "Routed" or "Next located event") whenever the chosen event is not the chronologically-first one, so the reordering is visible rather than invisible.
This is the cheapest possible fix (a label, not new logic) and it resolves F8's stated harm, which was invisibility, not the underlying rule.

### g. Where leave-by lives on 4x2

Three options were on the table: the current map-overlay pill (Variant "current"), the panel line (Variant A), and an opaque top bar over a full-bleed map (Variant B).
F10, now verified directly in `CommuteWidget.kt`, proves the current approach has a structural fallback gap: `showLeaveBy` is forced false in the WIDE info column, the pill only renders when `mapBitmap != null`, and the placeholder text list returns empty for `SnapshotMode.COMMUTE`, so on cold start or a persistent map failure during a commute window, leave-by appears nowhere on the widget.
Variant B is still a map-pixel-dependent placement in spirit (an overlay anchored to a map area), so it does not structurally close the same gap unless it is explicitly required to render even with no bitmap, which then makes it functionally identical to a panel line rendered over a blank background.
Ruling: ship Variant A, the panel line, for the 4x2 widget.
It is the only option that cannot regress into F10 again, because it lives in the info column and never depends on whether a bitmap loaded, and it is also the smaller implementation change, since it only touches the info-column composable rather than restructuring WIDE into a full-bleed map layout.
Variant B remains a legitimate future option if the owner later decides the map's spatial context should dominate the cell, but it is not the moderator's pick today, since the info-column fix is required regardless in order to close F10.

## Bugs

These are defects, not design or cost trade-offs, and should be fixed regardless of which redesign items the owner accepts.

### BUG-1: Leave-by can vanish entirely on the 4x2 widget (F10, verified)

Verified directly in `CommuteWidget.kt`.
`WideLayout` builds its `InfoStyle` with `showLeaveBy = false` (line 378), so the info column never shows the leave-by line on WIDE.
The leave-by pill is rendered only inside the map `Box`, gated on `mapBitmap != null` (line 418), so with no map bitmap the pill does not render either.
The code comment above that gate claims the no-map placeholder "already carries a Leave by text line," but `mapAreaPlaceholderLines` returns `emptyList()` for `SnapshotMode.COMMUTE` (lines 974-976), so that placeholder carries nothing.
Net effect: on cold start, or during any persistent Static Maps failure while a commute window is active, the leave-by time is present nowhere on the 4x2 widget, silently, with no error state distinguishing it from "leave-by is simply off."
Fix: FIX-3 below, which is also required regardless of the debate (g) ruling.

### BUG-2: Timed favourite override can display stale data past its own expiry (F6)

Verified in `SettingsRepository.activeFavourite`.
Expiry is checked only when this function is called: if the stored `ActiveFavourite` is expired, it self-clears and returns null, but nothing calls `provideGlance` (or otherwise forces a re-render) at the exact expiry instant.
Outside a commute window, the only remaining re-render opportunities are a manual tap or the next window boundary, which can be hours away.
Net effect: the widget can keep showing a favourite's ETA, map, and highlighted chip for hours after the favourite's window has actually expired, which will read to the owner as the app being simply wrong, not stale.
Fix: schedule a one-shot WorkManager job at `expiresAtEpochMillis`, mirroring the existing `EventLeaveByScheduler` one-shot alarm pattern, to force a re-render at the instant of expiry.

### BUG-3: Day chips silently disable commute mode for a whole weekday (F1)

Verified in `SettingsScreen.kt` (`HistorySection`, lines 829-860) against `resolveWidgetMode` behavior.
The day chips that feed `historyDays` sit directly under a switch labeled "Collect commute history," with the caption "Widget modes still follow the windows when this is off."
That caption is true of the switch itself but false of the chips beneath it: `resolveWidgetMode` treats any day outside `historyDays` as calendar-mode-only, unconditionally, so unchecking a day chip removes commute mode entirely for that weekday, not just history sampling.
This is a mislabeled control causing an unintended state change, not a design preference: the UI text makes an explicit claim about behavior that the code does not honor for part of what the control does.
Fix: FIX-5 below (rename and relocate the chips, correct the caption).

## Prioritized fixes

Ordered by impact-per-effort for this owner: glanceable clarity first, tap latency close second, ongoing cost third.
Each fix lists the report(s) and specific findings it merges.

### QUICK WINS

#### FIX-1: Scope the early `updateAll` to TAP-triggered refreshes only

`refreshNow` currently renders the widget twice on every refresh regardless of trigger, once immediately on mutex acquisition and once after the fetch completes.
Gate the first render to `RefreshTrigger.TAP` only (which also covers `FavouriteAction`), and skip it entirely for AUTO and SLOT, since no one is looking at a background-triggered refresh the instant it fires.
Impact: performance estimates 50-300 ms saved per background-triggered refresh and elimination of 38-42 redundant background renders per day, out of roughly 42 daily worker executions.
Effort: S.
Risk: Low, this only removes work, it does not change any user-visible behavior on TAP.
Source: performance report (remedy 2, waste finding 2), ruling (a).

#### FIX-2: Visible pending state on tap

Add a `refreshing` flag read by `provideGlance`: set it true, `updateAll`, run the fetch, set it false, `updateAll` again.
On TAP, render the ETA at roughly 45 percent alpha (traffic-colored) while pending, full alpha once settled; a debounced tap (inside the 5-second `MIN_REFRESH_GAP_MS` window) must still play the pending frame for 150-300 ms or the control will continue to feel broken, per the designer's note.
Impact: this is the direct fix for the owner's stated top pain, "still feels slow after a tap," which the researcher traces to the complete absence of any loading affordance (F12); the designer rates the alpha-swap treatment High feasibility.
Effort: M.
Risk: Low; failure states must keep using the existing warning glyph, not the pending alpha, to avoid conflating "slow" with "broken."
Source: researcher F12, designer perceived-latency treatments (rank 1), ruling (a).

#### FIX-3: Close the F10 leave-by fallback gap

Add a leave-by line to the WIDE info column whenever leave-by is enabled and computed, independent of whether a map bitmap is present, and remove the map-overlay pill's implicit assumption that the placeholder text already covers this case.
Impact: closes BUG-1 outright; this is also a hard prerequisite for ruling (g)'s Variant A recommendation, so it should ship even before the larger hierarchy rebuild (FIX-7).
Effort: S.
Risk: Low.
Source: researcher F10 (verified), designer contested position 4, rulings (b) and (g).

#### FIX-4: Proactively refresh device location at window-boundary AUTO refreshes

At each AUTO refresh (window-boundary trigger, twice daily), request a fresh location fix so `lastLocation` stays within the 2-minute freshness cutoff (`isLocationFresh`, `DEFAULT_LOCATION_MAX_AGE_MILLIS`).
This keeps subsequent taps on the fast path (5-50 ms cached-fix read) instead of risking the slow path, which can block up to the full 15-second `LOCATION_TIMEOUT_MS` on a stale cache.
Impact: directly attacks the worst-case tail of the tap-latency budget (performance report: "stale location can add the full 15-second timeout"); researcher lists this as an easier-functionality candidate with no new settings required.
Effort: S.
Risk: Low.
Source: researcher (easier-functionality candidate 2), performance tap-latency budget.

#### FIX-5: Rename and relocate the "Commute days" chips (fixes BUG-3)

Move the day chips out from under the "Collect commute history" switch, rename them to "Commute days," and correct or remove the now-inaccurate caption.
Scope `historyEnabled` purely to sampling, as the researcher's remedy specifies; optionally collapse the seven chips into a Weekdays / Every day / Custom preset per the researcher's smart-default suggestion, since that is a naming and layout change, not a removal.
Impact: closes BUG-3; also directly improves the settings decision inventory the researcher flagged (day chips were the most consequential mis-grouping in a 24-plus-decision settings screen).
Effort: S.
Risk: Low.
Source: researcher F1, settings decision inventory.

### Clarity-focused fixes

#### FIX-6: Rebuild the 4x2 visual hierarchy (Variant A)

Cut favourite chips (see FIX-1/d and Owner Decisions), distance, the 3dp traffic bar, and the standing "updated Xm ago" caption from the panel; move leave-by into the panel as a 12sp line below ETA; keep ETA as the single 28sp bold hero; for calendar-located events drop the "at h:mm" line in favor of the leave-by line, since leave-by is the actionable clock.
This is the designer's Variant A composition applied to the real WIDE cell, and it directly resolves the designer's P0-rated finding that "seven-plus items in an 88x110 composition" defeats the glance regardless of which item is nominally the hero.
Impact: designer rates this P0 (highest severity in that report); it is the single largest concrete step toward the owner's top stated priority, glanceable clarity.
Effort: M-L (touches every `ModeInfo` branch and the WIDE layout composable).
Risk: Low-Medium; Glance flattens to RemoteViews with no animation, so verify the static compositions on-device rather than in a preview.
Source: designer P0 items 1, 2, 3, 4, 5, 6 and the Variant A cut list; rulings (b), (c), (g).

#### FIX-7: Freshness as ETA dim, not standing text

Replace the always-present "updated Xm ago" caption with a permanent ETA dim once `fetchedAt` is older than roughly 10 minutes, full opacity otherwise; this is distinct from the pending-alpha treatment in FIX-2, and the two must use visually distinct alpha levels so "pending" and "stale" are never confused.
Impact: frees the row the designer wants for ETA while preserving the trust signal the researcher's F2/F6 findings say the owner still needs when data actually goes stale.
Effort: S-M.
Risk: Low; must not reuse the pending-alpha value, per the designer's own warning against conflating pending and failure/stale states.
Source: designer perceived-latency table, ruling (c).

#### FIX-8: De-duplicate the calendar-empty and unlocated-event layouts

Stop rendering the same title/time text independently in both the info column and the map-pane placeholder (`mapAreaPlaceholderLines`); give an unlocated event a single full-width card with no fake map area, and free the map pane in the true calendar-empty case for something else, for example a countdown to the next window.
Impact: resolves a genuine duplication the researcher and designer independently flagged; low severity individually but removes a "failed split" the designer calls out by name.
Effort: M.
Risk: Low.
Source: researcher F11, designer position 8.

#### FIX-9: Label the 30-minute located-event preference

When `selectTodayEvent` chooses a located event over a chronologically-earlier unlocated one, show a small caption (for example "Routed") so the reordering is visible instead of invisible.
Impact: resolves F8's stated harm (invisible reordering) without touching the underlying rule, which ruling (f) keeps.
Effort: S.
Risk: Low.
Source: researcher F8, designer position 7, ruling (f).

### Tap-latency and cost fixes

#### FIX-10: Static Maps render-hash cache

Compute a hash over everything that actually draws (polyline, speed intervals, markers, origin/destination, dimensions) and reuse the existing file on a hash match instead of unconditionally re-fetching, re-decoding, resizing, and re-encoding a new map on every TAP, AUTO, and calendar-mode refresh.
Impact: performance report calls this the single largest waste finding (CRITICAL); estimates 0.4-2.2 seconds and 0.3-0.9 MiB saved per cache hit.
Effort: M.
Risk: M; the hash must genuinely include everything that affects the rendered image, since speed-interval color changes alone can change the map even when the polyline geometry does not.
Source: performance waste finding 1, ranked remedy 1.

#### FIX-11: Parallelize geocode and location lookups; cache geocodes by event identity

For located calendar events, run the geocoding call and the device-location fetch concurrently instead of serially, and cache a successful geocode result keyed by event identity so a stable event's location is not re-geocoded on every refresh.
Impact: 150-1000 ms saved on calendar-mode taps, plus elimination of repeat geocodes for an event that has not changed; calendar mode is used daily per the owner's ground truth, so this compounds.
Effort: M.
Risk: M; cache invalidation must key off something that changes when the event's location text changes, not just event ID, in case an event is edited in place.
Source: performance waste finding 5, ranked remedy 4.

#### FIX-12: Cheaper Static Maps request and leaner SLOT-triggered Routes calls

Request Static Maps at 600x600 scale 2 (1200px) directly, so the existing decode/resize/re-encode step becomes unnecessary; strip unused polyline, speed-interval, and traffic fields from the Routes request issued by SLOT-triggered (history-sampling) refreshes, which discard that data today.
Impact: 40-200 ms plus 10-20 percent fewer map bytes per fetch; 50-90 percent smaller response bytes across the 36 SLOT samples per weekday.
Effort: S-M.
Risk: Low.
Source: performance ranked remedies 3 and 7.

#### FIX-13: Single settings-read accessor per refresh

Replace the current pattern of separate cold DataStore flows for settings, snapshot, and favourite (which the performance report counts at up to 9 `.first()` calls, and up to 21 JSON decode operations, per single-widget refresh) with one `readState()` accessor that decodes the `Preferences` object once and threads the result through.
Impact: roughly 9 reads down to 3 (2 once FIX-1 removes the redundant early render's reads); 5-50 ms plus allocations saved per refresh.
Effort: M.
Risk: Low.
Source: performance waste finding 9, ranked remedy 10.

#### FIX-14: Render right after the snapshot save; move bookkeeping after pixels

Move history insert and leave-by notification scheduling to after the final `updateAll` rather than before it, so the owner sees the settled state as soon as the data is ready instead of waiting on unrelated bookkeeping.
Impact: 5-100 ms saved on the visible end of every refresh.
Effort: S-M.
Risk: M; verify no downstream code depends on history/notification state being written before the render completes.
Source: performance waste finding 10, ranked remedy 11.

#### FIX-15: Explicit per-call timeouts; keep a valid ETA when only the map fails

Add an explicit `callTimeout` to each OkHttp operation (Routes and Static Maps currently share a client with only connect/read timeouts and no overall call timeout), and change failure handling so a Static-Maps-only failure preserves the already-successful route/ETA data rather than discarding the whole refresh.
Impact: bounds worst-case network tails; saves a genuinely valid ETA in the common case where only the map image fetch fails.
Effort: S-M.
Risk: M; touches the shared failure-snapshot path (`failureSnapshot`), which already has mode/target-transition guards that must not regress.
Source: performance ranked remedy 12.

### Reliability fixes

#### FIX-16: Give the commute leave-by its own precise one-shot alarm

Port the existing `EventLeaveByScheduler` pattern (a precise one-shot WorkManager alarm) to the daily-used commute leave-by, which today is only checked from inside `performCommuteRefresh`, reachable via TAP, AUTO, or the 10-minute SLOT cadence, with no dedicated alarm of its own.
Impact: the researcher notes this inverts precision relative to usage today: the less-critical per-event leave-by has the precise alarm, while the daily-used commute leave-by can silently miss its own notification window if a SLOT tick is delayed or skipped.
Effort: M (mirrors an existing, working pattern).
Risk: Low.
Source: researcher F9.

### Scheduling and cost fixes

#### FIX-17: Unify the boundary and slot scheduler chains

`SlotFetchWorker` and `WindowBoundaryWorker` both fire at 07:00, 10:00, 17:00, and 20:00 today, colliding at every boundary; the mutex usually suppresses the second, but which one wins (and therefore whether a map refresh happens) is nondeterministic.
Separately, SLOT's inclusive-endpoint ranges can trigger a full calendar-mode fetch (geocode, locate, route, map) at 10:00 and 20:00 even though the widget itself treats those instants as outside the window.
Unify into one boundary-plus-slot schedule with half-open windows to remove both the race and the endpoint misfire.
Impact: 4 fewer worker executions per day; removes a real correctness-adjacent race, not just a cost concern.
Effort: M.
Risk: M; scheduling logic is exactly the kind of code that benefits from the existing `WindowConsistencyTest`/`CommuteSchedulerTest`/`SlotFetchWorkerTest` coverage being extended alongside the change, not just relied on as-is.
Source: performance waste findings 7 and 8.

#### FIX-18: Opt-in coarse calendar-mode staleness tick (default off, cost-adding, owner-gated)

Add a settings toggle (default off) that, only while a located calendar event is currently displayed, runs a coarse 15-20 minute routes-only refresh tick, mirroring the SLOT design, with settings copy that explicitly names the added background network and battery cost.
This must not ship as a default per the owner's standing decision that any new background fetching beyond taps and window boundaries is a cost-adding proposal requiring explicit acceptance, not a default behavior.
Impact: closes F2's unbounded calendar-mode staleness (currently "updated 3h ago" is the expected steady state outside windows) for a mode the owner uses daily, at a real but bounded and disclosed ongoing cost.
Effort: M.
Risk: M, specifically because it is new recurring background work, which is exactly the category the owner's standing decision flags for extra scrutiny.
Source: researcher F2, easier-functionality candidate 4, ruling (e); see also Owner Decisions below, since whether to accept the added cost is the owner's call, not the moderator's.

## Owner decisions

These are removal, merger, and cost-acceptance questions the audit surfaced; the moderator recommends, the owner decides.

**1. Favourites: remove the subsystem entirely, or keep a low-visibility saved-places escape hatch?**
The chips themselves are already recommended for removal from the widget surface regardless (ruling d, FIX-1/d), which is a design decision, not a removal decision.
The open question is whether to also delete the settings section, the `performFavouriteRefresh` parallel pipeline, and the `ActiveFavourite` expiry state, which is fully built and, per the owner's ground truth, never used, and is also the direct root cause of bug F6.
Recommendation: keep a minimal "saved places" list plus a one-tap "route there" action in settings only, with no widget-facing chip, no dedicated refresh pipeline, and no timed expiry state; this captures the rare-trip value the researcher flagged at near-zero ongoing cost and eliminates F6's staleness surface entirely rather than merely patching it.

**2. Stats screen, history sampling, and the SQLite store: remove entirely?**
`StatsScreen.kt`, `HistoryStore.kt` (SQLite, two indexes, three aggregates), and the data-management sub-panel are fully built and, per the owner's ground truth, never used; every 10-minute SLOT sample is ongoing pure cost for zero consumed value today.
Recommendation: remove the screen and the store.
If there is any chance the owner wants the underlying commute-duration history for a future leave-by-accuracy feature, keep only the raw SLOT-driven sampling write path and drop the screen and the query/aggregate surface; otherwise remove all of it.

**3. Calendar-mode staleness tick: accept the added background cost?**
FIX-18 builds this as an opt-in, default-off setting, which satisfies the letter of the owner's standing minimal-cost decision, but turning it on is still a real, ongoing cost trade-off only the owner can accept.
Recommendation: enable it, because calendar mode is used daily per the owner's ground truth and the current staleness is unbounded and commonly hours-long, but the owner should pick the cadence (15 versus 20 minutes) directly, since that is a personal freshness-versus-battery preference with no objectively correct answer.

## Appendix: specialist report digests

**UX researcher report.**
The researcher's central finding is that the app's actual pain points are mechanism problems, not feature gaps: `historyDays` silently double-gates whether commute mode exists at all on a given day, not just whether history is sampled (F1); calendar mode, used daily, has no periodic refresh so "updated 3h ago" is the unbounded steady state (F2); tap-refresh has no loading affordance and a silent 5-second no-op window plus a location path that can block up to 15 seconds (F12, the stated top pain); and a fully-built favourites subsystem the owner never uses is also the root cause of a real staleness bug, a stale favourite that outlives its own expiry with no self-correcting refresh (F6, alongside the unused F3/F4 favourites and stats machinery).
The report also verified and reported the F10 leave-by fallback gap on WIDE, flagged an inverted precision relationship between the daily-used commute leave-by (coarse 10-minute SLOT cadence) and the rarer per-event leave-by (a precise one-shot alarm, F9), and catalogued a 24-plus-item settings screen where only about 10 decisions map to daily-used features.

**UI designer report.**
The designer's verdict is that the 4x2 widget is "a data dump in an 88dp column, not a glance surface": ETA is nominally the hero but loses to five competing sibling elements, a redundant 3dp traffic bar duplicating the ETA's own color, unused favourite chips consuming the panel's scarce width, and a leave-by pill relegated to the noisiest, most collision-prone part of the map despite being co-primary in actual daily use.
The report verifies that `infoWidth = LocalSize.current.width * 0.4f` is a fixed 88dp column, not a true 40 percent of the real WIDE cell, and proposes three redesign variants, recommending Variant A (chips gone, leave-by moved into the panel, ETA as the sole large element) as the one to ship, while cataloguing seven feasible perceived-latency treatments compatible with Glance's two-static-composition constraint (no animation, no shimmer) and explicitly ruling out any return to text-based loading copy.

**Performance report.**
The performance engineer's estimate is that a normal commute tap costs 0.7-4.0 seconds to final pixels, dominated by an unconditional, uncached Static Maps round trip (network fetch, full decode, resize, re-encode, then a second decode for Glance) that runs on every single TAP, AUTO, and calendar-mode refresh with no cache check at all, the report's single CRITICAL waste finding.
The report also finds that `refreshNow` performs two full `updateAll` passes per refresh (one of which is almost always pointless), that a calendar-mode refresh is a strict, needlessly serial waterfall of calendar-query, geocode, location, and route calls that could partly run concurrently, and that nominal defaults alone schedule roughly 42 background worker executions and 76-84 background `updateAll` calls per active weekday before the owner ever taps the widget once, for a typical weekday total of 41-43 Routes calls and 5-7 Static Maps calls.
