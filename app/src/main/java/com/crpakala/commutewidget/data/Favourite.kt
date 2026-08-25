package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class Favourite(
    val label: String,
    val place: Place,
)
