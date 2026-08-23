package io.motohub.android.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoNightModeTest {
    @Test
    fun `auto follows the phone night bit`() {
        assertTrue(AndroidAutoNightMode.AUTO.isNight(systemNight = true))
        assertFalse(AndroidAutoNightMode.AUTO.isNight(systemNight = false))
    }

    @Test
    fun `day stays light regardless of the phone theme`() {
        assertFalse(AndroidAutoNightMode.DAY.isNight(systemNight = true))
        assertFalse(AndroidAutoNightMode.DAY.isNight(systemNight = false))
    }

    @Test
    fun `night stays dark regardless of the phone theme`() {
        assertTrue(AndroidAutoNightMode.NIGHT.isNight(systemNight = true))
        assertTrue(AndroidAutoNightMode.NIGHT.isNight(systemNight = false))
    }
}
