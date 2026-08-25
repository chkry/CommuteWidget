package com.crpakala.commutewidget.api

data class LatLng(val lat: Double, val lng: Double)

enum class RouteTravelMode { DRIVE, TWO_WHEELER }

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()
}
