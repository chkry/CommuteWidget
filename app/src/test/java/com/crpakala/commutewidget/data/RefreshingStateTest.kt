package com.crpakala.commutewidget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshingStateTest {
    private val staleAfterMillis = 60_000L

    @Test
    fun isRefreshingActive_nullIsInactive() {
        assertFalse(isRefreshingActive(null, nowEpochMillis = 1_000L, staleAfterMillis))
    }

    @Test
    fun isRefreshingActive_freshIsActive() {
        assertTrue(
            isRefreshingActive(
                sinceEpochMillis = 1_000L,
                nowEpochMillis = 1_000L + staleAfterMillis - 1,
                staleAfterMillis = staleAfterMillis,
            ),
        )
    }

    @Test
    fun isRefreshingActive_atBoundaryIsInactive() {
        assertFalse(
            isRefreshingActive(
                sinceEpochMillis = 1_000L,
                nowEpochMillis = 1_000L + staleAfterMillis,
                staleAfterMillis = staleAfterMillis,
            ),
        )
    }

    @Test
    fun isRefreshingActive_beyondBoundaryIsInactive() {
        assertFalse(
            isRefreshingActive(
                sinceEpochMillis = 1_000L,
                nowEpochMillis = 1_000L + staleAfterMillis + 1,
                staleAfterMillis = staleAfterMillis,
            ),
        )
    }
}
