package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class EventRouteOriginTest {
    private val home = Place(address = "Home", lat = 12.9487089, lng = 77.7214219)
    private val deviceFix = LatLng(12.9120671, 77.6450194)

    @Test
    fun origin_usesDeviceFixWhenAvailable() {
        assertEquals(deviceFix, eventRouteOrigin(ApiResult.Success(deviceFix), home))
    }

    @Test
    fun origin_fallsBackToHomeWhenFixFails() {
        val origin = eventRouteOrigin(ApiResult.Failure("Location unavailable"), home)
        assertEquals(LatLng(home.lat, home.lng), origin)
    }

    @Test
    fun origin_fallsBackToHomeForAnyFailureCause() {
        val origin = eventRouteOrigin(ApiResult.Failure("Network error", RuntimeException("boom")), home)
        assertEquals(LatLng(home.lat, home.lng), origin)
    }
}
