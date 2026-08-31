package com.crpakala.commutewidget.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSettingsHelpersTest {
    @Test
    fun healthPermissionsRequireEveryDeclaredPermission() {
        val required = setOf("read.steps", "write.water")

        assertTrue(hasAllHealthPermissions(required, required))
        assertTrue(hasAllHealthPermissions(required + "extra", required))
        assertFalse(hasAllHealthPermissions(setOf("read.steps"), required))
    }
}
