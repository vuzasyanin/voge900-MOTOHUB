// Public, GPL-3.0/AGPL-3.0-licensed bridge: exposes Core's T-Box transport (ridedaemon-lib,
// GPL-3.0) and Android Auto AAP receiver (aa/, AGPL-3.0 technique ported from headunit-revived)
// to another app's process over Binder IPC, so a closed-source companion app can use both
// without linking this code into its own binary. See the "Core/Pro split" note in
// documentation/ARCHITECTURE.md for why this boundary exists.
package io.motohub.android.ipc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.view.Surface
import androidx.core.app.NotificationCompat
import io.motohub.android.R
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.aa.AaNavigationGuidance
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AaCompositor
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.androidauto.AndroidAutoReceiverOwnership
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoSessionService
import io.motohub.android.androidauto.withFullVideoTarget
import io.motohub.android.feature.settings.AndroidAutoAspectMatchingMode
import io.motohub.android.feature.settings.AndroidAutoResolutionMode
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoQuality
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.FormedP2pGroup
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

class IpcBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoStreamLock = Any()
    @Volatile private var videoStreamInput: ParcelFileDescriptor? = null
    @Volatile private var videoStreamJob: Job? = null

    // ── T-Box transport ──────────────────────────────────────────────

    private val sessionListeners = RemoteCallbackList<ITBoxSessionListener>()
    private var sessionPollJob: Job? = null
    @Volatile private var lastKnownHandle: TBoxSessionHandle? = null
    @Volatile private var activeConnect: Pair<CoreTBoxConnector, Deferred<Boolean>>? = null

    private val tboxTransportBinder = object : ITBoxTransportService.Stub() {
        override fun isSessionReady(): Boolean = TBoxSessionRegistry.current() != null

        override fun getActiveMotorcycle(): MotorcycleSummary? =
            TBoxSessionRegistry.current()?.motorcycle?.let { profile ->
                MotorcycleSummary(
                    id = profile.id,
                    ssid = profile.ssid,
                    modelId = profile.modelId,
                    displayName = profile.displayName
                )
            }

        // TBoxSessionHandle doesn't carry a negotiated encoder profile today — that negotiation
        // happens per session-start inside each mode's own startCapture(), not as a queryable
        // property. Phase 2 (once a caller actually needs this) wires the real value through.
        override fun getNegotiatedEncoderProfile(): EncoderProfileParcel? = null

        // Runs the same EasyConn video start + live TFT-area negotiation Core's own
        // ProjectionSessionService does, but on behalf of a companion app that can't contain the
        // GPL transport. Blocking on the binder thread; the returned width/height are the raw TFT
        // area (the caller derives its own encoder profile/bitrate from it). offerAccessUnit()
        // starts delivering frames only after this returns non-null.
        override fun startVideoSession(): EncoderProfileParcel? =
            kotlinx.coroutines.runBlocking {
                closeVideoStreamPipe()
                var handle = TBoxSessionRegistry.current() ?: return@runBlocking null
                val fallbackArea = TBoxModelProfile.fallbackVideoArea(
                    handle.motorcycle.modelId,
                    null,
                    ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
                )
                var result = handle.transport.negotiateVideoConfiguration(
                    host = handle.host,
                    savedArea = null,
                    fallbackArea = fallbackArea,
                    timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MS
                )
                if (result.isFailure) {
                    // NOT a timing issue - RideDaemonTransport.stop() (called whenever ANY mode's
                    // session ends, e.g. Android Auto) fully tears down the underlying
                    // MobileSession (session = null), so a bare retry of start(host) fails
                    // identically every time with "Call discover() with an active T-Box link
                    // before starting the session". A rider's manual "Connect" again works only
                    // because it re-runs discover() from scratch - do that here instead of a
                    // pointless delayed retry of the exact same broken call.
                    ProjectionEventLog.warning(
                        "IPC_TBOX",
                        "startVideoSession negotiation failed (first attempt): " +
                            "${result.exceptionOrNull()?.message}. Re-discovering the T-Box before retrying."
                    )
                    val rediscovered = handle.transport.discover(handle.link, handle.motorcycle.modelId)
                    val freshHost = rediscovered.getOrNull()
                    if (freshHost == null) {
                        ProjectionEventLog.warning(
                            "IPC_TBOX",
                            "Re-discovery failed: ${rediscovered.exceptionOrNull()?.message}"
                        )
                    } else {
                        handle = handle.copy(host = freshHost)
                        TBoxSessionRegistry.install(handle)
                        result = handle.transport.negotiateVideoConfiguration(
                            host = handle.host,
                            savedArea = null,
                            fallbackArea = fallbackArea,
                            timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MS
                        )
                    }
                }
                val configuration = result.getOrElse {
                    ProjectionEventLog.warning(
                        "IPC_TBOX",
                        "startVideoSession negotiation failed: ${it.message}"
                    )
                    return@runBlocking null
                }
                val area = configuration.rawArea
                ProjectionEventLog.record(
                    "IPC_TBOX",
                    "Video session started for a companion app; TFT area ${area.width}x${area.height} " +
                        "(source=${configuration.source})."
                )
                EncoderProfileParcel(
                    width = area.width,
                    height = area.height,
                    frameRate = 30,
                    bitRate = 2_500_000,
                    usedFallback = configuration.source == io.motohub.android.tbox.TBoxVideoAreaSource.FALLBACK
                )
            }

        override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
            TBoxSessionRegistry.current()?.transport?.offerAccessUnit(accessUnit) ?: false

        // Runs Core's own GPL connect flow (hudlib) on behalf of a companion app that can't
        // contain it. Blocking on the binder thread until READY, mirroring the AIDL contract.
        // Launched as a cancellable Deferred (not a bare runBlocking body) so a concurrent
        // cancelConnect() call — arriving on a DIFFERENT binder thread — can actually interrupt
        // it instead of this call only ever returning once the connect attempt times out on
        // its own. See cancelConnect() below.
        //
        // The connector comes from CoreTBoxConnectors rather than being built here: a connector
        // owns an exclusive WifiNetworkSpecifier request, and a second live one fights the first
        // for the association. Building one per call left an orphan behind on every reconnect.
        // CoreTBoxConnectors also reuses the previous connector across retries for the same SSID
        // instead of tearing it down, so a still-pending Wi-Fi hunt survives a retry.
        override fun connect(request: MotorcycleConnectRequest): Boolean = runConnect(request, null)

        override fun getContractVersion(): Int = IpcBridgeContract.CONTRACT_VERSION

        // The caller formed the Wi-Fi Direct group in its own process and passes the addresses it
        // resolved there, because this one cannot resolve them for a group it did not form. Bad
        // addresses are refused here rather than deep in the connect: a caller that cannot say
        // where the group is has not really handed one over.
        override fun connectOverFormedGroup(
            request: MotorcycleConnectRequest,
            localIpv4: String?,
            groupOwnerIpv4: String?
        ): Boolean {
            val local = parseIpv4(localIpv4)
            val groupOwner = parseIpv4(groupOwnerIpv4)
            if (local == null || groupOwner == null) {
                ProjectionEventLog.error(
                    "IPC_TBOX",
                    "AIDL connectOverFormedGroup refused: unusable addresses " +
                        "(local=$localIpv4, groupOwner=$groupOwnerIpv4)."
                )
                return false
            }
            ProjectionEventLog.record(
                "IPC_TBOX",
                "AIDL connect over the group the companion app formed: phone=$localIpv4, dash=$groupOwnerIpv4."
            )
            return runConnect(request, FormedP2pGroup(local, groupOwner))
        }

        private fun runConnect(
            request: MotorcycleConnectRequest,
            formedGroup: FormedP2pGroup?
        ): Boolean =
            kotlinx.coroutines.runBlocking {
                val connector = CoreTBoxConnectors.acquire(applicationContext, request.ssid)
                val deferred = serviceScope.async { connector.connect(request.toProfile(), formedGroup) }
                activeConnect = connector to deferred
                val result = try {
                    deferred.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    false
                }
                if (activeConnect?.second === deferred) activeConnect = null
                result
            }

        // Dotted-quad literals only. getByName() would resolve anything else through DNS, on the
        // binder thread, for a value that is always a literal when the caller is honest.
        private fun parseIpv4(value: String?): java.net.Inet4Address? {
            val text = value?.trim().orEmpty()
            if (!IPV4_LITERAL.matches(text)) return null
            return runCatching { java.net.InetAddress.getByName(text) }
                .getOrNull() as? java.net.Inet4Address
        }

        override fun cancelConnect() {
            val (connector, deferred) = activeConnect ?: return
            deferred.cancel()
            serviceScope.launch { connector.cancel() }
        }

        override fun disconnect() {
            closeVideoStreamPipe()
            kotlinx.coroutines.runBlocking {
                // Tear down the registry's session first (it may belong to Core's own UI rather
                // than to this bridge), then release our connector - which also closes the Wi-Fi
                // request that building a throwaway connector here used to leave behind.
                CoreTBoxConnector.disconnectActiveSession()
                CoreTBoxConnectors.clear()
            }
        }

        override fun registerSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.register(listener)
            ensureSessionPolling()
        }

        override fun unregisterSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.unregister(listener)
        }

        override fun openVideoStream(): ParcelFileDescriptor? = openVideoStreamPipe()

        override fun closeVideoStream() {
            closeVideoStreamPipe()
        }

        /**
         * The companion app mirrors this log next to its own so a rider shares one file
         * instead of exporting from two apps. A real file, not a pipe: the export is
         * bounded (log ring + message caps) and a file descriptor stays readable even
         * after this service is unbound.
         */
        override fun openDiagnosticLogSnapshot(): ParcelFileDescriptor? = runCatching {
            val text = ProjectionEventLog.exportText()
            if (text.isBlank()) return@runCatching null
            val file = java.io.File(cacheDir, "ipc-diagnostics-snapshot.txt")
            file.writeText(text, Charsets.UTF_8)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.onFailure {
            ProjectionEventLog.warning("IPC", "Diagnostic log snapshot failed.", it)
        }.getOrNull()

        override fun clearDiagnosticLog() {
            ProjectionEventLog.clear()
            // After the clear so it survives it: without this line a rider who cleared from
            // the companion app sees a Core log that "emptied itself" with nothing to say why.
            ProjectionEventLog.record("IPC", "Diagnostic log cleared at the companion app's request.")
        }
    }

    /**
     * Opens the high-rate data plane once. The Binder bridge remains the control
     * plane; encoded frames are length-prefixed on this local pipe instead of
     * becoming one Binder transaction per frame.
     */
    private fun openVideoStreamPipe(): ParcelFileDescriptor? {
        synchronized(videoStreamLock) {
            closeVideoStreamPipeLocked()
            return runCatching {
                val pipe = ParcelFileDescriptor.createPipe()
                videoStreamInput = pipe[0]
                // The companion app streams through this pipe on the shared T-Box session, but
                // it lives in another process and cannot claim the session itself. Hold the
                // claim on its behalf so a mode stopping inside Core cannot end the session
                // while the companion is still streaming on it.
                TBoxSessionRegistry.claim(SESSION_CONSUMER)
                videoStreamJob = serviceScope.launch {
                    readVideoStream(pipe[0])
                }
                pipe[1]
            }.onFailure {
                ProjectionEventLog.error("IPC_TBOX", "Unable to open the PRO video data pipe.", it)
            }.getOrNull()
        }
    }

    private fun closeVideoStreamPipe() {
        synchronized(videoStreamLock) {
            closeVideoStreamPipeLocked()
        }
    }

    private fun closeVideoStreamPipeLocked() {
        videoStreamJob?.cancel()
        videoStreamJob = null
        videoStreamInput?.runCatching { close() }
        videoStreamInput = null
        TBoxSessionRegistry.release(SESSION_CONSUMER)
    }

    private suspend fun readVideoStream(input: ParcelFileDescriptor) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(input).use { raw ->
                DataInputStream(BufferedInputStream(raw, VIDEO_PIPE_BUFFER_BYTES)).use { stream ->
                    var consecutiveRejectedFrames = 0
                    while (currentCoroutineContext().isActive) {
                        val size = try {
                            stream.readInt()
                        } catch (_: EOFException) {
                            break
                        }
                        require(size in 1..MAX_VIDEO_ACCESS_UNIT_BYTES) {
                            "Invalid PRO video access unit size: $size"
                        }
                        val accessUnit = ByteArray(size)
                        stream.readFully(accessUnit)
                        val transport = TBoxSessionRegistry.current()?.transport ?: break
                        if (!transport.offerAccessUnit(accessUnit)) {
                            consecutiveRejectedFrames++
                            if (consecutiveRejectedFrames >= MAX_CONSECUTIVE_REJECTED_FRAMES) {
                                ProjectionEventLog.warning(
                                    "IPC_TBOX",
                                    "PRO video pipe stopped because CORE rejected " +
                                        "$consecutiveRejectedFrames consecutive AVC frames."
                                )
                                break
                            }
                        } else {
                            consecutiveRejectedFrames = 0
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure !is kotlinx.coroutines.CancellationException) {
                ProjectionEventLog.error("IPC_TBOX", "PRO video pipe reader stopped.", failure)
            }
        } finally {
            synchronized(videoStreamLock) {
                if (videoStreamInput === input) {
                    videoStreamInput = null
                    videoStreamJob = null
                }
            }
        }
    }

    /** TBoxSessionRegistry exposes no observable state (a deliberately small, in-process-only
     *  registry — see its own doc comment), so this is the simplest way to turn its polled
     *  current() into ready/lost callbacks for a remote caller without changing that shared,
     *  already-depended-upon class. */
    private fun ensureSessionPolling() {
        if (sessionPollJob?.isActive == true) return
        sessionPollJob = serviceScope.launch {
            while (true) {
                // Nobody is listening: this loop exists only to feed remote callbacks, and it
                // used to keep ticking for the life of the process after the last companion
                // unbound. registerSessionListener() calls back in here, so a later listener
                // restarts it.
                if (sessionListeners.registeredCallbackCount == 0) {
                    ProjectionEventLog.debug(
                        "IPC",
                        "No session listeners left; stopping the session poll until one registers."
                    )
                    return@launch
                }
                val handle = TBoxSessionRegistry.current()
                if ((handle != null) != (lastKnownHandle != null)) {
                    val ready = handle != null
                    val count = sessionListeners.beginBroadcast()
                    for (i in 0 until count) {
                        runCatching {
                            if (ready) sessionListeners.getBroadcastItem(i).onSessionReady()
                            else sessionListeners.getBroadcastItem(i).onSessionLost()
                        }
                    }
                    sessionListeners.finishBroadcast()
                }
                lastKnownHandle = handle
                delay(SESSION_POLL_INTERVAL_MS)
            }
        }
    }

    // ── Android Auto receiver ────────────────────────────────────────

    private val stateListeners = RemoteCallbackList<IAndroidAutoStateListener>()
    private val navigationListeners = RemoteCallbackList<INavigationGuidanceListener>()
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null

    private fun AaNavigationGuidance.Snapshot.toParcel() = NavigationGuidanceParcel(
        active = active,
        rerouting = rerouting,
        maneuverType = maneuverType,
        roundaboutExitNumber = roundaboutExitNumber,
        road = road,
        distanceToManeuverMeters = distanceToManeuverMeters,
        timeToManeuverSeconds = timeToManeuverSeconds,
        distanceRemainingMeters = distanceRemainingMeters,
        timeToArrivalSeconds = timeToArrivalSeconds,
        estimatedTimeAtArrival = estimatedTimeAtArrival
    )

    private fun broadcastGuidance(snapshot: AaNavigationGuidance.Snapshot) {
        val parcel = snapshot.toParcel()
        val count = navigationListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { navigationListeners.getBroadcastItem(i).onGuidanceChanged(parcel) }
        }
        navigationListeners.finishBroadcast()
    }

    private val androidAutoBinder = object : IAndroidAutoReceiverService.Stub() {
        override fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An Android Auto receiver session is already active.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) {
                publishState(
                    AndroidAutoIpcState.FAILED,
                    "Core's full Android Auto session is already active; stop it before attaching a preview."
                )
                return false
            }
            if (!SingleKeyKeyManager.isAvailable(this@IpcBridgeService)) {
                publishState(AndroidAutoIpcState.FAILED, "Android Auto identity is not included in this build.")
                return false
            }
            publishState(AndroidAutoIpcState.PREPARING, "")
            val profile = AndroidAutoCapabilityProfiles.fallback().withFullVideoTarget()
            val activeCompositor = AaCompositor(
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                displayMode = AndroidAutoDisplayMode.STRETCH,
                sourceGeometry = profile.video
            )
            if (!activeCompositor.start()) {
                publishState(AndroidAutoIpcState.FAILED, "Compositor failed to initialize (EGL/GL).")
                return false
            }
            val decoderSurface = activeCompositor.inputSurface
            if (decoderSurface == null) {
                activeCompositor.release()
                publishState(AndroidAutoIpcState.FAILED, "Compositor did not create a video surface.")
                return false
            }
            activeCompositor.setOutput(surface, width, height, profile.video.width, profile.video.height)
            compositor = activeCompositor

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                onVideoReady = { publishState(AndroidAutoIpcState.STREAMING, "") },
                onSessionEnded = { clean, userExit ->
                    publishState(
                        AndroidAutoIpcState.STOPPED,
                        when {
                            userExit -> "The user ended the Android Auto session."
                            clean -> "Android Auto ended the session."
                            else -> "Android Auto connection closed unexpectedly."
                        }
                    )
                    releaseReceiver()
                },
                mapTouchToSource = activeCompositor::mapCanvasToUi,
                capabilityProfile = profile,
                downstreamBlockedMillis = activeCompositor::downstreamBlockedMillis
            )
            // Registering here is what lets a leaked receiver from another feature be handed over
            // instead of turning into a bare EADDRINUSE: the checks above already refuse while a
            // *live* Core session is running, so this only ever takes over a stale claim.
            AndroidAutoReceiverOwnership.claim(this@IpcBridgeService, "embedded") { releaseReceiver() }
            if (!activeReceiver.start()) {
                releaseReceiver()
                publishState(AndroidAutoIpcState.FAILED, "Android Auto local port ${AaReceiver.PORT} is unavailable.")
                return false
            }
            receiver = activeReceiver
            publishState(AndroidAutoIpcState.RECEIVER_READY, "")
            triggerSelfModeForEmbeddedReceiver()
            return true
        }

        override fun detachOutputSurface() {
            // Same reasoning as stopFullSession: a pending trigger would re-launch Google
            // Android Auto after the receiver is gone.
            selfModeJob?.cancel()
            selfModeJob = null
            releaseReceiver()
        }

        override fun attachPreviewSurface(surface: Surface, width: Int, height: Int): Boolean {
            compositor?.let {
                it.setPreview(surface, width, height)
                return true
            }
            if (!AndroidAutoRuntime.isActive()) return false
            AndroidAutoPreviewRuntime.attach(surface, width, height)
            return true
        }

        override fun detachPreviewSurface() {
            compositor?.let {
                it.clearPreview()
                return
            }
            AndroidAutoPreviewRuntime.detachAttachedPreview()
        }

        // Touches arriving here come from the TFT, in output-canvas coordinates, which is the
        // space AaReceiver.sendTouch expects: it runs mapTouchToSource (the compositor's
        // canvas -> Android Auto UI mapping) on whatever it is given.
        override fun sendTouch(action: Int, x: Int, y: Int): Boolean {
            val activeReceiver = receiver ?: return false
            activeReceiver.sendTouch(action, x, y)
            return true
        }

        override fun sendPreviewTouch(action: Int, x: Int, y: Int): Boolean {
            compositor?.let { activeCompositor ->
                val mapped = activeCompositor.mapPreviewToUi(x, y) ?: return false
                // sendSourceTouch, NOT sendTouch: mapPreviewToUi has already produced Android
                // Auto UI coordinates, and sendTouch would map them a second time as if they
                // were TFT canvas pixels. That double transform is what made the embedded
                // "Preview & touch" screen land every tap short of where the rider pressed -
                // wrong by a factor of canvasWidth/sourceWidth, so exact at the origin and
                // worse the further out you touch. The full-screen preview path in
                // AndroidAutoSessionService.sendPreviewTouch already used sendSourceTouch;
                // only this bridge did not.
                receiver?.sendSourceTouch(action, mapped.first, mapped.second) ?: return false
                return true
            }
            if (!AndroidAutoRuntime.isActive()) return false
            AndroidAutoPreviewRuntime.sendTouch(action, 0, x, y)
            return true
        }

        // Route key/scroll to Core's active AAP input channel (installed by AaReceiver while a
        // session streams). Returns false when no input channel is ready.
        override fun sendKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)
        override fun sendScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

        // Applies a companion app's settings snapshot to Core's own prefs (via the same setters
        // the UI uses) so the next session honors them. Enums are parsed defensively.
        override fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
            val ctx = applicationContext
            runCatching {
                MotoHubSettings.setAndroidAutoResolution(
                    ctx, AndroidAutoResolutionMode.valueOf(settings.resolutionMode)
                )
            }
            runCatching {
                MotoHubSettings.setAndroidAutoAspectMatching(
                    ctx, AndroidAutoAspectMatchingMode.valueOf(settings.aspectMatching)
                )
            }
            runCatching {
                MotoHubSettings.setVideoQuality(ctx, VideoQuality.valueOf(settings.videoQuality))
            }
            runCatching { MotoHubSettings.setDisableTouchscreen(ctx, settings.disableTouchscreen) }
            runCatching { MotoHubSettings.setSeamlessResume(ctx, settings.seamlessResume) }
            // Display mode (Garage's Stretch/Fit/Letterbox) is stored per-motorcycle, in the
            // caller's OWN app data — Core never sees it unless the caller forwards it here.
            // AndroidAutoSessionService reads it back keyed by handle.motorcycle (the same
            // profile this connect installed), so this must be saved before startFullSession.
            runCatching {
                if (settings.displayMode.isNotBlank()) {
                    TBoxSessionRegistry.current()?.motorcycle?.let { motorcycle ->
                        AndroidAutoDisplayModeStore(ctx).save(
                            motorcycle,
                            AndroidAutoDisplayMode.valueOf(settings.displayMode)
                        )
                    }
                }
            }
            // Handlebar configuration is mirrored from the companion's own stores into Core's:
            // Core's Android Auto bridge reads Core's HandlebarControlStore, so without this the
            // rider's PRO-side configuration never applied to AA sessions. Gated on
            // handlebarSyncProvided so a pre-sync caller's parcel (fields deserialize as
            // false/empty) leaves Core's own configuration untouched.
            if (settings.handlebarSyncProvided) {
                runCatching {
                    io.motohub.android.feature.controls.HandlebarControlStore.setManagedByCompanion(ctx, true)
                    io.motohub.android.feature.controls.HandlebarControlStore.setEnabled(
                        ctx, settings.handlebarControlsEnabled
                    )
                    settings.handlebarMapping.split(',').forEach { entry ->
                        val gestureId = entry.substringBefore('=', "")
                        val actionId = entry.substringAfter('=', "")
                        val gesture = io.motohub.android.feature.controls.HandlebarGesture.entries
                            .firstOrNull { it.id == gestureId }
                        val action = io.motohub.android.feature.controls.HandlebarAction.entries
                            .firstOrNull { it.id == actionId }
                        if (gesture != null && action != null) {
                            io.motohub.android.feature.controls.HandlebarControlStore.setAction(ctx, gesture, action)
                        }
                    }
                    io.motohub.android.feature.controls.DoubleTapDelay.entries
                        .firstOrNull { it.millis == settings.handlebarDoubleTapMillis }
                        ?.let { io.motohub.android.feature.controls.HandlebarTimingPrefs.setDoubleTap(ctx, it) }
                    io.motohub.android.feature.controls.SelectHoldDelay.entries
                        .firstOrNull { it.millis == settings.handlebarSelectHoldMillis }
                        ?.let { io.motohub.android.feature.controls.HandlebarTimingPrefs.setSelectHold(ctx, it) }
                    io.motohub.android.feature.controls.HandlebarTimingPrefs.setEagerSingles(
                        ctx, settings.handlebarEagerSingles
                    )
                    io.motohub.android.feature.controls.HandlebarTimingPrefs.setHoldsEnabled(
                        ctx, settings.handlebarHoldsEnabled
                    )
                    // The taught calibration travels too: Core's bridge and mapping UI read
                    // Core's own store, and the stores are scoped to the session's motorcycle,
                    // so the companion's per-bike teaching lands per-bike here as well. A live
                    // bridge then re-decides the volume pin against the fresh calibration.
                    io.motohub.android.feature.controls.HandlebarCalibration.import(
                        ctx, settings.handlebarCalibration
                    )
                    io.motohub.android.feature.controls.MediaButtonBridge.refreshVolumeGestureUse()
                    // Apply live when an AA session is already capturing (settings re-pushed by
                    // the companion at every session start, including embedded dashboard AA).
                    io.motohub.android.feature.controls.MediaButtonBridge.setTargetCaptureActive(
                        io.motohub.android.feature.controls.MediaButtonBridge.TARGET_ANDROID_AUTO,
                        settings.handlebarControlsEnabled
                    )
                    ProjectionEventLog.record(
                        "IPC_AA",
                        "Handlebar configuration mirrored from companion: " +
                            "enabled=${settings.handlebarControlsEnabled}."
                    )
                }
            }
            ProjectionEventLog.record("IPC_AA", "Applied companion Android Auto settings snapshot.")
        }

        // Toggles day/night on the running session via the same runtime path the UI uses.
        override fun setNightMode(isNight: Boolean): Boolean =
            AndroidAutoPreviewRuntime.setNightMode(isNight)

        // Triggers Core's own existing AndroidAutoSessionService unchanged — this deliberately
        // does not duplicate its pipeline (watchdog/recovery/T-Box negotiation) here. Both this
        // and attachOutputSurface ultimately bind the same fixed local AA port; the loser of a
        // race fails cleanly (AaReceiver.start() returns false), it does not crash.
        override fun startFullSession(): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An embedded preview session is already attached.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) return true
            ensureFullSessionStateForwarding()
            AndroidAutoSessionService.start(this@IpcBridgeService)
            // Core's own UI (MainActivity) normally fires the self-mode trigger once the receiver
            // is ready. When a companion app drives the session over AIDL, that Activity isn't in
            // the loop, so trigger it here instead — the broadcast fallback works from a service.
            triggerSelfModeWhenReady()
            return true
        }

        override fun stopFullSession() {
            // Cancel any pending self-mode trigger first: a stop issued while the receiver is
            // still coming up would otherwise fire AaSelfMode after teardown and immediately
            // re-launch Google Android Auto, making the stop look like it "didn't work".
            selfModeJob?.cancel()
            selfModeJob = null
            AndroidAutoSessionService.stop(this@IpcBridgeService)
        }

        override fun registerStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.register(listener)
            ensureFullSessionStateForwarding()
        }

        override fun unregisterStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.unregister(listener)
        }

        override fun registerNavigationGuidanceListener(listener: INavigationGuidanceListener) {
            navigationListeners.register(listener)
            // A widget attaching mid-ride should not wait for the next turn to learn the
            // current one.
            runCatching { listener.onGuidanceChanged(AaNavigationGuidance.latest.toParcel()) }
        }

        override fun unregisterNavigationGuidanceListener(listener: INavigationGuidanceListener) {
            navigationListeners.unregister(listener)
        }

    }

    private var selfModeJob: Job? = null

    /** Mirrors MainActivity.startAndroidAuto's coordinator: wait for the receiver to be ready,
     *  let it settle, then ask Google Android Auto to connect to Core's local AAP port. */
    private fun triggerSelfModeWhenReady() {
        selfModeJob?.cancel()
        selfModeJob = serviceScope.launch {
            val state = kotlinx.coroutines.withTimeoutOrNull(SELF_MODE_READY_TIMEOUT_MS) {
                AndroidAutoRuntime.state
                    .dropWhile {
                        it is AndroidAutoRuntimeState.Idle ||
                            it is AndroidAutoRuntimeState.Stopped ||
                            it is AndroidAutoRuntimeState.Failed
                    }
                    .first {
                        it is AndroidAutoRuntimeState.ReceiverReady ||
                            it is AndroidAutoRuntimeState.Failed
                    }
            }
            if (state is AndroidAutoRuntimeState.ReceiverReady) {
                delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                    AaSelfMode.trigger(
                        context = applicationContext,
                        onProgress = { detail ->
                            // Keep Core's own UI and the companion's in step during the wait.
                            AndroidAutoRuntime.publishStartupDetail(detail)
                            publishState(AndroidAutoIpcState.RECEIVER_READY, detail)
                        },
                        log = { ProjectionEventLog.record("AAP", it) }
                    )
                }
            }
        }
    }

    /**
     * The embedded (Ride Dashboard) receiver is ready as soon as AaReceiver.start() returns and
     * never drives AndroidAutoRuntime, so [triggerSelfModeWhenReady]'s state wait would block
     * forever here. Without a trigger the receiver just listens on the local AAP port and Google
     * Android Auto is never asked to connect - the dashboard sits on "STARTING ANDROID AUTO"
     * indefinitely, which is exactly what the full-session path avoids by calling AaSelfMode.
     */
    private fun triggerSelfModeForEmbeddedReceiver() {
        selfModeJob?.cancel()
        selfModeJob = serviceScope.launch {
            delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
            if (receiver != null) {
                AaSelfMode.trigger(
                    context = applicationContext,
                    // The companion has no window into Core's startup: without forwarding the
                    // progress it shows a motionless "preparing" for the whole attempt sequence.
                    onProgress = { publishState(AndroidAutoIpcState.RECEIVER_READY, it) },
                    log = { ProjectionEventLog.record("AAP", it) }
                )
            }
        }
    }

    private fun releaseReceiver() {
        receiver?.stop()
        receiver = null
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
        // No-op when another owner has already taken the port over (it is the one that called us).
        AndroidAutoReceiverOwnership.release(this@IpcBridgeService)
    }

    private var fullSessionForwardingJob: Job? = null

    /** Forwards Core's own AndroidAutoRuntime.state (used by AndroidAutoSessionService, already
     *  Core's shipping full-AA feature) to remote listeners — Core's implementation itself is
     *  untouched, this only republishes its existing state on the same channel embedded-preview
     *  callers already listen on. */
    private fun ensureFullSessionStateForwarding() {
        if (fullSessionForwardingJob?.isActive == true) return
        fullSessionForwardingJob = serviceScope.launch {
            var firstEmission = true
            AndroidAutoRuntime.state.collectLatest { state ->
                // AndroidAutoRuntime.state is a StateFlow that keeps whatever it was last set to
                // by ANY previous attempt (including one that predates this listener, e.g. a
                // user tapping start before a T-Box was ready). Don't surface a stale
                // Failed/Stopped from before this listener existed as if it just happened now —
                // only report it if it happens WHILE we're actually watching.
                if (firstEmission) {
                    firstEmission = false
                    if (state is AndroidAutoRuntimeState.Failed || state is AndroidAutoRuntimeState.Stopped) {
                        publishState(AndroidAutoIpcState.IDLE, "")
                        return@collectLatest
                    }
                }
                val (ipcState, message) = when (state) {
                    AndroidAutoRuntimeState.Idle -> AndroidAutoIpcState.IDLE to ""
                    AndroidAutoRuntimeState.Preparing -> AndroidAutoIpcState.PREPARING to ""
                    AndroidAutoRuntimeState.ReceiverReady -> AndroidAutoIpcState.RECEIVER_READY to ""
                    AndroidAutoRuntimeState.Streaming -> AndroidAutoIpcState.STREAMING to ""
                    is AndroidAutoRuntimeState.Stopped -> AndroidAutoIpcState.STOPPED to state.reason
                    is AndroidAutoRuntimeState.Failed -> AndroidAutoIpcState.FAILED to state.message
                }
                publishState(ipcState, message)
            }
        }
    }

    private fun publishState(state: Int, message: String) {
        ProjectionEventLog.debug("IPC_AA", "state=$state message=$message")
        val count = stateListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { stateListeners.getBroadcastItem(i).onStateChanged(state, message) }
        }
        stateListeners.finishBroadcast()
    }

    // ── Service lifecycle ────────────────────────────────────────────

    // This service only exists while a companion app (PRO) is bound to it — but a plain bound
    // service with no foreground presence is just a background process to the OS, and OEM
    // battery managers (ColorOS/OnePlus in particular) reap those aggressively even while the
    // binding client (PRO) is itself in the foreground. That silently drops TBoxSessionRegistry
    // (in-memory only) and any active AA session, surfacing as "No T-Box is ready" or a session
    // that stops working until the rider disconnects and reconnects. Run in the foreground for
    // this service's whole lifetime (bind-to-unbind) so it survives like Core's own AA/Mirroring/
    // Advanced streaming sessions already do.
    override fun onCreate() {
        super.onCreate()
        // Single consumer by design: this bridge is the only cross-process door into CORE, so
        // it owns the guidance fan-out for however many companion listeners register.
        AaNavigationGuidance.setListener(::broadcastGuidance)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.core_bridge_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.core_bridge_channel_description) }
        )
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.core_bridge_notification_title))
            .setContentText(getString(R.string.core_bridge_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onBind(intent: Intent): IBinder? = when (intent.action) {
        IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT -> tboxTransportBinder
        IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER -> androidAutoBinder
        else -> null
    }

    override fun onDestroy() {
        closeVideoStreamPipe()
        sessionPollJob?.cancel()
        fullSessionForwardingJob?.cancel()
        selfModeJob?.cancel()
        releaseReceiver()
        AaNavigationGuidance.setListener(null)
        sessionListeners.kill()
        stateListeners.kill()
        navigationListeners.kill()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val SESSION_CONSUMER = "companion-app"
        const val VIDEO_PIPE_BUFFER_BYTES = 64 * 1024
        const val MAX_VIDEO_ACCESS_UNIT_BYTES = 2 * 1024 * 1024
        const val MAX_CONSECUTIVE_REJECTED_FRAMES = 3
        const val SESSION_POLL_INTERVAL_MS = 1_000L
        const val VIDEO_CONFIGURATION_TIMEOUT_MS = 10_000L
        const val SELF_MODE_READY_TIMEOUT_MS = 10_000L
        const val ANDROID_AUTO_RECEIVER_SETTLE_MS = 900L
        const val CHANNEL_ID = "core_bridge_v1"
        const val NOTIFICATION_ID = 9101
        val IPV4_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    }
}
