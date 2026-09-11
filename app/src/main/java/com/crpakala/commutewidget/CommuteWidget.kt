package com.crpakala.commutewidget

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.calendar.UPCOMING_EVENT_LIMIT
import com.crpakala.commutewidget.data.AircraftRecord
import com.crpakala.commutewidget.data.AirportDelayStats
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportSnapshot
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.CustomPillOccurrence
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.aircraftTypeName
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.RidePhase
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.data.UpcomingEvent
import com.crpakala.commutewidget.data.isRefreshingActive
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.WidgetMode
import com.crpakala.commutewidget.engine.airportShowsFlightCard
import com.crpakala.commutewidget.engine.airportShowsLayoverReachedPill
import com.crpakala.commutewidget.engine.airportShowsLeaveByAndBest
import com.crpakala.commutewidget.engine.airportShowsToAirportPill
import com.crpakala.commutewidget.engine.currentBestDepartureTarget
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.mapInSampleSize
import com.crpakala.commutewidget.engine.probeLeaveByMinute
import com.crpakala.commutewidget.engine.resolveRidePhase
import com.crpakala.commutewidget.engine.resolveWidgetMode
import com.crpakala.commutewidget.engine.rideLastFailed
import com.crpakala.commutewidget.engine.shouldOfferRide
import com.crpakala.commutewidget.engine.shouldRunCommutePipeline
import com.crpakala.commutewidget.engine.shouldShowBestDeparture
import com.crpakala.commutewidget.engine.shouldShowReached
import com.crpakala.commutewidget.health.CommuteAudioDetector
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SMALL_BREAKPOINT = DpSize(110.dp, 110.dp)
private val WIDE_BREAKPOINT = DpSize(220.dp, 110.dp)
private val LARGE_BREAKPOINT = DpSize(220.dp, 220.dp)

// SizeMode.Responsive reports the matched breakpoint, not the physical box, so LARGE alone cannot
// tell a 4x3 from the owner's 4x6 - which is why the flight card's detail list used to scroll. This
// fourth breakpoint names the box that is genuinely tall enough for the fixed detail block, and
// MEDIUM the one tall enough for the ground block without it. Each height is its own layout's
// content floor at text scale 115, plus the 30 dp footer row and the card's 24 dp of padding:
// 307 + 54 for TALL, 215 + 54 for MEDIUM. 270 is also under 278.67 dp, the real height of the
// owner's 3-row box, so the 4x4 matches MEDIUM whether or not the launcher scales what it reports
// (One UI reports 1.2x the box it renders into at hsResizeRatio 0.8333). Anything shorter falls
// back to the WIDE card, which ends in a weighted spacer and simply breathes at that height.
private val MEDIUM_BREAKPOINT = DpSize(220.dp, 270.dp)
private val TALL_BREAKPOINT = DpSize(220.dp, 360.dp)
private const val MAP_DECODE_MAX_EDGE = 1200
private val LEAVE_BY_LATE_COLOR = Color(0xFFEA4335)
internal const val ETA_PENDING_ALPHA = 0.45f
internal const val ETA_STALE_AFTER_MILLIS = 10L * 60L * 1000L

internal enum class EtaDisplayState {
    PENDING,
    STALE,
    FRESH,
}

class CommuteWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_BREAKPOINT, WIDE_BREAKPOINT, LARGE_BREAKPOINT, MEDIUM_BREAKPOINT, TALL_BREAKPOINT),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = SettingsRepository.get(context)
        val initialData = repo.widgetRenderDataSnapshot()
        val lightScheme = dynamicLightColorScheme(context)
        val darkScheme = dynamicDarkColorScheme(context)
        val colors = ColorProviders(
            light = lightScheme,
            dark = darkScheme,
        )
        provideContent {
            // Bug fix 2026-08-31: everything dynamic is read INSIDE the composition. A live
            // Glance session only recomposes on observed state change - values captured before
            // provideContent stay frozen until the session dies, and Samsung's app freezer keeps
            // sessions alive for a long time. That left tap-dismissed health pills visible even
            // though the tap had written its state. Collecting the DataStore flow here makes
            // every write (tap actions, refresher, workers, settings screen) recompose and
            // republish the widget immediately, with or without an explicit updateAll.
            val data by repo.widgetRenderData.collectAsState(initial = initialData)
            val settings = data.settings
            val snapshot = data.snapshot
            val mapBitmap = remember(snapshot?.mapImagePath, snapshot?.fetchedAtEpochMillis) {
                loadMapBitmap(snapshot?.mapImagePath)
            }
            val nowEpochMillis = System.currentTimeMillis()
            val now = ZonedDateTime.now()
            val nowMinuteOfDay = now.hour * 60 + now.minute
            val configured = settings.apiKey.isNotBlank() && settings.home != null && settings.work != null
            val today = now.toLocalDate().toString()
            val widgetMode = resolveWidgetMode(
                dayOfWeekIso = now.dayOfWeek.value,
                minuteOfDay = nowMinuteOfDay,
                commuteDays = settings.commuteDays,
                morningStart = settings.morningSlotStartMinuteOfDay,
                morningEnd = settings.morningSlotEndMinuteOfDay,
                eveningStart = settings.eveningSlotStartMinuteOfDay,
                eveningEnd = settings.eveningSlotEndMinuteOfDay,
            )
            val windowDirection = (widgetMode as? WidgetMode.Commute)?.direction
            val ridePhase = if (windowDirection != null) resolveRidePhase(data.rideState, today, windowDirection) else RidePhase.OFFERED
            val rideReached = ridePhase == RidePhase.REACHED
            val bestDepartureTarget = currentBestDepartureTarget(
                nowMinuteOfDay = nowMinuteOfDay,
                morningStart = settings.morningSlotStartMinuteOfDay,
                morningEnd = settings.morningSlotEndMinuteOfDay,
                eveningStart = settings.eveningSlotStartMinuteOfDay,
                eveningEnd = settings.eveningSlotEndMinuteOfDay,
            )
            val bestDepartureLine = if (
                shouldShowBestDeparture(
                    result = data.bestDeparture,
                    enabled = settings.bestDepartureEnabled,
                    todayIsCommuteDay = now.dayOfWeek.value in settings.commuteDays,
                    today = today,
                    target = bestDepartureTarget,
                    showingCalendarEvent = snapshot?.mode == SnapshotMode.CALENDAR_EVENT,
                    rideReached = rideReached,
                )
            ) {
                bestDepartureLineText(data.bestDeparture!!.bestMinuteOfDay)
            } else {
                null
            }
            val rideActive = shouldRunCommutePipeline(widgetMode, ridePhase)
            val showReached = shouldShowReached(widgetMode, ridePhase, snapshot?.mode)
            val refreshingActive = isRefreshingActive(data.refreshingSince, nowEpochMillis)
            val commutePillRow = CommutePillRowContent(
                leaveByMinuteOfDay = probeLeaveByMinute(data.commuteProbe, widgetMode, ridePhase, settings.leaveByEnabled, today),
                bestLine = bestDepartureLine,
                rideDirection = windowDirection?.takeIf { shouldOfferRide(widgetMode, ridePhase, snapshot?.mode, refreshingActive) },
                rideFailed = windowDirection != null && rideLastFailed(data.rideState, today, windowDirection),
            )
            val nextAlarmLine = runCatching {
                val info = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock
                // Only real clock-app alarms: Samsung Modes and Routines (and similar apps) register
                // schedule triggers as system alarm clocks, which read as phantom alarms the owner
                // never set. The show intent's creator package identifies the actual owner.
                info?.takeIf { isClockAppAlarm(it.showIntent?.creatorPackage) }
                    ?.let { formatAlarmLine(it.triggerTime) }
            }.getOrNull()

            val audiobookPlaying = settings.audiobookSuppressionEnabled &&
                CommuteAudioDetector.isCommuteAudioPlaying(context, settings.commuteAudioPackages)
            val healthChrome = if (snapshot != null) {
                resolveVisibleHealthChrome(
                    snapshotNudges = snapshot.healthNudges,
                    dayState = data.healthDayState,
                    todayIsoDate = now.toLocalDate().toString(),
                    nowMinuteOfDay = nowMinuteOfDay,
                    mode = snapshot.mode,
                    audiobookPlaying = audiobookPlaying,
                )
            } else {
                VisibleHealthChrome(pills = emptyList(), line = null)
            }
            val customPillRow = if (snapshot != null) {
                resolveCustomPillRowContent(
                    occurrences = snapshot.customPillOccurrences,
                    dayState = data.healthDayState,
                    todayIsoDate = now.toLocalDate().toString(),
                    maxVisible = customPillCapFor(snapshot.mode),
                )
            } else {
                CustomPillRowContent(occurrences = emptyList(), overflowLabel = null)
            }

            val backgroundAlpha = settings.widgetBackgroundOpacityPercent.coerceIn(30, 100) / 100f
            val background = dayNightColorProvider(
                day = lightScheme.surface.copy(alpha = backgroundAlpha),
                night = darkScheme.surface.copy(alpha = backgroundAlpha),
            )
            GlanceTheme(colors = colors) {
                WidgetScaffold(
                    configured = configured,
                    snapshot = snapshot,
                    mapBitmap = mapBitmap,
                    background = background,
                    extras = WidgetExtras(
                        nowEpochMillis = nowEpochMillis,
                        nowMinuteOfDay = nowMinuteOfDay,
                        leaveByEnabled = settings.leaveByEnabled,
                        refreshingSince = data.refreshingSince,
                        bestDepartureLine = bestDepartureLine,
                        nextAlarmLine = nextAlarmLine,
                        pillCorner = settings.mapPillCorner,
                        textScale = settings.widgetTextScalePercent.coerceIn(70, 150) / 100f,
                        sleepBriefEnabled = settings.sleepBriefEnabled,
                        healthPills = healthChrome.pills,
                        healthLineLabel = healthChrome.line?.let(::healthLineCaption),
                        customPillRow = customPillRow,
                        rideActive = rideActive,
                        showReached = showReached,
                        rideReached = rideReached,
                        rideDirection = windowDirection,
                        commutePillRow = commutePillRow,
                        hasFlightStatusKey = settings.flightStatusApiKey.isNotBlank(),
                        healthColors = HealthChromeColors(
                            mapTextDemoted = dayNightColorProvider(
                                day = lightScheme.onSurfaceVariant.copy(alpha = HEALTH_DEMOTION_ALPHA),
                                night = darkScheme.onSurfaceVariant.copy(alpha = HEALTH_DEMOTION_ALPHA),
                            ),
                            cardTextDemoted = dayNightColorProvider(
                                day = lightScheme.onSurface.copy(alpha = HEALTH_DEMOTION_ALPHA),
                                night = darkScheme.onSurface.copy(alpha = HEALTH_DEMOTION_ALPHA),
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

class CommuteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CommuteWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        CommuteScheduler.ensureScheduledAsync(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        CommuteRefresher.refreshNow(context)
    }
}

class NavigateAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val snapshot = repo.snapshot()
        val destLat: Double
        val destLng: Double
        val snapshotLat = snapshot?.destinationLat
        val snapshotLng = snapshot?.destinationLng
        if (snapshotLat != null && snapshotLng != null) {
            destLat = snapshotLat
            destLng = snapshotLng
        } else {
            val dest = settings.work ?: settings.home ?: return
            destLat = dest.lat
            destLng = dest.lng
        }
        val modeChar = when (settings.travelMode) {
            TravelMode.DRIVE -> "d"
            TravelMode.TWO_WHEELER -> "l"
        }
        launchNavigation(context, destLat, destLng, modeChar)
    }
}

private data class HealthChromeColors(
    val mapTextDemoted: ColorProvider,
    val cardTextDemoted: ColorProvider,
)

private data class WidgetExtras(
    val nowEpochMillis: Long,
    val nowMinuteOfDay: Int,
    val leaveByEnabled: Boolean,
    val refreshingSince: Long?,
    /** Pre-formatted "Best: 3:30 pm" line, null when disabled/stale/slot passed. */
    val bestDepartureLine: String? = null,
    /** Pre-formatted "⏰ Alarm 7:00 am" line, null when no next alarm is set. */
    val nextAlarmLine: String? = null,
    val pillCorner: MapPillCorner = MapPillCorner.TOP_START,
    val textScale: Float = 1f,
    val sleepBriefEnabled: Boolean = false,
    val healthPills: List<NudgeCandidate> = emptyList(),
    val healthLineLabel: String? = null,
    val healthColors: HealthChromeColors? = null,
    val customPillRow: CustomPillRowContent = CustomPillRowContent(emptyList(), null),
    val rideActive: Boolean = false,
    val showReached: Boolean = false,
    /** True when the window's ride was consumed by a Reached tap, hiding Leave by and Best until the next slot. */
    val rideReached: Boolean = false,
    /** The window's direction, for the Reached action parameter. */
    val rideDirection: Direction? = null,
    val commutePillRow: CommutePillRowContent = CommutePillRowContent(null, null, null, false),
    /** True when an AirLabs key is configured; the flight row invites one when it is not. */
    val hasFlightStatusKey: Boolean = false,
)

/** Owner-configurable text scaling applied to every user-visible size on the widget. */
private fun scaledSp(base: Int, scale: Float): TextUnit = (base * scale).sp

private data class InfoStyle(
    val destinationFontSize: TextUnit,
    val etaFontSize: TextUnit,
    val leaveByFontSize: TextUnit,
    val inlineEta: Boolean,
    /** FIX-9: WIDE and LARGE show the "Routed" caption when applicable; SMALL skips it for space. */
    val showRoutedCaption: Boolean,
    /** WIDE renders leave-by as a pill on the map instead of a panel line; the panel is too narrow. */
    val showLeaveBy: Boolean = true,
    /** WIDE shows the route distance under the ETA; SMALL and LARGE stay two-line for space. */
    val showDistance: Boolean = false,
    /** WIDE and LARGE show the best-departure line; SMALL skips it for space. */
    val showBestDeparture: Boolean = false,
    /**
     * WIDE and LARGE show idle-state captions (event countdown, morning brief);
     * SMALL skips them for space. Mirrors [showRoutedCaption] size gating.
     */
    val showExtendedCaptions: Boolean = false,
    /** LARGE keeps the full sleep-prefixed brief; WIDE truncates when a sleep prefix is present. */
    val fullMorningBrief: Boolean = false,
    /** LARGE commute info column can host the health caption without displacing the ETA. */
    val showHealthLine: Boolean = false,
)

@Composable
private fun WidgetScaffold(
    configured: Boolean,
    snapshot: CommuteSnapshot?,
    mapBitmap: Bitmap?,
    background: ColorProvider,
    extras: WidgetExtras,
) {
    val root = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(background)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)

    when {
        !configured -> UnconfiguredContent(root, extras.textScale)
        snapshot == null -> EmptySnapshotContent(root, extras.textScale)
        else -> ConfiguredContent(root, snapshot, mapBitmap, extras)
    }
}

@Composable
private fun UnconfiguredContent(modifier: GlanceModifier, textScale: Float = 1f) {
    val context = LocalContext.current
    val openSettings = actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    Box(
        modifier = modifier.clickable(openSettings).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Open to set up",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = scaledSp(14, textScale),
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun EmptySnapshotContent(modifier: GlanceModifier, textScale: Float = 1f) {
    Box(
        modifier = modifier.clickable(actionRunCallback<RefreshAction>()).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Tap to load",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = scaledSp(14, textScale),
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun ConfiguredContent(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    mapBitmap: Bitmap?,
    extras: WidgetExtras,
) {
    if (snapshot.mode == SnapshotMode.CALENDAR_EMPTY) {
        CalendarEmptyCard(modifier, snapshot, extras)
        return
    }
    val airport = snapshot.airport
    if (snapshot.mode == SnapshotMode.AIRPORT && airport != null && airportRendersFlightCard(airport.phase)) {
        FlightCard(modifier, airport, extras)
        return
    }
    val size = LocalSize.current
    when {
        size.width >= LARGE_BREAKPOINT.width && size.height >= LARGE_BREAKPOINT.height -> {
            LargeLayout(modifier, snapshot, mapBitmap, extras)
        }
        size.width >= WIDE_BREAKPOINT.width -> {
            WideLayout(modifier, snapshot, mapBitmap, extras)
        }
        else -> {
            SmallLayout(modifier, snapshot, extras)
        }
    }
}

@Composable
private fun SmallLayout(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
) {
    val accent = trafficAccentColor(snapshot.durationSeconds, snapshot.durationNoTrafficSeconds)
    val airport = snapshot.airport
    if (snapshot.mode == SnapshotMode.AIRPORT && airport != null) {
        // 2x2 has no map to overlay, so the airport pills take the nav glyph's place while the
        // configured corner still decides which end of the widget they sit at, as on WIDE. Leave
        // by drops out of the info column because the pill row already carries it, and the row
        // runs compact: four stacked pills would cover the info column on a 110dp widget, so Best
        // (the one pill nothing is driven from) gives up its slot and To Airport, Reached and the
        // card's Done all stay reachable.
        Box(modifier = modifier.clickable(actionRunCallback<RefreshAction>())) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(8.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.Start,
            ) {
                RoutedInfo(
                    snapshot = snapshot,
                    extras = extras,
                    accent = accent,
                    style = InfoStyle(
                        destinationFontSize = scaledSp(11, extras.textScale),
                        etaFontSize = scaledSp(20, extras.textScale),
                        leaveByFontSize = scaledSp(11, extras.textScale),
                        inlineEta = false,
                        showRoutedCaption = false,
                        showLeaveBy = false,
                    ),
                )
            }
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = pillCornerAlignment(extras.pillCorner),
            ) {
                AirportMapPillOverlay(snapshot, airport, extras, compact = true)
            }
        }
        return
    }
    // 2x2 has no map either, so a routed event's Reached pill overlays the info column in the
    // configured corner - the same corner the airport pills take above.
    val eventReached = eventReachedTarget(snapshot)
    Box(modifier = modifier.clickable(actionRunCallback<RefreshAction>())) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.Start,
        ) {
            RoutedInfo(
                snapshot = snapshot,
                extras = extras,
                accent = accent,
                style = InfoStyle(
                    destinationFontSize = scaledSp(11, extras.textScale),
                    etaFontSize = scaledSp(24, extras.textScale),
                    leaveByFontSize = scaledSp(11, extras.textScale),
                    inlineEta = false,
                    showRoutedCaption = false,
                ),
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = "📍",
                style = TextStyle(fontSize = 14.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<NavigateAction>()).padding(2.dp),
            )
        }
        if (eventReached != null) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = pillCornerAlignment(extras.pillCorner),
            ) {
                Box(modifier = GlanceModifier.padding(6.dp)) {
                    ReachedPill(
                        eventReachedTapAction(eventReached.startEpochMillis, eventReached.title),
                        extras.textScale,
                    )
                }
            }
        }
    }
}

@Composable
private fun WideLayout(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    mapBitmap: Bitmap?,
    extras: WidgetExtras,
) {
    val accent = trafficAccentColor(snapshot.durationSeconds, snapshot.durationNoTrafficSeconds)
    val infoWidth = LocalSize.current.width * 0.45f
    Row(modifier = modifier) {
        Column(
            modifier = GlanceModifier
                .width(infoWidth)
                .fillMaxHeight()
                .padding(10.dp)
                .clickable(actionRunCallback<RefreshAction>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            RoutedInfo(
                snapshot = snapshot,
                extras = extras,
                accent = accent,
                style = InfoStyle(
                    destinationFontSize = scaledSp(11, extras.textScale),
                    etaFontSize = scaledSp(22, extras.textScale),
                    leaveByFontSize = scaledSp(12, extras.textScale),
                    inlineEta = false,
                    showRoutedCaption = true,
                    showLeaveBy = false,
                    showDistance = true,
                    showExtendedCaptions = true,
                ),
            )
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionRunCallback<NavigateAction>()),
        ) {
            MapPane(
                snapshot = snapshot,
                bitmap = mapBitmap,
                modifier = GlanceModifier.fillMaxSize(),
            )
            // Pills ride on the map; the panel is too narrow for their full strings. Deliberately
            // NOT gated on the bitmap so they can never vanish with a failed map fetch. Corner is
            // owner-configurable so the stack can dodge whatever the route usually covers.
            // Reached replaces Best while a ride is active: the map pill corner tracks the ride's
            // end, not the pre-ride best-departure estimate.
            val airport = snapshot.airport
            if (snapshot.mode == SnapshotMode.AIRPORT && airport != null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = pillCornerAlignment(extras.pillCorner),
                ) {
                    AirportMapPillOverlay(snapshot, airport, extras)
                }
            } else {
                val bestLine = extras.bestDepartureLine
                val leaveByMinute = snapshot.leaveByMinuteOfDay
                val showLeaveByPill = leaveByMinute != null && shouldShowLeaveBy(snapshot, extras.leaveByEnabled, extras.rideReached)
                val showBestOnMap = showBestDepartureOnMap(extras.rideActive, bestLine)
                // A routed event's Reached joins this same row: the commute Reached and Best can
                // never be showing at the same time (both need a COMMUTE snapshot), so the row is
                // at most Leave by plus one Reached.
                val eventReached = eventReachedTarget(snapshot)
                if (showLeaveByPill || showBestOnMap || extras.showReached || eventReached != null) {
                    Box(
                        modifier = GlanceModifier.fillMaxSize(),
                        contentAlignment = pillCornerAlignment(extras.pillCorner),
                    ) {
                        Row(
                            modifier = GlanceModifier.padding(6.dp),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            if (leaveByMinute != null && showLeaveByPill) {
                                LeaveByPill(leaveByMinute, extras.nowMinuteOfDay, extras.textScale)
                            }
                            val reachedDirection = extras.rideDirection?.takeIf { extras.showReached }
                            if (reachedDirection != null) {
                                if (showLeaveByPill) {
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                }
                                ReachedPill(reachedTapAction(reachedDirection), extras.textScale)
                            } else if (eventReached != null) {
                                if (showLeaveByPill) {
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                }
                                ReachedPill(
                                    eventReachedTapAction(eventReached.startEpochMillis, eventReached.title),
                                    extras.textScale,
                                )
                            } else if (showBestOnMap && bestLine != null) {
                                if (showLeaveByPill) {
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                }
                                MapTextPill(bestLine, extras.textScale)
                            }
                        }
                    }
                }
            }
            val hasCustomPillRow = !extras.customPillRow.isEmpty
            if (extras.healthPills.isNotEmpty() || hasCustomPillRow) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = pillCornerAlignment(oppositeCorner(extras.pillCorner)),
                ) {
                    // Owner ruling 2026-08-31 (sprint 3): custom pills stack vertically below the
                    // built-in health pill row (4dp gap) rather than sharing one Row - the WIDE
                    // map is too narrow to risk a combined row overflowing past the edge.
                    Column {
                        if (extras.healthPills.isNotEmpty()) {
                            HealthPillStack(
                                pills = extras.healthPills,
                                extras = extras,
                                expandMorningLabel = false,
                            )
                        }
                        if (hasCustomPillRow) {
                            if (extras.healthPills.isNotEmpty()) {
                                Spacer(modifier = GlanceModifier.height(4.dp))
                            }
                            CustomPillMapRow(content = extras.customPillRow, extras = extras)
                        }
                    }
                }
            }
        }
    }
}

private fun pillCornerAlignment(corner: MapPillCorner): Alignment = when (corner) {
    MapPillCorner.TOP_START -> Alignment.TopStart
    MapPillCorner.TOP_END -> Alignment.TopEnd
    MapPillCorner.BOTTOM_START -> Alignment.BottomStart
    MapPillCorner.BOTTOM_END -> Alignment.BottomEnd
}


@Composable
private fun LargeLayout(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    mapBitmap: Bitmap?,
    extras: WidgetExtras,
) {
    val accent = trafficAccentColor(snapshot.durationSeconds, snapshot.durationNoTrafficSeconds)
    Box(modifier = modifier) {
        MapPane(
            snapshot = snapshot,
            bitmap = mapBitmap,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            ) {
                RoutedInfo(
                    snapshot = snapshot,
                    extras = extras,
                    accent = accent,
                    style = InfoStyle(
                        destinationFontSize = scaledSp(16, extras.textScale),
                        etaFontSize = scaledSp(16, extras.textScale),
                        leaveByFontSize = scaledSp(11, extras.textScale),
                        inlineEta = true,
                        showRoutedCaption = true,
                        showBestDeparture = !extras.rideActive,
                        showExtendedCaptions = true,
                        fullMorningBrief = true,
                        showHealthLine = true,
                    ),
                )
            }
            Spacer(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxWidth()
                    .clickable(actionRunCallback<NavigateAction>()),
            )
        }
        val airport = snapshot.airport
        if (snapshot.mode == SnapshotMode.AIRPORT && airport != null) {
            // LARGE otherwise keeps leave-by and best as plain text inside the info card, but
            // airport mode needs a clickable To Airport pill regardless, so the whole row renders
            // as pills in the pill corner (unused by LARGE outside airport mode).
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = pillCornerAlignment(extras.pillCorner),
            ) {
                AirportMapPillOverlay(snapshot, airport, extras)
            }
        }
        // The configured pill corner is otherwise unused on LARGE, so a routed event's Reached
        // takes it rather than stacking with the health pills opposite (where the commute Reached
        // has to sit, because that corner already carries the ride's own row).
        val eventReached = eventReachedTarget(snapshot)
        if (eventReached != null) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = pillCornerAlignment(extras.pillCorner),
            ) {
                Box(modifier = GlanceModifier.padding(6.dp)) {
                    ReachedPill(
                        eventReachedTapAction(eventReached.startEpochMillis, eventReached.title),
                        extras.textScale,
                    )
                }
            }
        }
        val hasCustomPillRow = !extras.customPillRow.isEmpty
        if (extras.healthPills.isNotEmpty() || hasCustomPillRow || extras.showReached) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = pillCornerAlignment(oppositeCorner(extras.pillCorner)),
            ) {
                // LARGE keeps leave-by and best inside the info card, so Reached stacks with the
                // pills in the opposite corner rather than adding a third overlay.
                Column {
                    val reachedDirection = extras.rideDirection?.takeIf { extras.showReached }
                    if (reachedDirection != null) {
                        ReachedPill(reachedTapAction(reachedDirection), extras.textScale)
                        if (extras.healthPills.isNotEmpty() || hasCustomPillRow) {
                            Spacer(modifier = GlanceModifier.height(4.dp))
                        }
                    }
                    if (extras.healthPills.isNotEmpty()) {
                        HealthPillStack(
                            pills = extras.healthPills,
                            extras = extras,
                            expandMorningLabel = true,
                        )
                    }
                    if (hasCustomPillRow) {
                        if (extras.healthPills.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.height(4.dp))
                        }
                        CustomPillMapRow(content = extras.customPillRow, extras = extras)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEmptyCard(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
) {
    val size = LocalSize.current
    val showHealth = showsHealthChrome(size.width.value.toInt())
    val showCustomPillRow = showsCustomPillRow(size.width.value.toInt())
    val expandMorningLabel =
        size.width >= LARGE_BREAKPOINT.width && size.height >= LARGE_BREAKPOINT.height
    val chips = if (showHealth) extras.healthPills else emptyList()
    val lineLabel = extras.healthLineLabel.takeIf { showHealth }
    val customPillContent = if (showCustomPillRow) extras.customPillRow else CustomPillRowContent(emptyList(), null)
    val hasHealthFooter = chips.isNotEmpty() || lineLabel != null || !customPillContent.isEmpty
    Box(
        modifier = modifier.clickable(actionRunCallback<RefreshAction>()).padding(12.dp),
        contentAlignment = if (hasHealthFooter) Alignment.TopStart else Alignment.CenterStart,
    ) {
        Column(modifier = if (hasHealthFooter) GlanceModifier.fillMaxSize() else GlanceModifier) {
            if (hasHealthFooter) {
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    CalendarEmptyCardBody(snapshot, extras, showHealth)
                }
                Spacer(modifier = GlanceModifier.height(8.dp))
                if (lineLabel != null) {
                    Text(
                        text = lineLabel,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = scaledSp(10, extras.textScale),
                        ),
                        maxLines = 1,
                    )
                }
                if (chips.isNotEmpty()) {
                    HealthChipRow(
                        pills = chips,
                        extras = extras,
                        expandMorningLabel = expandMorningLabel,
                    )
                }
                if (!customPillContent.isEmpty) {
                    if (chips.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                    CustomPillChipRow(content = customPillContent, extras = extras)
                }
            } else {
                CalendarEmptyCardBody(snapshot, extras, showHealth)
            }
        }
    }
}

@Composable
private fun CalendarEmptyCardBody(snapshot: CommuteSnapshot, extras: WidgetExtras, showHealth: Boolean) {
    val textScale = extras.textScale
    val case = calendarEmptyCase(snapshot)
    // showHealth mirrors the SMALL width gate (finding 5): below 220dp the card renders exactly
    // as before this feature, with no commute body and no pill row.
    val commuteBody = showHealth && showsCommuteWindowBody(extras.rideDirection != null, case)
    val flightPreview = snapshot.flightPreview
    val merged = headlineIsPreviewedFlight(snapshot)
    val upcomingEvents = upcomingEventsWithoutPreview(snapshot.upcomingEvents, flightPreview)
    val windDown = !commuteBody && isWindDown(snapshot)
    // Owner request 2026-08-31: the sleep estimate shows every day, so calendar-mode cards carry
    // the same brief segment commute mornings get ("Slept ~6h 40m" / "Short sleep").
    val sleepCaption = sleepBriefSegment(
        sleepEstimateMinutes = snapshot.sleepEstimateMinutes,
        shortSleepDay = snapshot.shortSleepDay,
        sleepBriefEnabled = extras.sleepBriefEnabled,
    )
    if (sleepCaption != null) {
        Text(
            text = sleepCaption,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(11, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
    if (commuteBody) {
        // In-window commute body (owner decision 1): window label plus the morning brief when
        // today still has events. No "Next up" line, no wind-down block, no alarm line here.
        Text(
            text = commuteWindowLabel(extras.rideDirection!!),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = scaledSp(18, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        val todayCount = snapshot.todayEventCount
        if (todayCount != null && todayCount > 0) {
            Text(
                text = formatTodayBrief(todayCount, snapshot.todayFirstEventStartEpochMillis),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = scaledSp(11, textScale),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    } else {
        if (flightPreview != null) {
            if (merged) {
                FlightPreviewBlockView(
                    block = flightPreviewBlock(
                        preview = flightPreview,
                        eventStartEpochMillis = snapshot.eventStartEpochMillis,
                        nowEpochMillis = extras.nowEpochMillis,
                        hasFlightStatusKey = extras.hasFlightStatusKey,
                        departureZones = countryZoneCandidates(flightPreview.status?.departureCountry),
                    ),
                    extras = extras,
                    showDetail = showHealth,
                )
            } else {
                FlightPreviewRow(flightPreview, extras, showDetail = showHealth)
            }
        }
        if (windDown) {
            Text(
                text = "Next up",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = scaledSp(11, textScale),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            upcomingEvents.take(UPCOMING_EVENT_LIMIT).forEachIndexed { index, event ->
                if (index > 0) {
                    Spacer(modifier = GlanceModifier.height(4.dp))
                }
                // Title wraps to two lines and the day-and-time line gets its own line, so a
                // long meeting title can never ellipsize the time away.
                Text(
                    text = event.title,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = scaledSp(16, textScale),
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 2,
                )
                Text(
                    text = formatUpcomingEventLine(event.startEpochMillis, extras.nowEpochMillis),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = scaledSp(14, textScale),
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
            if (extras.nextAlarmLine != null) {
                AlarmLine(extras.nextAlarmLine, textScale)
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
        }
        when (case) {
            CalendarEmptyCase.UNLOCATED_EVENT -> if (!merged) {
                Text(
                    text = calendarEventTitle(snapshot.destinationLabel),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = scaledSp(14, textScale),
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = formatEventClockTime(snapshot.eventStartEpochMillis!!),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = scaledSp(28, textScale),
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                val freeMinutes = eventCountdownMinutes(
                    snapshot.eventStartEpochMillis,
                    extras.nowEpochMillis,
                )
                if (freeMinutes != null) {
                    Text(
                        text = formatFreeFor(freeMinutes),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = scaledSp(11, textScale),
                        ),
                        maxLines = 1,
                    )
                }
            }
            CalendarEmptyCase.NONE -> {
                if (showsCalendarNoneText(windDown, case)) {
                    Text(
                        text = calendarNoneText(extras.rideDirection),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = scaledSp(14, textScale),
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 3,
                    )
                }
                if (!windDown && extras.nextAlarmLine != null) {
                    AlarmLine(extras.nextAlarmLine, textScale)
                }
            }
        }
    }
    // Sub-220dp gates the pill row off (showHealth), but a window can still be open, so the old
    // plain best-departure line keeps rendering here exactly as it did before the pill row shipped.
    if (!showHealth && extras.commutePillRow.bestLine != null) {
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = extras.commutePillRow.bestLine,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(11, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
    if (showHealth && !extras.commutePillRow.isEmpty) {
        Spacer(modifier = GlanceModifier.height(4.dp))
        CommutePillRow(extras)
    }
}

/**
 * The calendar card's flight row: the date and route on line 1, the departure detail on line 2 and
 * a caption on line 3 when there is something to say about the data itself. SMALL (below the
 * [showsHealthChrome] width gate) keeps line 1 only - the detail line does not fit at 2x2 without
 * ellipsizing away the half that carries the gate.
 *
 * The whole row is one tap target ([FlightPreviewTapAction]): a manual status refresh, or Undo for
 * a Done tap when the flight's airport window is still open. It renders only when there is a
 * preview, so there is never a clickable row with nothing behind it.
 */
@Composable
private fun FlightPreviewRow(preview: FlightPreview, extras: WidgetExtras, showDetail: Boolean) {
    val textScale = extras.textScale
    Column(modifier = GlanceModifier.clickable(actionRunCallback<FlightPreviewTapAction>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = flightPreviewTitleLine(preview),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = scaledSp(14, textScale),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 2,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (showDetail) {
                val chip = flightStatusChip(preview.status?.status, preview.status?.departureDelayMinutes, preview.status?.source)
                Spacer(modifier = GlanceModifier.width(6.dp))
                FlightStatusChipText(chip, textScale)
            }
        }
        if (showDetail) {
            Text(
                text = flightPreviewDetailLine(
                    preview = preview,
                    departureZones = countryZoneCandidates(preview.status?.departureCountry),
                ),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = scaledSp(12, textScale),
                ),
                maxLines = 2,
            )
            val note = flightPreviewNoteLine(preview, extras.hasFlightStatusKey)
            if (note != null) {
                Text(
                    text = note,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = scaledSp(10, textScale),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(modifier = GlanceModifier.height(4.dp))
}

/** "scheduled" becomes "Scheduled"; a word the API already capitalised is left alone. */
internal fun capitaliseStatusWord(status: String): String =
    status.replaceFirstChar { it.titlecase(Locale.US) }

/**
 * The airline as one dense line, "Singapore Airlines · SQ / SIA", with the operating carrier and
 * the codeshare appended only when the leg actually is one. The two codes share a cell because
 * nobody reads IATA without ICAO next to it, and a label for either would say less than the codes do.
 */
internal fun flightCardAirlineLine(status: FlightStatus?): String? {
    val name = status?.airlineName ?: status?.operatingAirline
    val codes = listOfNotNull(status?.airlineIata, status?.airlineIcao).joinToString(" / ").ifEmpty { null }
    return listOfNotNull(
        name,
        codes,
        status?.operatingAirline?.takeIf { status.airlineName != null && it != status.airlineName }
            ?.let { "operated by $it" },
        status?.codeshareOf?.let { "codeshare of $it" },
    ).joinToString(" · ").ifEmpty { null }
}

/** "PNR 123ABC · Seat 14A" line, either half omitted when its value is null, or null when both are. */
internal fun flightCardPnrSeatLine(airport: AirportSnapshot): String? {
    val pnr = airport.flight.confirmationNumber?.let { "PNR $it" }
    val seat = airport.flight.seat?.let { "Seat $it" }
    return listOfNotNull(pnr, seat).joinToString(" · ").ifEmpty { null }
}

/**
 * How many characters the aircraft row's 10 sp half-width cell holds at text scale 115. The row is
 * two `defaultWeight` cells across 314 dp, so each is 157 dp, and 10 sp measures 3.87 dp a
 * character at scale 100 and 4.45 at 115.
 */
internal const val FLIGHT_COMPACT_CELL_MAX_CHARS = 35

/**
 * The aircraft row's left cell on the wide and medium cards. The record locator wins the cell
 * outright, because it is the one string on the card the owner cannot derive from anything else on
 * it - the designator already names the carrier by its IATA prefix - and neither of those two sizes
 * has the height for a line of its own. The airline name keeps its place in front of the booking
 * whenever both still fit the cell, which is every booking without a seat number.
 */
internal fun flightCardCompactAirlineCell(airline: String?, booking: String?): String? {
    if (booking == null) return airline
    val joined = if (airline == null) booking else "$airline · $booking"
    return if (joined.length <= FLIGHT_COMPACT_CELL_MAX_CHARS) joined else booking
}

/**
 * "Fleet: Airbus, MSN 61234, 2 x jet, built 2021 (4 y)": everything known about the airframe itself
 * rather than about today's leg, from the cached fleet [record] first and the live status second.
 * It is the only place on any card the build year, the engines, the manufacturer and the serial
 * appear, which is what keeps it and [flightCardAircraftLine] from saying the same thing twice.
 *
 * Null when neither source carries a single one of the four. Tall card only: it is 50 characters
 * of provenance, and nothing on it changes between now and the gate.
 */
internal fun flightCardFleetLine(status: FlightStatus?, record: AircraftRecord?): String? {
    val builtYear = record?.builtYear ?: status?.aircraftBuiltYear
    val ageYears = record?.ageYears ?: status?.aircraftAgeYears
    val details = listOfNotNull(
        record?.manufacturer ?: status?.aircraftManufacturer,
        (record?.serialNumber ?: status?.aircraftSerialNumber)?.let { "MSN $it" },
        // AirLabs sends the engine as a category ("jet", "turboprop") and the count as a number,
        // and on the owner's plan the fleets endpoint sends neither, so the live flight record is
        // the only source. Whatever the type string holds is printed verbatim - a plan that one
        // day returns "GE9X" reads "2 x GE9X" with no further work.
        engineDetail(
            count = record?.engineCount ?: status?.aircraftEngineCount,
            type = record?.engineType ?: status?.aircraftEngineType,
        ),
        when {
            builtYear != null && ageYears != null -> "built $builtYear ($ageYears y)"
            builtYear != null -> "built $builtYear"
            ageYears != null -> "$ageYears y old"
            else -> null
        },
    )
    return if (details.isEmpty()) null else "Fleet: ${details.joinToString(", ")}"
}

/** "2 x jet", "jet" with no count, "2 engines" with no type, null with neither. */
private fun engineDetail(count: Int?, type: String?): String? = when {
    count != null && type != null -> "$count x $type"
    type != null -> type
    count != null -> if (count == 1) "1 engine" else "$count engines"
    else -> null
}

/**
 * "SIN is 2 h 30 min ahead of BLR", the one fact a card carrying two clocks in two zones otherwise
 * leaves the reader to work out. It sits in the ground block's heading row, between the two codes
 * it relates, so it costs the card no height at all. Null whenever either offset or either code is
 * unknown - a shift the card cannot name both ends of is not worth a sentence.
 */
internal fun flightCardZoneShiftLine(status: FlightStatus?): String? {
    val departureOffset = status?.departureUtcOffsetMinutes ?: return null
    val arrivalOffset = status.arrivalUtcOffsetMinutes ?: return null
    val origin = status.departureIata ?: return null
    val destination = status.arrivalIata ?: return null
    val shift = arrivalOffset - departureOffset
    return when {
        shift > 0 -> "$destination is ${formatFlightDuration(shift)} ahead of $origin"
        shift < 0 -> "$destination is ${formatFlightDuration(-shift)} behind $origin"
        else -> "$destination and $origin share a time zone"
    }
}

/** "+NN min" for a late estimate, "-NN min" for an early one. */
internal fun formatDelayMinutes(minutes: Int): String = if (minutes >= 0) "+$minutes min" else "$minutes min"

/** "T1 · Gate 7", either half omitted when its value is null, or null when both are. */
internal fun formatTerminalGate(terminal: String?, gate: String?): String? {
    val terminalText = terminal?.let { "T$it" }
    val gateText = gate?.let { "Gate $it" }
    return listOfNotNull(terminalText, gateText).joinToString(" · ").ifEmpty { null }
}

/** "1 h 35 min", or "45 min" under the hour, or a bare "2 h" on the hour. */
internal fun formatFlightDuration(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "$remainder min"
        remainder == 0 -> "$hours h"
        else -> "$hours h $remainder min"
    }
}

/**
 * The WIDE card's aircraft row as its two cells: the airline on the left (the operating carrier
 * stands in when the marketing name is missing) and the aircraft on the right, "Airbus A350-900 ·
 * 9V-SMA". Either cell is null when its half is unknown, and both are when the status is.
 */
internal fun flightCardAircraftRow(status: FlightStatus?): Pair<String?, String?> {
    val airline = status?.airlineName ?: status?.operatingAirline
    val aircraft = listOfNotNull(status?.aircraftDisplayName, status?.aircraftRegistration)
        .joinToString(" · ")
        .ifEmpty { null }
    return airline to aircraft
}

/** The two IATA codes the whole card is anchored on, the status first and the calendar second. */
private fun flightIataPair(airport: AirportSnapshot): Pair<String, String> {
    val flight = airport.flight
    val status = airport.status
    return (status?.departureIata ?: flight.departureIata ?: flight.departureAirportName ?: "?") to
        (status?.arrivalIata ?: flight.arrivalIata ?: flight.arrivalAirportName ?: "?")
}

/**
 * The LARGE card's ground block: two columns headed by the IATA codes, one row per fact aligned
 * across both. Every cell always has text - a gate that lands at T-2h must not push three rows down
 * the card when it does, so the slot reads "TBA" (or "on the day" from a timetable-only source)
 * until it is published.
 */
internal data class FlightGroundBlock(
    val originCode: String,
    val destinationCode: String,
    val departureTerminal: String,
    val arrivalTerminal: String,
    val departureGate: String,
    val arrivalGate: String,
    val checkIn: String,
    val belt: String,
)

internal fun flightCardGroundBlock(airport: AirportSnapshot): FlightGroundBlock {
    val status = airport.status
    val flight = airport.flight
    val (originCode, destinationCode) = flightIataPair(airport)
    val unknownGate = unknownGateLabel(status?.source)
    return FlightGroundBlock(
        originCode = originCode,
        destinationCode = destinationCode,
        departureTerminal = terminalSlotLabel(status?.departureTerminal ?: flight.departureTerminal),
        arrivalTerminal = terminalSlotLabel(status?.arrivalTerminal ?: flight.arrivalTerminal),
        departureGate = status?.departureGate?.let { "Gate $it" } ?: unknownGate,
        arrivalGate = status?.arrivalGate?.let { "Gate $it" } ?: unknownGate,
        checkIn = status?.departureCheckInDesk?.let { "Check-in $it" } ?: "Check-in TBA",
        belt = status?.arrivalBaggageBelt?.let { "Belt $it" } ?: "Belt TBA",
    )
}

private fun terminalSlotLabel(terminal: String?): String = terminal?.let { "Terminal $it" } ?: "Terminal TBA"

/**
 * The WIDE card's ground one-liner, "T2 · Gate D2 D3 -> T1 · Gate D41 · Belt TBA": the same facts as
 * the LARGE block minus the check-in desk, which is the one of the seven that only matters before
 * bag drop. Never null - the TBA placeholders keep the line's shape for the same reason the block's.
 */
internal fun flightCardGroundLine(airport: AirportSnapshot): String {
    val block = flightCardGroundBlock(airport)
    val departure = listOfNotNull(block.departureTerminal.shortTerminal(), block.departureGate).joinToString(" · ")
    val arrival = listOfNotNull(block.arrivalTerminal.shortTerminal(), block.arrivalGate, block.belt)
        .joinToString(" · ")
    return "$departure -> $arrival"
}

/** "Terminal 2" becomes "T2" for the one-liner, and an unpublished terminal drops out of it entirely. */
private fun String.shortTerminal(): String? = removePrefix("Terminal ").takeIf { it != "TBA" }?.let { "T$it" }

/**
 * The airframe as one dense line, "Airbus A350-900 · 9V-SMA", from the status first and the cached
 * [record] second. Which tail number is flying the leg, and what it is.
 *
 * The build year and the engines used to ride this line and now belong to [flightCardFleetLine],
 * which sits directly under it on the only card that renders either. Two adjacent lines both
 * saying "built 2019" would be a defect, not emphasis, and the fleet line draws from both sources
 * so nothing is lost by the move.
 */
internal fun flightCardAircraftLine(status: FlightStatus?, record: AircraftRecord?): String? {
    val type = status?.aircraftDisplayName
        ?: record?.model
        ?: aircraftTypeName(record?.typeIcao)
        ?: record?.typeIcao
        ?: status?.aircraftManufacturer
        ?: record?.manufacturer
    return listOfNotNull(
        type,
        status?.aircraftRegistration ?: record?.reg,
    ).joinToString(" · ").ifEmpty { null }
}

/**
 * Both airport names in full with their country code appended, departure over arrival, the
 * calendar's own names standing in wherever AirLabs sent none. The cities are deliberately gone:
 * the grid's IATA pair locates both ends already, so "Bengaluru" under "Kempegowda International
 * Airport" was the same fact twice, while the country is the one thing neither the name nor the
 * IATA code says out loud.
 */
internal fun flightCardAirportLines(status: FlightStatus?, flight: FlightEvent): List<String> = listOfNotNull(
    airportNameLine(status?.departureAirportName ?: flight.departureAirportName, status?.departureCountry),
    airportNameLine(status?.arrivalAirportName ?: flight.arrivalAirportName, status?.arrivalCountry),
)

private fun airportNameLine(name: String?, country: String?): String? =
    if (name == null) null else listOfNotNull(name, country).joinToString(", ")

/**
 * How many characters an 11 sp fact line holds before it ellipsises. Measured off the owner's card
 * with `uiautomator dump`: "902 km/h · 12,593 m · 3,179 km · -5 km/h · 137 deg" is 49 characters
 * and 208.80 dp, so 4.26 dp a character against 314 dp of content width. The dense lines are all
 * kept under it by construction; the facts paragraph also renders at two lines, so a value longer
 * than anything the card has yet seen wraps rather than losing its tail.
 */
internal const val FLIGHT_FACT_LINE_MAX_CHARS = 70

/**
 * How the rest of the departure board is running, "BLR board: 1 delayed 30+ min, avg 32 min". Null
 * when nothing is late.
 *
 * This flight's own "Departure +14 min · Arrival -13 min" line used to sit above it and is gone:
 * the medium and tall cards are the only two that ever rendered it, and both now print the
 * scheduled row against the live clock row with a zone name on each - "Sched 11:35 am IST" under
 * "Left 11:49 am IST" is the same two deltas, in the currency the traveller reads. The row that
 * line was taking is now the phone-time row, so the trade costs neither card a dp. The wide card
 * never carried it: its delays ride the clock cells.
 */
internal fun flightCardBoardDelayLine(stats: AirportDelayStats?): String? =
    stats?.takeIf { it.delayedCount >= 1 }?.let { "${it.iata} board: ${delayStatsValue(it)}" }

// 30 is the floor the delays query itself asks for, so the line has to name it or the count reads
// as every departure rather than every badly delayed one.
private fun delayStatsValue(stats: AirportDelayStats): String = listOfNotNull(
    "${stats.delayedCount} delayed 30+ min",
    stats.averageDelayMinutes.takeIf { it > 0 }?.let { "avg ${formatFlightDuration(it)}" },
).joinToString(", ")

/**
 * Footer caption: the last successful fetch's clock time wins whenever one exists (even if a later
 * retry failed and left [AirportSnapshot.statusError] set alongside the still-cached status),
 * followed by the airline's own last-updated stamp - so a stale gate can be told apart from a stale
 * fetch. A fetch that has only ever failed shows its error instead, and a card that has never been
 * fetched invites the tap.
 *
 * The endpoint the status came from is deliberately absent at every size: "via schedules+flight" is
 * a developer fact, and the two stamps answer the only two questions a traveller has of a caption.
 */
internal fun flightCardFooter(
    airport: AirportSnapshot,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val fetchedAt = airport.statusFetchedAtMillis
    val error = airport.statusError
    if (fetchedAt == null) {
        return if (error != null && airport.status == null) "Status unavailable - $error" else "Tap for live status"
    }
    return listOfNotNull(
        "Updated ${formatEventClockTime(fetchedAt, zone)}",
        airport.status?.lastUpdatedUtcMillis?.let { "airline data ${formatEventClockTime(it, zone)}" },
    ).joinToString(" · ")
}

/**
 * Card pill height. A deliberate second deviation from the 48 dp touch-target guideline, after v1's
 * 40 dp: the pill is a dismissal, not the card's purpose, and 10 dp of pill is most of an
 * information row on a 4x2. Same chrome as the map pills otherwise.
 */
private const val CARD_PILL_HEIGHT_DP = 30

/**
 * The airborne cards' centre column. Wider than the ground cards' 88 dp because it holds the
 * progress bar; every airborne row that has a centre uses it, so the three cells stay in one
 * vertical line from the IATA codes down to the scheduled row.
 */
private val AIRBORNE_CENTRE_WIDTH = 120.dp

/**
 * The ground block heading row's centre, sized for the longest zone-shift sentence at text scale
 * 115: "AKL is 13 h 45 min ahead of LAX" is 31 characters and 10 sp measures 4.45 dp a character
 * there, which is 138 dp. The 77 dp that is left to each outer cell is four times what a
 * three-letter code needs.
 */
private val ZONE_SHIFT_CELL_WIDTH = 160.dp

/** Done pill: 30 dp tall, in the card's own footer row rather than overlaid on it. */
@Composable
private fun AirportDismissPill(textScale: Float) {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .height(CARD_PILL_HEIGHT_DP.dp)
            .padding(horizontal = 12.dp)
            .clickable(actionRunCallback<AirportDismissAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Done",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}
/**
 * The merged calendar block: the card's headline event and its flight row are the same flight, so
 * they render once. Still one tap target ([FlightPreviewTapAction]), exactly as the row it replaces.
 */
@Composable
private fun FlightPreviewBlockView(block: FlightPreviewBlock, extras: WidgetExtras, showDetail: Boolean) {
    val textScale = extras.textScale
    val size = LocalSize.current
    val large = size.width >= LARGE_BREAKPOINT.width && size.height >= LARGE_BREAKPOINT.height
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(modifier = GlanceModifier.clickable(actionRunCallback<FlightPreviewTapAction>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = block.heading,
                style = TextStyle(color = muted, fontSize = scaledSp(11, textScale), fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            block.countdown?.let {
                Text(
                    text = it,
                    style = TextStyle(color = muted, fontSize = scaledSp(11, textScale)),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = block.identity,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = scaledSp(14, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Text(
            text = block.time,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = scaledSp(28, textScale),
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        if (showDetail) {
            block.detail?.let {
                Text(
                    text = it,
                    style = TextStyle(color = muted, fontSize = scaledSp(11, textScale)),
                    maxLines = 1,
                )
            }
        }
        if (large) {
            block.caption?.let {
                Text(
                    text = it,
                    style = TextStyle(color = muted, fontSize = scaledSp(10, textScale)),
                    maxLines = 1,
                )
            }
        }
        block.calendarLine?.let {
            Text(
                text = it,
                style = TextStyle(color = muted, fontSize = scaledSp(10, textScale)),
                maxLines = 1,
            )
        }
    }
    Spacer(modifier = GlanceModifier.height(4.dp))
}

/** The status chip's word plus which theme role carries it; the colour itself is resolved at render time. */
internal data class FlightCardChip(val label: String, val tone: FlightChipTone)

/**
 * Chip colour roles. Airport boards use green/amber/red, but Material 3 dynamic colour has no
 * semantic green and on a widget colour should mark the exception rather than the norm - so the
 * quiet state stays [QUIET] and only the two states worth a second look take a real colour.
 */
internal enum class FlightChipTone { QUIET, WARN, LIVE, ERROR }

/**
 * The one status chip in the card's top-right slot. A timetable-only source is named before any
 * status word is read: it carries no gate and no delay, so "On time" from it would be a claim the
 * card cannot make.
 */
internal fun flightStatusChip(status: String?, delayMinutes: Int?, source: String?): FlightCardChip {
    if (status == null) return FlightCardChip("Tap", FlightChipTone.QUIET)
    if (source == "routes") return FlightCardChip("Timetable", FlightChipTone.QUIET)
    return when (status.lowercase(Locale.US)) {
        "scheduled" ->
            if (delayMinutes != null && delayMinutes >= 1) {
                FlightCardChip("Delayed", FlightChipTone.WARN)
            } else {
                FlightCardChip("On time", FlightChipTone.QUIET)
            }
        "active", "en-route" -> FlightCardChip("In the air", FlightChipTone.LIVE)
        "landed" -> FlightCardChip("Landed", FlightChipTone.LIVE)
        "delayed" -> FlightCardChip("Delayed", FlightChipTone.WARN)
        "cancelled" -> FlightCardChip("Cancelled", FlightChipTone.ERROR)
        "diverted", "redirected", "incident" -> FlightCardChip("Diverted", FlightChipTone.ERROR)
        else -> FlightCardChip("Tap", FlightChipTone.QUIET)
    }
}

/** True while the aircraft is up: the airborne card replaces the departure detail with arrival detail. */
internal fun flightCardIsAirborne(status: FlightStatus?): Boolean =
    status?.status?.lowercase(Locale.US) in AIRBORNE_FLIGHT_STATUSES

private val AIRBORNE_FLIGHT_STATUSES = setOf("active", "en-route")

/**
 * What fills the gate slot before a gate lands. The slot never collapses - a gate arrives about
 * T-2h and a card that reflowed when it did would move every row under it.
 */
internal fun unknownGateLabel(source: String?): String =
    if (source == "routes") "Gate on the day" else "Gate TBA"

/** "T2 · Gate D2", falling back to [unknownGateLabel] rather than dropping the gate half. */
internal fun formatTerminalGateOrTba(terminal: String?, gate: String?, source: String?): String =
    listOfNotNull(terminal?.let { "T$it" }, gate?.let { "Gate $it" } ?: unknownGateLabel(source))
        .joinToString(" · ")

/**
 * A flight clock the way a boarding pass prints it: [utcMillis] in the airport's own local time
 * when [offsetMinutes] says what that is, and in [fallbackZone] - the phone's - when it does not.
 * Departure clocks take the departure airport's offset and arrival clocks the arrival airport's, so
 * one card can and routinely does carry two different zones.
 *
 * [zoneLabel] names which of the two this clock is in, "6:56 pm SGT". The owner read an arrival in
 * Singapore time as their own, which is what the label is for; it is appended rather than built in
 * because the caller is the only thing that knows whether its size has room for it.
 */
internal fun formatAirportClockTime(
    utcMillis: Long,
    offsetMinutes: Int?,
    fallbackZone: ZoneId,
    zoneLabel: String? = null,
): String = formatEventClockTime(utcMillis, airportZone(offsetMinutes, fallbackZone)) +
    zoneLabel?.let { " $it" }.orEmpty()

private fun airportZone(offsetMinutes: Int?, fallbackZone: ZoneId): ZoneId =
    offsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: fallbackZone

private fun localDateAt(utcMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate()

/** " +1" / " +2" when the arrival falls on a later local day than the departure, "" otherwise. */
private fun arrivalDaySuffix(departureDate: LocalDate, arrivalDate: LocalDate): String {
    val days = ChronoUnit.DAYS.between(departureDate, arrivalDate)
    return if (days > 0L) " +$days" else ""
}

/**
 * The clock time the calendar description itself printed ("11:35"), in the card's own 12-hour
 * style. It is already airport-local - Gmail writes the itinerary's local times - so it is what a
 * card shows when no status has ever been fetched and no offset is therefore known. Null when the
 * text is not a clock time after all.
 */
internal fun formatPrintedClockTime(printedHm: String): String? {
    val parts = printedHm.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return formatClockTime(hour * 60 + minute)
}

/** "+8", "+5:30", "-4": the numeric zone hint, used where a clock carries no IATA code to locate it. */
internal fun utcOffsetLabel(offsetMinutes: Int): String {
    val sign = if (offsetMinutes < 0) "-" else "+"
    val hours = abs(offsetMinutes) / 60
    val minutes = abs(offsetMinutes) % 60
    return if (minutes == 0) "$sign$hours" else "$sign$hours:${minutes.toString().padStart(2, '0')}"
}

/**
 * The zone's own short name at [instantMillis] - "SGT", "IST", "AEST" - read off the first of
 * [candidates] whose rules put it on exactly [offsetMinutes] there, and the numeric "UTC+8" /
 * "UTC+5:30" / "UTC-4" when none of them does.
 *
 * AirLabs sends an offset, never a zone, and an offset alone cannot be named: +8 is SGT, AWST,
 * CST and more. The country code it sends alongside narrows it to a handful of zones, which is
 * what [candidates] is; the offset then picks the one that is actually in force on the day, so a
 * DST-observing zone is named as it stands rather than as it stands in January.
 *
 * Candidates are a parameter rather than a lookup because the only source of them on the device is
 * ICU ([countryZoneCandidates]), and android.icu does not exist on the JVM the unit tests run on.
 * An empty list is the ordinary case for an airport whose country AirLabs did not name and lands
 * on the numeric form, which still locates the clock.
 */
internal fun zoneLabel(offsetMinutes: Int, candidates: List<ZoneId>, instantMillis: Long): String {
    val instant = Instant.ofEpochMilli(instantMillis)
    val match = candidates.firstOrNull { it.rules.getOffset(instant).totalSeconds / 60 == offsetMinutes }
        ?: return utcOffsetLabel(offsetMinutes)
    val pair = ZONE_ABBREVIATIONS[match.id] ?: return utcOffsetLabel(offsetMinutes)
    return if (match.rules.isDaylightSavings(instant)) pair.second else pair.first
}

/**
 * Standard and daylight abbreviation for every zone the card is likely to name, keyed by zone id.
 *
 * It is a bundled table rather than a formatter call because there is no formatter that answers.
 * `DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH)` gives the right answer on the JVM and the
 * wrong one on the device: Android's CLDR data carries no English short name for most zones
 * (Asia/Singapore has none at all, Asia/Kolkata is IST only in en_IN), so every label rendered as
 * "GMT+05:30" and truncated the clock cells it sits in. These are the names the airlines, the
 * airports and the traveller actually use.
 *
 * Retired ids are listed beside their replacements because ICU's canonical id for a country is
 * often the old one - India answers Asia/Calcutta, Vietnam Asia/Saigon - and the lookup is by id.
 *
 * Daylight is picked by [java.time.zone.ZoneRules.isDaylightSavings] at the instant, so Sydney is
 * AEDT in January and AEST in July, and Dublin is GMT in winter and IST in summer.
 */
private val ZONE_ABBREVIATIONS: Map<String, Pair<String, String>> = mapOf(
    "Asia/Kolkata" to ("IST" to "IST"),
    "Asia/Calcutta" to ("IST" to "IST"),
    "Asia/Singapore" to ("SGT" to "SGT"),
    "Asia/Dubai" to ("GST" to "GST"),
    "Asia/Tokyo" to ("JST" to "JST"),
    "Asia/Hong_Kong" to ("HKT" to "HKT"),
    "Asia/Shanghai" to ("CST" to "CST"),
    "Asia/Chongqing" to ("CST" to "CST"),
    "Asia/Bangkok" to ("ICT" to "ICT"),
    "Asia/Jakarta" to ("WIB" to "WIB"),
    "Asia/Makassar" to ("WITA" to "WITA"),
    "Asia/Kuala_Lumpur" to ("MYT" to "MYT"),
    "Asia/Manila" to ("PHT" to "PHT"),
    "Asia/Seoul" to ("KST" to "KST"),
    "Asia/Karachi" to ("PKT" to "PKT"),
    "Asia/Dhaka" to ("BST" to "BST"),
    "Asia/Kathmandu" to ("NPT" to "NPT"),
    "Asia/Katmandu" to ("NPT" to "NPT"),
    "Asia/Colombo" to ("IST" to "IST"),
    "Asia/Riyadh" to ("AST" to "AST"),
    "Asia/Qatar" to ("AST" to "AST"),
    "Asia/Kuwait" to ("AST" to "AST"),
    "Asia/Bahrain" to ("AST" to "AST"),
    "Asia/Baghdad" to ("AST" to "AST"),
    "Asia/Tehran" to ("IRST" to "IRDT"),
    "Asia/Tashkent" to ("UZT" to "UZT"),
    "Asia/Taipei" to ("CST" to "CST"),
    "Asia/Ho_Chi_Minh" to ("ICT" to "ICT"),
    "Asia/Saigon" to ("ICT" to "ICT"),
    "Asia/Yangon" to ("MMT" to "MMT"),
    "Asia/Rangoon" to ("MMT" to "MMT"),
    "Asia/Kabul" to ("AFT" to "AFT"),
    "Asia/Almaty" to ("ALMT" to "ALMT"),
    "Asia/Baku" to ("AZT" to "AZT"),
    "Asia/Tbilisi" to ("GET" to "GET"),
    "Asia/Yerevan" to ("AMT" to "AMT"),
    "Asia/Jerusalem" to ("IST" to "IDT"),
    "Asia/Tel_Aviv" to ("IST" to "IDT"),
    "Asia/Amman" to ("EET" to "EEST"),
    "Asia/Beirut" to ("EET" to "EEST"),
    "Australia/Sydney" to ("AEST" to "AEDT"),
    "Australia/Canberra" to ("AEST" to "AEDT"),
    "Australia/Melbourne" to ("AEST" to "AEDT"),
    "Australia/Hobart" to ("AEST" to "AEDT"),
    "Australia/Brisbane" to ("AEST" to "AEST"),
    "Australia/Perth" to ("AWST" to "AWST"),
    "Australia/Adelaide" to ("ACST" to "ACDT"),
    "Australia/Darwin" to ("ACST" to "ACST"),
    "Pacific/Auckland" to ("NZST" to "NZDT"),
    "Europe/London" to ("GMT" to "BST"),
    "Europe/Paris" to ("CET" to "CEST"),
    "Europe/Berlin" to ("CET" to "CEST"),
    "Europe/Amsterdam" to ("CET" to "CEST"),
    "Europe/Brussels" to ("CET" to "CEST"),
    "Europe/Vienna" to ("CET" to "CEST"),
    "Europe/Madrid" to ("CET" to "CEST"),
    "Europe/Rome" to ("CET" to "CEST"),
    "Europe/Zurich" to ("CET" to "CEST"),
    "Europe/Copenhagen" to ("CET" to "CEST"),
    "Europe/Stockholm" to ("CET" to "CEST"),
    "Europe/Oslo" to ("CET" to "CEST"),
    "Europe/Warsaw" to ("CET" to "CEST"),
    "Europe/Prague" to ("CET" to "CEST"),
    "Europe/Istanbul" to ("TRT" to "TRT"),
    "Europe/Moscow" to ("MSK" to "MSK"),
    "Europe/Athens" to ("EET" to "EEST"),
    "Europe/Helsinki" to ("EET" to "EEST"),
    "Europe/Bucharest" to ("EET" to "EEST"),
    "Europe/Kyiv" to ("EET" to "EEST"),
    "Europe/Kiev" to ("EET" to "EEST"),
    "Europe/Lisbon" to ("WET" to "WEST"),
    "Europe/Dublin" to ("GMT" to "IST"),
    "Atlantic/Reykjavik" to ("GMT" to "GMT"),
    "Africa/Johannesburg" to ("SAST" to "SAST"),
    "Africa/Cairo" to ("EET" to "EEST"),
    "Africa/Nairobi" to ("EAT" to "EAT"),
    "Africa/Addis_Ababa" to ("EAT" to "EAT"),
    "Africa/Lagos" to ("WAT" to "WAT"),
    "Africa/Accra" to ("GMT" to "GMT"),
    "America/New_York" to ("EST" to "EDT"),
    "America/Chicago" to ("CST" to "CDT"),
    "America/Denver" to ("MST" to "MDT"),
    "America/Los_Angeles" to ("PST" to "PDT"),
    "America/Phoenix" to ("MST" to "MST"),
    "America/Toronto" to ("EST" to "EDT"),
    "America/Montreal" to ("EST" to "EDT"),
    "America/Vancouver" to ("PST" to "PDT"),
    "America/Mexico_City" to ("CST" to "CST"),
    "America/Sao_Paulo" to ("BRT" to "BRST"),
    "America/Argentina/Buenos_Aires" to ("ART" to "ART"),
    "America/Buenos_Aires" to ("ART" to "ART"),
    "America/Bogota" to ("COT" to "COT"),
    "America/Lima" to ("PET" to "PET"),
    "America/Santiago" to ("CLT" to "CLST"),
    "America/Anchorage" to ("AKST" to "AKDT"),
    "Pacific/Honolulu" to ("HST" to "HST"),
    "Indian/Maldives" to ("MVT" to "MVT"),
    "Indian/Mauritius" to ("MUT" to "MUT"),
    // A device parked on UTC is a real setting, and "+0" would be a poor name for it.
    "UTC" to ("UTC" to "UTC"),
    "Etc/UTC" to ("UTC" to "UTC"),
    "GMT" to ("GMT" to "GMT"),
    "Etc/GMT" to ("GMT" to "GMT"),
    "Z" to ("UTC" to "UTC"),
)

/**
 * True when [text] fits a cell [cellChars] wide. The budgets are measured at text scale 115, the
 * widest the appearance screen offers, so anything that fits there fits at 100 and 85 too.
 *
 * An 11 sp line holds 70 characters across the 314 dp content width, which is 4.49 dp a character
 * at scale 100; the other sizes scale from it and the widths are the grid row's own cells.
 */
internal fun clockCellFits(text: String, cellChars: Int): Boolean = text.length <= cellChars

/** 16 sp clock in the ground grid's (314 - 88) / 2 = 113 dp outer cell. */
internal const val FLIGHT_CLOCK_CELL_CHARS_GROUND = 15

/** 16 sp clock in the airborne grid's (314 - 120) / 2 = 97 dp outer cell, narrower for the bar. */
internal const val FLIGHT_CLOCK_CELL_CHARS_AIRBORNE = 12

/** 11 sp scheduled or phone note in the ground grid's 113 dp outer cell. */
internal const val FLIGHT_NOTE_CELL_CHARS_GROUND = 21

/** 11 sp scheduled, departed or phone note in the airborne grid's 97 dp outer cell. */
internal const val FLIGHT_NOTE_CELL_CHARS_AIRBORNE = 18

/** 12 sp preview detail line, two lines of the full 314 dp width. */
internal const val FLIGHT_PREVIEW_LINE_MAX_CHARS = 110

/**
 * The first of [candidates] that fits [cellChars], and the last one when none does. The last is
 * always the bare clock, which fits every cell on the card, so nothing ever ellipsises.
 */
private fun firstFittingClock(cellChars: Int, vararg candidates: String): String =
    candidates.firstOrNull { clockCellFits(it, cellChars) } ?: candidates.last()

/**
 * The phone-time row's centre caption. It rides the cell the block time and the route distance use
 * on the rows above, so it costs no height, and without it a bare second clock under a scheduled
 * one is a third time with nothing saying whose it is.
 */
internal const val PHONE_TIME_CAPTION = "Phone time"

/** The phone's own zone name at [instantMillis]. The device zone is its only candidate, so it always names it. */
internal fun phoneZoneLabel(zone: ZoneId, instantMillis: Long): String =
    zoneLabel(phoneOffsetMinutes(zone, instantMillis), listOf(zone), instantMillis)

private fun phoneOffsetMinutes(zone: ZoneId, instantMillis: Long): Int =
    zone.rules.getOffset(Instant.ofEpochMilli(instantMillis)).totalSeconds / 60

/**
 * The phone's own reading of an airport clock, "4:26 pm IST". Null when that airport is already on
 * the phone's offset, which is the whole point of the line: it answers "what time is that where I
 * am", and an end that is already here would be answering it with the clock printed above it.
 *
 * [departureMillis], when given, adds the arrival clock's day marker read in the phone zone, so a
 * red-eye that lands "+1" in Singapore but the same evening at home says so.
 */
internal fun phoneClockLine(
    utcMillis: Long,
    airportOffsetMinutes: Int?,
    zone: ZoneId,
    departureMillis: Long? = null,
): String? {
    if (airportOffsetMinutes == null || airportOffsetMinutes == phoneOffsetMinutes(zone, utcMillis)) {
        return null
    }
    val daySuffix = departureMillis
        ?.let { arrivalDaySuffix(localDateAt(it, zone), localDateAt(utcMillis, zone)) }
        .orEmpty()
    return "${formatEventClockTime(utcMillis, zone)} ${phoneZoneLabel(zone, utcMillis)}$daySuffix"
}

/** [phoneClockLine]'s clock half, for a cell too narrow to hold the zone name on the same line. */
internal fun phoneClockTime(
    utcMillis: Long,
    airportOffsetMinutes: Int?,
    zone: ZoneId,
    departureMillis: Long? = null,
): String? {
    if (airportOffsetMinutes == null || airportOffsetMinutes == phoneOffsetMinutes(zone, utcMillis)) {
        return null
    }
    val daySuffix = departureMillis
        ?.let { arrivalDaySuffix(localDateAt(it, zone), localDateAt(utcMillis, zone)) }
        .orEmpty()
    return formatEventClockTime(utcMillis, zone) + daySuffix
}

/**
 * Arrival clock time with a " +1" / " +2" suffix when it lands on a later local day than the
 * departure. Each end is read in its own airport's offset when one is known, so the suffix answers
 * the question the traveller actually asks - is this the next day where I land - rather than
 * whether the phone's date happened to roll over.
 */
internal fun formatArrivalClockTime(
    departureMillis: Long,
    arrivalMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    departureOffsetMinutes: Int? = null,
    arrivalOffsetMinutes: Int? = null,
    zoneLabel: String? = null,
): String = formatAirportClockTime(arrivalMillis, arrivalOffsetMinutes, zone, zoneLabel) + arrivalDaySuffix(
    localDateAt(departureMillis, airportZone(departureOffsetMinutes, zone)),
    localDateAt(arrivalMillis, airportZone(arrivalOffsetMinutes, zone)),
)

/**
 * The departure clock of [flight] itself: the departure airport's local time once a status has
 * carried an offset, the description's own printed time while none has, and the device zone when
 * neither is known. [departureMillis] must be this flight's own departure - scheduled or estimated
 * - because the printed fallback can only ever stand in for that one instant.
 */
internal fun flightDepartureClock(
    departureMillis: Long,
    status: FlightStatus?,
    flight: FlightEvent,
    zone: ZoneId,
    zoneLabel: String? = null,
): String = if (status != null) {
    formatAirportClockTime(departureMillis, status.departureUtcOffsetMinutes, zone, zoneLabel)
} else {
    // No status is no offset, so there is no zone to name: the printed itinerary time is simply
    // whatever the airline wrote, and labelling it would be asserting something unknown.
    flight.departureLocalHm?.let(::formatPrintedClockTime) ?: formatEventClockTime(departureMillis, zone)
}

/** [flightDepartureClock]'s arrival twin, carrying the day suffix [formatArrivalClockTime] adds. */
internal fun flightArrivalClock(
    departureMillis: Long,
    arrivalMillis: Long,
    status: FlightStatus?,
    flight: FlightEvent,
    zone: ZoneId,
    zoneLabel: String? = null,
): String {
    if (status != null) {
        return formatArrivalClockTime(
            departureMillis = departureMillis,
            arrivalMillis = arrivalMillis,
            zone = zone,
            departureOffsetMinutes = status.departureUtcOffsetMinutes,
            arrivalOffsetMinutes = status.arrivalUtcOffsetMinutes,
            zoneLabel = zoneLabel,
        )
    }
    val printed = flight.arrivalLocalHm?.let(::formatPrintedClockTime)
        ?: return formatArrivalClockTime(departureMillis, arrivalMillis, zone)
    return printed + arrivalDaySuffix(localDateAt(departureMillis, zone), localDateAt(arrivalMillis, zone))
}

/**
 * Great-circle route distance, "3,140 km". Deliberately not [formatDistanceKm], which takes metres
 * and renders one decimal for the road ETA - a flight leg wants whole kilometres with a separator.
 */
internal fun formatRouteDistanceKm(km: Int): String = String.format(Locale.US, "%,d km", km)

/**
 * True when the two instants land on different clock faces. The scheduled note exists only to
 * explain a clock that moved, so it is suppressed whenever the two times print the same - a
 * scheduled 11:35:00 against an estimated 11:35:40 is not a delay, it is rounding.
 */
private fun movedClock(scheduled: Long, shown: Long): Boolean =
    Math.floorDiv(scheduled, 60_000L) != Math.floorDiv(shown, 60_000L)

/** Cell count in the airborne progress bar. */
internal const val FLIGHT_PROGRESS_CELLS = 13

/** The two halves of the text progress bar; Glance allows one style per Text, so the bar is two of them. */
internal data class FlightProgressBar(val filled: String, val empty: String)

internal fun progressBarCells(percent: Int): FlightProgressBar {
    val filled = (percent.coerceIn(0, 100) / 100f * FLIGHT_PROGRESS_CELLS).roundToInt()
    return FlightProgressBar("█".repeat(filled), "░".repeat(FLIGHT_PROGRESS_CELLS - filled))
}

/** "1 h 52 min left", computed locally from the arrival estimate so it survives with no connectivity. */
internal fun flightRemainingLabel(arrivalMillis: Long, nowEpochMillis: Long): String {
    val minutes = ((arrivalMillis - nowEpochMillis) / 60_000L).toInt()
    return if (minutes > 0) "${formatFlightDuration(minutes)} left" else "Arriving"
}

/**
 * "890 km/h · 11,000 m" and, when [vectors] is set, the vertical speed, the heading and the
 * transponder too - which is what retires the separate Live section on the tall card. The first
 * four carry no labels because the sign on the vertical speed and the "deg" on the heading are
 * what tell them apart from the ground speed; the squawk is labelled because four bare digits are
 * the one token on the line that says nothing on their own.
 *
 * The route distance is gone: the airborne card now prints [flightDistanceProgressLine], which
 * sums to it, and the tall line needed the room the total was taking.
 */
internal fun flightCardTelemetryLine(
    status: FlightStatus?,
    vectors: Boolean = false,
): String? = listOfNotNull(
    status?.speedKmh?.let { "$it km/h" },
    status?.altitudeMeters?.let { String.format(Locale.US, "%,d m", it) },
    status?.verticalSpeedKmh?.takeIf { vectors }?.let { "$it km/h" },
    status?.headingDegrees?.takeIf { vectors }?.let { "$it deg" },
    status?.transponderCode?.takeIf { vectors }?.let { "Squawk $it" },
).joinToString(" · ").ifEmpty { null }

/**
 * "858 km flown · 2,321 km to go", split out of the route distance by the same percentage the
 * progress bar is drawn from. Derived rather than fetched for the same reason the bar is: above
 * 10,000 ft there is no fetch, and a percentage the card computed itself still moves.
 */
internal fun flightDistanceProgressLine(routeDistanceKm: Int?, percent: Int): String? {
    if (routeDistanceKm == null) return null
    val flown = (routeDistanceKm * percent.coerceIn(0, 100) / 100.0).roundToInt()
    return "${formatRouteDistanceKm(flown)} flown · ${formatRouteDistanceKm(routeDistanceKm - flown)} to go"
}

/** Every string the ground flight card renders, at any size, in one pure value. */
internal data class FlightCardGrid(
    val designator: String,
    val smallIdentity: String,
    val chip: FlightCardChip,
    val originCode: String,
    val destinationCode: String,
    val duration: String?,
    val distance: String?,
    val departureTime: String,
    val arrivalTime: String,
    val departureZoneTime: String,
    val arrivalZoneTime: String,
    val departureZoneTimeWide: String,
    val arrivalZoneTimeWide: String,
    val departureZone: String?,
    val arrivalZone: String?,
    val departureDelay: String?,
    val arrivalDelay: String?,
    val departureScheduledNote: String?,
    val arrivalScheduledNote: String?,
    val departureScheduledClock: String?,
    val arrivalScheduledClock: String?,
    val departurePhoneTime: String?,
    val arrivalPhoneTime: String?,
    val departurePhoneClock: String?,
    val arrivalPhoneClock: String?,
    val phoneZone: String?,
    val phoneTimeCaption: String?,
    val smallGate: String,
    val bookingLine: String?,
    val layoverHeadline: String?,
    val layoverRoute: String?,
    val layoverCaption: String?,
)

/**
 * The whole ground card as text. The clock cells carry the estimate when one moved, because that is
 * the time the traveller has to make; the delay keeps its own cell so the two are never confused,
 * and [FlightCardGrid.departureScheduledNote] names the time that was booked, which the delta does
 * not. LAYOVER fills the same grid from the connecting leg and adds the three layover-only strings.
 */
internal fun flightCardGrid(
    airport: AirportSnapshot,
    zone: ZoneId = ZoneId.systemDefault(),
    departureZones: List<ZoneId> = emptyList(),
    arrivalZones: List<ZoneId> = emptyList(),
): FlightCardGrid {
    val flight = airport.flight
    val status = airport.status
    val source = status?.source
    val (originCode, destinationCode) = flightIataPair(airport)
    val departureScheduled = status?.departureScheduledUtcMillis ?: flight.departureMillis
    val arrivalScheduled = status?.arrivalScheduledUtcMillis ?: flight.arrivalMillis
    val departureShown = status?.departureEstimatedUtcMillis ?: departureScheduled
    val arrivalShown = status?.arrivalEstimatedUtcMillis ?: arrivalScheduled
    val durationMinutes = status?.durationMinutes
        ?: ((arrivalScheduled - departureScheduled) / 60_000L).toInt().takeIf { it > 0 }
    val departureTime = flightDepartureClock(departureShown, status, flight, zone)
    val departureOffset = status?.departureUtcOffsetMinutes
    val arrivalOffset = status?.arrivalUtcOffsetMinutes
    val departureZoneLabel = departureOffset?.let { zoneLabel(it, departureZones, departureShown) }
    val arrivalZoneLabel = arrivalOffset?.let { zoneLabel(it, arrivalZones, arrivalShown) }
    val arrivalTime = flightArrivalClock(departureShown, arrivalShown, status, flight, zone)
    val departurePhoneTime = phoneClockLine(departureShown, departureOffset, zone)
    val arrivalPhoneTime = phoneClockLine(arrivalShown, arrivalOffset, zone, departureShown)
    val gateLabel = status?.departureGate?.let { "Gate $it" } ?: unknownGateLabel(source)
    val terminalLabel = (status?.departureTerminal ?: flight.departureTerminal)?.let { "T$it" }
    val layoverMinutes = airport.layoverFromArrivalMillis
        ?.let { ((departureScheduled - it) / 60_000L).toInt() }
        ?.takeIf { it > 0 }
    return FlightCardGrid(
        designator = flight.designator,
        smallIdentity = if (status == null) {
            "${flight.designator} · Tap for status"
        } else {
            "${flight.designator} · $originCode to $destinationCode"
        },
        chip = flightStatusChip(status?.status, status?.departureDelayMinutes, source),
        originCode = originCode,
        destinationCode = destinationCode,
        duration = durationMinutes?.let { formatFlightDuration(it) },
        distance = airport.routeDistanceKm?.let { formatRouteDistanceKm(it) },
        departureTime = departureTime,
        arrivalTime = arrivalTime,
        departureZoneTime = flightDepartureClock(departureShown, status, flight, zone, departureZoneLabel),
        arrivalZoneTime = flightArrivalClock(departureShown, arrivalShown, status, flight, zone, arrivalZoneLabel),
        // The 4x2 has no height for a second line in a cell, so it trades the zone's name for its
        // offset and, when even that will not fit, for nothing: the IATA code is directly above.
        departureZoneTimeWide = firstFittingClock(
            FLIGHT_CLOCK_CELL_CHARS_GROUND,
            flightDepartureClock(departureShown, status, flight, zone, departureZoneLabel),
            flightDepartureClock(departureShown, status, flight, zone, departureOffset?.let(::utcOffsetLabel)),
            departureTime,
        ),
        arrivalZoneTimeWide = firstFittingClock(
            FLIGHT_CLOCK_CELL_CHARS_GROUND,
            flightArrivalClock(departureShown, arrivalShown, status, flight, zone, arrivalZoneLabel),
            flightArrivalClock(departureShown, arrivalShown, status, flight, zone, arrivalOffset?.let(::utcOffsetLabel)),
            arrivalTime,
        ),
        departureZone = departureZoneLabel,
        arrivalZone = arrivalZoneLabel,
        departureDelay = status?.departureDelayMinutes?.takeIf { abs(it) >= 1 }?.let { formatDelayMinutes(it) },
        arrivalDelay = status?.arrivalDelayMinutes?.takeIf { abs(it) >= 1 }?.let { formatDelayMinutes(it) },
        departureScheduledNote = departureScheduled
            .takeIf { movedClock(it, departureShown) }
            ?.let { "Sched ${flightDepartureClock(it, status, flight, zone, departureZoneLabel)}" },
        arrivalScheduledNote = arrivalScheduled
            .takeIf { movedClock(it, arrivalShown) }
            ?.let { "Sched ${flightArrivalClock(departureShown, it, status, flight, zone, arrivalZoneLabel)}" },
        departureScheduledClock = departureScheduled
            .takeIf { movedClock(it, departureShown) }
            ?.let { "Sched ${flightDepartureClock(it, status, flight, zone)}" },
        arrivalScheduledClock = arrivalScheduled
            .takeIf { movedClock(it, arrivalShown) }
            ?.let { "Sched ${flightArrivalClock(departureShown, it, status, flight, zone)}" },
        departurePhoneTime = departurePhoneTime,
        arrivalPhoneTime = arrivalPhoneTime,
        departurePhoneClock = phoneClockTime(departureShown, departureOffset, zone),
        arrivalPhoneClock = phoneClockTime(arrivalShown, arrivalOffset, zone, departureShown),
        phoneZone = phoneZoneLabel(zone, departureShown).takeIf { departurePhoneTime != null || arrivalPhoneTime != null },
        phoneTimeCaption = PHONE_TIME_CAPTION.takeIf { departurePhoneTime != null || arrivalPhoneTime != null },
        smallGate = if (airport.layover) {
            "$gateLabel · $departureTime"
        } else {
            listOfNotNull(gateLabel, terminalLabel).joinToString(" · ")
        },
        bookingLine = flightCardPnrSeatLine(airport),
        layoverHeadline = layoverMinutes?.takeIf { airport.layover }?.let { "Layover ${formatFlightDuration(it)}" },
        layoverRoute = "${flight.designator} to $destinationCode".takeIf { airport.layover },
        layoverCaption = airport.layoverFromDesignator
            ?.takeIf { airport.layover }
            ?.let { from ->
                // The inbound leg landed at this flight's own departure airport, so its clock is
                // the departure offset, not the arrival one.
                airport.layoverFromArrivalMillis?.let {
                    "$from landed ${formatAirportClockTime(it, status?.departureUtcOffsetMinutes, zone)}"
                }
            },
    )
}

/** Every string the airborne card renders. Departure detail is dead weight once wheels are up, so arrival detail takes the same slots. */
internal data class FlightAirborneGrid(
    val designator: String,
    val chip: FlightCardChip,
    val originCode: String,
    val destinationCode: String,
    val progress: FlightProgressBar,
    val percentLabel: String,
    val remaining: String,
    val totalDuration: String?,
    val departedLabel: String?,
    val departedClock: String?,
    val departedLabelWide: String?,
    val arrivalTime: String,
    val arrivalZoneTime: String,
    val arrivalZoneTimeWide: String,
    val departureZone: String?,
    val arrivalZone: String?,
    val arrivalDelay: String?,
    val departureScheduledNote: String?,
    val arrivalScheduledNote: String?,
    val departureScheduledClock: String?,
    val arrivalScheduledClock: String?,
    val departurePhoneTime: String?,
    val arrivalPhoneTime: String?,
    val departurePhoneClock: String?,
    val arrivalPhoneClock: String?,
    val phoneZone: String?,
    val phoneTimeCaption: String?,
    val distanceProgress: String?,
    val telemetry: String?,
    val telemetryLive: String?,
    val telemetryDetail: String?,
    val arrivalBelt: String?,
    val bookingLine: String?,
    val smallRoute: String,
)

/**
 * The airborne card as text. Time remaining leads and is computed here from the timestamps rather
 * than read from a field that needs a fetch, because above 10,000 ft there is no fetch; the percent
 * is derived the same way when the provider sent none, so the bar always has a length, and
 * [FlightAirborneGrid.distanceProgress] is cut from that same percent for the same reason.
 *
 * Three telemetry strings, one per size: [FlightAirborneGrid.telemetry] is speed and altitude for
 * the wide card, [FlightAirborneGrid.telemetryLive] appends the distance pair for the medium one,
 * which has no room for a second line, and [FlightAirborneGrid.telemetryDetail] takes the vectors
 * and the squawk instead because the tall card prints the distance pair on its own line.
 */
internal fun flightAirborneGrid(
    airport: AirportSnapshot,
    nowEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    departureZones: List<ZoneId> = emptyList(),
    arrivalZones: List<ZoneId> = emptyList(),
): FlightAirborneGrid {
    val flight = airport.flight
    val status = airport.status
    val (originCode, destinationCode) = flightIataPair(airport)
    val departureMillis = status?.departureActualUtcMillis
        ?: status?.departureEstimatedUtcMillis
        ?: status?.departureScheduledUtcMillis
        ?: flight.departureMillis
    val arrivalMillis = status?.arrivalEstimatedUtcMillis
        ?: status?.arrivalScheduledUtcMillis
        ?: flight.arrivalMillis
    val leg = arrivalMillis - departureMillis
    val percent = status?.percentComplete ?: if (leg > 0L) {
        ((nowEpochMillis - departureMillis) * 100L / leg).toInt()
    } else {
        0
    }
    val clampedPercent = percent.coerceIn(0, 100)
    val arrivalTime = flightArrivalClock(departureMillis, arrivalMillis, status, flight, zone)
    val departureOffset = status?.departureUtcOffsetMinutes
    val arrivalOffset = status?.arrivalUtcOffsetMinutes
    val departureZoneLabel = departureOffset?.let { zoneLabel(it, departureZones, departureMillis) }
    val arrivalZoneLabel = arrivalOffset?.let { zoneLabel(it, arrivalZones, arrivalMillis) }
    val departurePhoneTime = phoneClockLine(departureMillis, departureOffset, zone)
    val arrivalPhoneTime = phoneClockLine(arrivalMillis, arrivalOffset, zone, departureMillis)
    val departureScheduled = status?.departureScheduledUtcMillis
    val arrivalScheduled = status?.arrivalScheduledUtcMillis
    val distanceProgress = flightDistanceProgressLine(airport.routeDistanceKm, clampedPercent)
    val telemetry = flightCardTelemetryLine(status)
    val arrivalBelt = listOfNotNull(
        (status?.arrivalTerminal ?: flight.arrivalTerminal)?.let { "T$it" },
        status?.arrivalBaggageBelt?.let { "Belt $it" },
    ).joinToString(" · ").ifEmpty { null }
    return FlightAirborneGrid(
        designator = flight.designator,
        chip = flightStatusChip(status?.status, status?.departureDelayMinutes, status?.source),
        originCode = originCode,
        destinationCode = destinationCode,
        progress = progressBarCells(clampedPercent),
        percentLabel = "$clampedPercent%",
        remaining = flightRemainingLabel(arrivalMillis, nowEpochMillis),
        totalDuration = (
            status?.durationMinutes
                ?: departureScheduled?.let { dep ->
                    arrivalScheduled?.let { ((it - dep) / 60_000L).toInt() }
                }
            )?.takeIf { it > 0 }?.let { formatFlightDuration(it) },
        departedLabel = status?.departureActualUtcMillis?.let {
            "Left ${formatAirportClockTime(it, departureOffset, zone, departureZoneLabel)}"
        },
        departedClock = status?.departureActualUtcMillis?.let {
            "Left ${formatAirportClockTime(it, departureOffset, zone)}"
        },
        departedLabelWide = status?.departureActualUtcMillis?.let {
            firstFittingClock(
                FLIGHT_NOTE_CELL_CHARS_AIRBORNE,
                "Left ${formatAirportClockTime(it, departureOffset, zone, departureZoneLabel)}",
                "Left ${formatAirportClockTime(it, departureOffset, zone, departureOffset?.let(::utcOffsetLabel))}",
                "Left ${formatAirportClockTime(it, departureOffset, zone)}",
            )
        },
        arrivalTime = arrivalTime,
        arrivalZoneTime = flightArrivalClock(departureMillis, arrivalMillis, status, flight, zone, arrivalZoneLabel),
        arrivalZoneTimeWide = firstFittingClock(
            FLIGHT_CLOCK_CELL_CHARS_AIRBORNE,
            flightArrivalClock(departureMillis, arrivalMillis, status, flight, zone, arrivalZoneLabel),
            flightArrivalClock(departureMillis, arrivalMillis, status, flight, zone, arrivalOffset?.let(::utcOffsetLabel)),
            arrivalTime,
        ),
        departureZone = departureZoneLabel,
        arrivalZone = arrivalZoneLabel,
        arrivalDelay = status?.arrivalDelayMinutes?.takeIf { abs(it) >= 1 }?.let { formatDelayMinutes(it) },
        departureScheduledNote = departureScheduled
            ?.takeIf { movedClock(it, departureMillis) }
            ?.let { "Sched ${formatAirportClockTime(it, departureOffset, zone, departureZoneLabel)}" },
        arrivalScheduledNote = arrivalScheduled
            ?.takeIf { movedClock(it, arrivalMillis) }
            ?.let { "Sched ${flightArrivalClock(departureMillis, it, status, flight, zone, arrivalZoneLabel)}" },
        departureScheduledClock = departureScheduled
            ?.takeIf { movedClock(it, departureMillis) }
            ?.let { "Sched ${formatAirportClockTime(it, departureOffset, zone)}" },
        arrivalScheduledClock = arrivalScheduled
            ?.takeIf { movedClock(it, arrivalMillis) }
            ?.let { "Sched ${flightArrivalClock(departureMillis, it, status, flight, zone)}" },
        departurePhoneTime = departurePhoneTime,
        arrivalPhoneTime = arrivalPhoneTime,
        departurePhoneClock = phoneClockTime(departureMillis, departureOffset, zone),
        arrivalPhoneClock = phoneClockTime(arrivalMillis, arrivalOffset, zone, departureMillis),
        phoneZone = phoneZoneLabel(zone, departureMillis).takeIf { departurePhoneTime != null || arrivalPhoneTime != null },
        phoneTimeCaption = PHONE_TIME_CAPTION.takeIf { departurePhoneTime != null || arrivalPhoneTime != null },
        distanceProgress = distanceProgress,
        telemetry = telemetry,
        telemetryLive = listOfNotNull(telemetry, distanceProgress).joinToString(" · ").ifEmpty { null },
        telemetryDetail = flightCardTelemetryLine(status, vectors = true),
        arrivalBelt = arrivalBelt,
        bookingLine = flightCardPnrSeatLine(airport),
        smallRoute = "$originCode to $destinationCode · $arrivalTime",
    )
}

/**
 * The OFFERED card's bottom caption: the same two numbers the map pills carry in RIDING, so the
 * decision to leave is on screen before the To Airport tap even though there is no map yet.
 */
internal fun airportOfferedPillLine(airport: AirportSnapshot, zone: ZoneId = ZoneId.systemDefault()): String? =
    listOfNotNull(
        airport.leaveByMillis?.let { "Leave by ${formatEventClockTime(it, zone)}" },
        airport.bestDepartureMillis?.let { "Best ${formatEventClockTime(it, zone)}" },
    ).joinToString(" · ").ifEmpty { null }

/**
 * True when the flight card replaces the map. Every phase but RIDING: OFFERED has no map image to
 * show (its Leave by comes from a route probe, not a rendered map), and the two card phases never
 * had one.
 */
internal fun airportRendersFlightCard(phase: AirportPhase): Boolean = phase != AirportPhase.RIDING

/**
 * The RIDING map's two title lines: "SQ509 to SIN" over the airport's own display name. One line
 * cannot hold both without ellipsizing the destination away, which is what "SQ509 to Sin..." was.
 */
internal fun airportMapTitleLines(snapshot: CommuteSnapshot): Pair<String, String?> {
    val airport = snapshot.airport ?: return "Airport" to null
    val flight = airport.flight
    val destination = airport.status?.arrivalIata
        ?: flight.arrivalIata
        ?: flight.arrivalAirportName?.trim()?.substringBefore(' ')?.ifEmpty { null }
        ?: "flight"
    return "${flight.designator} to $destination" to snapshot.destinationLabel?.trim()?.ifEmpty { null }
}

/** Line 1's identity half, "SQ509 BLR to SIN", shared by the flight row and the merged block. */
internal fun flightPreviewIdentity(preview: FlightPreview): String {
    val flight = preview.flight
    val status = preview.status
    val from = status?.departureIata ?: flight.departureIata ?: status?.departureAirportName ?: flight.departureAirportName
    val to = status?.arrivalIata ?: flight.arrivalIata ?: status?.arrivalAirportName ?: flight.arrivalAirportName
    val route = when {
        from != null && to != null -> "$from to $to"
        from != null -> "from $from"
        to != null -> "to $to"
        else -> null
    }
    return listOfNotNull(flight.designator.ifBlank { null }, route).joinToString(" ").ifEmpty { "Flight" }
}

/** The merged calendar block's five lines. */
internal data class FlightPreviewBlock(
    val heading: String,
    val countdown: String?,
    val identity: String,
    val time: String,
    val detail: String?,
    val caption: String?,
    val calendarLine: String?,
)

/**
 * The calendar card's headline and its flight row are the same flight, so they render once. The
 * countdown counts to the flight's own departure rather than the calendar entry's start: one clock
 * on the card and one countdown to it. The two are only both shown when the calendar entry is a
 * check-in block booked five minutes or more before the flight, which is the one case where two
 * clocks are honest.
 */
internal fun flightPreviewBlock(
    preview: FlightPreview,
    eventStartEpochMillis: Long?,
    nowEpochMillis: Long,
    hasFlightStatusKey: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    departureZones: List<ZoneId> = emptyList(),
): FlightPreviewBlock {
    val departureMillis = flightPreviewDepartureMillis(preview)
    val departureDate = Instant.ofEpochMilli(departureMillis).atZone(zone)
    val daysOut = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate(),
        departureDate.toLocalDate(),
    )
    val status = preview.status
    val detail = listOfNotNull(
        status?.let {
            formatTerminalGateOrTba(it.departureTerminal ?: preview.flight.departureTerminal, it.departureGate, it.source)
        } ?: formatTerminalGate(preview.flight.departureTerminal, null),
        preview.flight.confirmationNumber?.let { "PNR $it" },
    ).joinToString(" · ").ifEmpty { null }
    val connection = preview.layoverFromDesignator?.let { from ->
        preview.layoverMinutes?.let { "Layover ${formatFlightDuration(it)} after $from" }
    }
    return FlightPreviewBlock(
        heading = if (daysOut <= 1L) "Next flight" else departureDate.format(FLIGHT_PREVIEW_DATE_FORMAT),
        countdown = eventCountdownMinutes(departureMillis, nowEpochMillis)?.let { formatCountdown(it) },
        identity = flightPreviewIdentity(preview),
        // "9:35 pm SGT · 7:05 pm IST". The block is the one place on the calendar card that shows
        // a flight's own clock, and the owner reads it standing in another zone, so it carries
        // both readings rather than the "(+8)" hint it used to, which named neither zone.
        time = listOfNotNull(
            flightDepartureClock(
                departureMillis,
                status,
                preview.flight,
                zone,
                status?.departureUtcOffsetMinutes?.let { zoneLabel(it, departureZones, departureMillis) },
            ),
            phoneClockLine(departureMillis, status?.departureUtcOffsetMinutes, zone),
        ).joinToString(" · "),
        detail = detail,
        caption = connection ?: flightPreviewNoteLine(preview, hasFlightStatusKey),
        calendarLine = eventStartEpochMillis
            ?.takeIf { abs(it - departureMillis) >= 5 * 60_000L }
            ?.let { "Calendar ${formatEventClockTime(it, zone)}" },
    )
}

/**
 * True when the calendar card's headline event IS the previewed flight, matched on title and start
 * exactly as [upcomingEventsWithoutPreview] matches the wind-down list. The merged block then
 * renders instead of both.
 */
internal fun headlineIsPreviewedFlight(snapshot: CommuteSnapshot): Boolean {
    val preview = snapshot.flightPreview ?: return false
    val start = snapshot.eventStartEpochMillis ?: return false
    return snapshot.destinationLabel?.trim() == preview.flight.title.trim() &&
        start == preview.flight.departureMillis
}

@Composable
private fun chipColorProvider(tone: FlightChipTone): ColorProvider = when (tone) {
    FlightChipTone.QUIET -> GlanceTheme.colors.onSurfaceVariant
    FlightChipTone.WARN -> GlanceTheme.colors.tertiary
    FlightChipTone.LIVE -> GlanceTheme.colors.primary
    FlightChipTone.ERROR -> GlanceTheme.colors.error
}

/**
 * The card's three-column spine: two equal outer columns anchored to their own edge and a fixed
 * centre so the arrival column is genuinely right-aligned. Glance's defaultWeight takes no weight
 * argument and splits the remainder evenly, which is exactly what is wanted.
 */
@Composable
private fun FlightGridRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    centre: (@Composable () -> Unit)? = null,
    centreWidth: Dp = 88.dp,
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.CenterStart) { start() }
        if (centre != null) {
            Box(modifier = GlanceModifier.width(centreWidth), contentAlignment = Alignment.Center) { centre() }
        }
        Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.CenterEnd) { end() }
    }
}

@Composable
private fun FlightCardText(
    text: String,
    size: Int,
    textScale: Float,
    color: ColorProvider,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        style = TextStyle(color = color, fontSize = scaledSp(size, textScale), fontWeight = weight),
        maxLines = 1,
    )
}

/**
 * A clock that keeps its zone name however narrow the cell: both on one line when they fit, and the
 * zone on a 10 sp caption line under the clock when they do not. Never ellipsises, which a single
 * `maxLines = 1` Text would - and an ellipsis on a clock cell is what took `Sched 7:00 pm GMT+0...`
 * off the owner's card.
 *
 * Medium and tall only. The wide card has no height to give a cell a second line, so it renders the
 * already-compacted `...Wide` string through [FlightCardText] instead.
 */
@Composable
private fun FlightClockCell(
    oneLine: String,
    clock: String,
    zone: String?,
    size: Int,
    cellChars: Int,
    textScale: Float,
    color: ColorProvider,
    alignEnd: Boolean,
    weight: FontWeight = FontWeight.Normal,
) {
    if (zone == null || clockCellFits(oneLine, cellChars)) {
        FlightCardText(oneLine, size, textScale, color, weight)
        return
    }
    Column(horizontalAlignment = if (alignEnd) Alignment.Horizontal.End else Alignment.Horizontal.Start) {
        FlightCardText(clock, size, textScale, color, weight)
        FlightCardText(zone, 10, textScale, GlanceTheme.colors.onSurfaceVariant)
    }
}

/** A facts-paragraph line: 11 sp, full width, and allowed a second line rather than an ellipsis. */
@Composable
private fun FlightFactText(
    text: String,
    textScale: Float,
    color: ColorProvider,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        style = TextStyle(color = color, fontSize = scaledSp(11, textScale), fontWeight = weight),
        maxLines = 2,
    )
}

@Composable
private fun FlightStatusChipText(chip: FlightCardChip, textScale: Float, prefix: String? = null) {
    FlightCardText(
        text = if (prefix != null) "$prefix · ${chip.label}" else chip.label,
        size = 11,
        textScale = textScale,
        color = chipColorProvider(chip.tone),
        weight = FontWeight.Medium,
    )
}

/** The two-tone text bar: one Text per colour, because Glance allows one style per Text. */
@Composable
private fun FlightProgressBarRow(
    progress: FlightProgressBar,
    percentLabel: String?,
    textScale: Float,
    cellSize: Int,
) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        FlightCardText(progress.filled, cellSize, textScale, GlanceTheme.colors.primary)
        FlightCardText(progress.empty, cellSize, textScale, GlanceTheme.colors.onSurfaceVariant)
        if (percentLabel != null) {
            Spacer(modifier = GlanceModifier.width(4.dp))
            FlightCardText(percentLabel, 11, textScale, GlanceTheme.colors.onSurfaceVariant, FontWeight.Medium)
        }
    }
}

/**
 * Airline and aircraft, one cell per edge, between the gate row and the footer, with the record
 * locator folded into the left cell by [flightCardCompactAirlineCell]. Wide and medium only: the
 * tall card gives the booking, the airframe and the airline a line each. The whole row disappears
 * when the status names none of the three.
 */
@Composable
private fun FlightAircraftRow(status: FlightStatus?, bookingLine: String?, textScale: Float) {
    val (airline, aircraft) = flightCardAircraftRow(status)
    val startCell = flightCardCompactAirlineCell(airline, bookingLine)
    if (startCell == null && aircraft == null) {
        return
    }
    val muted = GlanceTheme.colors.onSurfaceVariant
    FlightGridRow(
        start = { startCell?.let { FlightCardText(it, 10, textScale, muted) } },
        end = { aircraft?.let { FlightCardText(it, 10, textScale, muted) } },
    )
}

/**
 * The bottom row every flight card ends with: a caption on the left and the phase's own pill on the
 * right, never overlaid, with 8 dp between them. OFFERED carries the To Airport pill and the Leave
 * by / Best caption, LAYOVER the Reached pill, REACHED the Done pill.
 */
@Composable
private fun FlightCardActionRow(airport: AirportSnapshot, caption: String?, textScale: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (caption != null) {
            Text(
                text = caption,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = scaledSp(11, textScale)),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        } else {
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        when {
            airportShowsToAirportPill(airport.phase) -> ToAirportPill(airport.phase, textScale, cardStyle = true)
            airportShowsLayoverReachedPill(airport.phase) -> AirportReachedPill(textScale, cardStyle = true)
            else -> AirportDismissPill(textScale)
        }
    }
}

/**
 * Full-width flight-status card, replacing the map for every phase but RIDING
 * ([airportRendersFlightCard]). Health and custom pills never render here - the card is dense
 * enough on its own - and the whole card is one tap target, now that nothing on it scrolls: the
 * LazyColumn detail list is gone, so there are no lazy rows a parent tap cannot reach.
 *
 * [MEDIUM_BREAKPOINT] and [TALL_BREAKPOINT], not [LARGE_BREAKPOINT], gate the two denser layouts.
 * SizeMode.Responsive reports the matched breakpoint rather than the physical box, so LARGE cannot
 * tell a 4x3 from a 4x4 from a 4x6, and each of the three needs a different amount of room. Every
 * box under 270 dp gets the WIDE card, which ends in a weighted spacer and therefore breathes at
 * that height instead of clipping its own footer.
 *
 * Every Column and Row below is kept to at most ten children on purpose. Glance 1.1.1 has generated
 * layouts for 0..10 children only; `insertContainerView` silently `coerceAtMost(10)`s anything
 * longer and merely logs the overflow, so an eleventh child is dropped from the widget with no
 * visible error. That is what took the footer and the Done pill off the 4x6 card. The two wide
 * bodies therefore dispatch through three `when` expressions that each emit exactly one child, so
 * the outer Column is nine children at every size whatever the card is showing.
 */
@Composable
private fun FlightCard(
    modifier: GlanceModifier,
    airport: AirportSnapshot,
    extras: WidgetExtras,
) {
    val size = LocalSize.current
    val cardSize = when {
        size.width < WIDE_BREAKPOINT.width -> FlightCardSize.SMALL
        size.height >= TALL_BREAKPOINT.height -> FlightCardSize.TALL
        size.height >= MEDIUM_BREAKPOINT.height -> FlightCardSize.MEDIUM
        else -> FlightCardSize.WIDE
    }
    val card = modifier.clickable(actionRunCallback<FlightCardTapAction>()).padding(12.dp)
    when {
        flightCardIsAirborne(airport.status) && cardSize == FlightCardSize.SMALL ->
            AirborneCardSmall(card, airport, extras)
        flightCardIsAirborne(airport.status) -> AirborneCardWide(card, airport, extras, cardSize)
        cardSize == FlightCardSize.SMALL -> FlightCardSmall(card, airport, extras)
        else -> FlightCardWide(card, airport, extras, cardSize)
    }
}

/** Which of the four flight-card bodies the matched breakpoint has room for. */
private enum class FlightCardSize { SMALL, WIDE, MEDIUM, TALL }

/**
 * The ground block: two columns headed by the two IATA codes, one row per fact aligned across both.
 * It is the card's most important information and so carries its largest type below the grid and
 * the only `onSurface` colour under it - size and colour are the whole hierarchy statement, since
 * Glance has no letter spacing and one style per Text to work with.
 *
 * Every cell always has text. A gate lands about T-2h and a belt later still; a block that grew a
 * row when they did would move everything under it down the card on an ordinary refresh.
 */
@Composable
private fun FlightGroundBlock(airport: AirportSnapshot, textScale: Float) {
    val block = flightCardGroundBlock(airport)
    val onSurface = GlanceTheme.colors.onSurface
    val heading = GlanceTheme.colors.primary
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(modifier = GlanceModifier.height(8.dp))
        // The zone shift rides the heading row's centre, which nothing else has ever used: two
        // three-letter codes need 17 dp of a 314 dp row. It is the only new fact on either card
        // that costs no height, and it sits between the two codes it is about.
        FlightGridRow(
            start = { FlightCardText(block.originCode, 11, textScale, heading, FontWeight.Medium) },
            end = { FlightCardText(block.destinationCode, 11, textScale, heading, FontWeight.Medium) },
            centre = {
                flightCardZoneShiftLine(airport.status)?.let {
                    FlightCardText(it, 10, textScale, GlanceTheme.colors.onSurfaceVariant)
                }
            },
            centreWidth = ZONE_SHIFT_CELL_WIDTH,
        )
        FlightGridRow(
            start = { FlightCardText(block.departureTerminal, 14, textScale, onSurface, FontWeight.Medium) },
            end = { FlightCardText(block.arrivalTerminal, 14, textScale, onSurface, FontWeight.Medium) },
        )
        FlightGridRow(
            start = { FlightCardText(block.departureGate, 14, textScale, onSurface, FontWeight.Medium) },
            end = { FlightCardText(block.arrivalGate, 14, textScale, onSurface, FontWeight.Medium) },
        )
        FlightGridRow(
            start = { FlightCardText(block.checkIn, 14, textScale, onSurface, FontWeight.Medium) },
            end = { FlightCardText(block.belt, 14, textScale, onSurface, FontWeight.Medium) },
        )
    }
}

/**
 * The tall card's facts, one dense line each under the ground block: the owner's own booking first,
 * then the airframe, the airline, both airport names, and how the rest of the departure board is
 * running. One size and one colour throughout because none of them outranks another, and a label
 * column in front of them would only repeat what every value already says.
 *
 * This flight's own delays are not here. They were a second printing of the scheduled row, which
 * sits five rows up with a zone name on every clock; the row they were taking is the phone-time
 * row now. The board line stays because it is about every other aeroplane, which nothing else on
 * the card says.
 *
 * Every line here renders at two lines rather than one: all of them are inside the width budget for
 * the data the card has ever seen, but an airline or airport name longer than any of it should wrap
 * rather than lose its tail, and the tall card has four wrapped lines' worth of room to spare.
 */
@Composable
private fun FlightCardFacts(airport: AirportSnapshot, textScale: Float) {
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // 6 dp, not 8: the fleet line costs 14.7 dp at text scale 115 and the last two of them
        // came from here.
        Spacer(modifier = GlanceModifier.height(6.dp))
        flightCardPnrSeatLine(airport)?.let { FlightFactText(it, textScale, muted, FontWeight.Medium) }
        flightCardAircraftLine(airport.status, airport.aircraft)?.let { FlightFactText(it, textScale, muted) }
        flightCardFleetLine(airport.status, airport.aircraft)?.let { FlightFactText(it, textScale, muted) }
        flightCardAirlineLine(airport.status)?.let { FlightFactText(it, textScale, muted) }
        flightCardAirportLines(airport.status, airport.flight).forEach {
            FlightFactText(it, textScale, muted)
        }
        flightCardBoardDelayLine(airport.departureDelayStats)?.let {
            FlightFactText(it, textScale, GlanceTheme.colors.tertiary)
        }
    }
}

/**
 * The medium card's tail: the booking, the airline and the airframe share one row, as they do on
 * the wide card. The departure board's line and the fleet line are the tall card's alone - neither
 * fits here at text scale 115, and the board is the one fact on the card that is about some other
 * aeroplane. This flight's own delays are the scheduled row's job at this size, which is what paid
 * for the phone-time row above.
 */
@Composable
private fun FlightMediumFacts(airport: AirportSnapshot, bookingLine: String?, textScale: Float) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // 4 dp, not the tall card's 6. The medium card is the tightest of the three against its
        // own breakpoint floor and this gap is where the dp came from.
        Spacer(modifier = GlanceModifier.height(4.dp))
        FlightAircraftRow(airport.status, bookingLine, textScale)
    }
}

/**
 * The scheduled row, directly under the clock row and on the same three cells, so the time that was
 * booked sits under the time that is now expected at each end. Either outer cell is empty when that
 * end is running to schedule. The row itself is unconditional - an empty Row is 0 dp tall - so
 * nothing below it moves when a delay lands or clears.
 */
@Composable
private fun FlightScheduledRow(
    start: String?,
    end: String?,
    textScale: Float,
    centre: String? = null,
    centreWidth: Dp = 88.dp,
    startClock: String? = null,
    endClock: String? = null,
    zone: String? = null,
    endZone: String? = zone,
    cellChars: Int = FLIGHT_NOTE_CELL_CHARS_GROUND,
) {
    val muted = GlanceTheme.colors.onSurfaceVariant
    FlightGridRow(
        start = {
            start?.let {
                FlightClockCell(it, startClock ?: it, zone, 11, cellChars, textScale, muted, alignEnd = false)
            }
        },
        end = {
            end?.let {
                FlightClockCell(it, endClock ?: it, endZone, 11, cellChars, textScale, muted, alignEnd = true)
            }
        },
        centre = { centre?.let { FlightCardText(it, 11, textScale, muted) } },
        centreWidth = centreWidth,
    )
}

/**
 * All three wide ground cards share the card down to the clock row. Below it they diverge: the tall
 * and medium cards get the scheduled row and the two-column ground block, the tall card the full
 * facts paragraph under it and the medium card the airline and aircraft row, the booking line and
 * this flight's delays; WIDE gets its per-clock delay cells, a single ground one-liner, the airline
 * and aircraft row, and the booking line.
 *
 * The airline is absent from the header at every size. It is what "SQ509 · Singapore Airlin..." was
 * losing, and a designator has no business sharing a row with a name long enough to need one.
 */
@Composable
private fun FlightCardWide(
    modifier: GlanceModifier,
    airport: AirportSnapshot,
    extras: WidgetExtras,
    cardSize: FlightCardSize,
) {
    val scale = extras.textScale
    val grid = flightCardGrid(
        airport = airport,
        departureZones = countryZoneCandidates(airport.status?.departureCountry),
        arrivalZones = countryZoneCandidates(airport.status?.arrivalCountry),
    )
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant
    val tertiary = GlanceTheme.colors.tertiary
    Column(modifier = modifier) {
        FlightGridRow(
            start = {
                if (grid.layoverHeadline != null) {
                    FlightCardText(grid.layoverHeadline, 18, scale, GlanceTheme.colors.primary, FontWeight.Bold)
                } else {
                    FlightCardText(grid.designator, 16, scale, onSurface, FontWeight.Medium)
                }
            },
            end = {
                FlightStatusChipText(grid.chip, scale, prefix = grid.designator.takeIf { grid.layoverHeadline != null })
            },
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        FlightGridRow(
            start = { FlightCardText(grid.originCode, 22, scale, onSurface, FontWeight.Bold) },
            end = { FlightCardText(grid.destinationCode, 22, scale, onSurface, FontWeight.Bold) },
            centre = { grid.duration?.let { FlightCardText(it, 11, scale, muted) } },
        )
        FlightGridRow(
            start = {
                if (cardSize == FlightCardSize.WIDE) {
                    FlightCardText(grid.departureZoneTimeWide, 16, scale, onSurface, FontWeight.Medium)
                } else {
                    FlightClockCell(
                        oneLine = grid.departureZoneTime,
                        clock = grid.departureTime,
                        zone = grid.departureZone,
                        size = 16,
                        cellChars = FLIGHT_CLOCK_CELL_CHARS_GROUND,
                        textScale = scale,
                        color = onSurface,
                        alignEnd = false,
                        weight = FontWeight.Medium,
                    )
                }
            },
            end = {
                if (cardSize == FlightCardSize.WIDE) {
                    FlightCardText(grid.arrivalZoneTimeWide, 16, scale, onSurface, FontWeight.Medium)
                } else {
                    FlightClockCell(
                        oneLine = grid.arrivalZoneTime,
                        clock = grid.arrivalTime,
                        zone = grid.arrivalZone,
                        size = 16,
                        cellChars = FLIGHT_CLOCK_CELL_CHARS_GROUND,
                        textScale = scale,
                        color = onSurface,
                        alignEnd = true,
                        weight = FontWeight.Medium,
                    )
                }
            },
            centre = { grid.distance?.let { FlightCardText(it, 11, scale, muted) } },
        )
        if (cardSize == FlightCardSize.WIDE) {
            // Each delay sits under the clock it moved, so the two can never be read as one. WIDE
            // only: the taller cards print the scheduled row against the live one instead, which
            // says the same thing in clock faces rather than deltas.
            FlightGridRow(
                start = { grid.departureDelay?.let { FlightCardText(it, 11, scale, tertiary, FontWeight.Medium) } },
                end = { grid.arrivalDelay?.let { FlightCardText(it, 11, scale, tertiary, FontWeight.Medium) } },
            )
        } else {
            // Two rows on one child, because the outer Column is already nine of the ten Glance
            // will render. The phone-time row reuses the scheduled row's three cells so its
            // clocks stay under the airport clocks they translate; both rows are unconditional
            // and an empty Row is 0 dp, so nothing below moves when either has nothing to say.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                FlightScheduledRow(
                    start = grid.departureScheduledNote,
                    end = grid.arrivalScheduledNote,
                    textScale = scale,
                    startClock = grid.departureScheduledClock,
                    endClock = grid.arrivalScheduledClock,
                    zone = grid.departureZone,
                    endZone = grid.arrivalZone,
                )
                FlightScheduledRow(
                    start = grid.departurePhoneTime,
                    end = grid.arrivalPhoneTime,
                    textScale = scale,
                    centre = grid.phoneTimeCaption,
                    startClock = grid.departurePhoneClock,
                    endClock = grid.arrivalPhoneClock,
                    zone = grid.phoneZone,
                )
            }
        }
        if (cardSize == FlightCardSize.WIDE) {
            FlightCardText(flightCardGroundLine(airport), 12, scale, onSurface, FontWeight.Medium)
        } else {
            FlightGroundBlock(airport, scale)
        }
        when (cardSize) {
            FlightCardSize.TALL -> FlightCardFacts(airport, scale)
            FlightCardSize.MEDIUM -> FlightMediumFacts(airport, grid.bookingLine, scale)
            else -> FlightAircraftRow(airport.status, grid.bookingLine, scale)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        FlightCardActionRow(
            airport = airport,
            caption = when {
                airport.phase == AirportPhase.OFFERED -> airportOfferedPillLine(airport)
                grid.layoverCaption != null -> grid.layoverCaption
                else -> flightCardFooter(airport)
            },
            textScale = scale,
        )
    }
}

@Composable
private fun FlightCardSmall(
    modifier: GlanceModifier,
    airport: AirportSnapshot,
    extras: WidgetExtras,
) {
    val scale = extras.textScale
    val grid = flightCardGrid(airport)
    val onSurface = GlanceTheme.colors.onSurface
    Column(modifier = modifier.fillMaxSize()) {
        if (grid.layoverHeadline != null) {
            FlightCardText(grid.layoverHeadline, 14, scale, GlanceTheme.colors.primary, FontWeight.Bold)
            grid.layoverRoute?.let { FlightCardText(it, 14, scale, onSurface, FontWeight.Medium) }
        } else {
            FlightCardText(grid.smallIdentity, 11, scale, GlanceTheme.colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                FlightCardText(grid.departureTime, 20, scale, onSurface, FontWeight.Bold)
                if (grid.departureDelay != null) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    FlightCardText(grid.departureDelay, 11, scale, GlanceTheme.colors.tertiary, FontWeight.Medium)
                }
            }
        }
        FlightCardText(grid.smallGate, 14, scale, onSurface, FontWeight.Medium)
        Spacer(modifier = GlanceModifier.defaultWeight())
        FlightCardActionRow(airport, airportOfferedPillLine(airport).takeIf { airport.phase == AirportPhase.OFFERED }, scale)
    }
}

/** The airborne scheduled row: the same three cells the clock row uses, so the columns line up. */
@Composable
private fun AirborneScheduledRow(grid: FlightAirborneGrid, scale: Float) {
    FlightScheduledRow(
        start = grid.departureScheduledNote,
        end = grid.arrivalScheduledNote,
        textScale = scale,
        centre = grid.totalDuration,
        centreWidth = AIRBORNE_CENTRE_WIDTH,
        startClock = grid.departureScheduledClock,
        endClock = grid.arrivalScheduledClock,
        zone = grid.departureZone,
        endZone = grid.arrivalZone,
        cellChars = FLIGHT_NOTE_CELL_CHARS_AIRBORNE,
    )
}

/** The airborne phone-time row, on those same three cells again. */
@Composable
private fun AirbornePhoneTimeRow(grid: FlightAirborneGrid, scale: Float) {
    FlightScheduledRow(
        start = grid.departurePhoneTime,
        end = grid.arrivalPhoneTime,
        textScale = scale,
        centre = grid.phoneTimeCaption,
        centreWidth = AIRBORNE_CENTRE_WIDTH,
        startClock = grid.departurePhoneClock,
        endClock = grid.arrivalPhoneClock,
        zone = grid.phoneZone,
        cellChars = FLIGHT_NOTE_CELL_CHARS_AIRBORNE,
    )
}

@Composable
private fun AirborneCardWide(
    modifier: GlanceModifier,
    airport: AirportSnapshot,
    extras: WidgetExtras,
    cardSize: FlightCardSize,
) {
    val scale = extras.textScale
    val grid = flightAirborneGrid(
        airport = airport,
        nowEpochMillis = extras.nowEpochMillis,
        departureZones = countryZoneCandidates(airport.status?.departureCountry),
        arrivalZones = countryZoneCandidates(airport.status?.arrivalCountry),
    )
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(modifier = modifier) {
        FlightGridRow(
            start = { FlightCardText(grid.designator, 16, scale, onSurface, FontWeight.Medium) },
            end = { FlightStatusChipText(grid.chip, scale) },
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        FlightGridRow(
            start = { FlightCardText(grid.originCode, 22, scale, onSurface, FontWeight.Bold) },
            end = { FlightCardText(grid.destinationCode, 22, scale, onSurface, FontWeight.Bold) },
            centre = { FlightProgressBarRow(grid.progress, grid.percentLabel, scale, cellSize = 12) },
            centreWidth = AIRBORNE_CENTRE_WIDTH,
        )
        FlightGridRow(
            start = {
                if (cardSize == FlightCardSize.WIDE) {
                    grid.departedLabelWide?.let { FlightCardText(it, 11, scale, muted) }
                } else {
                    grid.departedLabel?.let {
                        FlightClockCell(
                            oneLine = it,
                            clock = grid.departedClock ?: it,
                            zone = grid.departureZone,
                            size = 11,
                            cellChars = FLIGHT_NOTE_CELL_CHARS_AIRBORNE,
                            textScale = scale,
                            color = muted,
                            alignEnd = false,
                        )
                    }
                }
            },
            end = {
                if (cardSize == FlightCardSize.WIDE) {
                    FlightCardText(grid.arrivalZoneTimeWide, 16, scale, onSurface, FontWeight.Medium)
                } else {
                    FlightClockCell(
                        oneLine = grid.arrivalZoneTime,
                        clock = grid.arrivalTime,
                        zone = grid.arrivalZone,
                        size = 16,
                        cellChars = FLIGHT_CLOCK_CELL_CHARS_AIRBORNE,
                        textScale = scale,
                        color = onSurface,
                        alignEnd = true,
                        weight = FontWeight.Medium,
                    )
                }
            },
            centre = { FlightCardText(grid.remaining, 14, scale, onSurface, FontWeight.Bold) },
            centreWidth = AIRBORNE_CENTRE_WIDTH,
        )
        when (cardSize) {
            // The tall card gets the scheduled row, then the distance pair on its own line, then
            // the vectors and the squawk. The medium card has room for one line under the scheduled
            // row, so speed, altitude and the distance pair share it - dropping speed and altitude
            // there would have shown less on the 4x4 than the 4x2 already shows.
            FlightCardSize.TALL -> Column(modifier = GlanceModifier.fillMaxWidth()) {
                AirborneScheduledRow(grid, scale)
                AirbornePhoneTimeRow(grid, scale)
                grid.distanceProgress?.let { FlightCardText(it, 11, scale, muted) }
                grid.telemetryDetail?.let { FlightCardText(it, 11, scale, muted) }
            }
            FlightCardSize.MEDIUM -> Column(modifier = GlanceModifier.fillMaxWidth()) {
                AirborneScheduledRow(grid, scale)
                AirbornePhoneTimeRow(grid, scale)
                grid.telemetryLive?.let { FlightCardText(it, 11, scale, muted) }
            }
            // The arrival delay rides the telemetry row's right cell: it costs no height and still
            // sits one row under the clock it moved.
            else -> FlightGridRow(
                start = { grid.telemetry?.let { FlightCardText(it, 11, scale, muted) } },
                end = {
                    grid.arrivalDelay?.let {
                        FlightCardText(it, 11, scale, GlanceTheme.colors.tertiary, FontWeight.Medium)
                    }
                },
            )
        }
        if (cardSize == FlightCardSize.WIDE) {
            FlightCardText(flightCardGroundLine(airport), 12, scale, onSurface, FontWeight.Medium)
        } else {
            FlightGroundBlock(airport, scale)
        }
        when (cardSize) {
            FlightCardSize.TALL -> FlightCardFacts(airport, scale)
            FlightCardSize.MEDIUM -> FlightMediumFacts(airport, grid.bookingLine, scale)
            else -> FlightAircraftRow(airport.status, grid.bookingLine, scale)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        FlightCardActionRow(airport, flightCardFooter(airport), scale)
    }
}

@Composable
private fun AirborneCardSmall(
    modifier: GlanceModifier,
    airport: AirportSnapshot,
    extras: WidgetExtras,
) {
    val scale = extras.textScale
    val grid = flightAirborneGrid(airport, extras.nowEpochMillis)
    Column(modifier = modifier.fillMaxSize()) {
        FlightCardText(grid.remaining, 14, scale, GlanceTheme.colors.onSurface, FontWeight.Bold)
        FlightProgressBarRow(grid.progress, percentLabel = null, textScale = scale, cellSize = 11)
        FlightCardText(grid.smallRoute, 10, scale, GlanceTheme.colors.onSurfaceVariant)
        Spacer(modifier = GlanceModifier.defaultWeight())
        FlightCardActionRow(airport, grid.arrivalBelt, scale)
    }
}

/**
 * Card-surface pill row inside a commute window: Leave by, Best, and Ride, in that order,
 * flowing left to right and wrapping onto further rows when the available width fills up
 * (owner decision 2). Glance has no flow layout, so the wrap is computed deterministically by
 * [commutePillRows] from an estimated pill width rather than real measurement.
 */
@Composable
private fun CommutePillRow(extras: WidgetExtras) {
    val row = extras.commutePillRow
    val pills = buildList {
        row.leaveByMinuteOfDay?.let { add(CommutePill(formatLeaveByLine(it), CommutePillKind.LEAVE_BY)) }
        row.bestLine?.let { add(CommutePill(it, CommutePillKind.BEST)) }
        row.rideDirection?.let { add(CommutePill(ridePillLabel(it), CommutePillKind.RIDE)) }
    }
    val rows = commutePillRows(pills, CARD_PILL_ROW_WIDTH_DP, extras.textScale)
    Column {
        rows.forEachIndexed { rowIndex, rowPills ->
            if (rowIndex > 0) {
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                rowPills.forEachIndexed { pillIndex, pill ->
                    if (pillIndex > 0) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                    }
                    CommutePillChrome(pill.kind, row, extras)
                }
            }
        }
    }
}

/** Renders one packed pill's real chrome by kind, reading back from [row]. Only Ride is clickable. */
@Composable
private fun CommutePillChrome(kind: CommutePillKind, row: CommutePillRowContent, extras: WidgetExtras) {
    when (kind) {
        CommutePillKind.LEAVE_BY ->
            LeaveByPill(row.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, extras.textScale, cardStyle = true)
        CommutePillKind.BEST ->
            MapTextPill(row.bestLine!!, extras.textScale, cardStyle = true)
        CommutePillKind.RIDE ->
            RidePill(row.rideDirection!!, row.rideFailed, extras.textScale, cardStyle = true)
        CommutePillKind.TO_AIRPORT -> Unit
    }
}

@Composable
private fun RoutedInfo(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    style: InfoStyle,
) {
    val title = when (snapshot.mode) {
        SnapshotMode.COMMUTE -> destinationDisplayLabel(snapshot.direction, snapshot.destinationLabel)
        SnapshotMode.CALENDAR_EVENT -> calendarEventTitle(snapshot.destinationLabel)
        SnapshotMode.CALENDAR_EMPTY -> calendarEventTitle(snapshot.destinationLabel)
        SnapshotMode.AIRPORT -> airportMapTitleLines(snapshot).first
    }
    if (style.inlineEta) {
        InlineTitleEta(title, snapshot, extras, accent, style.etaFontSize)
    } else {
        DestinationLine(title, style.destinationFontSize, snapshot.lastFetchFailed)
        EtaText(snapshot, extras, accent, style.etaFontSize)
    }
    // The airport's own name never shares line 1 with the flight: at 2x4 that ellipsized the
    // destination away ("SQ509 to Sin..."). SMALL keeps line 1 alone, as it has no room for both.
    if (snapshot.mode == SnapshotMode.AIRPORT && style.showExtendedCaptions) {
        airportMapTitleLines(snapshot).second?.let {
            Text(
                text = it,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = scaledSp(10, extras.textScale),
                ),
                maxLines = 1,
            )
        }
    }
    val countdownMinutes = eventCountdownMinutes(snapshot.eventStartEpochMillis, extras.nowEpochMillis)
    if (shouldShowEventCountdown(snapshot, style.showExtendedCaptions) && countdownMinutes != null) {
        Text(
            text = formatCountdown(countdownMinutes),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(10, extras.textScale),
            ),
            maxLines = 1,
        )
    }
    if (shouldShowTodayBrief(snapshot, style.showExtendedCaptions, extras.sleepBriefEnabled)) {
        val truncation = pickBriefTruncation(
            isLarge = style.fullMorningBrief,
            hasSleepPrefix = extras.sleepBriefEnabled && snapshot.sleepEstimateMinutes != null,
        )
        val brief = buildBriefLine(
            sleepEstimateMinutes = snapshot.sleepEstimateMinutes,
            shortSleepDay = snapshot.shortSleepDay,
            sleepBriefEnabled = extras.sleepBriefEnabled,
            meetingCount = snapshot.todayEventCount,
            firstStartEpochMillis = snapshot.todayFirstEventStartEpochMillis,
            truncation = truncation,
        )
        if (brief != null) {
            Text(
                text = brief,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = scaledSp(10, extras.textScale),
                ),
                maxLines = 1,
            )
        }
    }
    if (
        style.showHealthLine &&
        snapshot.mode == SnapshotMode.COMMUTE &&
        extras.healthLineLabel != null
    ) {
        Text(
            text = extras.healthLineLabel,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(10, extras.textScale),
            ),
            maxLines = 1,
        )
    }
    if (style.showDistance && snapshot.distanceMeters > 0L) {
        Text(
            text = formatDistanceKm(snapshot.distanceMeters),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(11, extras.textScale),
            ),
            maxLines = 1,
        )
    }
    if (shouldShowRoutedCaption(snapshot, style.showRoutedCaption)) {
        RoutedCaption(extras.textScale)
    }
    if (style.showLeaveBy && shouldShowLeaveBy(snapshot, extras.leaveByEnabled, extras.rideReached)) {
        LeaveByLine(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, style.leaveByFontSize)
    }
    if (style.showBestDeparture && extras.bestDepartureLine != null) {
        Text(
            text = extras.bestDepartureLine,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(10, extras.textScale),
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun InlineTitleEta(
    title: String,
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    fontSize: TextUnit,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        EtaText(snapshot, extras, accent, fontSize)
        if (snapshot.lastFetchFailed) {
            WarningGlyph()
        }
    }
}

@Composable
private fun DestinationLine(text: String, fontSize: TextUnit, showWarning: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = fontSize,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (showWarning) {
            WarningGlyph()
        }
    }
}

@Composable
private fun EtaText(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    fontSize: TextUnit,
) {
    // Traffic-colored ETA plus the traffic dot: the dot keeps congestion legible even while the
    // text carries a state (pending alpha, stale grey), and vice versa.
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            text = formatEta(snapshot.durationSeconds),
            style = TextStyle(
                color = etaColorProvider(accent, extras, snapshot.fetchedAtEpochMillis),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.width(5.dp))
        Box(
            modifier = GlanceModifier
                .size(9.dp)
                .cornerRadius(5.dp)
                .background(accent),
        ) {}
    }
}

@Composable
private fun etaColorProvider(
    accent: Color,
    extras: WidgetExtras,
    fetchedAtEpochMillis: Long,
): ColorProvider {
    return when (
        etaDisplayState(
            extras.refreshingSince,
            fetchedAtEpochMillis,
            extras.nowEpochMillis,
        )
    ) {
        EtaDisplayState.PENDING -> ColorProvider(accent.copy(alpha = ETA_PENDING_ALPHA))
        EtaDisplayState.STALE -> GlanceTheme.colors.onSurfaceVariant
        EtaDisplayState.FRESH -> ColorProvider(accent)
    }
}

@Composable
private fun LeaveByLine(minuteOfDay: Int, nowMinuteOfDay: Int, fontSize: TextUnit) {
    val late = isLeaveByPast(minuteOfDay, nowMinuteOfDay)
    Text(
        text = formatLeaveByLine(minuteOfDay),
        style = TextStyle(
            color = if (late) ColorProvider(LEAVE_BY_LATE_COLOR) else GlanceTheme.colors.onSurfaceVariant,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

/** Opaque leave-by pill for the WIDE map overlay, legible over map tiles. */
@Composable
private fun LeaveByPill(
    minuteOfDay: Int,
    nowMinuteOfDay: Int,
    textScale: Float = 1f,
    cardStyle: Boolean = false,
) {
    val late = isLeaveByPast(minuteOfDay, nowMinuteOfDay)
    // Card pills keep a taller box for the tap target, map pills stay compact over tiles.
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .let { base ->
                if (cardStyle) {
                    base.height(36.dp).padding(horizontal = 10.dp)
                } else {
                    base.padding(horizontal = 8.dp, vertical = 3.dp)
                }
            },
        contentAlignment = if (cardStyle) Alignment.Center else Alignment.TopStart,
    ) {
        Text(
            text = formatLeaveByLine(minuteOfDay),
            style = TextStyle(
                color = if (late) ColorProvider(LEAVE_BY_LATE_COLOR) else GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Opaque generic text pill for the map overlay stack (e.g. "Best: 3:30 pm"). */
@Composable
private fun MapTextPill(text: String, textScale: Float = 1f, cardStyle: Boolean = false) {
    // Card pills keep a taller box for the tap target, map pills stay compact over tiles.
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .let { base ->
                if (cardStyle) {
                    base.height(36.dp).padding(horizontal = 10.dp)
                } else {
                    base.padding(horizontal = 8.dp, vertical = 3.dp)
                }
            },
        contentAlignment = if (cardStyle) Alignment.Center else Alignment.TopStart,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Opaque ride pill, same chrome as [MapTextPill]. The tap writes ride state and the refresh fetches the route. Red means the last attempt failed. */
@Composable
private fun RidePill(
    direction: Direction,
    failed: Boolean,
    textScale: Float = 1f,
    cardStyle: Boolean,
) {
    // Card pills keep a taller box for the tap target, map pills stay compact over tiles.
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .let { base ->
                if (cardStyle) {
                    base.height(36.dp).padding(horizontal = 10.dp)
                } else {
                    base.padding(horizontal = 8.dp, vertical = 3.dp)
                }
            }
            .clickable(rideTapAction(direction)),
        contentAlignment = if (cardStyle) Alignment.Center else Alignment.TopStart,
    ) {
        Text(
            text = ridePillLabel(direction),
            style = TextStyle(
                color = if (failed) ColorProvider(LEAVE_BY_LATE_COLOR) else GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Opaque reached pill, same chrome as [MapTextPill]. Map-only surface: Reached never renders on a
 * card. [action] is what the tap runs - the commute ride's [reachedTapAction], or a routed
 * event's [eventReachedTapAction], which closes that event instance instead.
 */
@Composable
private fun ReachedPill(action: Action, textScale: Float = 1f) {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(action),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = REACHED_PILL_LABEL,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Airport map-pill row content: which of the three packed pills are visible and their text/phase. */
internal data class AirportPillRowContent(
    val leaveByMinuteOfDay: Int?,
    val bestLine: String?,
    val toAirportPhase: AirportPhase?,
)

/** Per-char estimated width for the airport map pill row (narrower surface than the [CARD_PILL_ROW_WIDTH_DP] card row). */
internal const val AIRPORT_PILL_ROW_WIDTH_DP = 200f

/** The same row on the 2x2 widget, where every pill gets its own line at the default text scale. */
internal const val SMALL_AIRPORT_PILL_ROW_WIDTH_DP = 100f

internal fun airportPills(row: AirportPillRowContent): List<CommutePill> = buildList {
    row.leaveByMinuteOfDay?.let { add(CommutePill(formatLeaveByLine(it), CommutePillKind.LEAVE_BY)) }
    row.bestLine?.let { add(CommutePill(it, CommutePillKind.BEST)) }
    row.toAirportPhase?.let { add(CommutePill(toAirportPillLabel(it), CommutePillKind.TO_AIRPORT)) }
}

/** To Airport pill label: "To Airport" until the tap starts the ride, then "Riding". */
internal fun toAirportPillLabel(phase: AirportPhase): String = when (phase) {
    AirportPhase.OFFERED -> "To Airport"
    AirportPhase.RIDING -> "Riding"
    AirportPhase.LAYOVER -> "To Airport"
    AirportPhase.REACHED -> "To Airport"
}

/** "Best 3:30 pm" or "Best 3:30 pm · 45 min" when a travel estimate is known; null when there is no best-departure estimate. */
internal fun airportBestPillText(
    bestDepartureMillis: Long?,
    bestTravelMinutes: Int?,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (bestDepartureMillis == null) return null
    val time = formatEventClockTime(bestDepartureMillis, zone)
    return if (bestTravelMinutes != null) "Best $time · $bestTravelMinutes min" else "Best $time"
}

/**
 * True when the map's Reached pill is on screen. RIDING always shows it: the owner asked to be able
 * to end the map and open the full flight card at any point in the drive, not only once the
 * arrive-by target passed or the refresher put the device inside the airport's radius. OFFERED keeps
 * the earned rule, and the card phases show none at all - the card carries its own pill.
 */
internal fun airportShowsReachedPill(airport: AirportSnapshot, nowEpochMillis: Long): Boolean {
    if (airportShowsFlightCard(airport.phase)) return false
    if (airport.phase == AirportPhase.RIDING) return true
    return airport.reachedOffered || nowEpochMillis >= airport.arriveByMillis
}

/** Renders one packed airport pill's real chrome by kind, reading back from [row]. Only To Airport is clickable. */
@Composable
private fun AirportPillChrome(kind: CommutePillKind, row: AirportPillRowContent, extras: WidgetExtras) {
    when (kind) {
        CommutePillKind.LEAVE_BY ->
            LeaveByPill(row.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, extras.textScale)
        CommutePillKind.BEST ->
            MapTextPill(row.bestLine!!, extras.textScale)
        CommutePillKind.TO_AIRPORT ->
            ToAirportPill(row.toAirportPhase!!, extras.textScale)
        CommutePillKind.RIDE -> Unit
    }
}

/** Opaque To Airport pill, same chrome as [MapTextPill]. The tap starts the ride and launches driving navigation; the refresh then returns the pills' updated phase. */
@Composable
private fun ToAirportPill(phase: AirportPhase, textScale: Float = 1f, cardStyle: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .let { base ->
                if (cardStyle) {
                    base.height(CARD_PILL_HEIGHT_DP.dp).padding(horizontal = 12.dp)
                } else {
                    base.padding(horizontal = 8.dp, vertical = 3.dp)
                }
            }
            .clickable(airportRideTapAction()),
        contentAlignment = if (cardStyle) Alignment.Center else Alignment.TopStart,
    ) {
        Text(
            text = toAirportPillLabel(phase),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Opaque airport Reached pill, same chrome as [ReachedPill]. The tap replaces the map with the flight card. */
@Composable
private fun AirportReachedPill(textScale: Float = 1f, cardStyle: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .let { base ->
                if (cardStyle) {
                    base.height(CARD_PILL_HEIGHT_DP.dp).padding(horizontal = 12.dp)
                } else {
                    base.padding(horizontal = 8.dp, vertical = 3.dp)
                }
            }
            .clickable(airportReachedTapAction()),
        contentAlignment = if (cardStyle) Alignment.Center else Alignment.TopStart,
    ) {
        Text(
            text = REACHED_PILL_LABEL,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(12, textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Airport map-pill row: Leave by, Best and To Airport, packed left to right and wrapping with
 * [commutePillRows] exactly like the card commute row, plus a trailing Reached pill once
 * [airportShowsReachedPill] is true. Leave by and Best come from [snapshot] and [airport] only
 * when [airportShowsLeaveByAndBest] allows them for the current phase.
 * [compact] is the 2x2 surface: the row packs against [SMALL_AIRPORT_PILL_ROW_WIDTH_DP] and drops
 * Best, which is the only pill here that drives nothing.
 */
@Composable
private fun AirportMapPillOverlay(
    snapshot: CommuteSnapshot,
    airport: AirportSnapshot,
    extras: WidgetExtras,
    compact: Boolean = false,
) {
    val showLeaveByAndBest = airportShowsLeaveByAndBest(airport.phase)
    val row = AirportPillRowContent(
        leaveByMinuteOfDay = snapshot.leaveByMinuteOfDay.takeIf { showLeaveByAndBest },
        bestLine = airportBestPillText(airport.bestDepartureMillis, airport.bestTravelMinutes)
            .takeIf { showLeaveByAndBest && !compact },
        toAirportPhase = airport.phase.takeIf { airportShowsToAirportPill(airport.phase) },
    )
    val pills = airportPills(row)
    val rowWidthDp = if (compact) SMALL_AIRPORT_PILL_ROW_WIDTH_DP else AIRPORT_PILL_ROW_WIDTH_DP
    val rows = commutePillRows(pills, rowWidthDp, extras.textScale)
    val showReachedPill = airportShowsReachedPill(airport, extras.nowEpochMillis)
    Column(modifier = GlanceModifier.padding(6.dp)) {
        rows.forEachIndexed { rowIndex, rowPills ->
            if (rowIndex > 0) {
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                rowPills.forEachIndexed { pillIndex, pill ->
                    if (pillIndex > 0) {
                        Spacer(modifier = GlanceModifier.width(4.dp))
                    }
                    AirportPillChrome(pill.kind, row, extras)
                }
            }
        }
        if (showReachedPill) {
            if (rows.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
            AirportReachedPill(extras.textScale)
        }
    }
}

@Composable
private fun HealthPillStack(
    pills: List<NudgeCandidate>,
    extras: WidgetExtras,
    expandMorningLabel: Boolean,
) {
    val colors = extras.healthColors ?: return
    // Owner ruling 2026-08-31: mirror the commute pill pair exactly - side by side in a Row
    // (6dp outer padding, 4dp between), each pill sized by padding like LeaveByPill.
    Row(
        modifier = GlanceModifier.padding(6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        pills.forEachIndexed { index, candidate ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            HealthActionChrome(
                candidate = candidate,
                extras = extras,
                colors = colors,
                expandMorningLabel = expandMorningLabel,
                minHeightDp = 0,
                mapStyle = true,
            )
        }
    }
}

@Composable
private fun HealthChipRow(
    pills: List<NudgeCandidate>,
    extras: WidgetExtras,
    expandMorningLabel: Boolean,
) {
    val colors = extras.healthColors ?: return
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        pills.forEachIndexed { index, candidate ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            HealthActionChrome(
                candidate = candidate,
                extras = extras,
                colors = colors,
                expandMorningLabel = expandMorningLabel,
                minHeightDp = 48,
                mapStyle = false,
            )
        }
    }
}

/**
 * Health action control. Owner ruling 2026-08-31: on maps it reuses the leave-by/best pill
 * colors verbatim (opaque surfaceVariant fill, onSurfaceVariant text, corner 10dp, no border);
 * on cards it draws NO fill at all so the chip interior is exactly the card background - any
 * translucent fill would stack on the background and read as more opaque than it. Demotion is
 * text alpha only. Never traffic-tinted, never inherits pending/stale ETA treatment.
 */
@Composable
private fun HealthActionChrome(
    candidate: NudgeCandidate,
    extras: WidgetExtras,
    colors: HealthChromeColors,
    expandMorningLabel: Boolean,
    minHeightDp: Int,
    mapStyle: Boolean,
) {
    val demoted = candidate.demoted
    val textColor = when {
        mapStyle && demoted -> colors.mapTextDemoted
        mapStyle -> GlanceTheme.colors.onSurfaceVariant
        demoted -> colors.cardTextDemoted
        else -> GlanceTheme.colors.onSurface
    }
    val glyph = healthGlyph(candidate.kind)
    val label = mapHealthLabel(candidate.kind, candidate.label, expandMorningLabel)
    val action = healthNudgeClickAction(candidate)
    // Map pills size themselves by padding exactly like LeaveByPill (8dp x 3dp); card chips keep
    // an explicit min height for a comfortable 48dp tap target.
    val chrome = GlanceModifier
        .let { base -> if (mapStyle) base.background(GlanceTheme.colors.surfaceVariant) else base }
        .cornerRadius(10.dp)
        .let { base -> if (mapStyle) base.padding(horizontal = 8.dp, vertical = 3.dp) else base.height(minHeightDp.dp).padding(horizontal = 8.dp) }
        .let { base -> if (action != null) base.clickable(action) else base }
    Box(modifier = chrome, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            if (glyph.isNotEmpty()) {
                Text(
                    text = glyph,
                    style = TextStyle(
                        color = textColor,
                        fontSize = scaledSp(12, extras.textScale),
                    ),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            Text(
                text = label,
                style = TextStyle(
                    color = textColor,
                    fontSize = scaledSp(12, extras.textScale),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Map-overlay row for custom pill reminders; stacked with [HealthPillStack], never merged into it (separate feature, separate cap). */
@Composable
private fun CustomPillMapRow(content: CustomPillRowContent, extras: WidgetExtras) {
    if (content.isEmpty) return
    Row(
        modifier = GlanceModifier.padding(6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        content.occurrences.forEachIndexed { index, occurrence ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            CustomPillChrome(occurrence = occurrence, extras = extras, mapStyle = true)
        }
        if (content.overflowLabel != null) {
            if (content.occurrences.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            CustomPillOverflowChrome(label = content.overflowLabel, extras = extras, mapStyle = true)
        }
    }
}

/** Card-footer row for custom pill reminders, sized like [HealthChipRow]. */
@Composable
private fun CustomPillChipRow(content: CustomPillRowContent, extras: WidgetExtras) {
    if (content.isEmpty) return
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        content.occurrences.forEachIndexed { index, occurrence ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            CustomPillChrome(occurrence = occurrence, extras = extras, mapStyle = false)
        }
        if (content.overflowLabel != null) {
            if (content.occurrences.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            CustomPillOverflowChrome(label = content.overflowLabel, extras = extras, mapStyle = false)
        }
    }
}

/**
 * One custom pill's chrome - text-only (no glyph), same opaque/no-fill split as
 * [HealthActionChrome] between map and card surfaces. Demotion (carry-over) is decided purely
 * from [CustomPillOccurrence.active] via [customPillDemotionAlpha], which has no pending/stale
 * input at all, so this element structurally cannot inherit the ETA's pending alpha or stale
 * grey treatment (repo invariant).
 */
@Composable
private fun CustomPillChrome(
    occurrence: CustomPillOccurrence,
    extras: WidgetExtras,
    mapStyle: Boolean,
) {
    val colors = extras.healthColors ?: return
    val demoted = customPillDemotionAlpha(occurrence.active) < 1f
    val textColor = when {
        mapStyle && demoted -> colors.mapTextDemoted
        mapStyle -> GlanceTheme.colors.onSurfaceVariant
        demoted -> colors.cardTextDemoted
        else -> GlanceTheme.colors.onSurface
    }
    val chrome = GlanceModifier
        .let { base -> if (mapStyle) base.background(GlanceTheme.colors.surfaceVariant) else base }
        .cornerRadius(10.dp)
        .let { base ->
            if (mapStyle) {
                base.padding(horizontal = 8.dp, vertical = 3.dp)
            } else {
                base.height(48.dp).padding(horizontal = 8.dp)
            }
        }
        .clickable(customPillTapAction(occurrence))
    Box(modifier = chrome, contentAlignment = Alignment.Center) {
        Text(
            text = customPillDisplayLabel(occurrence.label),
            style = TextStyle(
                color = textColor,
                fontSize = scaledSp(12, extras.textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Inert "+N" overflow indicator - always demoted, never clickable. */
@Composable
private fun CustomPillOverflowChrome(label: String, extras: WidgetExtras, mapStyle: Boolean) {
    val colors = extras.healthColors ?: return
    val textColor = if (mapStyle) colors.mapTextDemoted else colors.cardTextDemoted
    val chrome = GlanceModifier
        .let { base -> if (mapStyle) base.background(GlanceTheme.colors.surfaceVariant) else base }
        .cornerRadius(10.dp)
        .let { base ->
            if (mapStyle) {
                base.padding(horizontal = 8.dp, vertical = 3.dp)
            } else {
                base.height(48.dp).padding(horizontal = 8.dp)
            }
        }
    Box(modifier = chrome, contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = TextStyle(
                color = textColor,
                fontSize = scaledSp(12, extras.textScale),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun AlarmLine(text: String, textScale: Float) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = scaledSp(11, textScale),
        ),
        maxLines = 1,
    )
}

/** FIX-9: a "Routed" caption under/beside a CALENDAR_EVENT title when it won the 30-minute located-event preference. */
@Composable
private fun RoutedCaption(textScale: Float = 1f) {
    Text(
        text = "Routed",
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = scaledSp(10, textScale),
        ),
        maxLines = 1,
    )
}

@Composable
private fun MapPane(
    snapshot: CommuteSnapshot,
    bitmap: Bitmap?,
    modifier: GlanceModifier,
) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Route map",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    val lines = mapAreaPlaceholderLines(snapshot)
    Box(
        modifier = modifier.background(GlanceTheme.colors.surfaceVariant).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text(
                text = if (snapshot.mode == SnapshotMode.COMMUTE) "🗺" else "📅",
                style = TextStyle(fontSize = 22.sp),
            )
            lines.forEachIndexed { index, line ->
                Spacer(modifier = GlanceModifier.height(if (index == 0) 4.dp else 2.dp))
                Text(
                    text = line,
                    style = if (index == 0) {
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    },
                    maxLines = if (index == 0) 2 else 1,
                )
            }
        }
    }
}

@Composable
private fun WarningGlyph() {
    Text(
        text = "⚠",
        style = TextStyle(
            color = GlanceTheme.colors.error,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = GlanceModifier.padding(start = 4.dp),
    )
}

private fun loadMapBitmap(path: String?): Bitmap? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = mapInSampleSize(bounds.outWidth, bounds.outHeight, MAP_DECODE_MAX_EDGE)
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

internal fun launchNavigation(context: Context, lat: Double, lng: Double, modeChar: String) {
    val coord = String.format(Locale.US, "%f,%f", lat, lng)
    val mapsIntent = Intent(Intent.ACTION_VIEW, "google.navigation:q=$coord&mode=$modeChar".toUri()).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(mapsIntent)
    } catch (_: ActivityNotFoundException) {
        val geoIntent = Intent(Intent.ACTION_VIEW, "geo:$coord".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(geoIntent)
        } catch (_: ActivityNotFoundException) {
            // No maps app installed.
        }
    }
}

/** [rideReached] hides the COMMUTE surface's leave-by after a Reached tap; an event snapshot's leave-by describes the event, not the consumed commute, so it survives. */
internal fun shouldShowLeaveBy(snapshot: CommuteSnapshot, leaveByEnabled: Boolean, rideReached: Boolean): Boolean {
    if (snapshot.mode == SnapshotMode.COMMUTE && rideReached) {
        return false
    }
    return (snapshot.mode == SnapshotMode.COMMUTE || snapshot.mode == SnapshotMode.CALENDAR_EVENT) &&
        leaveByEnabled &&
        snapshot.leaveByMinuteOfDay != null
}

/** What a routed event's Reached pill needs to close that instance: the same pair the stored closed-event key is built from. */
internal data class EventReachedTarget(val startEpochMillis: Long, val title: String)

/**
 * The routed calendar event's Reached pill target, or null when no pill is offered. Only a
 * [SnapshotMode.CALENDAR_EVENT] snapshot qualifies, and only when it carries both the event start
 * and a non-blank title - without either the tap could not name the instance to close. The commute
 * map and airport mode have their own Reached pills and are never offered this one.
 */
internal fun eventReachedTarget(snapshot: CommuteSnapshot): EventReachedTarget? {
    if (snapshot.mode != SnapshotMode.CALENDAR_EVENT) {
        return null
    }
    val start = snapshot.eventStartEpochMillis ?: return null
    val title = snapshot.destinationLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return EventReachedTarget(start, title)
}

/**
 * FIX-9: the "Routed" caption only ever applies to a [SnapshotMode.CALENDAR_EVENT] snapshot whose
 * event won the 30-minute located-event preference, and only on sizes that opt in
 * ([InfoStyle.showRoutedCaption] - WIDE and LARGE; SMALL skips it for space).
 */
internal fun shouldShowRoutedCaption(snapshot: CommuteSnapshot, captionAllowedForSize: Boolean): Boolean {
    return captionAllowedForSize && snapshot.mode == SnapshotMode.CALENDAR_EVENT && snapshot.routedOverEarlier
}

/** WIDE/LARGE event-countdown caption under a CALENDAR_EVENT or AIRPORT title; omit when started or start is null. */
internal fun shouldShowEventCountdown(snapshot: CommuteSnapshot, captionAllowedForSize: Boolean): Boolean {
    return captionAllowedForSize &&
        (snapshot.mode == SnapshotMode.CALENDAR_EVENT || snapshot.mode == SnapshotMode.AIRPORT)
}

/**
 * Morning-brief caption on a To Work COMMUTE snapshot when today's remaining event count is
 * populated and positive. Size-gated the same way as [shouldShowRoutedCaption] (WIDE/LARGE).
 */
internal fun shouldShowTodayBrief(
    snapshot: CommuteSnapshot,
    captionAllowedForSize: Boolean,
    sleepBriefEnabled: Boolean = false,
): Boolean {
    val count = snapshot.todayEventCount
    val hasMeetings = count != null && count > 0
    val hasSleep = sleepBriefEnabled && snapshot.sleepEstimateMinutes != null
    return captionAllowedForSize &&
        snapshot.mode == SnapshotMode.COMMUTE &&
        snapshot.direction == Direction.TO_WORK &&
        (hasMeetings || hasSleep)
}

/**
 * True when the calendar-empty snapshot carries at least one upcoming event for the "Next up"
 * section, counted after [upcomingEventsWithoutPreview] has removed the previewed flight's own
 * calendar entry - a card whose only upcoming event IS the flight shows the flight row alone
 * rather than a "Next up" heading over an empty list.
 */
internal fun isWindDown(snapshot: CommuteSnapshot): Boolean {
    return upcomingEventsWithoutPreview(snapshot.upcomingEvents, snapshot.flightPreview).isNotEmpty()
}

/**
 * The "Next up" entries with the previewed flight's own calendar event dropped, matched on title
 * and start time (the flight row is built from the same calendar entry, so a flight inside the next
 * seven days would otherwise be listed twice on one card).
 */
internal fun upcomingEventsWithoutPreview(
    upcomingEvents: List<UpcomingEvent>,
    preview: FlightPreview?,
): List<UpcomingEvent> {
    if (preview == null) {
        return upcomingEvents
    }
    return upcomingEvents.filterNot {
        it.title == preview.flight.title && it.startEpochMillis == preview.flight.departureMillis
    }
}

/**
 * The departure instant the flight row believes: the live schedule when a status came back, else
 * the calendar event's own start. Line 1's date and line 2's clock time both read this one value,
 * so the row can never date a flight on one day and time it on another.
 */
internal fun flightPreviewDepartureMillis(preview: FlightPreview): Long =
    preview.status?.departureScheduledUtcMillis ?: preview.flight.departureMillis

private val FLIGHT_PREVIEW_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/**
 * Line 1: "Tue 13 Oct · SQ509 BLR to SIN". The date prefix is what the merged block drops (its
 * countdown already says when); the identity half is [flightPreviewIdentity], shared by both.
 */
internal fun flightPreviewTitleLine(preview: FlightPreview, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(flightPreviewDepartureMillis(preview))
        .atZone(zone)
        .format(FLIGHT_PREVIEW_DATE_FORMAT)
    return "$date · ${flightPreviewIdentity(preview)}"
}

/**
 * Line 2: "Departs 11:35 am · T2 · Gate 5 · PNR ABC123 · +6 min". The clock time is the live
 * schedule when known and the calendar's own start otherwise; a moved estimate is appended to it
 * only when it differs by a full minute or more, the way [flightCardDepartureLine] treats it. The
 * trailing segment is the departure delay when there is one, and otherwise the status word, but
 * only while that word says something beyond the default "scheduled".
 */
internal fun flightPreviewDetailLine(
    preview: FlightPreview,
    zone: ZoneId = ZoneId.systemDefault(),
    departureZones: List<ZoneId> = emptyList(),
): String {
    val flight = preview.flight
    val status = preview.status
    val scheduledMillis = flightPreviewDepartureMillis(preview)
    val estimated = status?.departureEstimatedUtcMillis
        ?.takeIf { abs(it - scheduledMillis) >= 60_000L }
        ?.let { " -> ${formatAirportClockTime(it, status.departureUtcOffsetMinutes, zone)}" }
        .orEmpty()
    val delay = status?.departureDelayMinutes?.takeIf { abs(it) >= 1 }?.let { formatDelayMinutes(it) }
    val statusWord = status?.status
        ?.takeIf { !it.equals("scheduled", ignoreCase = true) }
        ?.let { capitaliseStatusWord(it) }
    // The label goes on the last clock of the pair, not on both: a moved estimate is in the same
    // zone as the time it moved from, and naming it twice on one segment is noise.
    val airportZoneLabel = status?.departureUtcOffsetMinutes
        ?.let { " ${zoneLabel(it, departureZones, scheduledMillis)}" }
        .orEmpty()
    return listOfNotNull(
        "Departs ${flightDepartureClock(scheduledMillis, status, flight, zone)}$estimated$airportZoneLabel",
        phoneClockLine(scheduledMillis, status?.departureUtcOffsetMinutes, zone),
        status?.let { formatTerminalGateOrTba(it.departureTerminal ?: flight.departureTerminal, it.departureGate, it.source) }
            ?: formatTerminalGate(flight.departureTerminal, null),
        flight.confirmationNumber?.let { "PNR $it" },
        flight.seat?.let { "Seat $it" },
        delay ?: statusWord,
    ).joinToString(" · ")
}

/**
 * Line 3, or null when the row has nothing to say about its own data: a timetable-only status
 * carries no gate or delay and never will until the day itself, a fetch that has only ever failed
 * says so, and a row that has never been fetched at all points at the missing AirLabs key.
 */
internal fun flightPreviewNoteLine(preview: FlightPreview, hasFlightStatusKey: Boolean): String? {
    val status = preview.status
    return when {
        status?.source == "routes" -> "Timetable only"
        preview.error != null && status == null -> "Status unavailable"
        status == null && preview.error == null && !hasFlightStatusKey -> "Add AirLabs key for gate and delays"
        else -> null
    }
}

/**
 * Day-and-time line for one "Next up" entry: "Tomorrow" plus the clock time when [startEpochMillis]
 * falls on [nowEpochMillis]'s local date plus one day, otherwise the short weekday (Locale.US) plus
 * the clock time, e.g. "Thu 10:00 am". Compares local dates, not elapsed duration, so an event just
 * after midnight still reads "Tomorrow" even though it is minutes rather than a full day away.
 */
internal fun formatUpcomingEventLine(
    startEpochMillis: Long,
    nowEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val startDate = Instant.ofEpochMilli(startEpochMillis).atZone(zone).toLocalDate()
    val tomorrow = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate().plusDays(1)
    val time = formatEventClockTime(startEpochMillis, zone)
    return if (startDate == tomorrow) {
        "Tomorrow $time"
    } else {
        "${startDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)} $time"
    }
}

/**
 * Whole minutes until [startEpochMillis], or null when the start is missing, already reached,
 * or less than one full minute away. Used only for positive-minute countdown captions.
 */
internal fun eventCountdownMinutes(startEpochMillis: Long?, nowEpochMillis: Long): Int? {
    if (startEpochMillis == null) return null
    val minutes = ((startEpochMillis - nowEpochMillis) / 60_000L).toInt()
    return minutes.takeIf { it > 0 }
}

/**
 * Three-way ETA treatment: a TAP-pending refresh dims the accent, a settled but
 * old snapshot greys the number, and a fresh settled snapshot keeps full accent.
 * Pending always wins so the two non-fresh states cannot be confused.
 */
internal fun etaDisplayState(
    refreshingSinceEpochMillis: Long?,
    fetchedAtEpochMillis: Long,
    nowEpochMillis: Long,
): EtaDisplayState {
    if (isRefreshingActive(refreshingSinceEpochMillis, nowEpochMillis)) {
        return EtaDisplayState.PENDING
    }
    if (nowEpochMillis - fetchedAtEpochMillis > ETA_STALE_AFTER_MILLIS) {
        return EtaDisplayState.STALE
    }
    return EtaDisplayState.FRESH
}

internal fun destinationDisplayLabel(direction: Direction, destinationLabel: String?): String {
    val trimmed = destinationLabel?.trim().orEmpty()
    return if (trimmed.isNotEmpty()) {
        "To $trimmed"
    } else {
        when (direction) {
            Direction.TO_WORK -> "To Work"
            Direction.TO_HOME -> "To Home"
        }
    }
}

internal fun calendarEventTitle(destinationLabel: String?): String {
    val trimmed = destinationLabel?.trim().orEmpty()
    return trimmed.ifEmpty { "Event" }
}

internal enum class CalendarEmptyCase {
    UNLOCATED_EVENT,
    NONE,
}

internal fun calendarEmptyCase(snapshot: CommuteSnapshot): CalendarEmptyCase {
    if (!snapshot.destinationLabel.isNullOrBlank() && snapshot.eventStartEpochMillis != null) {
        return CalendarEmptyCase.UNLOCATED_EVENT
    }
    return CalendarEmptyCase.NONE
}

/** True when the NONE branch's empty-day text should render: never during wind-down, whose "Next up" section already fills that space. */
internal fun showsCalendarNoneText(windDown: Boolean, case: CalendarEmptyCase): Boolean =
    !windDown && case == CalendarEmptyCase.NONE

/**
 * Owner decision 1: inside a commute window the commute body replaces the wind-down block and
 * the NONE text, but an unlocated event still wins and keeps the plain card.
 */
internal fun showsCommuteWindowBody(inWindow: Boolean, case: CalendarEmptyCase): Boolean {
    return inWindow && case != CalendarEmptyCase.UNLOCATED_EVENT
}

/**
 * Text lines shown in the map pane when a COMMUTE or CALENDAR_EVENT snapshot
 * has no bitmap. First line is a title, the rest are captions.
 *
 * COMMUTE returns empty (glyph only). CALENDAR_EVENT returns the event title
 * and clock time. Leave-by is never included: the info panel always carries it
 * when the advisor is enabled. CALENDAR_EMPTY returns empty because that mode
 * uses a full-width card, not a map pane.
 */
internal fun mapAreaPlaceholderLines(
    snapshot: CommuteSnapshot,
    zone: ZoneId = ZoneId.systemDefault(),
): List<String> {
    return when (snapshot.mode) {
        SnapshotMode.COMMUTE -> emptyList()
        SnapshotMode.CALENDAR_EVENT -> buildList {
            add(calendarEventTitle(snapshot.destinationLabel))
            snapshot.eventStartEpochMillis?.let { add(formatEventClockTime(it, zone)) }
        }
        SnapshotMode.CALENDAR_EMPTY -> emptyList()
        SnapshotMode.AIRPORT -> buildList {
            add(calendarEventTitle(snapshot.destinationLabel))
            snapshot.eventStartEpochMillis?.let { add(formatEventClockTime(it, zone)) }
        }
    }
}

internal fun formatClockTime(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 59)
    val hour24 = clamped / 60
    val minute = clamped % 60
    val hour12 = when (val hour = hour24 % 12) {
        0 -> 12
        else -> hour
    }
    val period = if (hour24 < 12) "am" else "pm"
    return "$hour12:${minute.toString().padStart(2, '0')} $period"
}

internal fun formatLeaveByLine(minuteOfDay: Int): String {
    return "Leave by ${formatClockTime(minuteOfDay)}"
}

internal fun bestDepartureLineText(minuteOfDay: Int): String {
    return "Best: ${formatClockTime(minuteOfDay)}"
}

internal fun formatEventClockTime(eventStartEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val zoned = Instant.ofEpochMilli(eventStartEpochMillis).atZone(zone)
    return formatClockTime(zoned.hour * 60 + zoned.minute)
}

internal fun formatCountdown(minutesUntil: Int): String {
    return "in ${formatCompactHoursMinutes(minutesUntil)}"
}

internal fun formatFreeFor(minutesUntil: Int): String {
    return "Free for ${formatCompactHoursMinutes(minutesUntil)}"
}

internal fun formatTodayBrief(
    count: Int,
    firstStartEpochMillis: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val meetings = if (count == 1) "1 meeting" else "$count meetings"
    if (firstStartEpochMillis == null) return meetings
    return "$meetings · first ${formatEventClockTime(firstStartEpochMillis, zone)}"
}

internal fun formatAlarmLine(triggerTimeEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    return "⏰ Alarm ${formatEventClockTime(triggerTimeEpochMillis, zone)}"
}

/** Allowlist of real clock apps; anything else (routine schedulers, sleep apps) is filtered out. */
internal fun isClockAppAlarm(creatorPackage: String?): Boolean {
    return creatorPackage in setOf(
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.deskclock",
    )
}

private fun formatCompactHoursMinutes(minutesUntil: Int): String {
    val hours = minutesUntil / 60
    val minutes = minutesUntil % 60
    return when {
        hours <= 0 -> "${minutes}m"
        minutes == 0 -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

internal fun isLeaveByPast(leaveByMinuteOfDay: Int, nowMinuteOfDay: Int): Boolean {
    return nowMinuteOfDay > leaveByMinuteOfDay
}

internal fun formatDistanceKm(distanceMeters: Long): String {
    return String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
}

internal fun formatEta(durationSeconds: Long): String {
    val totalMinutes = if (durationSeconds <= 0L) {
        0
    } else {
        ((durationSeconds + 59L) / 60L).toInt()
    }
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    // Compact form: the WIDE panel cannot fit "1 hr 15 min" at hero size.
    return if (minutes == 0) "${hours}h" else "${hours}h ${minutes}m"
}

private fun trafficAccentColor(durationSeconds: Long, durationNoTrafficSeconds: Long): Color {
    if (durationNoTrafficSeconds <= 0L) return Color(0xFF34A853)
    val ratio = durationSeconds.toDouble() / durationNoTrafficSeconds.toDouble()
    return when {
        ratio > 1.5 -> Color(0xFFEA4335)
        ratio > 1.15 -> Color(0xFFF9AB00)
        else -> Color(0xFF34A853)
    }
}

/** Map pill label that ends a tap-to-ride commute. */
internal const val REACHED_PILL_LABEL = "Reached"

/** Card pill label that starts a tap-to-ride commute for the current window. */
internal fun ridePillLabel(direction: Direction): String = when (direction) {
    Direction.TO_WORK -> "Ride Work"
    Direction.TO_HOME -> "Ride Home"
}

/** In-window commute body label: the same "To Work" / "To Home" text the window model uses. */
internal fun commuteWindowLabel(direction: Direction): String = when (direction) {
    Direction.TO_WORK -> "To Work"
    Direction.TO_HOME -> "To Home"
}

/** NONE-case card text: the window label while a commute window is open, else the true empty-day text. */
internal fun calendarNoneText(rideDirection: Direction?): String {
    return if (rideDirection != null) commuteWindowLabel(rideDirection) else "No commute or events scheduled"
}

/**
 * Commute pill row content for card surfaces: probe leave-by, best departure, and the Ride pill.
 * Built in provideGlance from the engine gates, rendered only when not empty.
 */
internal data class CommutePillRowContent(
    val leaveByMinuteOfDay: Int?,
    val bestLine: String?,
    val rideDirection: Direction?,
    val rideFailed: Boolean,
) {
    val isEmpty: Boolean get() = leaveByMinuteOfDay == null && bestLine == null && rideDirection == null
}

/** A card commute pill's kind, used to read the right value back off [CommutePillRowContent] when rendering. TO_AIRPORT is packed only by the airport map pill row, never the card row. */
internal enum class CommutePillKind { LEAVE_BY, BEST, RIDE, TO_AIRPORT }

/** One card commute pill's rendered text and kind, the unit [commutePillRows] packs into rows. */
internal data class CommutePill(val text: String, val kind: CommutePillKind)

/** Per-character estimated pill width in dp, standing in for real text measurement (Glance has none). */
internal const val CARD_PILL_CHAR_WIDTH_DP = 6f

/** Estimated horizontal padding plus corner chrome added to every pill's estimated width, in dp. */
internal const val CARD_PILL_HORIZONTAL_PADDING_DP = 20f

/** Gap between two pills on the same row, in dp. */
internal const val CARD_PILL_GAP_DP = 6f

/** Under SizeMode.Responsive, LocalSize reports the matched breakpoint (220dp), not the physical card width, so the pill row packs against this calibrated width instead - about 300dp usable on the 4x2/4x4 card after its own 12dp padding each side. */
internal const val CARD_PILL_ROW_WIDTH_DP = 300f

/**
 * Deterministic left-to-right packing of [pills] into rows that fit within [availableWidthDp],
 * estimating each pill's width from its text length rather than real measurement (owner decision
 * 2: Glance has no flow layout). A pill wider than the whole row still gets its own row rather
 * than being dropped or clipped.
 */
internal fun commutePillRows(
    pills: List<CommutePill>,
    availableWidthDp: Float,
    textScale: Float,
): List<List<CommutePill>> {
    if (pills.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<CommutePill>>()
    var rowWidthDp = 0f
    for (pill in pills) {
        val pillWidthDp = pill.text.length * CARD_PILL_CHAR_WIDTH_DP * textScale + CARD_PILL_HORIZONTAL_PADDING_DP
        val currentRow = rows.lastOrNull()
        if (currentRow != null && rowWidthDp + CARD_PILL_GAP_DP + pillWidthDp <= availableWidthDp) {
            currentRow.add(pill)
            rowWidthDp += CARD_PILL_GAP_DP + pillWidthDp
        } else {
            rows.add(mutableListOf(pill))
            rowWidthDp = pillWidthDp
        }
    }
    return rows
}

/** Best departure leaves the map while a ride is active because the Reached pill takes its slot. */
internal fun showBestDepartureOnMap(rideActive: Boolean, bestLine: String?): Boolean =
    !rideActive && bestLine != null
