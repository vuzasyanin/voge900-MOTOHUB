package io.motohub.android.androidauto

/** Pure recovery-timing logic for a full Android Auto session — no AGPL dependency, kept in
 *  shared code (and tested from both flavors) even though only Core's AndroidAutoSessionService
 *  currently calls it. */
internal fun shouldAutoRecoverAndroidAuto(
    hasReachedStreaming: Boolean,
    enabled: Boolean
): Boolean = hasReachedStreaming && enabled

/**
 * EasyConn recovery must not run while the T-Box Wi-Fi is gone. It rediscovers over the link
 * and, when that network is null, submits a second WifiNetworkSpecifier that fights the
 * connector's rejoin ladder — the two exclusive requests then tear each other down.
 */
internal fun shouldDeferEasyConnRecovery(
    wifiParked: Boolean,
    networkAvailable: Boolean
): Boolean = wifiParked && !networkAvailable

/**
 * An unexpected AAP drop is not session-fatal while seamless resume is holding the
 * foreground service for Wi-Fi rejoin. Google's head-unit server dies about 15s after the
 * dash AP vanishes; tearing the service down then drops process importance so Android
 * refuses the next specifier request.
 */
internal fun shouldHoldSessionForWifiRejoin(
    userExit: Boolean,
    seamlessResume: Boolean,
    wifiParked: Boolean
): Boolean = !userExit && seamlessResume && wifiParked

internal fun isAndroidAutoWatchdogStalled(
    nowElapsed: Long,
    lastProgressElapsed: Long,
    thresholdMillis: Long
): Boolean = lastProgressElapsed > 0L && nowElapsed - lastProgressElapsed >= thresholdMillis

/**
 * How long to wait after a clean dash-page leave before burning EasyConn discovery.
 * VOGE/CFMOTO firmware often closes EasyConn immediately on Back, then tears the AP
 * down ~20s later when the rider is still on the stock cluster. A short settle lets
 * [shouldDeferEasyConnRecovery] win instead of a 30s NSD sit on a dying link.
 */
internal const val DASH_LEAVE_SETTLE_MS = 8_000L

internal const val STANDARD_RECOVERY_GIVE_UP_MS = 120_000L

/** Rider may stay on the stock cluster for a while, then press Up to come back. */
internal const val DASH_LEAVE_RECOVERY_GIVE_UP_MS = 180_000L

internal fun looksLikeDashProjectionLeave(reason: String): Boolean {
    val text = reason.lowercase()
    return text.contains("ended android auto") ||
        (text.contains("error reading header") && text.contains("eof"))
}

/**
 * Back on the dash leaves the projection page and closes EasyConn while Wi-Fi is
 * still up. That is expected, not a radio failure: hold Android Auto and wait for
 * the rider to return with Up.
 */
internal fun isCleanDashProjectionLeave(
    hasReachedStreaming: Boolean,
    wifiAvailable: Boolean,
    reasonLooksLikeLeave: Boolean
): Boolean = hasReachedStreaming && wifiAvailable && reasonLooksLikeLeave

internal fun recoveryGiveUpMillis(dashProjectionLeave: Boolean): Long =
    if (dashProjectionLeave) DASH_LEAVE_RECOVERY_GIVE_UP_MS else STANDARD_RECOVERY_GIVE_UP_MS
