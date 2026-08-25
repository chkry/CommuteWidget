package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class Place(
    val address: String,
    val lat: Double,
    val lng: Double,
)
