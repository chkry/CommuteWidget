package com.crpakala.commutewidget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.data.isRefreshingActive
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.currentBestDepartureTarget
import com.crpakala.commutewidget.engine.mapInSampleSize
import com.crpakala.commutewidget.engine.shouldShowBestDeparture
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

private val SMALL_BREAKPOINT = DpSize(110.dp, 110.dp)
private val WIDE_BREAKPOINT = DpSize(220.dp, 110.dp)
private val LARGE_BREAKPOINT = DpSize(220.dp, 220.dp)
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
        setOf(SMALL_BREAKPOINT, WIDE_BREAKPOINT, LARGE_BREAKPOINT),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val snapshot = repo.snapshot()
        val mapBitmap = loadMapBitmap(snapshot?.mapImagePath)
        val nowEpochMillis = System.currentTimeMillis()
        val now = ZonedDateTime.now()
        val nowMinuteOfDay = now.hour * 60 + now.minute
        val configured = settings.apiKey.isNotBlank() && settings.home != null && settings.work != null
        val refreshingSince = repo.refreshingSince()
        val bestDeparture = repo.bestDeparture()
        val bestDepartureTarget = currentBestDepartureTarget(
            nowMinuteOfDay = nowMinuteOfDay,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )
        val bestDepartureLine = if (
            shouldShowBestDeparture(
                result = bestDeparture,
                enabled = settings.bestDepartureEnabled,
                todayIsCommuteDay = now.dayOfWeek.value in settings.commuteDays,
                today = now.toLocalDate().toString(),
                target = bestDepartureTarget,
                showingCalendarEvent = snapshot?.mode == SnapshotMode.CALENDAR_EVENT,
            )
        ) {
            bestDepartureLineText(bestDeparture!!.bestMinuteOfDay)
        } else {
            null
        }

        val colors = ColorProviders(
            light = dynamicLightColorScheme(context),
            dark = dynamicDarkColorScheme(context),
        )
        provideContent {
            GlanceTheme(colors = colors) {
                WidgetScaffold(
                    configured = configured,
                    snapshot = snapshot,
                    mapBitmap = mapBitmap,
                    extras = WidgetExtras(
                        nowEpochMillis = nowEpochMillis,
                        nowMinuteOfDay = nowMinuteOfDay,
                        leaveByEnabled = settings.leaveByEnabled,
                        refreshingSince = refreshingSince,
                        bestDepartureLine = bestDepartureLine,
                        pillCorner = settings.mapPillCorner,
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

private data class WidgetExtras(
    val nowEpochMillis: Long,
    val nowMinuteOfDay: Int,
    val leaveByEnabled: Boolean,
    val refreshingSince: Long?,
    /** Pre-formatted "Best: 3:30 pm" line, null when disabled/stale/slot passed. */
    val bestDepartureLine: String? = null,
    val pillCorner: MapPillCorner = MapPillCorner.TOP_START,
)

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
)

@Composable
private fun WidgetScaffold(
    configured: Boolean,
    snapshot: CommuteSnapshot?,
    mapBitmap: Bitmap?,
    extras: WidgetExtras,
) {
    val root = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(16.dp)

    when {
        !configured -> UnconfiguredContent(root)
        snapshot == null -> EmptySnapshotContent(root)
        else -> ConfiguredContent(root, snapshot, mapBitmap, extras)
    }
}

@Composable
private fun UnconfiguredContent(modifier: GlanceModifier) {
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun EmptySnapshotContent(modifier: GlanceModifier) {
    Box(
        modifier = modifier.clickable(actionRunCallback<RefreshAction>()).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Tap to load",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
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
        CalendarEmptyCard(modifier, snapshot, extras.bestDepartureLine)
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
    Column(
        modifier = modifier.padding(8.dp).clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        RoutedInfo(
            snapshot = snapshot,
            extras = extras,
            accent = accent,
            style = InfoStyle(
                destinationFontSize = 11.sp,
                etaFontSize = 24.sp,
                leaveByFontSize = 11.sp,
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
                    destinationFontSize = 11.sp,
                    etaFontSize = 22.sp,
                    leaveByFontSize = 12.sp,
                    inlineEta = false,
                    showRoutedCaption = true,
                    showLeaveBy = false,
                    showDistance = true,
                ),
            )
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionRunCallback<NavigateAction>()),
            contentAlignment = pillCornerAlignment(extras.pillCorner),
        ) {
            MapPane(
                snapshot = snapshot,
                bitmap = mapBitmap,
                modifier = GlanceModifier.fillMaxSize(),
            )
            // Pills ride on the map; the panel is too narrow for their full strings. Deliberately
            // NOT gated on the bitmap so they can never vanish with a failed map fetch. Corner is
            // owner-configurable so the stack can dodge whatever the route usually covers.
            val showLeaveByPill = shouldShowLeaveBy(snapshot, extras.leaveByEnabled)
            if (showLeaveByPill || extras.bestDepartureLine != null) {
                Row(
                    modifier = GlanceModifier.padding(6.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    if (showLeaveByPill) {
                        LeaveByPill(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay)
                    }
                    if (extras.bestDepartureLine != null) {
                        if (showLeaveByPill) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                        }
                        MapTextPill(extras.bestDepartureLine)
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
                        destinationFontSize = 16.sp,
                        etaFontSize = 16.sp,
                        leaveByFontSize = 11.sp,
                        inlineEta = true,
                        showRoutedCaption = true,
                        showBestDeparture = true,
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
    }
}

@Composable
private fun CalendarEmptyCard(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    bestDepartureLine: String? = null,
) {
    Box(
        modifier = modifier.clickable(actionRunCallback<RefreshAction>()).padding(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            when (calendarEmptyCase(snapshot)) {
                CalendarEmptyCase.UNLOCATED_EVENT -> {
                    Text(
                        text = calendarEventTitle(snapshot.destinationLabel),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = formatEventClockTime(snapshot.eventStartEpochMillis!!),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                CalendarEmptyCase.NEXT_WINDOW -> {
                    Text(
                        text = "Next up",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = snapshot.nextWindowLabel!!,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = formatClockTime(snapshot.nextWindowStartMinuteOfDay!!),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                CalendarEmptyCase.NONE -> {
                    Text(
                        text = "No commute or events scheduled",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 3,
                    )
                }
            }
            if (bestDepartureLine != null) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = bestDepartureLine,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
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
    }
    if (style.inlineEta) {
        InlineTitleEta(title, snapshot, extras, accent, style.etaFontSize)
    } else {
        DestinationLine(title, style.destinationFontSize, snapshot.lastFetchFailed)
        EtaText(snapshot, extras, accent, style.etaFontSize)
    }
    if (style.showDistance && snapshot.distanceMeters > 0L) {
        Text(
            text = formatDistanceKm(snapshot.distanceMeters),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
    if (shouldShowRoutedCaption(snapshot, style.showRoutedCaption)) {
        RoutedCaption()
    }
    if (style.showLeaveBy && shouldShowLeaveBy(snapshot, extras.leaveByEnabled)) {
        LeaveByLine(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, style.leaveByFontSize)
    }
    if (style.showBestDeparture && extras.bestDepartureLine != null) {
        Text(
            text = extras.bestDepartureLine,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
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
    // White (theme onSurface) ETA with a traffic-colored dot beside it: the colored-text approach
    // made amber traffic read as "dimmed" on dark backgrounds, hiding the pending-alpha signal.
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            text = formatEta(snapshot.durationSeconds),
            style = TextStyle(
                color = etaColorProvider(extras, snapshot.fetchedAtEpochMillis),
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
        EtaDisplayState.PENDING -> dayNightColorProvider(
            day = Color.Black.copy(alpha = ETA_PENDING_ALPHA),
            night = Color.White.copy(alpha = ETA_PENDING_ALPHA),
        )
        EtaDisplayState.STALE -> GlanceTheme.colors.onSurfaceVariant
        EtaDisplayState.FRESH -> GlanceTheme.colors.onSurface
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

/** Opaque leave-by pill for the WIDE map overlay; legible over map tiles. */
@Composable
private fun LeaveByPill(minuteOfDay: Int, nowMinuteOfDay: Int) {
    val late = isLeaveByPast(minuteOfDay, nowMinuteOfDay)
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = formatLeaveByLine(minuteOfDay),
            style = TextStyle(
                color = if (late) ColorProvider(LEAVE_BY_LATE_COLOR) else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Opaque generic text pill for the map overlay stack (e.g. "Best: 3:30 pm"). */
@Composable
private fun MapTextPill(text: String) {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** FIX-9: a "Routed" caption under/beside a CALENDAR_EVENT title when it won the 30-minute located-event preference. */
@Composable
private fun RoutedCaption() {
    Text(
        text = "Routed",
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 10.sp,
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

private fun launchNavigation(context: Context, lat: Double, lng: Double, modeChar: String) {
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

internal fun shouldShowLeaveBy(snapshot: CommuteSnapshot, leaveByEnabled: Boolean): Boolean {
    return (snapshot.mode == SnapshotMode.COMMUTE || snapshot.mode == SnapshotMode.CALENDAR_EVENT) &&
        leaveByEnabled &&
        snapshot.leaveByMinuteOfDay != null
}

/**
 * FIX-9: the "Routed" caption only ever applies to a [SnapshotMode.CALENDAR_EVENT] snapshot whose
 * event won the 30-minute located-event preference, and only on sizes that opt in
 * ([InfoStyle.showRoutedCaption] - WIDE and LARGE; SMALL skips it for space).
 */
internal fun shouldShowRoutedCaption(snapshot: CommuteSnapshot, captionAllowedForSize: Boolean): Boolean {
    return captionAllowedForSize && snapshot.mode == SnapshotMode.CALENDAR_EVENT && snapshot.routedOverEarlier
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
    NEXT_WINDOW,
    NONE,
}

internal fun calendarEmptyCase(snapshot: CommuteSnapshot): CalendarEmptyCase {
    if (!snapshot.destinationLabel.isNullOrBlank() && snapshot.eventStartEpochMillis != null) {
        return CalendarEmptyCase.UNLOCATED_EVENT
    }
    if (!snapshot.nextWindowLabel.isNullOrBlank() && snapshot.nextWindowStartMinuteOfDay != null) {
        return CalendarEmptyCase.NEXT_WINDOW
    }
    return CalendarEmptyCase.NONE
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
