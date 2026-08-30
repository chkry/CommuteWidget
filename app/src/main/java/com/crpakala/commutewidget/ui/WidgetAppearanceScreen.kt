package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Widget appearance" category: background opacity, text size, and the map pill position picker
 * (moved here from the old "Best departure" section).
 */
@Composable
fun WidgetAppearanceScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    applicationContext: Context,
    padding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppearanceSection(
                opacityPercent = settings.widgetBackgroundOpacityPercent,
                textScalePercent = settings.widgetTextScalePercent,
                onOpacityChanged = { percent ->
                    scope.launch {
                        repository.setWidgetBackgroundOpacityPercent(percent)
                        refreshWidget(applicationContext)
                    }
                },
                onTextScaleChanged = { percent ->
                    scope.launch {
                        repository.setWidgetTextScalePercent(percent)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
        item {
            MapPillCornerSection(
                pillCorner = settings.mapPillCorner,
                onPillCornerChanged = { corner ->
                    scope.launch {
                        repository.setMapPillCorner(corner)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
    }
}

@Composable
internal fun AppearanceSection(
    opacityPercent: Int,
    textScalePercent: Int,
    onOpacityChanged: (Int) -> Unit,
    onTextScaleChanged: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Background opacity", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Lower values give the translucent One UI look",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Solid" to 100, "85%" to 85, "70%" to 70, "50%" to 50).forEach { (label, value) ->
                FilterChip(
                    selected = opacityPercent == value,
                    onClick = { onOpacityChanged(value) },
                    label = { Text(label) },
                )
            }
        }
        Text("Text size", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Small" to 85, "Default" to 100, "Large" to 115).forEach { (label, value) ->
                FilterChip(
                    selected = textScalePercent == value,
                    onClick = { onTextScaleChanged(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
internal fun MapPillCornerSection(
    pillCorner: MapPillCorner,
    onPillCornerChanged: (MapPillCorner) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Map pill position", style = MaterialTheme.typography.titleMedium)
        Text(
            "Where the leave-by and best-time pills sit on the widget's map",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = pillCorner == MapPillCorner.TOP_START,
                onClick = { onPillCornerChanged(MapPillCorner.TOP_START) },
                label = { Text(mapPillCornerLabel(MapPillCorner.TOP_START)) },
            )
            FilterChip(
                selected = pillCorner == MapPillCorner.TOP_END,
                onClick = { onPillCornerChanged(MapPillCorner.TOP_END) },
                label = { Text(mapPillCornerLabel(MapPillCorner.TOP_END)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = pillCorner == MapPillCorner.BOTTOM_START,
                onClick = { onPillCornerChanged(MapPillCorner.BOTTOM_START) },
                label = { Text(mapPillCornerLabel(MapPillCorner.BOTTOM_START)) },
            )
            FilterChip(
                selected = pillCorner == MapPillCorner.BOTTOM_END,
                onClick = { onPillCornerChanged(MapPillCorner.BOTTOM_END) },
                label = { Text(mapPillCornerLabel(MapPillCorner.BOTTOM_END)) },
            )
        }
    }
}
