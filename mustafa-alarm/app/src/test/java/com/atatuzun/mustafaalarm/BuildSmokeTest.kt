package com.atatuzun.mustafaalarm

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSmokeTest {
    @Test
    fun applicationIdIsMustafaAlarm() {
        assertEquals("com.atatuzun.mustafaalarm", BuildConfig.APPLICATION_ID)
    }
}
