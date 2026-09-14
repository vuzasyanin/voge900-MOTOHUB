package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import api.Api
import api.MobileCallback
import api.MobileSession
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MOTO_HUB_SIMULATOR_MODEL_ID = "MOTO-HUB-SIMULATOR"
internal const val RIDE_DAEMON_STARTUP_TIMEOUT_SEC = 25L
private const val REVERSE_PORT_WAIT_MS = 12_000L
private const val REVERSE_PORT_POLL_MS = 400L
private const val PXC_STALL_WARNING_MS = 6_000L
/**
 * How long the dash may say nothing at all on the PXC control link, while we are still feeding it
 * video, before the session is declared dead. The reverse channel keepalive runs every 2s, so this
 * is ten missed beats; a rider log that showed a 16.6s gap had the dash tear down all three
 * sockets straight afterwards, so nothing shorter than that gap is worth waiting for.
 */
private const val PXC_STALL_FATAL_MS = 20_000L
/** How often the watchdog looks; a fraction of the budget above, not a precise alarm. */
private const val PXC_WATCHDOG_INTERVAL_MS = 2_000L
/**
 * A frame offered more recently than this means we are actively streaming, which is the only state
 * where silence is a fault worth killing the session over: a paused dashboard is not a dead dash,
 * and it is not telling the rider anything untrue either.
 */
private const val PXC_STALL_STREAMING_WINDOW_MS = 5_000L
/**
 * A PXC event only counts as a "beat" — evidence the dash keeps a control-link cadence — when it
 * arrives while video is already flowing and stands at least this far from the previous PXC
 * event. Handshake traffic is a burst milliseconds apart before the first frame; a keepalive
 * cadence is one event every ~2s during streaming. This gap is what tells them apart.
 */
internal const val PXC_STREAMING_BEAT_MIN_GAP_MS = 1_000L
/**
 * How many streaming-time beats the dash must have shown before its PXC silence is allowed to
 * kill the session. A CFDL16 (field log 2026-07-31) sends six PXC events in the first ~3s —
 * only one of them during streaming — and then nothing, while its TFT keeps displaying video
 * for another 25 minutes; a dash with a real keepalive cadence reaches three beats within ~6s.
 * Three is the smallest count that separates the two shapes.
 */
internal const val PXC_STREAMING_CADENCE_MIN_BEATS = 3L
private const val PUSH_FRAME_TIMEOUT_MS = 5_000L
private const val PUSH_FRAME_SUBMIT_WAIT_MS = 1_000L
private const val PUSH_FRAME_SUBMIT_RETRY_DELAY_MS = 5L
private const val REJECTED_FRAME_LOG_INTERVAL = 100L
private val REVERSE_PORTS = intArrayOf(10920, 10921, 10922)

internal fun isCurrentRideDaemonSession(callbackGeneration: Long, activeGeneration: Long): Boolean =
    callbackGeneration != 0L && callbackGeneration == activeGeneration

/** Kotlin boundary around the GPL gomobile binding. Network selection stays outside this class. */
class RideDaemonTransport(
    context: Context
) : TBoxTransport {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(appContext)
    // Keep only one access unit queued behind the native call. A zero-capacity
    // SynchronousQueue made a short pushFrame() overlap look like a dead session to PRO.
    // The bounded queue retains the watchdog below without allowing an unbounded backlog.
    private val pushFrameExecutor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1)
    ) { runnable -> Thread(runnable, "MotoHubPushFrame").apply { isDaemon = true } }
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()
    @Volatile
    private var session: MobileSession? = null
    @Volatile
    private var sessionLink: TBoxLink? = null
    /**
     * The endpoint the last completed discovery confirmed, so a dash-page return can re-probe it
     * directly instead of deriving it again. Its lifetime matches its validity exactly: recovery
     * reuses this transport instance, and a fresh instance has no session to resume into.
     */
    @Volatile
    private var lastConfirmedHost: TBoxHost? = null
    private val sessionLock = Any()
    private val nextSessionGeneration = AtomicLong(0L)
    @Volatile
    private var activeSessionGeneration = 0L
    @Volatile
    private var protocolProfile: TBoxModelProfile = TBoxModelProfile.GENERIC
    private val pxcEvents = AtomicLong(0L)
    private val mediaControlEvents = AtomicLong(0L)
    private val framesOffered = AtomicLong(0L)
    private val framesTimedOut = AtomicLong(0L)
    private val framesRejected = AtomicLong(0L)
    private val lastPxcEventElapsed = AtomicLong(0L)
    private val lastMediaControlEventElapsed = AtomicLong(0L)
    private val lastFrameOfferedElapsed = AtomicLong(0L)
    /** Streaming-time PXC beats seen so far (see [isStreamingPxcBeat]); the silence watchdog's
     *  fatal verdict is gated on this reaching [PXC_STREAMING_CADENCE_MIN_BEATS]. */
    private val pxcStreamingBeats = AtomicLong(0L)
    private val pxcWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MotoHubPxcWatchdog").apply { isDaemon = true }
    }
    @Volatile
    private var pxcWatchdogTask: ScheduledFuture<*>? = null
    /** One report per session: the rider needs the failure once, not every tick. */
    private val pxcStallReported = AtomicBoolean(false)
    /** One log per session for the opposite outcome: silence observed on a dash that never
     *  showed a cadence, so the watchdog stood down instead of killing the session. */
    private val pxcQuietDashReported = AtomicBoolean(false)
    /** Distinct (source, command) pairs already dumped this session for opcode identification. */
    private val unknownCommandsLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    override fun configureProtocolProfile(profile: TBoxModelProfile) {
        protocolProfile = profile
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        discoverInternal(link, expectedModelId, resumeAfterDashLeave = false)

    override suspend fun discoverForResume(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        discoverInternal(link, expectedModelId, resumeAfterDashLeave = true)

    private suspend fun discoverInternal(
        link: TBoxLink,
        expectedModelId: String?,
        resumeAfterDashLeave: Boolean
    ): Result<TBoxHost> = withContext(Dispatchers.IO) {
        ProjectionEventLog.record(
            "DISCOVERY",
            if (resumeAfterDashLeave) {
                "Starting short EasyConn resume discovery on T-Box link (${link.label})."
            } else {
                "Starting Android NSD discovery on T-Box link (${link.label})."
            }
        )
        runCatching {
            stopSession()
            resetProtocolStats()
            val host = if (resumeAfterDashLeave) {
                discoverForResumeWithRetry(link, expectedModelId)
            } else {
                discoverWithRetry(link, expectedModelId)
            }
            lastConfirmedHost = host
            val profile = protocolProfile.takeIf { it != TBoxModelProfile.GENERIC }
                ?: TBoxModelProfile.resolve(expectedModelId, null)
            val mobileConfig = Api.newMobileConfig(
                ByteArray(0),
                30L,
                RIDE_DAEMON_STARTUP_TIMEOUT_SEC,
                5L,
                10L,
                3L
            ).apply {
                setSupportFunction(profile.advertisedSupportFunction.toLong())
                setProactivePxcHeartbeatEnabled(profile.requiresProactivePxcHeartbeat)
                // Only a dashboard that no profile claims is allowed to renegotiate the
                // video frame format from its own supportExtendProtocol byte. Every
                // recognised unit keeps the indexed framing it already displays.
                setPlainVideoFramingAllowed(profile == TBoxModelProfile.GENERIC)
                // The dash asks for wall-clock time over PXC and the daemon answers it,
                // but only Android knows the zone: Go's local location on a device is
                // UTC and carries no usable name. The id alone was not enough - it only
                // labelled the reply while the times inside it stayed on UTC, so a
                // rider's Voge dash was being set two hours wrong (log 2026-08-06, app
                // 1.1.45). The offset is what actually moves the clock, and Android is
                // the only side that knows it with DST applied. Both are read per
                // session, so a rider who crosses a border gets the new zone on the
                // next connect. hudlib also waits ~2s after CLIENT_INFO and, only
                // if the dash never sent 0x10450 and currentHUTime still looks
                // like uptime, pushes the same ACK unsolicited. A Voge that
                // already asks (the common path) is not sent a second packet.
                setTimeZoneID(java.util.TimeZone.getDefault().id)
                setTimeZoneOffsetSeconds(
                    java.util.TimeZone.getDefault()
                        .getOffset(System.currentTimeMillis())
                        .toLong() / 1000L
                )
            }
            val generation = nextSessionGeneration.incrementAndGet()
            val createdSession = Api.newMobileSession(
                mobileConfig,
                SessionCallback(generation)
            )
            synchronized(sessionLock) {
                session = createdSession
                sessionLink = link
                activeSessionGeneration = generation
            }
            createdSession.setECHost(
                Api.newStreamHost(host.ipAddress, host.port.toString(), host.packageName)
            )
            ProjectionEventLog.record(
                "DISCOVERY",
                "RideDaemon live-only session configured for ${host.ipAddress}:${host.port}; " +
                    "package=${host.packageName}; profile=${profile.key}; " +
                    "supportFunction=${profile.advertisedSupportFunction}; " +
                    "proactivePxcHeartbeat=${profile.requiresProactivePxcHeartbeat}; " +
                    "plainVideoFramingAllowed=${profile == TBoxModelProfile.GENERIC}; " +
                    "timeZone=${java.util.TimeZone.getDefault().id}."
            )
            host
        }.onFailure { failure ->
            stopSession()
            // User/scope cancellation is not a discovery failure; clean up and propagate it.
            if (failure is CancellationException) throw failure
            if (resumeAfterDashLeave) {
                ProjectionEventLog.warning(
                    "DISCOVERY",
                    "EasyConn resume window empty: ${failure.message}"
                )
            } else {
                ProjectionEventLog.error("DISCOVERY", "RideDaemon discovery/configuration failed.", failure)
            }
        }
    }

    override suspend fun start(host: TBoxHost): Result<Unit> =
        withContext(Dispatchers.IO) {
            val activeSession = session
            val activeLink = sessionLink
            if (activeSession == null || activeLink == null) {
                return@withContext Result.failure(
                    IllegalStateException("Call discover() with an active T-Box link before starting the session")
                )
            }
            runCatching {
                ensureReversePortsAvailable()
                ProjectionEventLog.record(
                    "TBOX",
                    "Starting EasyConn handshake to ${host.ipAddress}:${host.port}; " +
                        "waiting for the TFT video area."
                )
                startWithNetworkSocket(activeSession, host, activeLink)
                ProjectionEventLog.record("TBOX", "RideDaemon startSessionWithSocketFd returned successfully.")
                armPxcWatchdog(activeSessionGeneration)
            }.onFailure {
                // The native call may already have opened 10920/10921/10922 before it
                // reports a timeout. Stop that session before the next user attempt.
                activeSession.runCatching { stopSession() }
                    .onFailure { stopFailure ->
                        ProjectionEventLog.warning("TBOX", "Failed to clean up the failed native session.", stopFailure)
                    }
                ProjectionEventLog.error("TBOX", "EasyConn handshake failed.", it)
            }
        }

    /**
     * Waits for the phone-side EasyConn listeners before handing them to the native session.
     *
     * Failing on the first probe made a routine hand-off look like a hard conflict: a rider log
     * showed the ports still held 10s after MOTO-HUB asked the official CFMOTO app to stop, the
     * Android Auto hand-off aborted with EADDRINUSE, and the very next manual attempt ~20s later
     * connected normally. killBackgroundProcesses() cannot touch a foreground service and the
     * kernel releases the sockets asynchronously either way, so the only correct behaviour is to
     * wait a bounded time and only then report the conflict.
     */
    private suspend fun ensureReversePortsAvailable() {
        var busy = busyReversePorts()
        if (busy.isEmpty()) return
        // Nothing can close another app's sockets on Android 14+; the bounded wait below is the
        // part that actually resolves the routine hand-off case (kernel releases asynchronously).
        ProjectionEventLog.warning(
            "TBOX",
            "Local reverse ports ${busy.joinToString()} are still held; waiting up to " +
                "${REVERSE_PORT_WAIT_MS}ms for them to be released."
        )
        val deadline = SystemClock.elapsedRealtime() + REVERSE_PORT_WAIT_MS
        while (busy.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            delay(REVERSE_PORT_POLL_MS)
            busy = busyReversePorts()
        }
        if (busy.isNotEmpty()) {
            throw IllegalStateException(
                "Another EasyConn session still holds local reverse ports " +
                    "${busy.joinToString()} after ${REVERSE_PORT_WAIT_MS}ms " +
                    "(address already in use). Force-stop the official CFMOTO app and retry."
            )
        }
        ProjectionEventLog.record("TBOX", "Local reverse ports 10920-10922 were released; continuing.")
    }

    /** Probes 10920-10922 exactly as the native reverse server will bind them. */
    private fun busyReversePorts(): List<Int> {
        val probes = mutableListOf<ServerSocket>()
        val busy = mutableListOf<Int>()
        try {
            REVERSE_PORTS.forEach { port ->
                val probe = ServerSocket()
                try {
                    // SO_REUSEADDR before bind, like the Go listener: sockets the previous
                    // session left in TIME_WAIT are ours to reuse and must not read as a
                    // foreign conflict. A live listener in another process still fails here.
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress(port), 1)
                    probes += probe
                } catch (_: IOException) {
                    runCatching { probe.close() }
                    busy += port
                }
            }
        } finally {
            probes.forEach { runCatching { it.close() } }
        }
        return busy
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val activeSession = session ?: return false
        if (!activeSession.isRunning) return false
        val future = submitPushFrame(activeSession, avcc) ?: return false
        return try {
            future.get(PUSH_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            framesOffered.incrementAndGet()
            lastFrameOfferedElapsed.set(SystemClock.elapsedRealtime())
            true
        } catch (timeout: java.util.concurrent.TimeoutException) {
            framesTimedOut.incrementAndGet()
            ProjectionEventLog.warning(
                "TBOX",
                "AVC frame dropped: pushFrame() exceeded ${PUSH_FRAME_TIMEOUT_MS}ms timeout. " +
                    "The T-Box may be unresponsive. Timeouts: ${framesTimedOut.get()}"
            )
            false
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to offer AVC access unit", failure)
            ProjectionEventLog.error("TBOX", "Unable to push an AVC access unit to RideDaemon.", failure)
            false
        }
    }

    /**
     * A transient overlap is recoverable: wait briefly for the bounded queue to accept the
     * access unit. Only a queue that remains blocked for the grace period is reported as a
     * transport failure to the caller.
     */
    private fun submitPushFrame(activeSession: MobileSession, avcc: ByteArray): java.util.concurrent.Future<*>? {
        val deadline = SystemClock.elapsedRealtime() + PUSH_FRAME_SUBMIT_WAIT_MS
        while (true) {
            try {
                return pushFrameExecutor.submit {
                    activeSession.pushFrame(avcc)
                }
            } catch (_: RejectedExecutionException) {
                val rejections = framesRejected.incrementAndGet()
                if (rejections == 1L || rejections % REJECTED_FRAME_LOG_INTERVAL == 0L) {
                    ProjectionEventLog.warning(
                        "TBOX",
                        "AVC frame submission temporarily delayed; waiting for the previous " +
                            "pushFrame() call. Rejections so far: $rejections."
                    )
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    ProjectionEventLog.error(
                        "TBOX",
                        "AVC frame submission stayed blocked for ${PUSH_FRAME_SUBMIT_WAIT_MS}ms."
                    )
                    return null
                }
                try {
                    Thread.sleep(PUSH_FRAME_SUBMIT_RETRY_DELAY_MS.coerceAtMost(remaining))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopSession()
    }

    private fun stopSession() {
        cancelPxcWatchdog()
        val sessionToStop: MobileSession?
        synchronized(sessionLock) {
            // Invalidate callbacks before asking the native session to stop. RideDaemon can
            // report the socket close asynchronously after stopSession() has been called.
            activeSessionGeneration = 0L
            sessionToStop = session
            session = null
            sessionLink = null
        }
        if (sessionToStop != null) {
            ProjectionEventLog.record("TBOX", "Stopping RideDaemon session. ${protocolSnapshot()}")
        }
        sessionToStop?.runCatching { stopSession() }
            ?.onFailure { ProjectionEventLog.warning("TBOX", "RideDaemon stopSession failed.", it) }
    }

    /**
     * The link this attempt should actually open its socket on.
     *
     * [TBoxLink.Infrastructure] holds one immutable [Network], captured when `discover()` ran.
     * Android replaces that `Network` whenever the association is rebuilt - a specifier network
     * that drops and is re-granted arrives as a *different* object - and binding a socket to the
     * old one fails with `EPERM`. The retry loop above was therefore structurally unable to
     * recover from the one failure it exists to absorb: it re-sent the identical dead handle
     * until it ran out of attempts.
     *
     * Field log 2026-07-30 (Zontes `ZT_…`): network 206 granted, lost 233ms later, 207 granted
     * two seconds after that - while all three handshake attempts bound to network 204, an even
     * older handle, and failed with EPERM in ~10ms each. The rider was then told the TFT had
     * refused the video stream.
     *
     * The process binding is the authority, not this class's own bookkeeping:
     * [TBoxNetworkConnector] binds the process on every successful join and clears the binding in
     * `onLost`, so it is exactly "the network T-Box traffic egresses over right now". A null
     * binding is deliberately NOT treated as fatal - the connector also unbinds briefly on
     * purpose - so the attempt falls back to the captured link and the retry gets another chance
     * once the replacement network is bound.
     */
    private fun linkForThisAttempt(link: TBoxLink): TBoxLink {
        if (link !is TBoxLink.Infrastructure) return link
        val bound = connectivityManager?.boundNetworkForProcess
        if (bound == null) {
            ProjectionEventLog.debug(
                "TBOX",
                "No T-Box network is bound to this process right now; the handshake keeps using " +
                    "the link from discovery (${link.label})."
            )
            return link
        }
        if (bound == link.network) return link
        ProjectionEventLog.warning(
            "TBOX",
            "The T-Box network was replaced during the EasyConn handshake " +
                "(${link.label} -> network=$bound); reopening the command socket on the current one."
        )
        // The session's link genuinely moved: leaving the dead handle in place would make every
        // later call on this session repeat the same EPERM.
        val refreshed = TBoxLink.Infrastructure(bound)
        sessionLink = refreshed
        return refreshed
    }

    /** Opens the EasyConn command socket over the established T-Box link. */
    private suspend fun startWithNetworkSocket(
        activeSession: MobileSession,
        host: TBoxHost,
        link: TBoxLink
    ) {
        val policy = EasyConnRetryPolicy()
        val connectedSocket = retryEasyConnStart(
            policy = policy,
            shouldRetry = ::isTransientEasyConnFailure,
            onRetry = { failedAttempt, delayMillis, failure ->
                ProjectionEventLog.warning(
                    "TBOX",
                    "EasyConn attempt $failedAttempt/${policy.maxAttempts} failed: " +
                        "${failure.message.orEmpty()}. Retrying in ${delayMillis}ms."
                )
            }
        ) { attempt ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val attemptLink = linkForThisAttempt(link)
            ProjectionEventLog.debug(
                "TBOX",
                "EasyConn attempt $attempt/${policy.maxAttempts}: opening link-bound command " +
                    "socket to ${host.ipAddress}:${host.port} (${attemptLink.label})."
            )
            val socket = attemptLink.createSocket()
            try {
                socket.connect(InetSocketAddress(host.ipAddress, host.port), EC_CONNECT_TIMEOUT_MS)
                ProjectionEventLog.record("TBOX", "EasyConn TCP command socket connected.")
                socket to attempt
            } catch (failure: Throwable) {
                socket.close()
                throw failure
            }
        }
        if (connectedSocket.second > 1) {
            ProjectionEventLog.record(
                "TBOX",
                "EasyConn TCP connection recovered on attempt " +
                    "${connectedSocket.second}/${policy.maxAttempts}."
            )
        }
        connectedSocket.first.use { socket ->
            ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
                val fd = descriptor.detachFd().toLong()
                // ParcelFileDescriptor duplicates the socket descriptor. Go owns and closes the
                // detached duplicate; the outer use{} closes the original Java socket.
                activeSession.startSessionWithSocketFd(fd)
            }
        }
    }

    // Catches only the withTimeout-specific subtype so a real user cancellation (plain
    // CancellationException) still propagates immediately instead of being retried; ensureActive
    // rethrows when the TimeoutCancellationException actually belongs to an enclosing withTimeout.
    private suspend fun discoverWithRetry(link: TBoxLink, expectedModelId: String?): TBoxHost {
        // A Wi-Fi Direct group has no bindable Network, so NSD cannot resolve the service over it.
        // Skip the (useless) discovery windows and probe the group owner directly, immediately after
        // the join while the p2p source address is still fresh - waiting 30s for NSD to fail was what
        // let the address go stale and made the probe socket bind fail with EADDRNOTAVAIL.
        if (link is TBoxLink.WifiDirect) return discoverOverWifiDirect(link)
        // The phone is the gateway here, so there is no advertised dash AP and no `.1` to aim at:
        // the dash is a DHCP client somewhere on our own tethering subnet. NSD is tried anyway
        // (cheap, and the dash may well advertise once it has an address) before sweeping.
        if (link is TBoxLink.PhoneHotspot) return discoverOverPhoneHotspot(link, expectedModelId)

        repeat(DISCOVERY_MAX_ATTEMPTS - 1) { attempt ->
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ProjectionEventLog.warning(
                    "DISCOVERY",
                    "No EasyConn advertisement seen within ${DISCOVERY_TIMEOUT_MS}ms " +
                        "(attempt ${attempt + 1}/$DISCOVERY_MAX_ATTEMPTS); restarting NSD discovery."
                )
                delay(DISCOVERY_RETRY_DELAY_MS)
            }
        }
        try {
            return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.warning(
                "DISCOVERY",
                "No EasyConn advertisement seen in $DISCOVERY_MAX_ATTEMPTS windows of " +
                    "${DISCOVERY_TIMEOUT_MS / 1000}s each; the T-Box may still be starting up. " +
                    "Sending an active wake probe."
            )
        }

        return finishInfrastructureDiscoveryOrThrow(
            link,
            expectedModelId,
            "The EasyConn service was not advertised in $DISCOVERY_MAX_ATTEMPTS discovery windows of " +
                "${DISCOVERY_TIMEOUT_MS / 1000}s each. This can happen when the official CFMOTO app is " +
                "already connected to the motorcycle, or when the T-Box is still starting up after " +
                "Wi-Fi association."
        )
    }

    /**
     * One short NSD window so a Back→Up dash return is not blocked for 30s on a
     * link that is about to bounce. The session service retries this until the
     * dash-return budget expires; the full wake-probe tail stays on first connect.
     *
     * Every step here is deliberately narrow, because this runs in a loop while the rider waits
     * and each attempt that answers "not yet" should cost as little as possible: the endpoint the
     * previous session used is tried first, and only when it stays quiet does the transport fall
     * back to deriving one from scratch.
     */
    private suspend fun discoverForResumeWithRetry(link: TBoxLink, expectedModelId: String?): TBoxHost {
        lastConfirmedHost?.let { known ->
            probeKnownEndpoint(link, known)?.let { return it }
        }
        if (link is TBoxLink.WifiDirect) return discoverOverWifiDirect(link, resume = true)
        if (link is TBoxLink.PhoneHotspot) return discoverOverPhoneHotspot(link, expectedModelId)
        try {
            return withTimeout(RESUME_DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            throw IllegalStateException(
                "The EasyConn service was not advertised in the dash-return window. " +
                    "The rider may still be on the stock instrument cluster."
            )
        }
    }

    /**
     * Re-confirms the endpoint the previous session used with a single short wake probe.
     *
     * A completed CMD_MDNS_RESPOND handshake is the same proof full discovery demands before it
     * hands an endpoint back, so on success the stored [host] is reused verbatim - package name
     * included, which a bare probe could not have supplied on an infrastructure link.
     */
    private suspend fun probeKnownEndpoint(link: TBoxLink, host: TBoxHost): TBoxHost? =
        withContext(Dispatchers.IO) {
            val identity = EasyConnClientIdentity.probeOrder().firstOrNull() ?: return@withContext null
            val answered = runCatching {
                link.createSocket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host.ipAddress, host.port),
                        RESUME_WAKE_PROBE_CONNECT_TIMEOUT_MS
                    )
                    socket.soTimeout = RESUME_WAKE_PROBE_READ_TIMEOUT_MS
                    writeWakeProbeFrame(socket.getOutputStream(), identity)
                    readWakeProbeAck(socket.getInputStream())
                }
            }.getOrDefault(false)
            if (!answered) {
                ProjectionEventLog.debug(
                    "DISCOVERY",
                    "Dash-return probe of the previous endpoint ${host.ipAddress}:${host.port} " +
                        "went unanswered; falling back to discovery."
                )
                return@withContext null
            }
            ProjectionEventLog.record(
                "DISCOVERY",
                "Dash returned on the previous EasyConn endpoint ${host.ipAddress}:${host.port}; " +
                    "skipped rediscovery."
            )
            host
        }

    private suspend fun finishInfrastructureDiscoveryOrThrow(
        link: TBoxLink,
        expectedModelId: String?,
        notFoundMessage: String
    ): TBoxHost {
        // Infrastructure fallback: a probe ACK on an AP link is preferably spent re-arming one more
        // NSD window, because a resolved advertisement carries the package name too.
        if (sendEasyConnWakeProbe(link) != null) {
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        }

        // Last resort on an AP link, and the reason this exists: field logs from Zontes, VOGE and
        // QJ dashes show NSD staying empty and 10930 refused while the dash is plainly up. Sweeping
        // the EasyConn neighborhood and re-running the probe there is the only way to tell "the
        // dash speaks EasyConn on an unusual port" apart from "the dash never answered at all".
        // The endpoint is used ONLY when the full CMD_MDNS_RESPOND handshake completes on it - an
        // open TCP port alone is never promoted to an EC endpoint, so the "no invented port"
        // rule in TBOX_STREAMING_CONTRACT.md still holds.
        val peerIp = peerIpv4For(link)
        val peerAddress = peerIp?.hostAddress
        if (peerIp != null && peerAddress != null) {
            val fallback = probeFallbackEasyConnPort(link, peerIp)
            if (fallback != null) {
                val (fallbackPort, fallbackIdentity) = fallback
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "EasyConn endpoint confirmed by wake probe on fallback port " +
                        "$peerAddress:$fallbackPort after NSD stayed empty."
                )
                return TBoxHost(peerAddress, fallbackPort, fallbackIdentity)
            }
        }
        throw IllegalStateException(notFoundMessage)
    }

    /**
     * Discovery for a Wi-Fi Direct group owner dash. NSD is skipped (no bindable Network to run it
     * on); instead the group owner is asked directly with an EasyConn wake probe. A completed ACK is
     * a full CMD_MDNS_RESPOND handshake, so the group owner IS the confirmed EC endpoint - not an
     * invented one - matching what every reference implementation does for P2P dashes.
     */
    private suspend fun discoverOverWifiDirect(
        link: TBoxLink.WifiDirect,
        resume: Boolean = false
    ): TBoxHost {
        val peerAddress = link.gatewayIp.hostAddress
        // Stay on the live group and keep probing. A single ~12s refusal used to tear the
        // group down and start a full rejoin, which on Xiaomi is refused on the first
        // attempt and which resets EasyConn on VOGE dashes that were still booting.
        //
        // A dash-page return needs none of that patience: the group is still formed, the dash
        // answered moments ago, and the caller is already retrying on its own schedule. So it
        // gets one round instead of two, and the outer retry takes the place of the inner one.
        val rounds = if (resume) 1 else DISCOVERY_MAX_ATTEMPTS
        repeat(rounds) { attempt ->
            val acknowledged = sendEasyConnWakeProbe(link, resume = resume)
            if (acknowledged != null && peerAddress != null) {
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Wi-Fi Direct EasyConn endpoint confirmed at $peerAddress:$WAKE_PROBE_PORT."
                )
                return TBoxHost(peerAddress, WAKE_PROBE_PORT, acknowledged)
            }
            if (attempt < rounds - 1) {
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Wi-Fi Direct dash did not answer the wake probe " +
                        "(attempt ${attempt + 1}/$rounds); keeping the group " +
                        "and retrying. The dash may still be starting EasyConn."
                )
                delay(DISCOVERY_RETRY_DELAY_MS)
            }
        }
        if (resume) {
            // No port sweep on a resume. The port cannot have moved since the session that just
            // ended, and twenty TCP connects would spend the rider's whole wait proving it.
            throw IllegalStateException(
                "The Wi-Fi Direct dash did not answer the dash-return wake probe at " +
                    "${link.gatewayIp.hostAddress}:$WAKE_PROBE_PORT. " +
                    "The rider may still be on the stock instrument cluster."
            )
        }
        // Some firmware variants refuse 10930 outright (observed as ECONNREFUSED on T-Boxes the
        // reference projects never reverse-engineered) while answering the same handshake on a
        // nearby port. Before giving up, sweep the known EasyConn neighborhood for open TCP
        // ports and retry the ACK-verified wake probe there - the endpoint is only ever used
        // when the full CMD_MDNS_RESPOND handshake completed, never invented from an open port.
        if (peerAddress != null) {
            val fallback = probeFallbackEasyConnPort(link, link.gatewayIp)
            if (fallback != null) {
                val (fallbackPort, fallbackIdentity) = fallback
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Wi-Fi Direct EasyConn endpoint confirmed on fallback port " +
                        "$peerAddress:$fallbackPort."
                )
                return TBoxHost(peerAddress, fallbackPort, fallbackIdentity)
            }
        }
        throw IllegalStateException(
            "The Wi-Fi Direct dash did not answer an EasyConn wake probe at " +
                "${link.gatewayIp.hostAddress}:$WAKE_PROBE_PORT (or on any nearby fallback port). " +
                "The dash may still be starting up, or its own companion app may already be " +
                "connected to it."
        )
    }

    /**
     * Discovery when the phone hosts the network. NSD is given one window first - it costs a few
     * seconds and would hand back the service package too, which the sweep cannot - then every
     * address on the tethering subnet is probed on the well-known port, nearest the phone first.
     *
     * The endpoint is adopted only when the full CMD_MDNS_RESPOND handshake completes, exactly as
     * on the other two transports: an open TCP port is never promoted on its own.
     */
    private suspend fun discoverOverPhoneHotspot(
        link: TBoxLink.PhoneHotspot,
        expectedModelId: String?
    ): TBoxHost {
        try {
            return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.record(
                "DISCOVERY",
                "No EasyConn advertisement on the hosted network; sweeping " +
                    "${link.subnet.localAddress.hostAddress}/${link.subnet.prefixLength} for the dash."
            )
        }
        val found = probeHostedSubnet(link)
            ?: throw IllegalStateException(
                "No motorcycle answered on the hotspot your phone is hosting. Check that the dash " +
                    "shows it is connected, and that the hotspot Ssid and Password match exactly " +
                    "what the dash is asking for."
            )
        val (host, identity) = found
        val address = host.hostAddress
            ?: throw IllegalStateException("The dash answered but its address could not be read.")
        ProjectionEventLog.record(
            "DISCOVERY",
            "EasyConn endpoint confirmed on the hosted network at $address:$WAKE_PROBE_PORT."
        )
        return TBoxHost(address, WAKE_PROBE_PORT, identity)
    }

    /**
     * Walks the hosted subnet looking for a dash. Two passes on purpose: a cheap connect to the
     * well-known port narrows 253 addresses down to the handful that answer at all, and only those
     * pay for the full ACK-verified probe. One pass of full probes over a /24 would take minutes.
     */
    private suspend fun probeHostedSubnet(link: TBoxLink.PhoneHotspot): Pair<Inet4Address, String>? =
        withContext(Dispatchers.IO) {
            val candidates = TBoxHotspotScan.candidateHosts(link.subnet)
            val reachable = mutableListOf<Inet4Address>()
            for (candidate in candidates) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val open = runCatching {
                    link.createSocket().use { socket ->
                        socket.connect(
                            InetSocketAddress(candidate, WAKE_PROBE_PORT),
                            HOSTED_SWEEP_CONNECT_TIMEOUT_MS
                        )
                    }
                    true
                }.getOrDefault(false)
                if (!open) continue
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Hosted-network sweep: ${candidate.hostAddress} accepted $WAKE_PROBE_PORT."
                )
                reachable += candidate
                // The dash is the only device on a hotspot opened for it, so the first responder
                // is almost certainly it - verify immediately rather than sweeping the whole /24.
                for (identity in EasyConnClientIdentity.probeOrder()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val acknowledged = runCatching {
                        link.createSocket().use { socket ->
                            socket.connect(
                                InetSocketAddress(candidate, WAKE_PROBE_PORT),
                                HOSTED_SWEEP_CONNECT_TIMEOUT_MS
                            )
                            socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                            writeWakeProbeFrame(socket.getOutputStream(), identity)
                            readWakeProbeAck(socket.getInputStream())
                        }
                    }.getOrDefault(false)
                    if (acknowledged) {
                        EasyConnClientIdentity.remember(identity)
                        return@withContext candidate to identity
                    }
                }
            }
            ProjectionEventLog.record(
                "DISCOVERY",
                if (reachable.isEmpty()) {
                    "Hosted-network sweep: nothing answered $WAKE_PROBE_PORT on " +
                        "${candidates.size} addresses. Either the dash has not joined the hotspot " +
                        "yet, or it speaks on a port MOTO-HUB does not know."
                } else {
                    "Hosted-network sweep: ${reachable.joinToString { it.hostAddress.orEmpty() }} " +
                        "accepted $WAKE_PROBE_PORT but none completed the EasyConn handshake."
                }
            )
            null
        }

    /**
     * Sweeps the candidate EasyConn ports over the P2P link and retries the wake probe on any
     * that accept a TCP connection. Returns the first port whose CMD_MDNS_RESPOND handshake
     * completes together with the client identity that earned the acknowledgement, or null when
     * no combination answers.
     */
    private suspend fun probeFallbackEasyConnPort(
        link: TBoxLink,
        peerIp: Inet4Address
    ): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val openPorts = FALLBACK_EC_PORTS.filter { port ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                runCatching {
                    link.createSocket().use { socket ->
                        socket.connect(
                            InetSocketAddress(peerIp, port),
                            FALLBACK_PORT_CONNECT_TIMEOUT_MS
                        )
                    }
                    true
                }.getOrDefault(false)
            }
            if (openPorts.isEmpty()) {
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Fallback port sweep found no open EasyConn candidates on " +
                        "${peerIp.hostAddress}."
                )
                return@withContext null
            }
            ProjectionEventLog.record(
                "DISCOVERY",
                "Fallback port sweep: open candidates ${openPorts.joinToString()} on " +
                    "${peerIp.hostAddress}; retrying the wake probe on each."
            )
            // Identity first, ports second: sweeping every open port with the leading identity
            // before reaching for an alternate keeps the common case as quick as it was, and the
            // port a dash answers on is far less predictable than the name it accepts.
            for (identity in EasyConnClientIdentity.probeOrder()) {
                for (port in openPorts) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    try {
                        link.createSocket().use { socket ->
                            socket.connect(
                                InetSocketAddress(peerIp, port),
                                FALLBACK_PORT_CONNECT_TIMEOUT_MS
                            )
                            socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                            writeWakeProbeFrame(socket.getOutputStream(), identity)
                            if (readWakeProbeAck(socket.getInputStream())) {
                                ProjectionEventLog.record(
                                    "DISCOVERY",
                                    "Fallback wake probe on port $port acknowledged as " +
                                        "\"$identity\"; later probes will lead with it."
                                )
                                EasyConnClientIdentity.remember(identity)
                                return@withContext port to identity
                            }
                        }
                    } catch (failure: Throwable) {
                        ProjectionEventLog.debug(
                            "DISCOVERY",
                            "Fallback wake probe on port $port as \"$identity\" failed: " +
                                "${failure.message}."
                        )
                    }
                }
            }
            null
        }

    /**
     * Actively asks the T-Box to respond instead of waiting for it to broadcast on its own.
     * Some Wi-Fi Direct group-owner T-Boxes never advertise `_EasyConn._tcp.` proactively; a
     * direct probe on the well-known port 10930 is what OpenCfMoto/OpenMoto observed working
     * for that case. A completed ACK is a full EasyConn CMD_MDNS_RESPOND handshake, so on a
     * Wi-Fi Direct group (where NSD has no bindable Network) the ACK-confirmed endpoint is used
     * directly as the EC host/port; on infrastructure links it only re-arms one more NSD window.
     */
    /**
     * @return the client identity the dash acknowledged, or null when none of them was answered.
     *
     * The leading identity keeps the whole retry budget to itself, because those retries exist for
     * a dash that is merely still booting and swapping names between them would answer a slow dash
     * with a name it never accepts. Only once that identity has been given every chance do the
     * alternates get one attempt each — extra time paid solely by riders the proven name failed.
     */
    /**
     * The T-Box address to aim a direct probe at, without waiting for discovery: the link's own
     * hint on a P2P group, otherwise derived from the AP's routes/DNS. Extracted so the wake probe
     * and the fallback port sweep aim at the same peer instead of deriving it twice.
     */
    private fun peerIpv4For(link: TBoxLink): Inet4Address? =
        link.peerHint ?: link.network?.let { network ->
            connectivityManager.getLinkProperties(network)?.let { properties ->
                deriveTBoxPeerIpv4(
                    gateways = properties.routes.filter { route -> route.isDefaultRoute }.mapNotNull { route -> route.gateway },
                    dnsServers = properties.dnsServers,
                    localAddresses = properties.linkAddresses.map { linkAddress -> linkAddress.address to linkAddress.prefixLength }
                )
            }
        }

    private suspend fun sendEasyConnWakeProbe(
        link: TBoxLink,
        resume: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val peerIp = peerIpv4For(link)
        if (peerIp == null) {
            ProjectionEventLog.debug("DISCOVERY", "Wake probe skipped: no usable peer IPv4 could be derived.")
            return@withContext null
        }
        // A first connect must consider every identity, because which one a dash accepts is not
        // knowable in advance. A dash-page return already knows: the leading entry is the name
        // that earned the last acknowledgement, and trying the alternates again would only add
        // seconds to a wait the rider is watching.
        val identities =
            if (resume) EasyConnClientIdentity.probeOrder().take(1) else EasyConnClientIdentity.probeOrder()
        val connectTimeoutMs =
            if (resume) RESUME_WAKE_PROBE_CONNECT_TIMEOUT_MS else WAKE_PROBE_CONNECT_TIMEOUT_MS
        val readTimeoutMs =
            if (resume) RESUME_WAKE_PROBE_READ_TIMEOUT_MS else WAKE_PROBE_READ_TIMEOUT_MS
        ProjectionEventLog.record(
            "DISCOVERY",
            "Sending an EasyConn wake probe to ${peerIp.hostAddress}:$WAKE_PROBE_PORT " +
                "(identities: ${identities.joinToString()})."
        )
        identities.forEachIndexed { position, identity ->
            val budget = if (resume || position > 0) 1 else WAKE_PROBE_ATTEMPTS
            repeat(budget) { attempt ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    link.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(peerIp, WAKE_PROBE_PORT), connectTimeoutMs)
                        socket.soTimeout = readTimeoutMs
                        writeWakeProbeFrame(socket.getOutputStream(), identity)
                        if (readWakeProbeAck(socket.getInputStream())) {
                            ProjectionEventLog.record(
                                "DISCOVERY",
                                "T-Box acknowledged the wake probe as \"$identity\" on attempt " +
                                    "${attempt + 1}/$budget."
                            )
                            EasyConnClientIdentity.remember(identity)
                            return@withContext identity
                        }
                    }
                    ProjectionEventLog.debug(
                        "DISCOVERY",
                        "Wake probe attempt ${attempt + 1}/$budget as \"$identity\": " +
                            "no acknowledgement."
                    )
                } catch (failure: Throwable) {
                    ProjectionEventLog.debug(
                        "DISCOVERY",
                        "Wake probe attempt ${attempt + 1}/$budget as \"$identity\" to " +
                            "${peerIp.hostAddress}:$WAKE_PROBE_PORT failed: ${failure.message}."
                    )
                }
                if (attempt < budget - 1) delay(WAKE_PROBE_RETRY_DELAY_MS)
            }
        }
        null
    }

    /** 16-byte little-endian header (cmd, totalLen, cmd xor totalLen, reserved) plus JSON payload. */
    private fun writeWakeProbeFrame(out: OutputStream, identity: String) {
        val payload = EasyConnClientIdentity.probeBody(identity).toByteArray(Charsets.UTF_8)
        val totalLen = WAKE_PROBE_HEADER_SIZE + payload.size
        val header = ByteBuffer.allocate(WAKE_PROBE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, CMD_MDNS_RESPOND)
        header.putInt(4, totalLen)
        header.putInt(8, CMD_MDNS_RESPOND xor totalLen)
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readWakeProbeAck(input: InputStream): Boolean {
        val header = ByteArray(WAKE_PROBE_HEADER_SIZE)
        if (!readFullyOrFalse(input, header)) return false
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buffer.getInt(0)
        val totalLen = buffer.getInt(4)
        val magic = buffer.getInt(8)
        if ((cmd xor totalLen) != magic || cmd != CMD_MDNS_RESPOND_ACK) return false
        val payloadLen = (totalLen - WAKE_PROBE_HEADER_SIZE).coerceAtLeast(0)
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFullyOrFalse(input, payload)) return false
        return payload.toString(Charsets.UTF_8).contains("true")
    }

    private fun readFullyOrFalse(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private suspend fun discoverWithAndroidNsd(
        link: TBoxLink,
        expectedModelId: String?
    ): TBoxHost = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val multicastLock = wifiManager.createMulticastLock("$TAG.mDns").apply {
            setReferenceCounted(false)
            acquire()
        }
        ProjectionEventLog.debug("DISCOVERY", "mDNS multicast lock acquired.")
        lateinit var listener: NsdManager.DiscoveryListener
        var serviceCallback: NsdManager.ServiceInfoCallback? = null
        val discoveryStopped = AtomicBoolean(false)

        fun stopDiscovery() {
            if (!discoveryStopped.compareAndSet(false, true)) return
            serviceCallback?.let { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            if (multicastLock.isHeld) multicastLock.release()
            ProjectionEventLog.debug("DISCOVERY", "NSD discovery stopped and multicast lock released.")
        }

        fun finish(result: Result<TBoxHost>) {
            if (!completed.compareAndSet(false, true)) return
            stopDiscovery()
            continuation.resumeWith(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Android NSD discovery started: $serviceType")
                ProjectionEventLog.record("DISCOVERY", "Android NSD started for serviceType=$serviceType.")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || !serviceInfo.serviceType.endsWith(SERVICE_TYPE)) return
                if (serviceCallback != null) return
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "NSD candidate found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}."
                )
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        if (!link.matchesResolvedNetwork(resolved.network)) {
                            // Only ONE candidate can hold the resolution slot (see onServiceFound).
                            // A candidate pinned to the WRONG network will never migrate to the
                            // T-Box link, so keeping the slot occupied silently blocked every
                            // later (correct) candidate until the discovery window expired. A
                            // null network is different: with network-scoped discovery it can be
                            // a transient of the resolution in progress, so that candidate keeps
                            // the slot and the next update decides.
                            if (resolved.network != null) {
                                ProjectionEventLog.warning(
                                    "DISCOVERY",
                                    "Candidate ${resolved.serviceName} resolved on the wrong " +
                                        "network (${resolved.network}); releasing the resolution " +
                                        "slot for the next candidate."
                                )
                                serviceCallback = null
                                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            }
                            return
                        }
                        val attributes = resolved.attributes
                        val simulatorProfileRequested =
                            TBoxModelProfile.fromModelId(expectedModelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR
                        val advertisedModelId = attributes[MODEL_ID_ATTRIBUTE]
                            ?.toString(Charsets.UTF_8)
                            ?.trim()
                        if (
                            simulatorProfileRequested &&
                            !isMotoHubSimulatorAdvertisement(resolved.serviceName, advertisedModelId)
                        ) {
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Ignoring EasyConn candidate ${resolved.serviceName}: " +
                                    "it is not an identified MOTO-HUB simulator preset (modelId=$advertisedModelId)."
                            )
                            serviceCallback = null
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            return
                        }
                        val packageName = decodeEasyConnPackage(attributes[PACKAGE_ATTRIBUTE])
                        if (packageName == null) {
                            Log.w(TAG, "EasyConn service resolved without package metadata")
                            ProjectionEventLog.warning("DISCOVERY", "Resolved EasyConn service has no package metadata.")
                            return
                        }

                        val advertisedIp = attributes[IP_ATTRIBUTE]
                            ?.toString(Charsets.UTF_8)
                            ?.let(::parseUsableEasyConnIpv4Literal)
                        val resolvedIp = resolved.hostAddresses
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull(::isUsableTBoxIpv4Address)
                            ?.hostAddress
                        val unusableResolvedIp = resolved.hostAddresses
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull()
                            ?.hostAddress
                        val derivedIp = if (!simulatorProfileRequested && advertisedIp == null && resolvedIp == null) {
                            link.peerHint?.hostAddress ?: link.network?.let { activeNetwork ->
                                connectivityManager.getLinkProperties(activeNetwork)?.let { linkProperties ->
                                    deriveTBoxPeerIpv4(
                                        gateways = linkProperties.routes
                                            .filter { it.isDefaultRoute }
                                            .mapNotNull { it.gateway },
                                        dnsServers = linkProperties.dnsServers,
                                        localAddresses = linkProperties.linkAddresses
                                            .map { it.address to it.prefixLength }
                                    )
                                }?.hostAddress
                            }
                        } else {
                            null
                        }
                        val ipAddress = advertisedIp ?: resolvedIp ?: derivedIp
                        val port = resolved.port
                        if (ipAddress.isNullOrBlank() || port !in 1..65535) {
                            Log.w(TAG, "EasyConn service resolved without a usable host")
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Resolved EasyConn service has invalid endpoint: " +
                                    "advertisedIp=${attributes[IP_ATTRIBUTE]?.toString(Charsets.UTF_8)}, " +
                                    "resolvedIp=$unusableResolvedIp, port=$port."
                            )
                            return
                        }
                        if (derivedIp != null) {
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "EasyConn advertised no IPv4 host; using network-derived peer $derivedIp."
                            )
                        }
                        ProjectionEventLog.record(
                            "DISCOVERY",
                            "NSD resolution accepted: $ipAddress:$port, package=$packageName, network=${resolved.network}."
                        )
                        finish(Result.success(TBoxHost(ipAddress, port, packageName)))
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        serviceCallback = null
                        Log.w(TAG, "EasyConn service callback failed: $errorCode")
                        ProjectionEventLog.warning("DISCOVERY", "Service info callback registration failed: $errorCode.")
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                serviceCallback = callback
                runCatching {
                    nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
                }.onFailure {
                    serviceCallback = null
                    Log.w(TAG, "Unable to register EasyConn service callback", it)
                    ProjectionEventLog.warning("DISCOVERY", "Unable to register NSD service info callback.", it)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                ProjectionEventLog.warning("DISCOVERY", "NSD service lost: ${serviceInfo?.serviceName}.")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                ProjectionEventLog.debug("DISCOVERY", "Android NSD stopped for serviceType=$serviceType.")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ProjectionEventLog.error("DISCOVERY", "Android NSD start failed: type=$serviceType, code=$errorCode.")
                finish(Result.failure(IllegalStateException("Android NSD start failed: $errorCode")))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Android NSD stop failed: $errorCode")
                ProjectionEventLog.warning("DISCOVERY", "Android NSD stop failed: code=$errorCode.")
            }
        }

        continuation.invokeOnCancellation { stopDiscovery() }
        runCatching {
            link.startNsdDiscovery(nsdManager, SERVICE_TYPE, callbackExecutor, listener)
        }.onFailure { finish(Result.failure(it)) }
    }

    private inner class SessionCallback(
        private val generation: Long
    ) : MobileCallback {
        override fun onError(message: String?, fatal: Boolean) {
            Log.w(TAG, "T-Box error fatal=$fatal: ${message.orEmpty()}")
            val detail = message.orEmpty().ifBlank { "EasyConn error without details." }
            if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) {
                Log.i(TAG, "Ignoring RideDaemon callback from an inactive session: $detail")
                ProjectionEventLog.debug("TBOX", "Ignored stale RideDaemon callback: $detail")
                return
            }
            if (fatal) {
                ProjectionEventLog.error("TBOX", "RideDaemon fatal callback: $detail")
            } else {
                ProjectionEventLog.warning("TBOX", "RideDaemon warning callback: $detail")
            }
            if (fatal) {
                mutableEvents.tryEmit(TBoxEvent.FatalError(detail))
            } else {
                mutableEvents.tryEmit(TBoxEvent.Warning(detail))
            }
        }

        override fun onEvent(time: Long, type: Long, command: Long, payload: ByteArray?) {
            // Both guarded: this runs for EVERY protocol event, touch moves included, and an
            // unconditional interpolated string here is paid whether or not anything reads it.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "T-Box event type=$type command=$command bytes=${payload?.size ?: 0}")
            }
            if (type == TRANSPORT_EVENT_SOURCE) {
                // The daemon's own decisions, not dash traffic. Logged at INFO because a field
                // log must be able to say which video frame format was on the wire - a framing
                // experiment whose outcome only exists in the daemon's stdout cannot be read.
                if (command == TRANSPORT_VIDEO_FRAMING_COMMAND) {
                    val extendByte = payload?.getOrNull(0)?.toInt() ?: -1
                    val plainApplied = payload?.getOrNull(1)?.toInt() == 1
                    ProjectionEventLog.record(
                        "TBOX",
                        "Video framing negotiated: dash supportExtendProtocol=$extendByte; " +
                            if (plainApplied) {
                                "frame index DROPPED (plain framing) for this session."
                            } else {
                                "frame index kept (indexed framing, the format every CFMOTO uses)."
                            }
                    )
                }
                return
            }
            val verbose = MotoHubSettings.verboseTBoxLogging(appContext)
            val now = SystemClock.elapsedRealtime()
            val sequence = when (type) {
                PXC_EVENT_SOURCE -> {
                    // The dash sends CLOCK_KEEPALIVE about every 2s. A long gap is the only
                    // warning that the link is dying: a rider log went silent for 16.6s and the
                    // T-Box then tore down all three sockets at once. Recording the gap is what
                    // separates "the bike gave up" from "the app stopped sending".
                    val previous = lastPxcEventElapsed.getAndSet(now)
                    if (previous > 0L && now - previous >= PXC_STALL_WARNING_MS) {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "PXC keepalive gap of ${now - previous}ms before this event; the " +
                                "T-Box control link went quiet."
                        )
                    }
                    if (isStreamingPxcBeat(previous, lastFrameOfferedElapsed.get(), now)) {
                        pxcStreamingBeats.incrementAndGet()
                    }
                    pxcEvents.incrementAndGet()
                }
                MEDIA_CONTROL_EVENT_SOURCE -> {
                    lastMediaControlEventElapsed.set(now)
                    mediaControlEvents.incrementAndGet()
                }
                else -> 0L
            }
            if (type == PXC_EVENT_SOURCE || type == MEDIA_CONTROL_EVENT_SOURCE) {
                val commandName = protocolCommandName(type, command)
                // Lambda form: this is the single highest-volume log line in the app (one per
                // protocol event, and a drag on the TFT is a stream of them), so the string is
                // not built at all when logging is off.
                ProjectionEventLog.debug("TBOX") {
                    "${protocolSourceName(type)} RX #$sequence command=" +
                        "0x${command.toString(16)} ($commandName) " +
                        "bytes=${payload?.size ?: 0}."
                }
                // Any control message that carries a body is worth dumping, named or not.
                // This used to fire only on UNKNOWN opcodes, which tied the evidence to the
                // gaps in the name table: naming a command would have silently switched off
                // the dump of the one payload we can actually read - the media CAPTURE_CONFIG,
                // whose bytes are how the video framing work was diagnosed. The control plane
                // is a handful of messages per session, so dumping all of them costs nothing.
                // With verbose logging every occurrence is dumped in full; without it, the
                // first occurrence of each distinct command is still dumped (truncated) so a
                // normal user's problem report already carries the evidence.
                if (payload != null && payload.isNotEmpty()) {
                    if (verbose) {
                        ProjectionEventLog.debug(
                            "TBOX",
                            "$commandName command 0x${command.toString(16)} payload (verbose): " +
                                payload.toDiagnosticHex() + "."
                        )
                    } else if (
                        unknownCommandsLogged.size < UNKNOWN_COMMAND_LOG_LIMIT &&
                        unknownCommandsLogged.add(type to command)
                    ) {
                        val preview = payload.copyOfRange(
                            0,
                            payload.size.coerceAtMost(UNKNOWN_COMMAND_PREVIEW_BYTES)
                        )
                        val truncated = if (payload.size > preview.size) "…(+${payload.size - preview.size}B)" else ""
                        ProjectionEventLog.record(
                            "TBOX",
                            "${protocolSourceName(type)} command 0x${command.toString(16)} " +
                                "($commandName) first seen; payload=${preview.toDiagnosticHex()}$truncated."
                        )
                    }
                }
            }
            if (type == PXC_EVENT_SOURCE) {
                ProjectionEventLog.debug("TBOX") {
                    "PXC event received: command=$command, bytes=${payload?.size ?: 0}."
                }
                // The two clock questions, at record level rather than debug: a dash asks one or
                // the other and never both, and which one it asked is the whole diagnosis for a
                // rider whose date and time keep resetting. At debug it was absent from every
                // ordinary problem report - exactly the reports that carry the complaint.
                if (command == PXC_QUERY_TIME_COMMAND || command == PXC_CLOCK_KEEPALIVE_COMMAND) {
                    ProjectionEventLog.record(
                        "CLOCK",
                        "Dash asked for the time with " +
                            "${protocolCommandName(type, command)} " +
                            "(0x${command.toString(16)}), ${payload?.size ?: 0} bytes. The daemon " +
                            "answers it with ${java.util.TimeZone.getDefault().id}."
                    )
                }
            }
            if (type == PXC_EVENT_SOURCE && command == PXC_HUD_CONFIG_COMMAND) {
                val capabilities = payload?.let(::decodeTBoxCapabilities)
                if (capabilities == null) {
                    // An empty CLIENT_INFO and an unparseable one are different faults and lead
                    // to the same place - the GENERIC profile - so the log has to tell them
                    // apart. A Zontes dash sends this command with a zero-length body (field log
                    // 2026-07-30), and "unable to decode" sent us looking for a parser bug that
                    // was never there: there was simply nothing to parse.
                    if (payload == null || payload.isEmpty()) {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "The T-Box announced CLIENT_INFO with an empty body; it reports no " +
                                "capabilities at all, so the generic dashboard profile applies."
                        )
                    } else {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "Unable to decode the T-Box CLIENT_INFO payload (${payload.size} bytes); " +
                                "the generic dashboard profile applies."
                        )
                    }
                } else {
                    // Full raw CLIENT_INFO, not just the few fields TBoxCapabilities extracts -
                    // ProjectionEventLog.redact() strips password/pin-shaped fields (btPin) and,
                    // since verbose became the default, the stable hardware identifiers too
                    // (HUID/uuid) - that redaction is what made defaulting verbose on safe to
                    // do. The gate is now about volume, not identifiers: one JSON blob per
                    // handshake is fine, and a rider who turns verbose off still gets the
                    // whitelisted subset from the unrecognised-dashboard branch below.
                    if (verbose) {
                        val rawJson = payload.toString(Charsets.UTF_8).trim().trimEnd(' ')
                        ProjectionEventLog.debug("TBOX", "CLIENT_INFO raw (verbose): $rawJson")
                    }
                    ProjectionEventLog.record(
                        "TBOX",
                        "T-Box capabilities received: hu=${capabilities.huName ?: "not reported"}, " +
                            "pxc=${capabilities.pxcVersion ?: "not reported"}, " +
                            "touch=${capabilities.screenTouch ?: "not reported"}."
                    )
                    // Brand identity, always. Carbit licenses the same dashboard stack well beyond
                    // CFMOTO and its SDK pairs each manufacturer's flavor with the phone package
                    // name it expects the companion app to advertise, so a rebadged dash can
                    // complete the whole handshake and still refuse to project. Two short fields,
                    // and the first thing worth knowing about an unfamiliar dashboard.
                    ProjectionEventLog.record(
                        "TBOX",
                        "Dashboard identity: flavor=${capabilities.flavor ?: "not reported"}, " +
                            "channel=${capabilities.channel ?: "not reported"}, " +
                            "brand=${capabilities.carBrand ?: "not reported"}, " +
                            "model=${capabilities.carModel ?: "not reported"}, " +
                            "profile=${protocolProfile.key}."
                    )
                    logDashClock(capabilities)
                    // Nothing claimed this dashboard, so no profile knows its geometry, touch
                    // behaviour or firmware quirks - the one case a rider cannot diagnose from
                    // the outside. Report the whitelisted CLIENT_INFO subset and every candidate
                    // profile's score unconditionally, the same rule AndroidAutoSessionService
                    // already applies to the scores. Every field here is one TBoxCapabilities
                    // already keeps, so this adds no identifier the log did not carry before.
                    if (protocolProfile == TBoxModelProfile.GENERIC) {
                        ProjectionEventLog.record(
                            "TBOX",
                            "Unrecognised dashboard: package=${capabilities.packageName ?: "?"}, " +
                                "version=${capabilities.versionName ?: "?"}" +
                                "(${capabilities.versionCode ?: "?"}), " +
                                "sdk=${capabilities.sdkVersion ?: "?"}, " +
                                "dashSupportFunction=${capabilities.supportFunction ?: "?"}, " +
                                "socketTimeoutWifi=${capabilities.socketTimeoutPeriodWifi ?: "?"}ms, " +
                                "sockAuth=${capabilities.socketServerAuth ?: "?"}, " +
                                "dpi=${capabilities.dpi ?: "?"}, " +
                                "productType=${capabilities.productType ?: "?"}, " +
                                "screenType=${capabilities.screenType ?: "?"}, " +
                                "landscapeAdaptive=${capabilities.landscapeAdaptive ?: "?"}, " +
                                "mirrorOverlayTouch=${capabilities.mirrorOverlayTouch ?: "?"}."
                        )
                        ProjectionEventLog.record(
                            "TBOX",
                            "Profile scores: ${TBoxModelProfile.scoreBreakdown(capabilities)}."
                        )
                    }
                    mutableEvents.tryEmit(TBoxEvent.Capabilities(capabilities))
                }
                return
            }
            if (type != MEDIA_CONTROL_EVENT_SOURCE) return
            if (command == MEDIA_STREAM_START_COMMAND) {
                ProjectionEventLog.record(
                    "TBOX",
                    "TFT video consumer is ready; requesting a fresh decoder sync frame."
                )
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                return
            }
            val eventPayload = payload ?: return
            if (command == MEDIA_TOUCH_COMMAND) {
                decodeTBoxTouch(eventPayload)?.let(mutableEvents::tryEmit)
                return
            }
            if (command == MEDIA_CAPTURE_CONFIG_COMMAND) {
                describeTBoxCaptureRequest(eventPayload)?.let { fields ->
                    ProjectionEventLog.record("TBOX", "TFT capture request: $fields.")
                }
                decodeTBoxVideoArea(eventPayload)?.let { area ->
                    ProjectionEventLog.record(
                        "TBOX",
                        "TFT capture area requested: ${area.width}x${area.height}."
                    )
                    mutableEvents.tryEmit(area)
                }
                return
            }
            runCatching {
                val safeArea = org.json.JSONObject(eventPayload.toString(Charsets.UTF_8))
                    .optJSONObject("viewAreaConfig")
                    ?.optJSONArray("viewAreas")
                    ?.optJSONObject(0)
                    ?.optJSONObject("safeArea")
                    ?: return@runCatching
                val width = safeArea.optInt("width")
                val height = safeArea.optInt("height")
                if (width > 0 && height > 0) {
                    ProjectionEventLog.record("TBOX", "TFT safe area received: ${width}x$height.")
                    mutableEvents.tryEmit(TBoxEvent.VideoArea(width, height))
                }
            }.onFailure {
                Log.w(TAG, "Invalid EasyConn screen configuration", it)
                ProjectionEventLog.warning("TBOX", "Invalid EasyConn screen configuration payload.", it)
            }
        }

        override fun onStopped() {
            Log.i(TAG, "T-Box session stopped")
            if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) {
                ProjectionEventLog.debug("TBOX", "Ignored stale RideDaemon stopped callback.")
                return
            }
            ProjectionEventLog.warning(
                "TBOX",
                "RideDaemon reported that the T-Box session stopped. ${protocolSnapshot()}"
            )
            mutableEvents.tryEmit(TBoxEvent.Stopped)
        }

    }

    /**
     * Reports what the dashboard thinks the time is, the moment it says so.
     *
     * Riders keep finding the dash's date and time back at a factory value, and nothing in the app
     * could say why, because `currentHUTime` was never read on the Kotlin side at all. Setting the
     * clock is the daemon's job and cannot be done from here, so this is deliberately only
     * evidence - but it is the evidence the question needs, and one ordinary log now answers it:
     * whether the dash arrived with a wall clock or an uptime counter, how far off it is, and
     * whether it claims to accept a correction at all. Without that, a Go-side change to re-sync
     * periodically would be a guess.
     */
    private fun logDashClock(capabilities: TBoxCapabilities) {
        val reported = capabilities.currentHuTimeMillis
        val supportsSync = capabilities.syncCorrectTime
        if (reported == null) {
            ProjectionEventLog.record(
                "CLOCK",
                "The dash did not report currentHUTime in CLIENT_INFO; " +
                    "supportSyncCorrectTime=${supportsSync ?: "not reported"}."
            )
            return
        }
        val verdict = if (looksLikeDashUptime(reported)) {
            "looks like uptime, not a wall clock (below ${HU_TIME_UPTIME_THRESHOLD_MS}ms), so its " +
                "clock was never set or was reset"
        } else {
            val skewMillis = reported - System.currentTimeMillis()
            "looks like a wall clock, ${skewMillis / 1_000L}s away from this phone's"
        }
        ProjectionEventLog.record(
            "CLOCK",
            "Dash clock on CLIENT_INFO: currentHUTime=$reported ($verdict); " +
                "supportSyncCorrectTime=${supportsSync ?: "not reported"}."
        )
    }

    /**
     * Starts watching the PXC control link for silence.
     *
     * The gap check in [SessionCallback.onEvent] only fires when the *next* event arrives, which
     * makes it useless for the failure riders actually hit: the dash stops talking and never comes
     * back, so there is no next event to carry the warning. A Zontes dash (field log 2026-07-30)
     * sent its last heartbeat 3s into the session, stayed silent for 96s while we pushed 1857
     * frames at it, and only then closed the socket - and for that whole minute and a half the app
     * told the rider "streaming is active on the motorcycle TFT". That claim is what this timer
     * exists to stop making.
     *
     * [PXC_STALL_FATAL_MS] of silence ends the session as a failure rather than trying to recover
     * in place: the caller's own retry path re-runs discovery and the handshake, which is the only
     * thing that has ever brought one of these links back.
     *
     * The verdict is gated on [pxcStreamingBeats]: a CFDL16 field log (2026-07-31) proved some
     * dashes go PXC-silent right after the handshake *by design*, with the TFT happily displaying
     * video for 25 more minutes — the timer must never fire on those. Only a dash that first
     * demonstrated a streaming-time keepalive cadence ([PXC_STREAMING_CADENCE_MIN_BEATS] beats,
     * see [isStreamingPxcBeat]) has its later silence treated as death.
     */
    private fun armPxcWatchdog(generation: Long) {
        cancelPxcWatchdog()
        if (generation == 0L) return
        pxcWatchdogTask = runCatching {
            pxcWatchdogExecutor.scheduleWithFixedDelay(
                { checkPxcLiveness(generation) },
                PXC_WATCHDOG_INTERVAL_MS,
                PXC_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrNull()
    }

    private fun cancelPxcWatchdog() {
        pxcWatchdogTask?.cancel(false)
        pxcWatchdogTask = null
    }

    private fun checkPxcLiveness(generation: Long) {
        // Generation-scoped: a tick that was already queued when the session was replaced must not
        // be able to kill its successor.
        if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) return
        if (session?.isRunning != true) return
        val now = SystemClock.elapsedRealtime()
        val lastFrame = lastFrameOfferedElapsed.get()
        if (lastFrame <= 0L || now - lastFrame > PXC_STALL_STREAMING_WINDOW_MS) return
        val lastPxc = lastPxcEventElapsed.get()
        // Never received anything: that is a handshake that did not complete, not a link that
        // died, and start() already reports it.
        if (lastPxc <= 0L) return
        val silence = now - lastPxc
        if (silence < PXC_STALL_FATAL_MS) return
        // Silence only means death on a dash that talks. A CFDL16 (field log 2026-07-31) sends
        // six PXC events in the first three seconds and then nothing, while its TFT keeps
        // displaying video for another 25 minutes — on that shape this timer used to kill every
        // healthy Android Auto session at the ~23s mark. Demand a demonstrated streaming-time
        // cadence before trusting its absence; a dash that never talked still gets caught by
        // socket errors and frame rejections when it actually dies.
        if (pxcStreamingBeats.get() < PXC_STREAMING_CADENCE_MIN_BEATS) {
            if (pxcQuietDashReported.compareAndSet(false, true)) {
                ProjectionEventLog.record(
                    "TBOX",
                    "PXC control link quiet for ${silence}ms while streaming, but this dash " +
                        "never kept a control-link cadence " +
                        "(streamingBeats=${pxcStreamingBeats.get()}); not treating silence as " +
                        "a fault. ${protocolSnapshot()}"
                )
            }
            return
        }
        if (!pxcStallReported.compareAndSet(false, true)) return
        ProjectionEventLog.error(
            "TBOX",
            "The T-Box control link has been silent for ${silence}ms while video was still being " +
                "sent; treating the session as dead. ${protocolSnapshot()}"
        )
        mutableEvents.tryEmit(
            TBoxEvent.FatalError(
                "The dash stopped responding while MOTO-HUB was still sending video. Put the bike " +
                    "on its phone-connection screen, make sure no other app is connected to the " +
                    "T-Box, and connect again."
            )
        )
    }

    private fun resetProtocolStats() {
        pxcEvents.set(0L)
        mediaControlEvents.set(0L)
        framesOffered.set(0L)
        framesTimedOut.set(0L)
        framesRejected.set(0L)
        lastPxcEventElapsed.set(0L)
        lastMediaControlEventElapsed.set(0L)
        lastFrameOfferedElapsed.set(0L)
        pxcStreamingBeats.set(0L)
        pxcStallReported.set(false)
        pxcQuietDashReported.set(false)
        unknownCommandsLogged.clear()
    }

    private fun protocolSnapshot(): String {
        val now = SystemClock.elapsedRealtime()
        fun age(last: AtomicLong): String = last.get().takeIf { it > 0L }?.let {
            "${(now - it).coerceAtLeast(0L)}ms ago"
        } ?: "never"
        return "protocolStats=" +
            "pxcRx=${pxcEvents.get()} (last=${age(lastPxcEventElapsed)}, " +
            "streamingBeats=${pxcStreamingBeats.get()}), " +
            "mediaCtrlRx=${mediaControlEvents.get()} (last=${age(lastMediaControlEventElapsed)}), " +
            "framesOffered=${framesOffered.get()} (last=${age(lastFrameOfferedElapsed)}), " +
            "frameTimeouts=${framesTimedOut.get()}, frameRejections=${framesRejected.get()}"
    }

    private companion object {
        const val TAG = "RideDaemonTransport"
        const val SERVICE_TYPE = "_EasyConn._tcp."
        const val PACKAGE_ATTRIBUTE = "packagename"
        const val MODEL_ID_ATTRIBUTE = "modelid"
        const val SIMULATOR_MODEL_ID = MOTO_HUB_SIMULATOR_MODEL_ID
        const val IP_ATTRIBUTE = "ip"
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val DISCOVERY_MAX_ATTEMPTS = 2
        const val DISCOVERY_RETRY_DELAY_MS = 500L
        const val RESUME_DISCOVERY_TIMEOUT_MS = 5_000L
        const val EC_CONNECT_TIMEOUT_MS = 10_000
        // Wake-probe fallback (see sendEasyConnWakeProbe): well-known port and frame layout
        // reverse-engineered by OpenCfMoto/OpenMoto, not part of the advertised EasyConn contract.
        const val WAKE_PROBE_PORT = 10930
        const val WAKE_PROBE_ATTEMPTS = 3
        const val WAKE_PROBE_CONNECT_TIMEOUT_MS = 3_000
        const val WAKE_PROBE_READ_TIMEOUT_MS = 5_000
        const val WAKE_PROBE_RETRY_DELAY_MS = 1_000L
        // The full timeouts above cover a dash that is still booting its EasyConn service. On a
        // dash-page return the dash is already up one Wi-Fi hop away, so a probe that has not
        // been answered this quickly means the rider simply has not pressed Up yet, and the
        // caller is better off retrying than sitting on the socket.
        const val RESUME_WAKE_PROBE_CONNECT_TIMEOUT_MS = 1_200
        const val RESUME_WAKE_PROBE_READ_TIMEOUT_MS = 2_000
        // Fallback sweep for firmware that refuses 10930: the only ports any reference EasyConn
        // implementation documents (PXC 10920-10922, probe 10930) plus a narrow neighborhood in
        // case the whole block shifted (same range TBoxPortScanner uses for diagnostics).
        val FALLBACK_EC_PORTS: List<Int> = (10915..10935).filter { it != WAKE_PROBE_PORT }
        const val FALLBACK_PORT_CONNECT_TIMEOUT_MS = 800
        // A hosted subnet is a /24 in the worst case, so this multiplies by 253 - it has to stay
        // short. Everything on it is one Wi-Fi hop away with no router in between, so a dash that
        // is going to answer answers well inside this; the budget is for the silent addresses.
        const val HOSTED_SWEEP_CONNECT_TIMEOUT_MS = 250
        const val WAKE_PROBE_HEADER_SIZE = 16
        const val CMD_MDNS_RESPOND = 0x70000010
        const val CMD_MDNS_RESPOND_ACK = 0x70000011
        // The identity presented in the probe body - and, on a Wi-Fi Direct group where NSD has no
        // bindable Network to resolve a package from, the one recorded on the resulting TBoxHost -
        // is whichever candidate the dash acknowledged. See EasyConnClientIdentity.
        const val MEDIA_CONTROL_EVENT_SOURCE = 3L
        const val PXC_EVENT_SOURCE = 2L

        // Daemon-originated events (hud/core EventSourceTransport): the transport reporting
        // its own decisions, currently only the negotiated video frame format.
        const val TRANSPORT_EVENT_SOURCE = 4L
        const val TRANSPORT_VIDEO_FRAMING_COMMAND = 1L
        /** Bounds for the always-on first-occurrence dump of unknown protocol commands. */
        const val UNKNOWN_COMMAND_LOG_LIMIT = 32
        const val UNKNOWN_COMMAND_PREVIEW_BYTES = 64
        const val PXC_HEARTBEAT_COMMAND = 0x70000000L
        const val PXC_HEARTBEAT_ACK_COMMAND = 0x70000001L
        const val PXC_CLOCK_KEEPALIVE_COMMAND = 0x10600L
        /** The other clock question, answered with JSON rather than the binary stamp above. */
        const val PXC_QUERY_TIME_COMMAND = 0x10450L
        const val MEDIA_CONTROL_PING_COMMAND = 64L
        const val PXC_HUD_CONFIG_COMMAND = 65_552L
        const val MEDIA_CAPTURE_CONFIG_COMMAND = 16L
        const val MEDIA_TOUCH_COMMAND = 32L
        const val MEDIA_STREAM_START_COMMAND = 112L

        fun protocolSourceName(type: Long): String = when (type) {
            PXC_EVENT_SOURCE -> "PXC"
            MEDIA_CONTROL_EVENT_SOURCE -> "MEDIA_CONTROL"
            else -> "UNKNOWN"
        }

        /**
         * Opcodes named by the open-cflink/open-cfmoto reverse-engineering work
         * (refs/open-cflink PxcFrame.kt and PxcHandshake.kt). Naming them here
         * only changes what the log reads like, but a field log full of
         * "UNKNOWN" hides which of these a dash did and did not send - which is
         * exactly the question a T-Box investigation starts from.
         *
         * QUERY_SPEED is a trap worth keeping labelled: it carries
         * {usbSpeed, wifiSpeed}, the link rate, and has nothing to do with how
         * fast the motorcycle is going.
         */
        private val PXC_COMMAND_NAMES = mapOf(
            PXC_HEARTBEAT_COMMAND to "HEARTBEAT",
            PXC_HEARTBEAT_ACK_COMMAND to "HEARTBEAT_ACK",
            PXC_CLOCK_KEEPALIVE_COMMAND to "CLOCK_KEEPALIVE",
            0x10601L to "CLOCK_KEEPALIVE_ACK",
            PXC_HUD_CONFIG_COMMAND to "CLIENT_INFO",
            0x10011L to "CLIENT_INFO_RLY",
            0x10020L to "MEDIA_FEATURE_CFG",
            0x10690L to "QUERY_SPEED",
            0x103a0L to "OTA_FTP_INFO",
            0x103e0L to "CHECK_SN",
            0x10780L to "LOG_REPORT",
            // The periodic pair on an easyride-flavour dash: 113 of each in a four-minute rider
            // session (2026-08-02), both empty. They are that dash's keepalive beat, in the place
            // CLOCK_KEEPALIVE occupies on a CFMOTO unit - which is the point of naming them.
            // Nothing should be gated on a particular opcode being "the" keepalive: the same log
            // carries zero 0x10600, while a CFDL16 sends six PXC messages in total and then stops.
            0x10630L to "PERIODIC_NOTIFY",
            0x10430L to "PERIODIC_NOTIFY_ALT",
            // Seen twice each in the same session, both empty; named only so a field log stops
            // reading as a wall of UNKNOWN. open-cfmoto's notes list 0x10450 as empty too, and
            // 0x10040 as carrying {maxNaviIcon, supportFunction}.
            //
            // 0x10450 turned out to be the OTHER clock question, answered with JSON rather than
            // the binary stamp 0x10600 wants. A dash sends one or the other, never both: a Voge
            // log (DIRECT-VOGE-034672, 2026-08-02) has one 0x10450 right after the handshake and
            // zero 0x10600 across five days, which is why its clock was never set.
            PXC_QUERY_TIME_COMMAND to "QUERY_TIME",
            0x10451L to "QUERY_TIME_ACK",
            0x104a0L to "NOTIFY_104A0",
            0x10040L to "NAVI_CAPS"
        )

        private val MEDIA_CONTROL_COMMAND_NAMES = mapOf(
            MEDIA_CONTROL_PING_COMMAND to "PING",
            MEDIA_STREAM_START_COMMAND to "STREAM_START",
            MEDIA_CAPTURE_CONFIG_COMMAND to "CAPTURE_CONFIG",
            MEDIA_TOUCH_COMMAND to "TOUCH"
        )

        fun protocolCommandName(type: Long, command: Long): String = when (type) {
            PXC_EVENT_SOURCE -> PXC_COMMAND_NAMES[command]
            MEDIA_CONTROL_EVENT_SOURCE -> MEDIA_CONTROL_COMMAND_NAMES[command]
            else -> null
        } ?: "UNKNOWN"
    }
}

/**
 * Whether a PXC event, arriving now, counts as a streaming-time keepalive beat (see
 * [PXC_STREAMING_CADENCE_MIN_BEATS]). Three conditions, each excluding a shape that must not
 * count: no frame offered yet excludes the handshake exchange; no previous event excludes the
 * very first message; a gap under [PXC_STREAMING_BEAT_MIN_GAP_MS] excludes the members of a
 * same-burst flurry, which prove one transmission, not a cadence.
 */
internal fun isStreamingPxcBeat(
    previousPxcEventElapsed: Long,
    lastFrameOfferedElapsed: Long,
    now: Long
): Boolean =
    lastFrameOfferedElapsed > 0L &&
        previousPxcEventElapsed > 0L &&
        now - previousPxcEventElapsed >= PXC_STREAMING_BEAT_MIN_GAP_MS

internal fun decodeEasyConnPackage(value: ByteArray?): String? = value
    ?.toString(Charsets.UTF_8)
    ?.trim()
    ?.takeIf(String::isNotBlank)

internal fun decodeTBoxVideoArea(payload: ByteArray): TBoxEvent.VideoArea? {
    if (payload.size < 4) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val width = body.getShort(0).toInt() and 0xFFFF
    val height = body.getShort(2).toInt() and 0xFFFF
    return if (width > 0 && height > 0) TBoxEvent.VideoArea(width, height) else null
}

/**
 * Render the dash's REQ_RV_CONFIG_CAPTURE body for the log. Layout (little endian), from the
 * EasyConn reverse-engineering notes:
 *
 * ```
 * deviceWidth s16@0   deviceHeight s16@2   fps i32@4      wantEncoder i32@8
 * supportCodec i32@12 minQuality s16@16    maxQuality s16@18
 * bitRate i32@20      capScreenMode b@24   touchMode b@25 orientation b@26
 * displayId b@27      videoType b@28       supportExtendProtocol b@29
 * ```
 *
 * Only [decodeTBoxVideoArea] drives behaviour. Everything else is logged because the fields the
 * transport ignores are exactly the ones that differ on non-CFMOTO firmware, and a dash that
 * negotiates fine yet shows nothing can only be told apart from a working one here.
 */
internal fun describeTBoxCaptureRequest(payload: ByteArray): String? {
    if (payload.size < 4) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(at: Int): Any = if (payload.size >= at + 2) body.getShort(at).toInt() and 0xFFFF else "?"
    fun i32(at: Int): Any = if (payload.size >= at + 4) body.getInt(at) else "?"
    fun u8(at: Int): Any = if (payload.size > at) payload[at].toInt() and 0xFF else "?"
    return "size=${payload.size}B, device=${u16(0)}x${u16(2)}, fps=${i32(4)}, " +
        "encoder=${i32(8)}, supportCodec=${i32(12)}, bitrate=${i32(20)}, " +
        "capScreenMode=${u8(24)}, touchMode=${u8(25)}, orientation=${u8(26)}, " +
        "videoType=${u8(28)}, supportExtendProtocol=${u8(29)}"
}

internal fun decodeTBoxTouch(payload: ByteArray): TBoxEvent.Touch? {
    if (payload.size < 8) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val action = when (body.getShort(0).toInt() and 0xFFFF) {
        2 -> 0 // DOWN
        1 -> 1 // UP
        3 -> 2 // MOVE
        else -> return null
    }
    val x = body.getShort(2).toInt() and 0xFFFF
    val y = body.getShort(4).toInt() and 0xFFFF
    val pointerId = body.getShort(6).toInt() and 0xFFFF
    return TBoxEvent.Touch(action, pointerId, x, y)
}

// deriveTBoxPeerIpv4 + isSameIpv4Subnet moved to the shared src/main TBoxPeerAddress.kt (pure IP
// math, not GPL) so both flavors can use them.

internal fun parseIpv4Literal(value: String): String? {
    val octets = value.trim().split('.')
    if (octets.size != 4) return null
    val numbers = octets.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return numbers.joinToString(".")
}

internal fun parseUsableEasyConnIpv4Literal(value: String): String? {
    val literal = parseIpv4Literal(value) ?: return null
    val address = InetAddress.getByName(literal)
    return literal.takeIf { isUsableTBoxIpv4Address(address) }
}

internal fun isMotoHubSimulatorAdvertisement(serviceName: String?, modelId: String?): Boolean {
    val normalizedName = serviceName?.trim().orEmpty()
    val normalizedModelId = modelId?.trim().orEmpty()
	if (normalizedModelId == MOTO_HUB_SIMULATOR_MODEL_ID) return true
    if (normalizedName.startsWith("MOTO-HUB T-Box Simulator")) return true
    return normalizedModelId in setOf(
        "37416",
        "37426",
        "66660703",
        "66660721",
        "66660732",
        "66660742"
    ) && (
        normalizedName.startsWith("CFDL") ||
            normalizedName.startsWith("CFMOTO-") ||
            normalizedName.startsWith("800NK")
        )
}

/** Space-separated lowercase hex, e.g. "7b 0a 20 20" - only ever used behind verbose logging. */
private fun ByteArray.toDiagnosticHex(): String = joinToString(" ") { byte -> "%02x".format(byte) }
