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
        assertTrue(shouldDeferEasyConnRecovery(parked = true, linkAvailable = false))
        assertFalse(shouldDeferEasyConnRecovery(parked = true, linkAvailable = true))
        assertFalse(shouldDeferEasyConnRecovery(parked = false, linkAvailable = false))
        assertFalse(shouldDeferEasyConnRecovery(parked = false, linkAvailable = true))
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
                linkAvailable = true,
                reasonLooksLikeLeave = true
            )
        )
        assertFalse(
            isCleanDashProjectionLeave(
                hasReachedStreaming = true,
                linkAvailable = false,
                reasonLooksLikeLeave = true
            )
        )
        assertFalse(
            isCleanDashProjectionLeave(
                hasReachedStreaming = false,
                linkAvailable = true,
                reasonLooksLikeLeave = true
            )
        )
    }

    @Test
    fun `a socket the dash closed from its end is a page leave`() {
        assertTrue(looksLikeDashProjectionLeave("T-Box error: 2 (read tcp: connection reset by peer)"))
        assertTrue(looksLikeDashProjectionLeave("T-Box error: 2 (write tcp: broken pipe)"))
        assertTrue(
            looksLikeDashProjectionLeave("T-Box error: 2 (use of closed network connection)")
        )
        assertFalse(
            looksLikeDashProjectionLeave("The Wi-Fi Direct group with the dash was lost.")
        )
        assertFalse(
            looksLikeDashProjectionLeave("The T-Box no longer accepts Android Auto frames.")
        )
    }

    @Test
    fun `a live Wi-Fi Direct group counts as an available link without a Network`() {
        // The service answers linkAvailable from its P2P group watcher rather than from
        // ConnectivityManager, which never reports a Network for a Wi-Fi Direct group.
        assertTrue(
            isCleanDashProjectionLeave(
                hasReachedStreaming = true,
                linkAvailable = true,
                reasonLooksLikeLeave = true
            )
        )
        assertFalse(shouldDeferEasyConnRecovery(parked = false, linkAvailable = true))
    }

    @Test
    fun `Wi-Fi Direct skips the dash-leave settle`() {
        assertEquals(0L, dashLeaveSettleMillis(wifiDirect = true))
        assertEquals(DASH_LEAVE_SETTLE_MS, dashLeaveSettleMillis(wifiDirect = false))
    }

    @Test
    fun `dash leave keeps a longer EasyConn resume budget`() {
        assertEquals(STANDARD_RECOVERY_GIVE_UP_MS, recoveryGiveUpMillis(dashProjectionLeave = false))
        assertEquals(DASH_LEAVE_RECOVERY_GIVE_UP_MS, recoveryGiveUpMillis(dashProjectionLeave = true))
        assertTrue(DASH_LEAVE_RECOVERY_GIVE_UP_MS > STANDARD_RECOVERY_GIVE_UP_MS)
        assertTrue(DASH_LEAVE_SETTLE_MS < STANDARD_RECOVERY_GIVE_UP_MS)
    }

    @Test
    fun `Wi-Fi Direct recovery gets the same budget as an infrastructure Wi-Fi park`() {
        assertEquals(
            WIFI_PARK_MILLIS,
            recoveryGiveUpMillis(dashProjectionLeave = false, wifiDirect = true)
        )
        assertTrue(WIFI_PARK_MILLIS > STANDARD_RECOVERY_GIVE_UP_MS)
        // A dash-page leave is already the longest budget, on either transport.
        assertEquals(
            DASH_LEAVE_RECOVERY_GIVE_UP_MS,
            recoveryGiveUpMillis(dashProjectionLeave = true, wifiDirect = true)
        )
    }

    @Test
    fun `a rider who may press Up any second is polled on a tight grid`() {
        assertEquals(DASH_RETURN_FAST_RETRY_MS, recoveryRetryMillis(dashReturn = true, elapsedMillis = 0L))
        assertEquals(
            DASH_RETURN_FAST_RETRY_MS,
            recoveryRetryMillis(dashReturn = true, elapsedMillis = DASH_RETURN_FAST_RETRY_WINDOW_MS - 1)
        )
        assertTrue(DASH_RETURN_FAST_RETRY_MS < STANDARD_RECOVERY_RETRY_MS)
    }

    @Test
    fun `the grid opens back up once attempts stop being cheap`() {
        assertEquals(
            STANDARD_RECOVERY_RETRY_MS,
            recoveryRetryMillis(dashReturn = true, elapsedMillis = DASH_RETURN_FAST_RETRY_WINDOW_MS)
        )
        assertEquals(
            STANDARD_RECOVERY_RETRY_MS,
            recoveryRetryMillis(dashReturn = true, elapsedMillis = 120_000L)
        )
    }

    @Test
    fun `a recovery that is not a dash return keeps the standard pause`() {
        assertEquals(
            STANDARD_RECOVERY_RETRY_MS,
            recoveryRetryMillis(dashReturn = false, elapsedMillis = 0L)
        )
        assertEquals(
            STANDARD_RECOVERY_RETRY_MS,
            recoveryRetryMillis(dashReturn = false, elapsedMillis = 60_000L)
        )
    }
}
