package com.crpakala.commutewidget.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure assertion that the requested permission strings match exactly what is declared in
 * AndroidManifest.xml. A drift here means Health Connect will silently deny access.
 */
class HealthConnectFacadeTest {
    @Test
    fun requiredPermissions_matchManifestDeclarations() {
        val expected = setOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.WRITE_HYDRATION",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
        )

        assertEquals(expected, HealthConnectFacade.REQUIRED_PERMISSIONS)
    }
}
