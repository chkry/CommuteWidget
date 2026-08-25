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
import androidx.glance.action.actionParametersOf
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.data.isRefreshingActive
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.engine.mapInSampleSize
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
private val WIDE_CHIP_COLUMN_WIDTH = 58.dp

/** WIDE middle column holds up to 4 slim chips between the info panel and the map. */
internal const val WIDE_MAX_FAVOURITE_CHIPS = 4

internal val FavouriteLabelKey = ActionParameters.Key<String>("favourite_label")

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
        val activeFavourite = repo.activeFavourite(nowEpochMillis)
        val refreshingSince = repo.refreshingSince()

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
                        showFavouriteChips = settings.showFavouriteChips,
                        favourites = settings.favourites,
                        activeFavouriteLabel = activeFavourite?.favourite?.label,
                        isRefreshing = isRefreshingActive(refreshingSince, nowEpochMillis),
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

class FavouriteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val label = parameters[FavouriteLabelKey] ?: return
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val nowEpochMillis = System.currentTimeMillis()
        val active = repo.activeFavourite(nowEpochMillis)
        if (active != null && active.favourite.label == label) {
            repo.clearActiveFavourite()
        } else {
            val favourite = settings.favourites.firstOrNull { it.label == label } ?: return
            repo.setActiveFavourite(favourite, settings.favouriteWindowMinutes, nowEpochMillis)
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP)
    }
}

private data class WidgetExtras(
    val nowEpochMillis: Long,
    val nowMinuteOfDay: Int,
    val leaveByEnabled: Boolean,
    val showFavouriteChips: Boolean,
    val favourites: List<Favourite>,
    val activeFavouriteLabel: String?,
    val isRefreshing: Boolean,
)

private data class InfoStyle(
    val destinationFontSize: TextUnit,
    val etaFontSize: TextUnit,
    val captionFontSize: TextUnit,
    val showDistance: Boolean,
    val showTrafficBar: Boolean,
    val inlineEta: Boolean,
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
    val quiet = snapshot.mode == SnapshotMode.CALENDAR_EMPTY
    Column(
        modifier = modifier.padding(8.dp).clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        ModeInfo(
            snapshot = snapshot,
            extras = extras,
            accent = accent,
            style = InfoStyle(
                destinationFontSize = 12.sp,
                etaFontSize = 22.sp,
                captionFontSize = 10.sp,
                showDistance = false,
                showTrafficBar = snapshot.mode == SnapshotMode.COMMUTE,
                inlineEta = false,
            ),
        )
        if (!quiet) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                UpdatedText(snapshot, extras, GlanceModifier.defaultWeight())
                if (snapshot.lastFetchFailed) {
                    WarningGlyph()
                }
                Text(
                    text = "📍",
                    style = TextStyle(fontSize = 14.sp),
                    modifier = GlanceModifier.clickable(actionRunCallback<NavigateAction>()).padding(2.dp),
                )
            }
        } else if (snapshot.lastFetchFailed) {
            WarningGlyph()
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
    val showChips = shouldShowFavouriteChips(extras)
    val quiet = snapshot.mode == SnapshotMode.CALENDAR_EMPTY
    val infoWidth = if (showChips) {
        LocalSize.current.width * 0.34f
    } else {
        LocalSize.current.width * 0.4f
    }
    val mapAction = if (quiet) {
        actionRunCallback<RefreshAction>()
    } else {
        actionRunCallback<NavigateAction>()
    }
    Row(modifier = modifier) {
        Column(
            modifier = GlanceModifier
                .width(infoWidth)
                .fillMaxHeight()
                .padding(8.dp)
                .clickable(actionRunCallback<RefreshAction>()),
            verticalAlignment = if (showChips || snapshot.mode != SnapshotMode.COMMUTE) {
                Alignment.Vertical.Top
            } else {
                Alignment.Vertical.CenterVertically
            },
        ) {
            ModeInfo(
                snapshot = snapshot,
                extras = extras,
                accent = accent,
                style = InfoStyle(
                    destinationFontSize = 12.sp,
                    etaFontSize = 20.sp,
                    captionFontSize = 10.sp,
                    showDistance = !quiet,
                    showTrafficBar = snapshot.mode == SnapshotMode.COMMUTE,
                    inlineEta = false,
                ),
            )
            if (!quiet) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    UpdatedText(snapshot, extras, GlanceModifier.defaultWeight())
                    if (snapshot.lastFetchFailed) {
                        WarningGlyph()
                    }
                }
            } else if (snapshot.lastFetchFailed) {
                WarningGlyph()
            }
        }
        if (showChips) {
            FavouriteChipColumn(
                favourites = extras.favourites,
                activeLabel = extras.activeFavouriteLabel,
            )
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(mapAction),
        ) {
            MapPane(
                bitmap = mapBitmap,
                modifier = GlanceModifier.fillMaxSize(),
                forcePlaceholder = quiet,
                placeholderGlyph = if (quiet) "📅" else "🗺",
            )
        }
    }
}

@Composable
private fun LargeLayout(
    modifier: GlanceModifier,
    snapshot: CommuteSnapshot,
    mapBitmap: Bitmap?,
    extras: WidgetExtras,
) {
    val accent = trafficAccentColor(snapshot.durationSeconds, snapshot.durationNoTrafficSeconds)
    val showChips = shouldShowFavouriteChips(extras)
    val quiet = snapshot.mode == SnapshotMode.CALENDAR_EMPTY
    val mapAction = if (quiet) {
        actionRunCallback<RefreshAction>()
    } else {
        actionRunCallback<NavigateAction>()
    }
    Box(modifier = modifier) {
        MapPane(
            bitmap = mapBitmap,
            modifier = GlanceModifier.fillMaxSize(),
            forcePlaceholder = quiet,
            placeholderGlyph = if (quiet) "📅" else "🗺",
        )
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    ModeInfo(
                        snapshot = snapshot,
                        extras = extras,
                        accent = accent,
                        style = InfoStyle(
                            destinationFontSize = 14.sp,
                            etaFontSize = 14.sp,
                            captionFontSize = 10.sp,
                            showDistance = false,
                            showTrafficBar = false,
                            inlineEta = snapshot.mode != SnapshotMode.CALENDAR_EMPTY,
                        ),
                    )
                    if (!quiet) {
                        UpdatedText(snapshot, extras, GlanceModifier)
                    }
                }
                if (snapshot.lastFetchFailed) {
                    WarningGlyph()
                }
            }
            Spacer(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxWidth()
                    .clickable(mapAction),
            )
            if (showChips) {
                FavouriteChipRow(
                    favourites = extras.favourites,
                    activeLabel = extras.activeFavouriteLabel,
                    maxChips = extras.favourites.size,
                    expandChips = false,
                )
            }
        }
    }
}

@Composable
private fun ModeInfo(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    style: InfoStyle,
) {
    when (snapshot.mode) {
        SnapshotMode.COMMUTE -> CommuteInfo(snapshot, extras, accent, style)
        SnapshotMode.CALENDAR_EVENT -> CalendarEventInfo(snapshot, extras, accent, style)
        SnapshotMode.CALENDAR_EMPTY -> CalendarEmptyInfo(snapshot, extras, style)
    }
}

@Composable
private fun CommuteInfo(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    style: InfoStyle,
) {
    val title = destinationDisplayLabel(snapshot.direction, snapshot.destinationLabel)
    if (style.inlineEta) {
        InlineTitleEta(title, snapshot, extras.isRefreshing, accent, style.etaFontSize)
    } else {
        DestinationLabelText(title, style.destinationFontSize)
        EtaText(snapshot, extras.isRefreshing, accent, style.etaFontSize)
    }
    if (style.showDistance && !extras.isRefreshing) {
        DistanceText(snapshot.distanceMeters)
    }
    if (style.showTrafficBar) {
        TrafficBar(accent)
    }
    if (shouldShowLeaveBy(snapshot, extras.leaveByEnabled)) {
        Spacer(modifier = GlanceModifier.height(2.dp))
        LeaveByChip(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, style.captionFontSize)
    }
}

@Composable
private fun CalendarEventInfo(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    accent: Color,
    style: InfoStyle,
) {
    CaptionText("Next event", style.captionFontSize)
    val title = calendarEventTitle(snapshot.destinationLabel)
    if (style.inlineEta) {
        InlineTitleEta(title, snapshot, extras.isRefreshing, accent, style.etaFontSize)
    } else {
        EventTitleText(title, style.destinationFontSize)
        EtaText(snapshot, extras.isRefreshing, accent, style.etaFontSize)
    }
    snapshot.eventStartEpochMillis?.let { start ->
        CaptionText(formatEventAtTime(start), style.captionFontSize)
    }
    if (style.showDistance && !extras.isRefreshing) {
        DistanceText(snapshot.distanceMeters)
    }
    if (style.showTrafficBar) {
        TrafficBar(accent)
    }
}

@Composable
private fun CalendarEmptyInfo(
    snapshot: CommuteSnapshot,
    extras: WidgetExtras,
    style: InfoStyle,
) {
    when (calendarEmptyCase(snapshot)) {
        CalendarEmptyCase.UNLOCATED_EVENT -> {
            CaptionText("Next event", style.captionFontSize)
            EventTitleText(calendarEventTitle(snapshot.destinationLabel), style.destinationFontSize)
            CaptionText(formatEventAtTime(snapshot.eventStartEpochMillis!!), style.captionFontSize)
        }
        CalendarEmptyCase.NEXT_WINDOW -> {
            Text(
                text = formatNextWindowLine(
                    snapshot.nextWindowLabel!!,
                    snapshot.nextWindowStartMinuteOfDay!!,
                ),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = style.destinationFontSize,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 2,
            )
        }
        CalendarEmptyCase.NONE -> {
            Text(
                text = "No commute or events scheduled",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = style.destinationFontSize,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 3,
            )
        }
    }
    if (extras.isRefreshing) {
        EtaText(snapshot, isRefreshing = true, accent = Color(0xFF34A853), fontSize = style.captionFontSize)
    }
}

@Composable
private fun InlineTitleEta(
    title: String,
    snapshot: CommuteSnapshot,
    isRefreshing: Boolean,
    accent: Color,
    fontSize: TextUnit,
) {
    val etaPart = if (isRefreshing) "Updating..." else formatEta(snapshot.durationSeconds)
    Text(
        text = "$title  $etaPart",
        style = TextStyle(
            color = if (isRefreshing) {
                GlanceTheme.colors.onSurfaceVariant
            } else {
                ColorProvider(accent)
            },
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun DestinationLabelText(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = fontSize,
        ),
        maxLines = 1,
    )
}

@Composable
private fun EventTitleText(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

@Composable
private fun CaptionText(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

@Composable
private fun EtaText(
    snapshot: CommuteSnapshot,
    isRefreshing: Boolean,
    accent: Color,
    fontSize: TextUnit,
) {
    if (isRefreshing) {
        Text(
            text = "Updating...",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    } else {
        Text(
            text = formatEta(snapshot.durationSeconds),
            style = TextStyle(
                color = ColorProvider(accent),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun DistanceText(distanceMeters: Long) {
    Text(
        text = formatDistanceKm(distanceMeters),
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 12.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun TrafficBar(accent: Color) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(3.dp)
            .cornerRadius(2.dp)
            .background(accent),
    ) {}
}

@Composable
private fun LeaveByChip(minuteOfDay: Int, nowMinuteOfDay: Int, fontSize: TextUnit) {
    val late = isLeaveByPast(minuteOfDay, nowMinuteOfDay)
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = formatLeaveByLine(minuteOfDay),
            style = TextStyle(
                color = if (late) ColorProvider(LEAVE_BY_LATE_COLOR) else GlanceTheme.colors.onSurfaceVariant,
                fontSize = fontSize,
                fontWeight = if (late) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun UpdatedText(snapshot: CommuteSnapshot, extras: WidgetExtras, modifier: GlanceModifier) {
    Text(
        text = formatUpdatedLine(snapshot.fetchedAtEpochMillis, extras.nowEpochMillis),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 10.sp,
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun FavouriteChipRow(
    favourites: List<Favourite>,
    activeLabel: String?,
    maxChips: Int,
    expandChips: Boolean,
) {
    val shown = favouriteChipsToShow(favourites, maxChips)
    if (shown.isEmpty()) return
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        shown.forEachIndexed { index, favourite ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            val chipModifier = if (expandChips) GlanceModifier.defaultWeight() else GlanceModifier
            FavouriteChip(
                favourite = favourite,
                highlighted = favourite.label == activeLabel,
                modifier = chipModifier,
            )
        }
    }
}

@Composable
private fun FavouriteChipColumn(
    favourites: List<Favourite>,
    activeLabel: String?,
) {
    val shown = favouriteChipsToShow(favourites, WIDE_MAX_FAVOURITE_CHIPS)
    if (shown.isEmpty()) return
    Column(
        modifier = GlanceModifier
            .width(WIDE_CHIP_COLUMN_WIDTH)
            .fillMaxHeight()
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        shown.forEachIndexed { index, favourite ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.height(3.dp))
            }
            FavouriteChip(
                favourite = favourite,
                highlighted = favourite.label == activeLabel,
                modifier = GlanceModifier.fillMaxWidth(),
                compact = true,
            )
        }
    }
}

@Composable
private fun FavouriteChip(
    favourite: Favourite,
    highlighted: Boolean,
    modifier: GlanceModifier,
    compact: Boolean = false,
) {
    val background = if (highlighted) {
        GlanceTheme.colors.primaryContainer
    } else {
        GlanceTheme.colors.secondaryContainer
    }
    val foreground = if (highlighted) {
        GlanceTheme.colors.onPrimaryContainer
    } else {
        GlanceTheme.colors.onSecondaryContainer
    }
    Box(
        modifier = modifier
            .background(background)
            .cornerRadius(12.dp)
            .padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = if (compact) 2.dp else 3.dp,
            )
            .clickable(
                actionRunCallback<FavouriteAction>(
                    actionParametersOf(FavouriteLabelKey to favourite.label),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = favourite.label,
            style = TextStyle(
                color = foreground,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun MapPane(
    bitmap: Bitmap?,
    modifier: GlanceModifier,
    forcePlaceholder: Boolean = false,
    placeholderGlyph: String = "🗺",
) {
    if (!forcePlaceholder && bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Route map",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(GlanceTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholderGlyph,
                style = TextStyle(fontSize = 22.sp),
            )
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

private fun shouldShowLeaveBy(snapshot: CommuteSnapshot, leaveByEnabled: Boolean): Boolean {
    return snapshot.mode == SnapshotMode.COMMUTE &&
        leaveByEnabled &&
        snapshot.leaveByMinuteOfDay != null
}

private fun shouldShowFavouriteChips(extras: WidgetExtras): Boolean {
    return extras.showFavouriteChips && extras.favourites.isNotEmpty()
}

internal fun favouriteChipsToShow(favourites: List<Favourite>, maxChips: Int): List<Favourite> {
    return favourites.take(maxChips.coerceAtLeast(0))
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

internal fun formatEventAtTime(eventStartEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val zoned = Instant.ofEpochMilli(eventStartEpochMillis).atZone(zone)
    return "at ${formatClockTime(zoned.hour * 60 + zoned.minute)}"
}

internal fun formatNextWindowLine(label: String, startMinuteOfDay: Int): String {
    return "Next: $label at ${formatClockTime(startMinuteOfDay)}"
}

internal fun isLeaveByPast(leaveByMinuteOfDay: Int, nowMinuteOfDay: Int): Boolean {
    return nowMinuteOfDay > leaveByMinuteOfDay
}

private fun formatEta(durationSeconds: Long): String {
    val totalMinutes = if (durationSeconds <= 0L) {
        0
    } else {
        ((durationSeconds + 59L) / 60L).toInt()
    }
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
}

private fun formatUpdatedLine(fetchedAtEpochMillis: Long, nowEpochMillis: Long): String {
    if (fetchedAtEpochMillis <= 0L) return "not yet"
    val seconds = ((nowEpochMillis - fetchedAtEpochMillis).coerceAtLeast(0L)) / 1000L
    return when {
        seconds < 60L -> "just now"
        seconds < 3600L -> "${seconds / 60L}m ago"
        else -> "${seconds / 3600L}h ago"
    }
}

private fun formatDistanceKm(distanceMeters: Long): String {
    return String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
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
