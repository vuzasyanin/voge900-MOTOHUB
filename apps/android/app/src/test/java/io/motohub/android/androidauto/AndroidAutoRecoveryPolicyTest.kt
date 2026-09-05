package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoRecoveryPolicyTest {
    @Test
    fun `recovery requires both an enabled preference and prior streaming`() {
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = false))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = true))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = false))
        assertTrue(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = true))
    }

    @Test
    fun `watchdog waits until the complete stall threshold`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 19_999L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
        assertTrue(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 20_000L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
    }

    @Test
    fun `watchdog ignores an uninitialized progress clock`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 30_000L,
                lastProgressElapsed = 0L,
                thresholdMillis = 10_000L
            )
        )
    }

    @Test
    fun `EasyConn recovery waits while Wi-Fi is parked and gone`() {
        assertTrue(shouldDeferEasyConnRecovery(wifiParked = true, networkAvailable = false))
        assertFalse(shouldDeferEasyConnRecovery(wifiParked = true, networkAvailable = true))
        assertFalse(shouldDeferEasyConnRecovery(wifiParked = false, networkAvailable = false))
        assertFalse(shouldDeferEasyConnRecovery(wifiParked = false, networkAvailable = true))
    }

    @Test
    fun `AAP drop keeps the session only while parked for Wi-Fi rejoin`() {
        assertTrue(
            shouldHoldSessionForWifiRejoin(
                userExit = false,
                seamlessResume = true,
                wifiParked = true
            )
        )
        assertFalse(
            shouldHoldSessionForWifiRejoin(
                userExit = true,
                seamlessResume = true,
                wifiParked = true
            )
        )
        assertFalse(
            shouldHoldSessionForWifiRejoin(
                userExit = false,
                seamlessResume = false,
                wifiParked = true
            )
        )
        assertFalse(
            shouldHoldSessionForWifiRejoin(
                userExit = false,
                seamlessResume = true,
                wifiParked = false
            )
        )
    }

    @Test
    fun `EOF while streaming on live Wi-Fi is a dash page leave`() {
        assertTrue(
            looksLikeDashProjectionLeave("The T-Box ended Android Auto.")
        )
        assertTrue(
            looksLikeDashProjectionLeave("T-Box error: 2 (error reading header: EOF)")
        )
        assertTrue(
            looksLikeDashProjectionLeave("T-Box error: 1 (error reading header: EOF (read 0 bytes: ))")
        )
        assertFalse(
            looksLikeDashProjectionLeave("Android Auto TFT stream stalled for at least 10 seconds.")
        )
        assertFalse(looksLikeDashProjectionLeave("Android Auto encoder stopped: codec reset"))
        assertTrue(
            isCleanDashProjectionLeave(
                hasReachedStreaming = true,
                wifiAvailable = true,
                reasonLooksLikeLeave = true
            )
        )
        assertFalse(
            isCleanDashProjectionLeave(
                hasReachedStreaming = true,
                wifiAvailable = false,
                reasonLooksLikeLeave = true
            )
        )
        assertFalse(
            isCleanDashProjectionLeave(
                hasReachedStreaming = false,
                wifiAvailable = true,
                reasonLooksLikeLeave = true
            )
        )
    }

    @Test
    fun `dash leave keeps a longer EasyConn resume budget`() {
        assertEquals(STANDARD_RECOVERY_GIVE_UP_MS, recoveryGiveUpMillis(dashProjectionLeave = false))
        assertEquals(DASH_LEAVE_RECOVERY_GIVE_UP_MS, recoveryGiveUpMillis(dashProjectionLeave = true))
        assertTrue(DASH_LEAVE_RECOVERY_GIVE_UP_MS > STANDARD_RECOVERY_GIVE_UP_MS)
        assertTrue(DASH_LEAVE_SETTLE_MS < STANDARD_RECOVERY_GIVE_UP_MS)
    }
}
