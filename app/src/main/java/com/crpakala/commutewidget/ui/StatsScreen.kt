package com.crpakala.commutewidget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.crpakala.commutewidget.history.HistoryStore
import com.crpakala.commutewidget.history.TimeBucketAverage
import com.crpakala.commutewidget.history.WeekdayAverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun pickLowestBucket(buckets: List<TimeBucketAverage>): TimeBucketAverage? =
    buckets.minByOrNull { it.avgDurationSeconds }

internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60).coerceIn(0, 23)
    return "${if (hour == 0) 12 else if (hour > 12) hour - 12 else hour}:${(minuteOfDay % 60).toString().padStart(2, '0')}"
}

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val history = remember { HistoryStore.get(context.applicationContext) }
    var direction by remember { mutableStateOf("TO_WORK") }
    var weekday by remember { mutableStateOf<List<WeekdayAverage>>(emptyList()) }
    var curve by remember { mutableStateOf<List<TimeBucketAverage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    LaunchedEffect(direction) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            history.weekdayAverages(direction) to history.timeOfDayCurve(direction, 10)
        }
        weekday = result.first
        curve = result.second
        loading = false
    }
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = onBack) { Text("Back") }
                Text("Commute stats", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = direction == "TO_WORK", onClick = { direction = "TO_WORK" }, label = { Text("To Work") })
                FilterChip(selected = direction == "TO_HOME", onClick = { direction = "TO_HOME" }, label = { Text("To Home") })
            }
            if (!loading && weekday.isEmpty() && curve.isEmpty()) {
                Text("No data yet. History collects during your configured slots.")
            } else if (loading) {
                Text("Loading stats...")
            } else {
                Text("Weekday averages", style = MaterialTheme.typography.titleMedium)
                WeekdayBarChart(weekday)
                Text("Time of day", style = MaterialTheme.typography.titleMedium)
                TimeCurveChart(curve)
                pickLowestBucket(curve)?.let { best ->
                    Text(
                        "Best: leave around ${formatMinuteOfDay(best.bucketStartMinuteOfDay)} " +
                            "(${best.avgDurationSeconds / 60} min avg)",
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayBarChart(averages: List<WeekdayAverage>) {
    val maximum = averages.maxOfOrNull { it.avgDurationSeconds }?.coerceAtLeast(1L) ?: 1L
    val labels = listOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        averages.forEach { average ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(labels[average.dayOfWeekIso], modifier = Modifier.padding(top = 2.dp))
                Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
                    val width = size.width * average.avgDurationSeconds.toFloat() / maximum
                    drawLine(
                        color = Color(0xFF3F51B5),
                        start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        end = androidx.compose.ui.geometry.Offset(width, size.height / 2),
                        strokeWidth = size.height * .65f,
                        cap = StrokeCap.Round,
                    )
                }
                Text("${average.avgDurationSeconds / 60} min (${average.sampleCount})")
            }
        }
    }
}

@Composable
private fun TimeCurveChart(curve: List<TimeBucketAverage>) {
    if (curve.isEmpty()) return
    val start = curve.minOf { it.bucketStartMinuteOfDay }
    val end = curve.maxOf { it.bucketStartMinuteOfDay }
    val maximum = curve.maxOf { it.avgDurationSeconds }.coerceAtLeast(1L)
    Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        val horizontalRange = (end - start).coerceAtLeast(10)
        fun x(minute: Int) = size.width * (minute - start).toFloat() / horizontalRange
        fun y(seconds: Long) = size.height - (size.height * seconds.toFloat() / maximum)
        curve.zipWithNext().forEach { (a, b) ->
            drawLine(
                color = Color(0xFF3F51B5),
                start = androidx.compose.ui.geometry.Offset(x(a.bucketStartMinuteOfDay), y(a.avgDurationSeconds)),
                end = androidx.compose.ui.geometry.Offset(x(b.bucketStartMinuteOfDay), y(b.avgDurationSeconds)),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
        curve.forEach {
            drawCircle(Color(0xFF3F51B5), 4f, androidx.compose.ui.geometry.Offset(x(it.bucketStartMinuteOfDay), y(it.avgDurationSeconds)))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        (start / 60..end / 60).forEach { hour ->
            Text(formatMinuteOfDay(hour * 60), style = MaterialTheme.typography.bodySmall)
        }
    }
}
