package com.crpakala.commutewidget.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WeekdayAverage(
    val dayOfWeekIso: Int,
    val avgDurationSeconds: Long,
    val sampleCount: Int,
)

data class TimeBucketAverage(
    val bucketStartMinuteOfDay: Int,
    val avgDurationSeconds: Long,
    val sampleCount: Int,
)

data class DateCount(
    val localDate: String,
    val sampleCount: Int,
)

class HistoryStore private constructor(context: Context) {
    private val helper = HistoryDatabaseHelper(context.applicationContext)

    suspend fun insert(sample: CommuteSample) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.insertOrThrow(TABLE_SAMPLES, null, sample.toContentValues())
        }
    }

    suspend fun weekdayAverages(direction: String): List<WeekdayAverage> =
        withContext(Dispatchers.IO) {
            requireValidDirection(direction)
            val query = HistoryQueries.weekdayAverages(direction)
            helper.readableDatabase.rawQuery(query.sql, query.args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WeekdayAverage(
                                dayOfWeekIso = cursor.getInt(0),
                                avgDurationSeconds = cursor.getLong(1),
                                sampleCount = cursor.getInt(2),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun timeOfDayCurve(
        direction: String,
        bucketMinutes: Int = DEFAULT_BUCKET_MINUTES,
    ): List<TimeBucketAverage> =
        withContext(Dispatchers.IO) {
            requireValidDirection(direction)
            val query = HistoryQueries.timeOfDayCurve(direction, bucketMinutes)
            helper.readableDatabase.rawQuery(query.sql, query.args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            TimeBucketAverage(
                                bucketStartMinuteOfDay = cursor.getInt(0),
                                avgDurationSeconds = cursor.getLong(1),
                                sampleCount = cursor.getInt(2),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun datesWithData(): List<DateCount> =
        withContext(Dispatchers.IO) {
            val query = HistoryQueries.datesWithData()
            helper.readableDatabase.rawQuery(query.sql, query.args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DateCount(
                                localDate = cursor.getString(0),
                                sampleCount = cursor.getInt(1),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun deleteDate(localDate: String): Int =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete(
                TABLE_SAMPLES,
                "$COLUMN_LOCAL_DATE = ?",
                arrayOf(localDate),
            )
        }

    suspend fun clearAll(): Int =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete(TABLE_SAMPLES, null, null)
        }

    suspend fun totalCount(): Long =
        withContext(Dispatchers.IO) {
            val query = HistoryQueries.totalCount()
            helper.readableDatabase.rawQuery(query.sql, query.args.toTypedArray()).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }

    suspend fun recentSamples(limit: Int = DEFAULT_RECENT_SAMPLES_LIMIT): List<CommuteSample> =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "limit must be positive" }
            val query = HistoryQueries.recentSamples(limit)
            helper.readableDatabase.rawQuery(query.sql, query.args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.toCommuteSample())
                    }
                }
            }
        }

    private fun CommuteSample.toContentValues(): ContentValues =
        ContentValues().apply {
            put(COLUMN_TIMESTAMP_EPOCH_MILLIS, timestampEpochMillis)
            put(COLUMN_LOCAL_DATE, localDate)
            put(COLUMN_MINUTE_OF_DAY, minuteOfDay)
            put(COLUMN_DAY_OF_WEEK_ISO, dayOfWeekIso)
            put(COLUMN_DIRECTION, direction)
            put(COLUMN_DURATION_SECONDS, durationSeconds)
            put(COLUMN_STATIC_DURATION_SECONDS, staticDurationSeconds)
            put(COLUMN_DISTANCE_METERS, distanceMeters)
            put(COLUMN_SOURCE, source)
        }

    private fun Cursor.toCommuteSample(): CommuteSample =
        CommuteSample(
            timestampEpochMillis = getLong(0),
            localDate = getString(1),
            minuteOfDay = getInt(2),
            dayOfWeekIso = getInt(3),
            direction = getString(4),
            durationSeconds = getLong(5),
            staticDurationSeconds = getLong(6),
            distanceMeters = getLong(7),
            source = getString(8),
        )

    private class HistoryDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_SAMPLES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_TIMESTAMP_EPOCH_MILLIS INTEGER NOT NULL,
                    $COLUMN_LOCAL_DATE TEXT NOT NULL,
                    $COLUMN_MINUTE_OF_DAY INTEGER NOT NULL,
                    $COLUMN_DAY_OF_WEEK_ISO INTEGER NOT NULL,
                    $COLUMN_DIRECTION TEXT NOT NULL,
                    $COLUMN_DURATION_SECONDS INTEGER NOT NULL,
                    $COLUMN_STATIC_DURATION_SECONDS INTEGER NOT NULL,
                    $COLUMN_DISTANCE_METERS INTEGER NOT NULL,
                    $COLUMN_SOURCE TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX $INDEX_SAMPLES_DATE ON $TABLE_SAMPLES($COLUMN_LOCAL_DATE)")
            db.execSQL(
                "CREATE INDEX $INDEX_SAMPLES_DIR_DOW ON $TABLE_SAMPLES($COLUMN_DIRECTION, $COLUMN_DAY_OF_WEEK_ISO)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val DATABASE_NAME = "commute_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SAMPLES = "commute_samples"
        private const val COLUMN_TIMESTAMP_EPOCH_MILLIS = "timestamp_epoch_millis"
        private const val COLUMN_LOCAL_DATE = "local_date"
        private const val COLUMN_MINUTE_OF_DAY = "minute_of_day"
        private const val COLUMN_DAY_OF_WEEK_ISO = "day_of_week_iso"
        private const val COLUMN_DIRECTION = "direction"
        private const val COLUMN_DURATION_SECONDS = "duration_seconds"
        private const val COLUMN_STATIC_DURATION_SECONDS = "static_duration_seconds"
        private const val COLUMN_DISTANCE_METERS = "distance_meters"
        private const val COLUMN_SOURCE = "source"
        private const val INDEX_SAMPLES_DATE = "idx_samples_date"
        private const val INDEX_SAMPLES_DIR_DOW = "idx_samples_dir_dow"
        private const val DEFAULT_BUCKET_MINUTES = 10
        private const val DEFAULT_RECENT_SAMPLES_LIMIT = 50

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context.applicationContext).also { instance = it }
            }
    }
}

internal data class SqlQuery(
    val sql: String,
    val args: List<String>,
)

internal object HistoryQueries {
    fun weekdayAverages(direction: String): SqlQuery =
        SqlQuery(
            sql =
                """
                SELECT day_of_week_iso, AVG(duration_seconds), COUNT(*)
                FROM commute_samples
                WHERE direction = ?
                GROUP BY day_of_week_iso
                ORDER BY day_of_week_iso ASC
                """.trimIndent(),
            args = listOf(direction),
        )

    fun timeOfDayCurve(direction: String, bucketMinutes: Int): SqlQuery {
        require(bucketMinutes > 0) { "bucketMinutes must be positive" }
        return SqlQuery(
            sql =
                """
                SELECT (minute_of_day / ?) * ?, AVG(duration_seconds), COUNT(*)
                FROM commute_samples
                WHERE direction = ?
                GROUP BY (minute_of_day / ?) * ?
                ORDER BY (minute_of_day / ?) * ? ASC
                """.trimIndent(),
            args =
                listOf(
                    bucketMinutes.toString(),
                    bucketMinutes.toString(),
                    direction,
                    bucketMinutes.toString(),
                    bucketMinutes.toString(),
                    bucketMinutes.toString(),
                    bucketMinutes.toString(),
                ),
        )
    }

    fun datesWithData(): SqlQuery =
        SqlQuery(
            sql =
                """
                SELECT local_date, COUNT(*)
                FROM commute_samples
                GROUP BY local_date
                ORDER BY local_date DESC
                """.trimIndent(),
            args = emptyList(),
        )

    fun totalCount(): SqlQuery = SqlQuery("SELECT COUNT(*) FROM commute_samples", emptyList())

    fun recentSamples(limit: Int): SqlQuery {
        require(limit > 0) { "limit must be positive" }
        return SqlQuery(
            sql =
                """
                SELECT timestamp_epoch_millis, local_date, minute_of_day, day_of_week_iso,
                    direction, duration_seconds, static_duration_seconds, distance_meters, source
                FROM commute_samples
                ORDER BY timestamp_epoch_millis DESC
                LIMIT ?
                """.trimIndent(),
            args = listOf(limit.toString()),
        )
    }
}
