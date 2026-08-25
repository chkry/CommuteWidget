package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class ActiveFavourite(
    val favourite: Favourite,
    val activatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

fun isActive(af: ActiveFavourite?, nowEpochMillis: Long): Boolean {
    if (af == null) {
        return false
    }
    return nowEpochMillis < af.expiresAtEpochMillis
}
