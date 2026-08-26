package com.crpakala.commutewidget.data

/**
 * Returns whether a refresh started at [sinceEpochMillis] should still be treated as in progress.
 * A null [sinceEpochMillis] or elapsed time at or beyond [staleAfterMillis] is inactive.
 */
fun isRefreshingActive(
    sinceEpochMillis: Long?,
    nowEpochMillis: Long,
    staleAfterMillis: Long = 60_000L,
): Boolean {
    if (sinceEpochMillis == null) {
        return false
    }
    return nowEpochMillis - sinceEpochMillis < staleAfterMillis
}
