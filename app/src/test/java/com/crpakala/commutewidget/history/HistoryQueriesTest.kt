package com.crpakala.commutewidget.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryQueriesTest {
    @Test
    fun weekdayAverages_buildsParameterizedQuery() {
        assertEquals(
            SqlQuery(
                sql =
                    """
                    SELECT day_of_week_iso, AVG(duration_seconds), COUNT(*)
                    FROM commute_samples
                    WHERE direction = ?
                    GROUP BY day_of_week_iso
                    ORDER BY day_of_week_iso ASC
                    """.trimIndent(),
                args = listOf("TO_WORK"),
            ),
            HistoryQueries.weekdayAverages("TO_WORK"),
        )
    }

    @Test
    fun timeOfDayCurve_buildsSqlIntegerDivisionQuery() {
        assertEquals(
            SqlQuery(
                sql =
                    """
                    SELECT (minute_of_day / ?) * ?, AVG(duration_seconds), COUNT(*)
                    FROM commute_samples
                    WHERE direction = ?
                    GROUP BY (minute_of_day / ?) * ?
                    ORDER BY (minute_of_day / ?) * ? ASC
                    """.trimIndent(),
                args = listOf("15", "15", "TO_HOME", "15", "15", "15", "15"),
            ),
            HistoryQueries.timeOfDayCurve("TO_HOME", 15),
        )
    }

    @Test
    fun datesWithData_buildsDescendingDateQuery() {
        assertEquals(
            SqlQuery(
                sql =
                    """
                    SELECT local_date, COUNT(*)
                    FROM commute_samples
                    GROUP BY local_date
                    ORDER BY local_date DESC
                    """.trimIndent(),
                args = emptyList(),
            ),
            HistoryQueries.datesWithData(),
        )
    }

    @Test
    fun totalCount_buildsCountQuery() {
        assertEquals(
            SqlQuery("SELECT COUNT(*) FROM commute_samples", emptyList()),
            HistoryQueries.totalCount(),
        )
    }

    @Test
    fun recentSamples_buildsParameterizedLimitQuery() {
        assertEquals(
            SqlQuery(
                sql =
                    """
                    SELECT timestamp_epoch_millis, local_date, minute_of_day, day_of_week_iso,
                        direction, duration_seconds, static_duration_seconds, distance_meters, source
                    FROM commute_samples
                    ORDER BY timestamp_epoch_millis DESC
                    LIMIT ?
                    """.trimIndent(),
                args = listOf("50"),
            ),
            HistoryQueries.recentSamples(50),
        )
    }

    @Test
    fun builders_rejectInvalidBucketsAndLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryQueries.timeOfDayCurve("TO_WORK", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryQueries.recentSamples(0)
        }
    }
}
