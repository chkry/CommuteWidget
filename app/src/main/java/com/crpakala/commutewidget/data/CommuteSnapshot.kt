package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class CommuteSnapshot(
    val direction: Direction,
    val durationSeconds: Long,
    val durationNoTrafficSeconds: Long,
    val distanceMeters: Long,
    val mapImagePath: String?,
    val fetchedAtEpochMillis: Long,
    val lastFetchFailed: Boolean,
    val lastErrorMessage: String?,
)
