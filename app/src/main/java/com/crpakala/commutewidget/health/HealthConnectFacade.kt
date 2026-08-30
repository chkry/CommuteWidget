package com.crpakala.commutewidget.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Thin, crash-proof wrapper over the Health Connect client.
 *
 * Every entry point degrades to a safe default (null / empty / false) on a missing provider,
 * a revoked permission, or any other failure - this widget must render fine with zero health
 * data. The pure day-record modeling lives in a sibling's `data` package; this object only talks
 * to the platform SDK.
 */
object HealthConnectFacade {

    /** Every Health Connect permission this app declares in the manifest and may request. */
    val REQUIRED_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    /** True only when the Health Connect provider is installed and reachable on this device. */
    fun isAvailable(context: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    /** The subset of [REQUIRED_PERMISSIONS] the user has actually granted. Empty on any failure. */
    suspend fun grantedPermissions(context: Context): Set<String> = runCatching {
        val client = clientOrNull(context) ?: return@runCatching emptySet()
        client.permissionController.getGrantedPermissions()
    }.getOrDefault(emptySet())

    /** Total steps from local midnight through now. Null on any failure or missing permission. */
    suspend fun readStepsToday(context: Context, zoneId: ZoneId): Long? {
        val startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = Instant.now().toEpochMilli()
        return readStepsBetween(context, startOfDay, now)
    }

    /** Total steps in `[startEpochMillis, endEpochMillis)`. Null on any failure or missing permission. */
    suspend fun readStepsBetween(
        context: Context,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): Long? = runCatching {
        val client = clientOrNull(context) ?: return@runCatching null
        val readPermission = HealthPermission.getReadPermission(StepsRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            return@runCatching null
        }
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(startEpochMillis),
                    Instant.ofEpochMilli(endEpochMillis),
                ),
            ),
        )
        response[StepsRecord.COUNT_TOTAL]
    }.getOrDefault(null)

    /** Exercise session (start, end) epoch-millis pairs from local midnight through now. */
    suspend fun readExerciseSessionsToday(context: Context, zoneId: ZoneId): List<Pair<Long, Long>> = runCatching {
        val client = clientOrNull(context) ?: return@runCatching emptyList()
        val readPermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            return@runCatching emptyList()
        }
        val startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now()),
            ),
        )
        response.records.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }
    }.getOrDefault(emptyList())

    /** Writes a hydration entry. True only on a confirmed insert with a granted write permission. */
    suspend fun writeHydration(context: Context, volumeMl: Int, atEpochMillis: Long): Boolean = runCatching {
        val client = clientOrNull(context) ?: return@runCatching false
        val writePermission = HealthPermission.getWritePermission(HydrationRecord::class)
        if (writePermission !in client.permissionController.getGrantedPermissions()) {
            return@runCatching false
        }
        val startTime = Instant.ofEpochMilli(atEpochMillis)
        val endTime = startTime.plusSeconds(1)
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(startTime)
        val record = HydrationRecord(
            startTime = startTime,
            startZoneOffset = zoneOffset,
            endTime = endTime,
            endZoneOffset = zoneOffset,
            volume = Volume.milliliters(volumeMl.toDouble()),
            metadata = Metadata.manualEntry(),
        )
        val result = client.insertRecords(listOf(record))
        result.recordIdsList.isNotEmpty()
    }.getOrDefault(false)

    private fun clientOrNull(context: Context): HealthConnectClient? = runCatching {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            null
        } else {
            HealthConnectClient.getOrCreate(context)
        }
    }.getOrDefault(null)
}
