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
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.engine.decideDirection
import com.crpakala.commutewidget.engine.mapInSampleSize
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale

private val SMALL_BREAKPOINT = DpSize(110.dp, 110.dp)
private val WIDE_BREAKPOINT = DpSize(220.dp, 110.dp)
private val LARGE_BREAKPOINT = DpSize(220.dp, 220.dp)
private const val MAP_DECODE_MAX_EDGE = 1200
private val LEAVE_BY_LATE_COLOR = Color(0xFFEA4335)

/** WIDE left panel is ~88dp; two chips stay legible, three would clip labels. */
internal const val WIDE_MAX_FAVOURITE_CHIPS = 2

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
            val home = settings.home ?: return
            val work = settings.work ?: return
            val now = ZonedDateTime.now()
            val direction = decideDirection(
                now.dayOfWeek,
                now.hour * 60 + now.minute,
                settings.switchMinuteOfDay,
            )
            val dest = if (direction == Direction.TO_WORK) work else home
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
    val showLeaveBy = shouldShowLeaveBy(snapshot, extras.leaveByEnabled)
    Column(
        modifier = modifier.padding(8.dp).clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        DestinationLabelText(snapshot, fontSize = 12.sp)
        Text(
            text = formatEta(snapshot.durationSeconds),
            style = TextStyle(
                color = ColorProvider(accent),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        if (showLeaveBy) {
            LeaveByText(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, fontSize = 10.sp)
        }
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(4.dp)
                .cornerRadius(2.dp)
                .background(accent),
        ) {}
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = formatUpdatedLine(snapshot.fetchedAtEpochMillis, extras.nowEpochMillis),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (snapshot.lastFetchFailed) {
                WarningGlyph()
            }
            Text(
                text = "📍",
                style = TextStyle(fontSize = 14.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<NavigateAction>()).padding(2.dp),
            )
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
    val leftWidth = LocalSize.current.width * 0.4f
    val showLeaveBy = shouldShowLeaveBy(snapshot, extras.leaveByEnabled)
    val showChips = shouldShowFavouriteChips(extras)
    Row(modifier = modifier) {
        Column(
            modifier = GlanceModifier
                .width(leftWidth)
                .fillMaxHeight()
                .padding(8.dp)
                .clickable(actionRunCallback<RefreshAction>()),
            verticalAlignment = if (showChips) {
                Alignment.Vertical.Top
            } else {
                Alignment.Vertical.CenterVertically
            },
        ) {
            DestinationLabelText(snapshot, fontSize = 12.sp)
            Text(
                text = formatEta(snapshot.durationSeconds),
                style = TextStyle(
                    color = ColorProvider(accent),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = formatDistanceKm(snapshot.distanceMeters),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .cornerRadius(2.dp)
                    .background(accent),
            ) {}
            if (showLeaveBy) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                LeaveByText(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, fontSize = 10.sp)
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    text = formatUpdatedLine(snapshot.fetchedAtEpochMillis, extras.nowEpochMillis),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (snapshot.lastFetchFailed) {
                    WarningGlyph()
                }
            }
            if (showChips) {
                Spacer(modifier = GlanceModifier.defaultWeight())
                FavouriteChipRow(
                    favourites = extras.favourites,
                    activeLabel = extras.activeFavouriteLabel,
                    maxChips = WIDE_MAX_FAVOURITE_CHIPS,
                    expandChips = true,
                    opaqueContainer = false,
                )
            }
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionRunCallback<NavigateAction>()),
        ) {
            MapPane(mapBitmap, GlanceModifier.fillMaxSize())
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
    val showLeaveBy = shouldShowLeaveBy(snapshot, extras.leaveByEnabled)
    val showChips = shouldShowFavouriteChips(extras)
    Box(modifier = modifier) {
        MapPane(mapBitmap, GlanceModifier.fillMaxSize())
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "${destinationDisplayLabel(snapshot.direction, snapshot.destinationLabel)}  ${formatEta(snapshot.durationSeconds)}",
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    if (showLeaveBy) {
                        LeaveByText(snapshot.leaveByMinuteOfDay!!, extras.nowMinuteOfDay, fontSize = 10.sp)
                    }
                    Text(
                        text = formatUpdatedLine(snapshot.fetchedAtEpochMillis, extras.nowEpochMillis),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
                if (snapshot.lastFetchFailed) {
                    WarningGlyph()
                }
            }
            Spacer(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxWidth()
                    .clickable(actionRunCallback<NavigateAction>()),
            )
            if (showChips) {
                FavouriteChipRow(
                    favourites = extras.favourites,
                    activeLabel = extras.activeFavouriteLabel,
                    maxChips = extras.favourites.size,
                    expandChips = false,
                    opaqueContainer = true,
                )
            }
        }
    }
}

@Composable
private fun DestinationLabelText(snapshot: CommuteSnapshot, fontSize: TextUnit) {
    Text(
        text = destinationDisplayLabel(snapshot.direction, snapshot.destinationLabel),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = fontSize,
        ),
        maxLines = 1,
    )
}

@Composable
private fun LeaveByText(minuteOfDay: Int, nowMinuteOfDay: Int, fontSize: TextUnit) {
    val late = isLeaveByPast(minuteOfDay, nowMinuteOfDay)
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

@Composable
private fun FavouriteChipRow(
    favourites: List<Favourite>,
    activeLabel: String?,
    maxChips: Int,
    expandChips: Boolean,
    opaqueContainer: Boolean,
) {
    val shown = favouriteChipsToShow(favourites, maxChips)
    if (shown.isEmpty()) return
    val rowModifier = if (opaqueContainer) {
        GlanceModifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    } else {
        GlanceModifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
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
private fun FavouriteChip(
    favourite: Favourite,
    highlighted: Boolean,
    modifier: GlanceModifier,
) {
    val background = if (highlighted) {
        GlanceTheme.colors.primary
    } else {
        GlanceTheme.colors.secondaryContainer
    }
    val foreground = if (highlighted) {
        GlanceTheme.colors.onPrimary
    } else {
        GlanceTheme.colors.onSecondaryContainer
    }
    Box(
        modifier = modifier
            .background(background)
            .cornerRadius(12.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp)
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
                fontSize = 10.sp,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun MapPane(bitmap: Bitmap?, modifier: GlanceModifier) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Route map",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(GlanceTheme.colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🗺",
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
    return leaveByEnabled && snapshot.leaveByMinuteOfDay != null
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

internal fun formatLeaveByLine(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 59)
    val hour24 = clamped / 60
    val minute = clamped % 60
    val hour12 = when (val hour = hour24 % 12) {
        0 -> 12
        else -> hour
    }
    val period = if (hour24 < 12) "am" else "pm"
    return "Leave by $hour12:${minute.toString().padStart(2, '0')} $period"
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
