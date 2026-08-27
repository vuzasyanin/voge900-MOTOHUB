package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.LogLevel
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

sealed interface TBoxNetworkEvent {
    data class Lost(val network: Network) : TBoxNetworkEvent
    data class Reacquired(val network: Network) : TBoxNetworkEvent
}

/** What the T-Box Wi-Fi rejoin ladder does next. */
internal sealed interface TBoxRejoinStep {
    /**
     * Serve this attempt's backoff, then come back here and decide again.
     * The wait is a suspension, and the process can leave the foreground inside it.
     */
    data class WaitThenRetry(val delayMillis: Long) : TBoxRejoinStep

    /**
     * Android would drop a specifier request made from where this process currently sits, so the
     * ladder waits instead of spending an attempt on a refusal that never reaches the radio.
     */
    data class WaitForForeground(val delayMillis: Long) : TBoxRejoinStep

    /** The backoff is served and this process may ask: submit, and spend the attempt. */
    data object SubmitNow : TBoxRejoinStep
    data object GiveUp : TBoxRejoinStep
}

/**
 * Decides the ladder's next move: a quick first retry for the ordinary blip, then a growing and
 * capped wait, and eventually surrender.
 *
 * [submissionWouldBeRefused] is the difference between a rejoin that could work and one that
 * cannot: `WifiNetworkFactory` drops a specifier request from a process past
 * `IMPORTANCE_FOREGROUND_SERVICE` without ever looking for the AP.
 *
 * [backoffElapsed] is why a submission is a step of its own rather than something the caller does
 * after sleeping on [TBoxRejoinStep.WaitThenRetry]. The caller sleeps and comes back, so the
 * background rule is re-applied to a fresh reading immediately before the submission it governs.
 */
internal fun nextTBoxRejoinStep(
    attempt: Int,
    elapsedMillis: Long,
    budgetMillis: Long,
    firstDelayMillis: Long,
    baseDelayMillis: Long,
    maxDelayMillis: Long,
    submissionWouldBeRefused: Boolean = false,
    backgroundPollMillis: Long = 0L,
    backoffElapsed: Boolean = false
): TBoxRejoinStep {
    if (elapsedMillis >= budgetMillis) return TBoxRejoinStep.GiveUp
    if (submissionWouldBeRefused) return TBoxRejoinStep.WaitForForeground(backgroundPollMillis)
    if (backoffElapsed) return TBoxRejoinStep.SubmitNow
    val delay = if (attempt <= 1) {
        firstDelayMillis
    } else {
        (baseDelayMillis * (attempt - 1)).coerceAtMost(maxDelayMillis)
    }
    return TBoxRejoinStep.WaitThenRetry(delay)
}

/** Requests the T-Box AP explicitly and binds the process for its reverse TCP servers. */
class TBoxNetworkConnector(context: Context) {
    private val appContext = context.applicationContext
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    @Volatile
    private var hiddenSsidFallbackLogged = false
    private val mutableEvents = MutableSharedFlow<TBoxNetworkEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TBoxNetworkEvent> = mutableEvents.asSharedFlow()

    /**
     * Guards the whole network-request lifecycle: clearing the previous registration, storing the
     * new one and handing it to ConnectivityManager have to happen as one step.
     *
     * They did not, and two callers - a connect() from the UI or the AIDL bridge, and the rejoin
     * ladder below - could interleave their clear/store pairs. Both callbacks ended up registered
     * with ConnectivityManager while the connector tracked only the last one. The untracked one
     * was unreachable forever: it kept receiving onLost, kept calling [scheduleRejoin], and no
     * disconnect() could ever release it. A rider's diagnostics showed two rejoin ladders running
     * in lockstep for nineteen minutes, each new exclusive WifiNetworkSpecifier request tearing
     * down the network the other had just been granted - and still fighting the rider's own manual
     * reconnect at the end of it.
     */
    private val requestLock = Any()

    /**
     * Every callback currently registered with ConnectivityManager. [callback] is the one the
     * current attempt owns; this set is what guarantees none of the others can be orphaned.
     */
    private val registeredCallbacks = mutableSetOf<ConnectivityManager.NetworkCallback>()
    /** Whether this connector is one of [liveRequesters]; see syncLiveRequesterCount. */
    private var countedAsLiveRequester = false

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var processBoundNetwork: Network? = null
    // Radio trail for the active network; see sampleLinkQuality. All written from the network
    // callback thread and read from it or from onLost, which the framework serialises.
    private var lastLinkSampleAtMs = 0L
    private var lastSampleTakenAtMs = 0L
    private var lastSampledRssi = UNSAMPLED_RSSI
    private var lastSampleDescription: String? = null
    @Volatile
    private var activeProfile: MotorcycleProfile? = null
    @Volatile
    private var connectedOnce = false
    /**
     * True between [releaseProcessBinding] and [rebindProcessToTBox]: the route was released on
     * purpose (a local projection start needs the internet route for Google's servers) and the
     * persistent callback must not re-bind it on ordinary DHCP/IPv6 link updates.
     */
    @Volatile
    private var processBindingSuspended = false
    @Volatile
    private var rejoinJob: Job? = null

    /**
     * One rejoin ladder at a time. `rejoinJob?.isActive` was a check-then-act across threads:
     * two onLost callbacks 6ms apart both passed it and started their own ladder.
     */
    private val rejoinActive = AtomicBoolean(false)

    /**
     * Identifies the ladder that currently owns [rejoinJob] and [rejoinActive], so a cancelled
     * ladder's `finally` cannot clear state that already belongs to its successor.
     */
    private val ladderToken = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    @Volatile
    private var simulatorMonitorJob: Job? = null

    /**
     * SSID of the specifier request currently registered with ConnectivityManager, whether or
     * not a network has been granted yet. What lets a retry for the same bike join the hunt
     * already in progress instead of restarting it — see [connect].
     */
    @Volatile
    private var pendingRequestSsid: String? = null
    /** When the live specifier request was submitted, so onUnavailable can say how fast it came. */
    @Volatile
    private var specifierSubmittedAt = 0L

    /**
     * Process importance at submit time. Android refuses a specifier request from a process that
     * is neither a foreground app nor a foreground service, and the refusal is indistinguishable
     * from "the dash is not broadcasting" unless this number is in the log next to it.
     */
    @Volatile
    private var specifierSubmitImportance = 0

    /** Terminal failure produced by the registered callback, observed by [awaitRequestedNetwork]. */
    @Volatile
    private var pendingFailure: Throwable? = null

    /**
     * Whether Android ever granted a network for the request currently registered - set by
     * `onAvailable`, which fires on association, well before any IP address exists.
     *
     * Separates the two ways a join can run out of time: the phone never associated to the AP at
     * all, or it associated and the dash never handed out a usable IPv4. They have different
     * causes and different remedies, and used to share one message that named only the second.
     */
    @Volatile
    private var networkGranted = false

    @Volatile
    private var pendingGiveUpJob: Job? = null

    suspend fun connect(profile: MotorcycleProfile): Result<Network> {
        if (TBoxModelProfile.fromModelId(profile.modelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR) {
            disconnect()
            activeProfile = profile
            connectedOnce = false
            processBindingSuspended = false
            return try {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Simulator profile detected for SSID ${profile.ssid}; reusing the phone's existing Wi-Fi " +
                        "instead of requesting a WifiNetworkSpecifier."
                )
                val network = withTimeout(CONNECTION_TIMEOUT_MS) { findExistingWifi(profile.ssid) }
                connectedOnce = true
                startSimulatorMonitor(profile)
                Result.success(network)
            } catch (_: TimeoutCancellationException) {
                ProjectionEventLog.error("NETWORK", "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms.")
                disconnect()
                Result.failure(
                    IllegalStateException(
                        "The simulator requires the phone and Mac to be connected to the same Wi-Fi network " +
                            "with a usable IPv4 address."
                    )
                )
            } catch (cancelled: CancellationException) {
                // A real cancellation (user cancel, scope teardown) must propagate, not become a Result.
                throw cancelled
            } catch (failure: Throwable) {
                ProjectionEventLog.error("NETWORK", "T-Box AP request failed.", failure)
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(
                    failure,
                    TBoxVpnDiagnostics.inspect(connectivityManager, dashAddress = null)
                )
                Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
        }

        // Session watchdogs retry through here every ~35s while recovering a dropped ride, and
        // with the screen off Android scans for Wi-Fi so rarely that a 30s window may not contain
        // a single scan. Tearing the exclusive request down and re-submitting on every attempt
        // kept resetting that hunt right before it could succeed (road test 2026-07-29: four
        // consecutive "Wi-Fi setup timed out" while recovering the CFDL16 mid-ride). Reuse what
        // is already there instead: first the granted network, then the still-pending request.
        if (activeProfile?.ssid == profile.ssid) {
            activeNetwork?.let { existing ->
                val rebindFailure = runCatching {
                    if (!processBindingSuspended && processBoundNetwork == null) {
                        check(connectivityManager.bindProcessToNetwork(existing)) {
                            "Android cannot restore the binding to the T-Box network."
                        }
                        processBoundNetwork = existing
                    }
                }.exceptionOrNull()
                if (rebindFailure == null) {
                    ProjectionEventLog.record(
                        "NETWORK",
                        "Reusing the active T-Box network $existing for ${profile.ssid}."
                    )
                    return Result.success(existing)
                }
                ProjectionEventLog.warning(
                    "NETWORK",
                    "Active T-Box network could not be re-bound; requesting a fresh one.",
                    rebindFailure
                )
            }
            if (pendingRequestSsid == profile.ssid) {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Joining the pending Wi-Fi request for ${profile.ssid} instead of re-submitting it."
                )
                return awaitRequestedNetwork(profile)
            }
        }

        disconnect()
        activeProfile = profile
        connectedOnce = false
        processBindingSuspended = false
        ProjectionEventLog.record(
            "NETWORK",
            "Requesting Android Wi-Fi network for SSID ${profile.ssid}; passwordPresent=${profile.password.isNotEmpty()}."
        )
        submitSpecifierRequest(profile)
        return awaitRequestedNetwork(profile)
    }

    fun disconnect() {
        // Invalidate the token first: the cancelled ladder's finally then finds the gate no
        // longer its own and leaves whatever comes next untouched.
        ladderToken.set(null)
        rejoinJob?.cancel()
        rejoinJob = null
        rejoinActive.set(false)
        simulatorMonitorJob?.cancel()
        simulatorMonitorJob = null
        activeProfile = null
        connectedOnce = false
        clearCurrentNetworkRequest()
    }

    private fun clearCurrentNetworkRequest() {
        synchronized(requestLock) { clearCurrentNetworkRequestLocked() }
    }

    /** Releases the process binding and *every* registered callback. Call under [requestLock]. */
    private fun clearCurrentNetworkRequestLocked() {
        ProjectionEventLog.debug(
            "NETWORK",
            "Disconnect requested; callbacks=${registeredCallbacks.size}, " +
                "activeNetwork=$activeNetwork, processBound=$processBoundNetwork."
        )
        callback = null
        pendingRequestSsid?.let { TBoxRequestGiveUpAlarm.disarm(appContext, it) }
        pendingRequestSsid = null
        pendingFailure = null
        networkGranted = false
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
        if (processBoundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            processBoundNetwork = null
        }
        activeNetwork = null
        val released = registeredCallbacks.toList()
        registeredCallbacks.clear()
        syncLiveRequesterCount()
        released.forEach { unregister(it) }
    }

    /** Releases one attempt's registration without touching a newer attempt's state. */
    private fun releaseCallback(target: ConnectivityManager.NetworkCallback) {
        synchronized(requestLock) {
            if (callback === target) {
                callback = null
                // This registration was the pending request; without it there is nothing left
                // for a retry to join.
                pendingRequestSsid = null
            }
            if (registeredCallbacks.remove(target)) unregister(target)
            syncLiveRequesterCount()
        }
    }

    /**
     * Keeps the process-wide count of connectors that currently hold a Wi-Fi request, and says so
     * the moment there is more than one.
     *
     * Two live connectors mean two `WifiNetworkSpecifier` requests for the same SSID, each with
     * its own rejoin ladder - and releasing either one tears down the association the other is
     * using. The phone then disconnects itself from the dash at full signal, which is
     * indistinguishable from the dash hanging up unless you happen to notice that every network
     * line in the log appears two or three times. That is how it was found (OnePlus, 1.1.24,
     * 2026-07-30, after the companion app's process was killed mid-session), and it is not
     * something a rider can reproduce on request: this line exists so the next log states it
     * outright instead of requiring someone to spot duplicated timestamps by eye.
     *
     * Counted per connector rather than per registration: one connector legitimately holds a
     * second callback for a moment while a rejoin attempt overlaps the previous one.
     */
    private fun syncLiveRequesterCount() {
        val holdsRequest = registeredCallbacks.isNotEmpty()
        if (holdsRequest == countedAsLiveRequester) return
        countedAsLiveRequester = holdsRequest
        val live = if (holdsRequest) {
            liveRequesters.incrementAndGet()
        } else {
            liveRequesters.decrementAndGet()
        }
        if (holdsRequest && live > 1) {
            // ERROR, because this is a fault in this app and nothing about it is the rider's
            // doing - and because at WARNING it never left the phone, so a bug we can already
            // detect exactly had no fleet numbers behind it at all. Only the first occurrence
            // per process is reported: once two connectors are fighting they re-register for as
            // long as the thrash lasts (thirteen times in the 2026-07-31 OnePlus log), and the
            // repeats say nothing the first one did not.
            val firstInProcess = duplicateRequestReported.compareAndSet(false, true)
            ProjectionEventLog.record(
                "NETWORK",
                "$live T-Box network connectors now hold a Wi-Fi request at the same time. They " +
                    "compete for the same association and releasing one drops the others, so " +
                    "expect connections that are granted and lost within a second. This is a " +
                    "MOTO-HUB fault, not the dash.",
                LogLevel.ERROR,
                reportToTelemetry = firstInProcess
            )
        }
    }

    private fun unregister(target: ConnectivityManager.NetworkCallback) {
        runCatching { connectivityManager.unregisterNetworkCallback(target) }
            .onFailure {
                ProjectionEventLog.warning("NETWORK", "Network callback unregister failed.", it)
            }
    }

    /** Current Wi-Fi network confirmed by the SSID-specific request callback, if still active. */
    fun currentNetwork(): Network? = activeNetwork

    /**
     * Whether this connector's live-or-pending Wi-Fi request already targets [ssid] - the same
     * check [connect] uses internally to decide whether a retry can reuse it instead of resetting
     * Android's join hunt from zero. Exposed so a caller that owns *this connector's identity*
     * (deciding whether to hand it back out for another attempt, rather than building a new one)
     * can make that call before [connect] ever runs.
     */
    fun isHuntingFor(ssid: String): Boolean = activeProfile?.ssid == ssid

    /** Waits for the persistent network request to reacquire the T-Box AP. */
    suspend fun awaitNetworkAvailable(timeoutMillis: Long): Network? =
        withTimeoutOrNull(timeoutMillis) {
            var network: Network? = currentNetwork()
            while (network == null) {
                delay(NETWORK_POLL_MS)
                network = currentNetwork()
            }
            network
        }

    /** Keeps the requested T-Box network alive but restores Android's normal process route. */
    @Synchronized
    fun releaseProcessBinding() {
        if (processBoundNetwork == null) return
        val released = connectivityManager.bindProcessToNetwork(null)
        processBoundNetwork = null
        processBindingSuspended = true
        ProjectionEventLog.record("NETWORK", "Process binding released; result=$released. T-Box request remains active.")
    }

    /** Rebinds reverse EasyConn sockets to the still-requested T-Box network. */
    @Synchronized
    fun rebindProcessToTBox(): Result<Network> = runCatching {
        processBindingSuspended = false
        val network = checkNotNull(activeNetwork) { "The T-Box network is no longer available." }
        check(connectivityManager.bindProcessToNetwork(network)) {
            "Android cannot restore the binding to the T-Box network."
        }
        processBoundNetwork = network
        ProjectionEventLog.record("NETWORK", "Process rebound to T-Box network=$network.")
        network
    }.onFailure { ProjectionEventLog.error("NETWORK", "Unable to restore T-Box process binding.", it) }

    /**
     * Restores the process route after a local projection has released it. Android can briefly
     * clear the callback's network while the T-Box AP is still being reacquired, so wait for the
     * persistent request instead of failing immediately on a transient null network.
     */
    suspend fun rebindProcessToTBoxWhenAvailable(timeoutMillis: Long): Result<Network> {
        if (awaitNetworkAvailable(timeoutMillis) == null) {
            val failure = IllegalStateException(
                "The T-Box network did not become available within ${timeoutMillis}ms."
            )
            ProjectionEventLog.error(
                "NETWORK",
                "Unable to restore T-Box process binding: network wait timed out.",
                failure
            )
            return Result.failure(failure)
        }
        return rebindProcessToTBox()
    }

    /**
     * Records what the radio can actually see, immediately before the request is submitted.
     *
     * This is the datum a rider log was missing. When Android never grants the network there is no
     * `onAvailable` and no link properties either, so the log says only that nothing happened - and
     * "the dash is not broadcasting", "the dash is on a channel this phone will not join" and "the
     * saved password is wrong" all look identical. A VOGE dash (SSID `VOGE-5G-58e4`, 2026-07-30)
     * cost a full log analysis and a round trip to the rider to get no further than that.
     *
     * The band matters on its own: an SSID advertising 5G is a hint, not evidence, and a dash whose
     * only AP sits on a 5GHz channel the phone's regulatory domain forbids can never be joined. The
     * sibling scan exists for the same reason - several of these dashboards broadcast a 2.4GHz twin
     * of the same network, and if one is in range it is almost certainly the one to pair with.
     *
     * BSSIDs are deliberately not logged: they are stable hardware identifiers and these logs get
     * pasted into public threads.
     */
    /**
     * Follows the radio for as long as the T-Box network lives, so a session that dies has a
     * signal trail behind it instead of a single reading taken at association.
     *
     * This is the datum that separates the two ways a ride session ends, which a rider log could
     * not tell apart: a link that fades (RSSI walking down over seconds, link speed collapsing)
     * versus an AP that simply vanishes at full strength - the dash rebooting its hotspot, or
     * handing the radio to something else. Both surface identically upstream, as a dead TCP
     * connection and an `onLost` a few seconds later. Zontes log 2026-07-30: the dash measured
     * -50dBm at 5180MHz when joined, then the session died 56s later with nothing recorded in
     * between.
     *
     * Driven by `onCapabilitiesChanged`, which Android already delivers on signal changes, so
     * there is no timer to own and nothing to stop when the session ends. The rate limit is
     * deliberately two-sided: at most one line per [LINK_SAMPLE_INTERVAL_MS] while the link is
     * steady, but every change of [LINK_SAMPLE_RSSI_STEP_DBM] or more regardless of how recently
     * one was logged - dense exactly while the link is moving, near-silent when it is not. The
     * lesson from `decode fps=` is that an unconditional per-event line costs more diagnostic
     * value than it adds.
     */
    private fun sampleLinkQuality(capabilities: NetworkCapabilities) {
        val wifiInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo ?: return
        val rssi = wifiInfo.rssi
        // The framework redacts WifiInfo from callers it does not trust with location, and hands
        // back its "no reading" sentinel otherwise. Logging that value would read as a link on
        // the brink, which is the opposite of "we do not know".
        if (rssi <= INVALID_RSSI_DBM) return
        val now = SystemClock.elapsedRealtime()
        val moved = lastSampledRssi != UNSAMPLED_RSSI &&
            kotlin.math.abs(rssi - lastSampledRssi) >= LINK_SAMPLE_RSSI_STEP_DBM
        val due = lastLinkSampleAtMs == 0L ||
            now - lastLinkSampleAtMs >= LINK_SAMPLE_INTERVAL_MS
        lastSampledRssi = rssi
        lastSampleDescription = "rssi=${rssi}dBm, frequency=${wifiInfo.frequency}MHz, " +
            "linkSpeed=${wifiInfo.linkSpeed}Mbps"
        lastSampleTakenAtMs = now
        if (!due && !moved) return
        lastLinkSampleAtMs = now
        ProjectionEventLog.debug("NETWORK", "T-Box link: $lastSampleDescription.")
    }

    /**
     * The single most useful line in a log of a session that died: what the radio looked like the
     * last time anyone measured it, and how stale that measurement was. A strong final sample
     * taken a moment earlier means the AP went away rather than faded.
     */
    private fun logLastLinkSample() {
        val description = lastSampleDescription ?: run {
            ProjectionEventLog.debug(
                "NETWORK",
                "No T-Box link measurement was available before the network was lost."
            )
            return
        }
        val age = SystemClock.elapsedRealtime() - lastSampleTakenAtMs
        ProjectionEventLog.warning(
            "NETWORK",
            "Last T-Box link measurement before the loss: $description, taken ${age}ms earlier."
        )
    }

    private fun ScanResult.ssidText(): String =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wifiSsid?.toString() else null)
            ?.removeSurrounding("\"")
            ?: @Suppress("DEPRECATION") SSID.orEmpty().removeSurrounding("\"")

    /**
     * Whether the dash is broadcasting its own SSID right now - **null when that cannot be said**.
     *
     * Tri-state is the whole point. A dash that is visibly on the air proves a PHONE_HOTSPOT
     * profile is aimed at the wrong transport, and [TBoxLinkResolver] uses exactly that to recover
     * instead of dead-ending. But an absent or empty scan is not evidence of absence - the long
     * comment in [logVisibleApSnapshot] lists the four ways a phone hands back nothing while the
     * dash is measurably there - and a false "not broadcasting" would send a rider whose hotspot
     * simply is not on down a join that cannot work. Only a definite sighting returns true.
     */
    @SuppressLint("MissingPermission")
    fun isDashBroadcasting(profile: MotorcycleProfile): Boolean? {
        val target = profile.ssid.trim().removeSurrounding("\"")
        if (target.isEmpty()) return null
        val results = runCatching { wifiManager.scanResults }.getOrNull() ?: return null
        if (results.isEmpty()) return null
        return results.any { it.ssidText().equals(target, ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    private fun logVisibleApSnapshot(profile: MotorcycleProfile) {
        val target = profile.ssid.trim().removeSurrounding("\"")
        val results = runCatching { wifiManager.scanResults }.getOrNull()
        if (results == null) {
            ProjectionEventLog.debug(
                "NETWORK",
                "Wi-Fi scan results are unavailable, so it cannot be said whether $target is in range."
            )
            publishScanFacts(visibility = "scan_unavailable")
            return
        }
        // An EMPTY result list is not evidence about the dash: it means this phone handed back no
        // scan at all - a cache the platform has not refreshed, scan throttling, or the
        // location/permission gate on getScanResults() - and a phone that can see nothing
        // whatsoever is describing itself, not the air. Saying "$target is NOT in the scan" there
        // convicts the dash of being silent on no evidence. A rider's log (Zontes ZT_…,
        // 2026-07-30) printed that verdict four times while the same dash measured -50dBm on
        // 5180MHz two minutes later.
        if (results.isEmpty()) {
            ProjectionEventLog.debug(
                "NETWORK",
                "The phone's Wi-Fi scan came back empty (0 networks), so it says nothing about " +
                    "whether $target is in range."
            )
            publishScanFacts(visibility = "scan_empty")
            return
        }
        val match = results.firstOrNull { it.ssidText().equals(target, ignoreCase = true) }
        // What the phone can see AT ALL is the other half of the answer, and the half that was
        // missing. "Not in the scan" convicts the dash; it stops doing so the moment the same
        // line shows the phone saw no 5GHz network whatsoever, and it convicts the dash's channel
        // rather than the dash when the phone demonstrably reaches the top of the band. A
        // China-market unit parked on channel 149-165 is invisible to a phone in an EU
        // regulatory domain, and no rider log had been able to tell that apart from a dash
        // that was simply switched off (VOGE 2026-07-30, QJ 2026-07-31 - both never once seen).
        //
        // A third cause outranks both, and is the one to rule out first because it is free to
        // check: some dashes never broadcast anything, because they are Wi-Fi CLIENTS waiting to
        // join a hotspot the phone hosts. Confirmed 2026-08-02 - a rider hit exactly this warning
        // at 11:00, switched to PHONE_HOTSPOT five minutes later, and streamed 9360 frames. The
        // scan evidence for that dash was indistinguishable from VOGE's and QJ's, so neither of
        // those is safely attributed to WPA3 or to the regulatory domain yet.
        val perBand = results.groupingBy { bandName(it.frequency) }.eachCount()
        val bandSummary = perBand.entries
            .sortedByDescending { it.value }
            .joinToString { (band, count) -> "$count on $band" }
        val topFiveGhzMhz = results.map { it.frequency }.filter { it in FIVE_GHZ_MHZ }.maxOrNull()
        val reach = topFiveGhzMhz?.let { ", highest 5GHz channel seen ${it}MHz" }.orEmpty()
        if (match == null) {
            ProjectionEventLog.warning(
                "NETWORK",
                "$target is NOT in the phone's latest Wi-Fi scan (${results.size} networks seen: " +
                    "$bandSummary$reach). Either the dash is not broadcasting it right now, the " +
                    "phone cannot see that channel, or this dash never broadcasts at all and " +
                    "expects your phone to HOST the network - check whether its pairing screen " +
                    "says \"open Android hotspot\", and if so pair it again with the " +
                    "\"My phone hosts the hotspot\" mode."
            )
        } else {
            // The security line is the one that can convict us rather than the dash. The specifier
            // below only ever offers setWpa2Passphrase, so an AP that requires WPA3/SAE can never
            // be matched no matter how correct the password is, and the failure is indistinguishable
            // from a dash that is not broadcasting. Logging what the AP actually advertises is what
            // separates those two, and nothing in a rider log has been able to so far.
            ProjectionEventLog.record(
                "NETWORK",
                "$target is in range: ${bandName(match.frequency)} (${match.frequency}MHz), " +
                    "rssi=${match.level}dBm, security=${securityName(match.capabilities)}."
            )
        }
        // A twin on the other band shares the dash's serial-looking last token.
        val tail = target.substringAfterLast('-', "").takeIf { it.length >= 3 }
        val siblings = if (tail == null) {
            emptyList()
        } else {
            results
                .map { it.ssidText() to it.frequency }
                .filter { (ssid, _) ->
                    ssid.isNotEmpty() &&
                        !ssid.equals(target, ignoreCase = true) &&
                        ssid.endsWith(tail, ignoreCase = true)
                }
                .distinctBy { it.first }
                .take(SIBLING_AP_LOG_LIMIT)
        }
        if (siblings.isNotEmpty()) {
            ProjectionEventLog.record(
                "NETWORK",
                "The same dash also broadcasts: " +
                    siblings.joinToString { (ssid, frequency) -> "$ssid on ${bandName(frequency)}" } +
                    ". If $target will not join, one of these may."
            )
        }
        publishScanFacts(
            visibility = if (match == null) "no" else "yes",
            match = match,
            fiveGhzSeen = perBand["5GHz"] ?: 0,
            topFiveGhzMhz = topFiveGhzMhz,
            siblingBand = siblings.firstOrNull()?.let { (_, frequency) -> bandName(frequency) }
        )
    }

    /**
     * Sends the SHAPE of the scan - never an SSID, never a BSSID - to telemetry, so the question
     * this snapshot exists to answer gets settled on fleet numbers instead of one shared log at a
     * time. Riders paste these logs into public threads and the fleet has no business knowing a
     * neighbour's network name; every value here is a bucket for the same reason.
     */
    private fun publishScanFacts(
        visibility: String,
        match: ScanResult? = null,
        fiveGhzSeen: Int? = null,
        topFiveGhzMhz: Int? = null,
        siblingBand: String? = null
    ) {
        ProjectionEventLog.setTelemetryFacts(
            mapOf(
                "tbox.ap_visible" to visibility,
                "tbox.ap_band" to (match?.let { bandName(it.frequency) } ?: "none"),
                "tbox.ap_security" to (match?.let { securityName(it.capabilities) } ?: "none"),
                "tbox.ap_rssi" to (match?.let { rssiBand(it.level) } ?: "none"),
                "tbox.scan_5ghz" to (fiveGhzSeen?.let(::scanCountBand) ?: "unknown"),
                "tbox.scan_5ghz_reach" to regulatoryReach(topFiveGhzMhz),
                "tbox.ap_sibling_band" to (siblingBand ?: "none")
            )
        )
    }

    /**
     * Registers the exclusive specifier request and returns immediately; the callback drives the
     * shared connection state, and [awaitRequestedNetwork] observes the outcome. Deliberately not
     * a suspend-until-connected call: the registration must be able to outlive any single
     * caller's patience, because Android keeps matching a live request against every later Wi-Fi
     * scan — that background hunt is exactly what a screen-off recovery needs.
     */
    private fun submitSpecifierRequest(profile: MotorcycleProfile) {
        logVisibleApSnapshot(profile)
        lateinit var networkCallback: ConnectivityManager.NetworkCallback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Association only - there is no address yet, so this is not success. It is the
                // one signal that separates "never joined the AP" from "joined it and got no IP",
                // and its absence across a whole rider log is itself the diagnosis.
                networkGranted = true
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Android granted network=$network for ${profile.ssid}; awaiting a usable IPv4 address."
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (network != activeNetwork) return
                sampleLinkQuality(networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val addresses = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                val gateways = linkProperties.routes.mapNotNull { it.gateway?.hostAddress }.distinct()
                // Dev-only raw logcat line (leaks the phone's Wi-Fi IPs) - the real,
                // production diagnostic record is ProjectionEventLog.debug() below, gated
                // by the runtime "Enable logging" setting regardless of build type.
                if (io.motohub.android.BuildConfig.DEBUG) Log.d(TAG, "Wi-Fi addresses=$addresses")
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Link properties changed: network=$network, interface=${linkProperties.interfaceName}, " +
                        "addresses=$addresses, gateways=$gateways."
                )
                val isTBoxNetwork = linkProperties.linkAddresses
                    .any { isUsableTBoxIpv4Address(it.address) }
                if (isTBoxNetwork) {
                    if (processBindingSuspended) {
                        // The binding was released on purpose while a local projection
                        // starts. Re-binding here on a routine DHCP/IPv6 link update would
                        // cut Google Android Auto off the internet mid-handshake; the
                        // projection flow rebinds explicitly when it is ready.
                        markConnected(network)
                        ProjectionEventLog.debug(
                            "NETWORK",
                            "T-Box link update accepted without re-binding: the process " +
                                "binding is deliberately released."
                        )
                        return
                    }
                    val bindFailure = runCatching {
                        check(connectivityManager.bindProcessToNetwork(network)) {
                            "Android cannot bind MOTO-HUB to the T-Box network."
                        }
                    }.exceptionOrNull()
                    if (bindFailure != null) {
                        val routing = TBoxVpnDiagnostics.inspect(
                            connectivityManager,
                            firstIpv4Gateway(linkProperties)
                        )
                        val message = TBoxVpnDiagnostics.userFacingMessage(bindFailure, routing)
                            ?: bindFailure.message.orEmpty()
                        Log.e(TAG, "T-Box process binding rejected; vpn=${routing?.describe() ?: "none"}", bindFailure)
                        ProjectionEventLog.error(
                            "NETWORK",
                            "Process binding rejected for network=$network; vpn=${routing?.describe() ?: "none"}; " +
                                "reason=$message."
                        )
                        pendingFailure = IllegalStateException(message, bindFailure)
                        releaseCallback(networkCallback)
                        return
                    }
                    processBoundNetwork = network
                    markConnected(network)
                    Log.i(TAG, "T-Box Wi-Fi is active: ${profile.ssid}, addresses=$addresses")
                    ProjectionEventLog.record(
                        "NETWORK",
                        "T-Box Wi-Fi validated and process-bound: ssid=${profile.ssid}, network=$network, addresses=$addresses."
                    )
                    if (MotoHubSettings.verboseTBoxLogging(appContext)) {
                        runCatching { wifiManager.connectionInfo }.getOrNull()?.let { info ->
                            ProjectionEventLog.debug(
                                "NETWORK",
                                "Wi-Fi link (verbose): frequency=${info.frequency}MHz, " +
                                    "rssi=${info.rssi}dBm, linkSpeed=${info.linkSpeed}Mbps."
                            )
                        }
                    }
                } else if (network == activeNetwork) {
                    // OnePlus can briefly publish incomplete LinkProperties while the AP stays
                    // associated. Only onLost is a real disconnect signal for the active network.
                    Log.w(TAG, "T-Box network address update is temporarily incomplete")
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Active T-Box network temporarily has no usable IPv4 address; " +
                            "waiting for onLost before disconnecting."
                    )
                }
            }

            override fun onLost(network: Network) {
                if (network != activeNetwork) return
                ProjectionEventLog.warning("NETWORK", "Android onLost received for active T-Box network=$network.")
                logLastLinkSample()
                if (processBoundNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    processBoundNetwork = null
                }
                activeNetwork = null
                mutableEvents.tryEmit(TBoxNetworkEvent.Lost(network))
                scheduleRejoin()
            }

            override fun onUnavailable() {
                // The elapsed time is the diagnosis, not decoration. Android takes seconds to scan
                // for a network it is genuinely hunting; a verdict inside a few tens of
                // milliseconds means the request was refused outright rather than attempted, and
                // reading that off two timestamps by hand is how it gets missed.
                val elapsed = specifierSubmittedAt
                    .takeIf { it > 0L }
                    ?.let { "${SystemClock.elapsedRealtime() - it}ms after the request" }
                    ?: "with no recorded request time"
                // Refused for being in the background, not for anything about the dash: telling
                // this rider to rescan the QR code sends them to fix a profile that is fine.
                val refusedAsBackground = !networkGranted &&
                    specifierSubmitImportance > FOREGROUND_SERVICE_IMPORTANCE
                ProjectionEventLog.error(
                    "NETWORK",
                    "Android reported the requested T-Box Wi-Fi as unavailable $elapsed; " +
                        "granted=$networkGranted, importanceAtRequest=$specifierSubmitImportance."
                )
                pendingFailure = IllegalStateException(
                    when {
                        networkGranted ->
                            "Android dropped the ${profile.ssid} network before it became usable."
                        refusedAsBackground ->
                            "Android refused the request for ${profile.ssid} without trying it: " +
                                "MOTO-HUB was in the background when it was made. Open MOTO-HUB " +
                                "and tap Connect again."
                        else ->
                            "Android gave up connecting to ${profile.ssid}: either the dash was " +
                                "not broadcasting it, the saved password no longer matches, or " +
                                "the connection dialog was dismissed. Rescan the dash QR code " +
                                "and retry."
                    }
                )
                releaseCallback(networkCallback)
            }
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(profile.ssid)
            .apply {
                if (profile.password.isNotBlank()) setWpa2Passphrase(profile.password)
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val importanceNow = processImportance()
        ProjectionEventLog.debug(
            "NETWORK",
            "Submitting WifiNetworkSpecifier request for ${profile.ssid} without INTERNET " +
                "capability; process importance=$importanceNow" +
                if (importanceNow > FOREGROUND_SERVICE_IMPORTANCE) {
                    " (background - Android will refuse this request)."
                } else {
                    "."
                }
        )

        // Drop the previous registration, reset this attempt's shared state, take ownership and
        // register - with no window in between for a concurrent caller to slip its own pair
        // through. The resets belong in here too: run outside the lock (as they were), the
        // rejoin ladder and a foreground connect() could interleave badly enough for one
        // attempt's verdict to be wiped by the other's reset, leaving awaitRequestedNetwork()
        // waiting out its whole timeout on an answer that had already arrived.
        val requestFailure = synchronized(requestLock) {
            clearCurrentNetworkRequestLocked()
            pendingFailure = null
            networkGranted = false
            // Per join, not per connector: a stale "last measurement before the loss" carried
            // over from the previous session would be read as this one's, which is worse than
            // none.
            lastLinkSampleAtMs = 0L
            lastSampleTakenAtMs = 0L
            lastSampledRssi = UNSAMPLED_RSSI
            lastSampleDescription = null
            specifierSubmittedAt = SystemClock.elapsedRealtime()
            specifierSubmitImportance = importanceNow
            callback = networkCallback
            registeredCallbacks += networkCallback
            syncLiveRequesterCount()
            pendingRequestSsid = profile.ssid
            runCatching {
                connectivityManager.requestNetwork(request, networkCallback)
            }.exceptionOrNull()
        }
        if (requestFailure != null) {
            ProjectionEventLog.error(
                "NETWORK",
                "ConnectivityManager.requestNetwork threw an exception.",
                requestFailure
            )
            pendingFailure = requestFailure
            releaseCallback(networkCallback)
            return
        }
        schedulePendingGiveUp(profile)
    }

    /**
     * How close to the user this process is, on Android's own scale (smaller is closer). Recorded
     * per request because it is the difference between "the dash is not there" and "we asked from
     * the background", which the failure itself cannot tell apart.
     */
    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    /** Success bookkeeping shared by both callback paths (bound and deliberately unbound). */
    private fun markConnected(network: Network) {
        activeNetwork = network
        connectedOnce = true
        pendingFailure = null
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
        // The registration deliberately outlives the join, but nothing needs to give it up any
        // more - and an alarm left armed would fire mid-ride.
        pendingRequestSsid?.let { TBoxRequestGiveUpAlarm.disarm(appContext, it) }
    }

    /**
     * Waits for [submitSpecifierRequest]'s callback to produce a usable network. On timeout the
     * registration is deliberately left in place: Android keeps matching it against every later
     * scan, so a recovery retry joins a hunt that has been running the whole time instead of
     * restarting it from zero. [schedulePendingGiveUp] still bounds how long the radio can be
     * held by a request nothing ever answers.
     *
     * The wait can run [UNAVAILABLE_GRACE_MS] past [CONNECTION_TIMEOUT_MS], but only while
     * nothing has been granted - the window exists to catch Android's own late verdict.
     */
    private suspend fun awaitRequestedNetwork(profile: MotorcycleProfile): Result<Network> {
        try {
            pollForOutcome(SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS)?.let { return it }
            if (!networkGranted) {
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Nothing granted for ${profile.ssid} within ${CONNECTION_TIMEOUT_MS}ms; waiting up " +
                        "to ${UNAVAILABLE_GRACE_MS}ms for Android's own verdict."
                )
                pollForOutcome(SystemClock.elapsedRealtime() + UNAVAILABLE_GRACE_MS)?.let { return it }
            }
        } catch (cancelled: CancellationException) {
            // A real cancellation (user cancel, scope teardown) must release the exclusive
            // request and propagate, not become a Result.
            clearCurrentNetworkRequest()
            throw cancelled
        }
        // Two things this line used to state as fact and could not know. Whether the request is
        // still pending: [schedulePendingGiveUp] may have released it while this wait was
        // running, and a 2026-07-31 rider log has "Releasing the pending request" printed
        // immediately above "the request stays pending for the next attempt". And how long the
        // wait actually took: a cached process is frozen by Android, every coroutine delay inside
        // it stops, and this loop only notices once the app is thawed - in that same log a 6s
        // grace took 505s and the elapsed time was reported as the 30s budget. That gap is the
        // difference between "the dash never answered" and "this app was not running to hear it".
        val stillPending = pendingRequestSsid == profile.ssid
        val elapsed = specifierSubmittedAt.takeIf { it > 0L }
            ?.let { SystemClock.elapsedRealtime() - it }
        val frozen = elapsed != null && elapsed > (CONNECTION_TIMEOUT_MS + UNAVAILABLE_GRACE_MS) * 2
        ProjectionEventLog.setTelemetryFacts(mapOf("tbox.wait_frozen" to if (frozen) "yes" else "no"))
        ProjectionEventLog.error(
            "NETWORK",
            "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms with " +
                (if (networkGranted) "the network granted but no usable IPv4 address" else "no network granted") +
                "; " +
                (if (stillPending) {
                    "the request stays pending for the next attempt."
                } else {
                    "the request has already been released."
                }) +
                (if (frozen) " The wait itself took ${elapsed}ms: this process was frozen while it ran." else "")
        )
        return Result.failure(IllegalStateException(setupTimeoutMessage(profile)))
    }

    /**
     * Polls the shared connection state until [deadline], returning null if it runs out with
     * neither a network nor a failure - the caller decides whether that is worth waiting past.
     */
    private suspend fun pollForOutcome(deadline: Long): Result<Network>? {
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return Result.success(it) }
            pendingFailure?.let { failure ->
                pendingFailure = null
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(
                    failure,
                    TBoxVpnDiagnostics.inspect(connectivityManager, dashAddress = null)
                )
                return Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
            delay(NETWORK_POLL_MS)
        }
        return null
    }

    /**
     * Names which half of the join ran out of time. One message used to cover both, and it named
     * only the second: a rider whose phone never associated at all was told Android had not got
     * an IP address *from the AP*, which points at the bike when the AP was never reached.
     */
    private fun setupTimeoutMessage(profile: MotorcycleProfile): String = if (networkGranted) {
        "The phone joined ${profile.ssid} but Android never obtained a usable IPv4 address from " +
            "it within ${CONNECTION_TIMEOUT_MS}ms. Switch the dash off and on again, then retry."
    } else {
        "The phone never joined ${profile.ssid}: Android did not associate to it within " +
            "${CONNECTION_TIMEOUT_MS}ms. Check that ${profile.ssid} is listed in the phone's Wi-Fi " +
            "settings while the dash shows its pairing screen - if it is not, the dash is not " +
            "broadcasting. If Android showed a dialog asking to connect to it, accept it and retry."
    }

    /**
     * A pending request left in place by [awaitRequestedNetwork] must not outlive every recovery
     * budget: past that point it only takes the Wi-Fi radio away from whoever asks next —
     * including the rider reconnecting by hand — so release it once nothing has connected for
     * [REJOIN_GIVE_UP_MS].
     */
    private fun schedulePendingGiveUp(profile: MotorcycleProfile) {
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = reconnectScope.launch {
            delay(REJOIN_GIVE_UP_MS)
            releasePendingRequest(profile.ssid, wokenByAlarm = false)
        }
        // The coroutine above is only as awake as the process. A rider who leaves the pairing
        // screen has the app cached within seconds, Android freezes it, and this budget stops
        // counting: the 2026-07-31 QJ log shows the 180s give-up landing at 528s, the exact
        // moment the rider reopened the app, with the exclusive request holding the radio for
        // the whole interval - and Sentry has reports of 727s. An alarm is the one timer the
        // freezer honours, because delivering it thaws the process.
        TBoxRequestGiveUpAlarm.arm(appContext, profile.ssid, REJOIN_GIVE_UP_MS) {
            releasePendingRequest(profile.ssid, wokenByAlarm = true)
        }
    }

    /**
     * Drops a request nothing ever answered. Idempotent, because the in-process timer and the
     * alarm both aim at it and either may get there first.
     */
    private fun releasePendingRequest(ssid: String, wokenByAlarm: Boolean) {
        // Decide and act as ONE step under the lock. markConnected() cancels the job, but a job
        // already past its own check cannot be cancelled out of the clear that follows: a grant
        // landing in that window had its brand-new network released immediately. Re-reading
        // activeNetwork inside the lock closes it, because markConnected() runs from the network
        // callback and its write is visible here.
        synchronized(requestLock) {
            if (activeNetwork != null || pendingRequestSsid != ssid) return
            ProjectionEventLog.warning(
                "NETWORK",
                "Releasing the pending T-Box Wi-Fi request for $ssid: nothing connected within " +
                    "${REJOIN_GIVE_UP_MS / 1_000L}s" +
                    (if (wokenByAlarm) "; the app's own timer was frozen, so the wake-up alarm did it" else "") +
                    "."
            )
            clearCurrentNetworkRequestLocked()
        }
    }

    private fun scheduleRejoin() {
        val profile = activeProfile ?: return
        if (!connectedOnce) return
        // Exactly one ladder, whatever raced its way in here.
        if (!rejoinActive.compareAndSet(false, true)) return
        val token = Any()
        ladderToken.set(token)
        rejoinJob = reconnectScope.launch {
            var attempt = 0
            var waitingForForeground = false
            var backoffElapsed = false
            val startedAt = SystemClock.elapsedRealtime()
            try {
                ladder@ while (activeProfile != null && connectedOnce && activeNetwork == null) {
                    val importanceNow = processImportance()
                    val step = nextTBoxRejoinStep(
                        attempt = attempt + 1,
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                        budgetMillis = REJOIN_GIVE_UP_MS,
                        firstDelayMillis = REJOIN_FIRST_DELAY_MS,
                        baseDelayMillis = REJOIN_BASE_DELAY_MS,
                        maxDelayMillis = REJOIN_MAX_DELAY_MS,
                        submissionWouldBeRefused = importanceNow > FOREGROUND_SERVICE_IMPORTANCE,
                        backgroundPollMillis = REJOIN_BACKGROUND_POLL_MS,
                        backoffElapsed = backoffElapsed
                    )
                    if (step is TBoxRejoinStep.WaitForForeground) {
                        if (!waitingForForeground) {
                            waitingForForeground = true
                            ProjectionEventLog.warning(
                                "NETWORK",
                                "Not asking Android for ${profile.ssid} yet: MOTO-HUB is in the " +
                                    "background (importance=$importanceNow), and a request made " +
                                    "from there is refused without the AP ever being looked " +
                                    "for. Waiting up to ${REJOIN_GIVE_UP_MS / 1_000L}s for " +
                                    "MOTO-HUB to come back to the foreground - open it to " +
                                    "reconnect now."
                            )
                        }
                        delay(step.delayMillis)
                        continue@ladder
                    }
                    if (step is TBoxRejoinStep.GiveUp) {
                        ProjectionEventLog.warning(
                            "NETWORK",
                            if (attempt == 0) {
                                "Giving up on the T-Box Wi-Fi after " +
                                    "${REJOIN_GIVE_UP_MS / 1_000L}s without ever being able to " +
                                    "ask: MOTO-HUB stayed in the background the whole time, " +
                                    "where Android refuses the request. Releasing the network " +
                                    "request; open MOTO-HUB and tap Connect."
                            } else {
                                "Giving up on the T-Box Wi-Fi after $attempt rejoin attempt(s) " +
                                    "over ${REJOIN_GIVE_UP_MS / 1_000L}s; releasing the network " +
                                    "request."
                            }
                        )
                        clearCurrentNetworkRequest()
                        break@ladder
                    }
                    if (step is TBoxRejoinStep.WaitThenRetry) {
                        delay(step.delayMillis)
                        backoffElapsed = true
                        continue@ladder
                    }
                    if (waitingForForeground) {
                        waitingForForeground = false
                        ProjectionEventLog.record(
                            "NETWORK",
                            "MOTO-HUB is back in the foreground; resuming the T-Box Wi-Fi rejoin."
                        )
                    }
                    attempt++
                    backoffElapsed = false
                    submitSpecifierRequest(profile)
                    val network = awaitLadderNetwork()
                    if (network != null) {
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi automatically reacquired on attempt $attempt: network=$network."
                        )
                        mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(network))
                        return@launch
                    }
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "T-Box Wi-Fi rejoin attempt $attempt failed."
                    )
                }
            } finally {
                // Only the ladder that still OWNS the gate may clear it. disconnect() cancels a
                // ladder and reopens the gate itself, but this block runs asynchronously
                // afterwards - so a NEW ladder could already have been started by then, and the
                // old one's finally would null out its job handle and reopen the gate under it,
                // leaving a running, uncancellable ladder. The token makes that clear a no-op:
                // whoever holds the current token owns the state, everyone else keeps its hands
                // off (same rule as bridges.remove(key, this) elsewhere).
                if (ladderToken.compareAndSet(token, null)) {
                    rejoinJob = null
                    rejoinActive.set(false)
                }
            }
        }
    }

    /** One ladder attempt's wait: no timeout error spam, no teardown — the loop resubmits anyway. */
    private suspend fun awaitLadderNetwork(): Network? {
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return it }
            pendingFailure?.let { failure ->
                pendingFailure = null
                ProjectionEventLog.warning(
                    "NETWORK",
                    "T-Box Wi-Fi rejoin request failed: ${failure.message}"
                )
                return null
            }
            delay(NETWORK_POLL_MS)
        }
        return null
    }

    private suspend fun findExistingWifi(ssid: String): Network {
        while (true) {
            val network = findMatchingWifi(ssid)
            if (network != null) {
                activeNetwork = network
                ProjectionEventLog.record(
                    "NETWORK",
                    "Existing Wi-Fi validated for simulator: ssid=${normalizeSsid(ssid)}, network=$network, " +
                        "addresses=${usableIpv4Addresses(network)}."
                )
                return network
            }
            delay(EXISTING_WIFI_POLL_MS)
        }
    }

    /** Polls the already-connected Wi-Fi used by the Mac simulator, which has no specifier callback. */
    private fun startSimulatorMonitor(profile: MotorcycleProfile) {
        if (simulatorMonitorJob?.isActive == true) return
        simulatorMonitorJob = reconnectScope.launch {
            try {
                while (activeProfile == profile && connectedOnce) {
                    delay(SIMULATOR_MONITOR_POLL_MS)
                    val matching = findMatchingWifi(profile.ssid)
                    val current = activeNetwork
                    when {
                        current != null && matching == null -> {
                            activeNetwork = null
                            ProjectionEventLog.warning(
                                "NETWORK",
                                "Simulator Wi-Fi disappeared; waiting for it to become available again."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Lost(current))
                        }
                        current == null && matching != null -> {
                            activeNetwork = matching
                            ProjectionEventLog.record(
                                "NETWORK",
                                "Simulator Wi-Fi automatically reacquired: network=$matching."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(matching))
                        }
                    }
                }
            } finally {
                simulatorMonitorJob = null
            }
        }
    }

    private fun findMatchingWifi(expectedSsid: String): Network? {
        val normalizedExpected = normalizeSsid(expectedSsid)
        val connectedSsid = runCatching { normalizeSsid(wifiManager.connectionInfo?.ssid.orEmpty()) }
            .getOrDefault("")
        val candidates = connectivityManager.allNetworks.asSequence()
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .filter { network -> usableIpv4Addresses(network).isNotEmpty() }
            .toList()
        val exactMatch = if (connectedSsid == normalizedExpected) {
            val active = connectivityManager.activeNetwork
            candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
        } else {
            null
        }
        if (exactMatch != null) return exactMatch

        // Some Android builds hide WifiInfo.ssid despite the granted Wi-Fi permissions. When there
        // is exactly one usable Wi-Fi network, it is still safe to use it for the simulator.
        if (connectedSsid.isBlank() || connectedSsid == "<unknown ssid>") {
            return candidates.singleOrNull()?.also {
                // This runs on a per-second poll, and the hidden SSID is a stable property of
                // the build, not an event: logging it every call buried real entries under one
                // warning per second for the whole session. Report the transition only.
                if (!hiddenSsidFallbackLogged) {
                    hiddenSsidFallbackLogged = true
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Android did not expose the current Wi-Fi SSID; using the only usable Wi-Fi network " +
                            "for the simulator. Further occurrences are not logged."
                    )
                }
            }
        }
        return null
    }

    private fun usableIpv4Addresses(network: Network): List<String> =
        connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.mapNotNull { it.address }
            ?.filter(::isUsableTBoxIpv4Address)
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

    private fun normalizeSsid(value: String): String = value.trim().removeSurrounding("\"")

    private fun firstIpv4Gateway(linkProperties: LinkProperties): InetAddress? =
        linkProperties.routes
            .mapNotNull { it.gateway }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()

    private companion object {
        const val TAG = "TBoxNetwork"
        const val CONNECTION_TIMEOUT_MS = 30_000L

        /**
         * Extra wait for Android's own `onUnavailable` verdict once [CONNECTION_TIMEOUT_MS] has
         * run out with nothing granted. Android times a specifier request out 30s after the rider
         * approves the picker, so its verdict always lands a few seconds after ours - in a rider
         * log of twelve consecutive failures it arrived 2.8-4.4s late every single time, which
         * meant the specific "Android could not deliver this network" reason was never the one
         * reported. Bounded, because a rider who leaves the picker open pushes it out of reach.
         */
        const val UNAVAILABLE_GRACE_MS = 6_000L
        /**
         * `ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE` - the ceiling
         * AOSP's `WifiNetworkFactory.isRequestFromForegroundAppOrService` accepts. Anything
         * higher (larger number = further from the user) has its specifier request dropped.
         */
        const val FOREGROUND_SERVICE_IMPORTANCE =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        const val EXISTING_WIFI_POLL_MS = 250L
        const val NETWORK_POLL_MS = 250L
        const val SIMULATOR_MONITOR_POLL_MS = 1_000L
        const val REJOIN_FIRST_DELAY_MS = 300L
        const val REJOIN_BASE_DELAY_MS = 2_500L
        const val REJOIN_MAX_DELAY_MS = 15_000L
        const val REJOIN_BACKGROUND_POLL_MS = 2_000L

        /**
         * How long the ladder keeps chasing a vanished T-Box AP. Deliberately longer than every
         * downstream recovery budget (mirroring and Android Auto both give up after 120s), so a
         * dropout short enough for a session to survive is still covered - and finite, so a bike
         * that is simply switched off does not leave the radio under an exclusive request.
         */
        const val REJOIN_GIVE_UP_MS = 180_000L
        /** Enough to show a band twin without turning a busy scan into a wall of text. */
        const val SIBLING_AP_LOG_LIMIT = 4

        /** Steady-link cadence for the radio trail; a moving link logs sooner (sampleLinkQuality). */
        const val LINK_SAMPLE_INTERVAL_MS = 15_000L

        /**
         * RSSI change that logs a sample regardless of the cadence. 6dB is a quarter of the
         * received power - large enough that Wi-Fi's own noise does not trip it, small enough
         * that a link on its way out produces several lines before it goes.
         */
        const val LINK_SAMPLE_RSSI_STEP_DBM = 6

        /** No sample taken yet; not a valid RSSI, so it cannot be mistaken for a reading. */
        const val UNSAMPLED_RSSI = Int.MIN_VALUE

        /**
         * How many connectors in this process currently hold a Wi-Fi request. Process-wide on
         * purpose: [requestLock] already makes one connector's own registrations safe, and cannot
         * see a second connector doing the same thing beside it - which is the failure this
         * counts. See syncLiveRequesterCount.
         */
        private val liveRequesters = java.util.concurrent.atomic.AtomicInteger(0)

        /** Keeps the duplicate-requester fault to one telemetry report per process. */
        private val duplicateRequestReported = AtomicBoolean(false)

        /**
         * "No reading" from the framework. `WifiInfo.INVALID_RSSI` is -127 but is not public API,
         * so the value is spelled out; anything at or below it is a sentinel, not a measurement.
         */
        const val INVALID_RSSI_DBM = -127
    }
}

/**
 * Summarises what an AP requires to be joined, from the raw `ScanResult.capabilities` string.
 *
 * WPA3 is called out on its own because it is the one answer that indicts this app: the specifier
 * only offers a WPA2 passphrase, so a dash that requires SAE cannot be joined at all.
 */
internal fun securityName(capabilities: String?): String {
    val caps = capabilities.orEmpty().uppercase()
    val schemes = buildList {
        if (caps.contains("SAE")) add("WPA3/SAE")
        if (caps.contains("RSN") || caps.contains("WPA2")) add("WPA2")
        if (caps.contains("WPA-") || caps.contains("WPA_") || Regex("(^|[^23A-Z])WPA($|[^23])").containsMatchIn(caps)) {
            add("WPA")
        }
        if (caps.contains("WEP")) add("WEP")
    }
    return when {
        schemes.isNotEmpty() -> schemes.joinToString("+")
        caps.isBlank() -> "not reported"
        else -> "open or unrecognised ($caps)"
    }
}

/**
 * How far up the 5GHz band this phone demonstrably reaches, from the highest channel its own scan
 * came back with.
 *
 * This is the fact that separates a silent dash from one on a channel the phone's regulatory
 * domain forbids. Channels 149-165 (5745MHz and up) are ordinary in the Chinese market and not
 * available to a phone operating under EU rules, so a dash sitting there is as invisible as a dash
 * that is switched off - and until now the log said the same words for both.
 *
 * Read as evidence about the PHONE, and only alongside the scan size: seeing nothing above
 * 5320MHz in a car park proves nothing, seeing it in a street full of routers is a strong hint.
 */
internal fun regulatoryReach(topFiveGhzMhz: Int?): String = when {
    topFiveGhzMhz == null -> "no 5GHz seen"
    topFiveGhzMhz >= 5745 -> "up to UNII-3"
    topFiveGhzMhz >= 5500 -> "up to UNII-2C"
    else -> "up to UNII-1/2A"
}

/** Coarse RSSI buckets: a tag carrying an exact dBm reading is a new tag value per rider. */
internal fun rssiBand(levelDbm: Int): String = when {
    levelDbm >= -60 -> "strong"
    levelDbm >= -75 -> "ok"
    else -> "weak"
}

/** Bucketed scan counts, for the same reason [rssiBand] is bucketed. */
internal fun scanCountBand(count: Int): String = when {
    count <= 0 -> "0"
    count <= 3 -> "1-3"
    else -> "4+"
}

/** The one definition of 5GHz in this file, so [bandName] and the scan snapshot cannot disagree. */
internal val FIVE_GHZ_MHZ = 4900..5900

/** Names the band a scan frequency sits in; "?" rather than a guess when it is out of range. */
internal fun bandName(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4GHz"
    in FIVE_GHZ_MHZ -> "5GHz"
    in 5925..7125 -> "6GHz"
    else -> "an unknown band"
}

internal fun isUsableTBoxIpv4Address(address: InetAddress): Boolean =
    address is Inet4Address &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isMulticastAddress
