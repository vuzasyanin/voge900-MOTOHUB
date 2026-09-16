package io.motohub.android.androidauto

/** Pure recovery-timing logic for a full Android Auto session — no AGPL dependency, kept in
 *  shared code (and tested from both flavors) even though only Core's AndroidAutoSessionService
 *  currently calls it. */
internal fun shouldAutoRecoverAndroidAuto(
    hasReachedStreaming: Boolean,
    enabled: Boolean
): Boolean = hasReachedStreaming && enabled

/**
 * EasyConn recovery must not run while the T-Box link is gone. It rediscovers over the link
 * and, when that network is null, submits a second WifiNetworkSpecifier that fights the
 * connector's rejoin ladder — the two exclusive requests then tear each other down.
 *
 * [linkAvailable] is "the radio link to the dash is up right now", not "ConnectivityManager has
 * a Network". A Wi-Fi Direct group never produces one, so asking the connector would answer
 * "gone" for a perfectly live P2P link — see [AndroidAutoSessionService.linkStillUp].
 */
internal fun shouldDeferEasyConnRecovery(
    parked: Boolean,
    linkAvailable: Boolean
): Boolean = parked && !linkAvailable

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

/**
 * The settle exists for one thing only: an access point that is about to bounce, so that the
 * bounce becomes a Wi-Fi park rather than a discovery burnt on a dying link. A Wi-Fi Direct
 * group has no such bounce - it is either formed or gone, and [AndroidAutoSessionService]'s
 * group watcher says which within milliseconds. Waiting here is then pure latency on the one
 * path the rider feels most: Back, then Up.
 */
internal fun dashLeaveSettleMillis(wifiDirect: Boolean): Long =
    if (wifiDirect) 0L else DASH_LEAVE_SETTLE_MS

internal const val STANDARD_RECOVERY_GIVE_UP_MS = 120_000L

/** Rider may stay on the stock cluster for a while, then press Up to come back. */
internal const val DASH_LEAVE_RECOVERY_GIVE_UP_MS = 180_000L

/**
 * How long seamless resume keeps the foreground service up while the dash is away. Matches
 * [io.motohub.android.tbox.TBoxNetworkConnector]'s rejoin give-up so the specifier ladder can
 * still submit while Android treats the process as a foreground service.
 */
internal const val WIFI_PARK_MILLIS = 180_000L

/**
 * Whether [reason] describes the dash hanging up on us rather than the link failing.
 *
 * Every clause is a peer-initiated socket close. Paired with a link that is still up (see
 * [isCleanDashProjectionLeave]) that can only mean the dash left the projection page, because
 * nothing else closes an EasyConn socket from the far end while the radio holds. A stall, an
 * encoder fault or a lost group is deliberately absent: those are failures of our side or of
 * the radio, and they belong in the ordinary recovery path.
 */
internal fun looksLikeDashProjectionLeave(reason: String): Boolean {
    val text = reason.lowercase()
    return text.contains("ended android auto") ||
        (text.contains("error reading header") && text.contains("eof")) ||
        text.contains("connection reset") ||
        text.contains("broken pipe") ||
        text.contains("use of closed network connection")
}

/**
 * Back on the dash leaves the projection page and closes EasyConn while the link is
 * still up. That is expected, not a radio failure: hold Android Auto and wait for
 * the rider to return with Up.
 *
 * [linkAvailable] must describe the transport actually in use. It was `currentNetwork() != null`
 * for a long time, which is always false on a Wi-Fi Direct group: a VOGE dash on P2P could
 * therefore never reach this branch, and every Back press paid for a full rediscovery and
 * handshake instead of the short resume this exists for.
 */
internal fun isCleanDashProjectionLeave(
    hasReachedStreaming: Boolean,
    linkAvailable: Boolean,
    reasonLooksLikeLeave: Boolean
): Boolean = hasReachedStreaming && linkAvailable && reasonLooksLikeLeave

/**
 * The budget a recovery run gets before it gives up and tears the session down.
 *
 * A Wi-Fi Direct session needs the same generosity an infrastructure one already gets from
 * [WIFI_PARK_MILLIS]. There is no P2P equivalent of `TBoxNetworkEvent.Lost`, so a group that
 * dissolves - the rider killed the ignition and walked off to refuel - lands in ordinary
 * recovery rather than in the park loop, and the shorter standard budget would end the
 * foreground service while the bike is still off. That matters more than the wait: once the
 * service is gone the process drops out of foreground importance and Android starts refusing
 * `WifiP2pManager.connect()` outright, so the rider has to reopen the app by hand.
 */
internal fun recoveryGiveUpMillis(
    dashProjectionLeave: Boolean,
    wifiDirect: Boolean = false
): Long = when {
    dashProjectionLeave -> DASH_LEAVE_RECOVERY_GIVE_UP_MS
    wifiDirect -> WIFI_PARK_MILLIS
    else -> STANDARD_RECOVERY_GIVE_UP_MS
}

/** The pause between recovery attempts once a dash is no longer expected back any second. */
internal const val STANDARD_RECOVERY_RETRY_MS = 5_000L

/** The pause between recovery attempts while the rider is plausibly still on the stock cluster. */
internal const val DASH_RETURN_FAST_RETRY_MS = 1_000L

/** How long after a dash leave the rider is treated as about to press Up. */
internal const val DASH_RETURN_FAST_RETRY_WINDOW_MS = 20_000L

/**
 * How long to wait after a failed recovery attempt before making the next one.
 *
 * On a dash-page return the attempt is a single TCP connect to the endpoint the dash was just
 * on, so a rider who has not come back yet costs one refused connect - about 10ms on the
 * 2026-09-14 Xiaomi log. Pausing 5s after that spends the rider's time, not the phone's: in
 * every successful return in that log the picture was back 1.4-2.4s after the dash answered,
 * and the whole rest of the 7-13s they waited was this delay running out. A 1s grid for the
 * first [DASH_RETURN_FAST_RETRY_WINDOW_MS] turns the average of it into half a second.
 *
 * The window matters as much as the interval. Past it, either the rider is staying on the
 * stock cluster or the dash is gone (rebooting, in the same log, twice), and attempts stop
 * being cheap - a group join can run for tens of seconds - so the grid opens back up rather
 * than queueing retries behind attempts that are already long.
 */
internal fun recoveryRetryMillis(dashReturn: Boolean, elapsedMillis: Long): Long =
    if (dashReturn && elapsedMillis < DASH_RETURN_FAST_RETRY_WINDOW_MS) {
        DASH_RETURN_FAST_RETRY_MS
    } else {
        STANDARD_RECOVERY_RETRY_MS
    }
