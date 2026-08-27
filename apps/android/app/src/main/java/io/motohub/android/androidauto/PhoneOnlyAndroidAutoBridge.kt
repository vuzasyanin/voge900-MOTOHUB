package io.motohub.android.androidauto

import android.content.Context
import android.view.Surface
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CORE-only: lets Android Auto run entirely for the phone's own screen, with no T-Box/TFT
 * connection at all. Mirrors [io.motohub.android.ipc.IpcBridgeService]'s `attachOutputSurface()`
 * compositor+receiver construction almost verbatim (that method exists for the exact same reason
 * — an arbitrary output Surface, zero T-Box dependency — but is reached over AIDL from a companion
 * app); this is the equivalent Core-native entry point, used directly by Core's own UI, so
 * Core-only riders never bind to `IpcBridgeService` (which unconditionally starts a "Core Bridge"
 * foreground notification on first bind) just to use Android Auto without a T-Box.
 *
 * The session (receiver+compositor) is built once in [start] and torn down only in [stop] -
 * [attachPreview]/[detachPreview] only re-point where the compositor draws, exactly like the real
 * T-Box session's AndroidAutoSessionService does. This matters: Android's SurfaceView routinely
 * fires surfaceDestroyed+surfaceCreated a couple of times during initial layout/window attach: if
 * that had rebuilt the whole receiver each time (as an earlier version of this file did), it
 * re-triggered AaSelfMode and made Google's Android Auto app visibly relaunch several times.
 *
 * The bridge itself is a process singleton, and deliberately so: the session it owns (a bound
 * local socket on 5288 + an EGL compositor) outlives any single Activity instance. Handing each
 * MainActivity its own bridge meant a configuration change - a plain screen rotation while the
 * phone preview was open - orphaned the running one: the recreated Activity got an empty bridge,
 * so closing the preview stopped nothing, and the abandoned receiver kept 5288 bound until the
 * process died, blocking every later Android Auto start.
 */
fun createAndroidAutoPhoneOnlyBridge(context: Context): AndroidAutoPhoneOnlyBridge =
    PhoneOnlyBridgeHolder.get(context.applicationContext)

private object PhoneOnlyBridgeHolder {
    private var instance: PhoneOnlyAndroidAutoBridge? = null

    @Synchronized
    fun get(applicationContext: Context): PhoneOnlyAndroidAutoBridge =
        instance ?: PhoneOnlyAndroidAutoBridge(applicationContext).also { instance = it }
}

class PhoneOnlyAndroidAutoBridge(private val context: Context) :
    AndroidAutoPreviewController, AndroidAutoPhoneOnlyBridge {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null
    private var selfModeJob: Job? = null
    private var videoWidth = 0
    private var videoHeight = 0

    override fun start(onFailure: (String) -> Unit) {
        if (receiver != null) return
        if (AndroidAutoRuntime.isActive()) {
            onFailure("Android Auto is already running.")
            return
        }
        if (!SingleKeyKeyManager.isAvailable(context)) {
            onFailure("Android Auto identity is not included in this build.")
            return
        }
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Preparing)

        val profile = AndroidAutoCapabilityProfiles.fallback().withFullVideoTarget()
        videoWidth = profile.video.width
        videoHeight = profile.video.height
        val activeCompositor = AaCompositor(
            log = { ProjectionEventLog.debug("PHONE_ONLY_AA", it) },
            displayMode = AndroidAutoDisplayModeStore(context).load(PHONE_ONLY_ANDROID_AUTO_PROFILE),
            sourceGeometry = profile.video,
            outputAppliesBackPressure = false
        )
        if (!activeCompositor.start()) {
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Failed("Compositor failed to initialize (EGL/GL)."))
            return
        }
        val decoderSurface = activeCompositor.inputSurface
        if (decoderSurface == null) {
            activeCompositor.release()
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Failed("Compositor did not create a video surface."))
            return
        }
        compositor = activeCompositor
        // Install only after the compositor exists. If the SurfaceView is already attached,
        // install() immediately reattaches that surface; installing earlier would drop that
        // callback while compositor is still null and leave the phone preview blank forever.
        AndroidAutoPreviewRuntime.install(this)

        val activeReceiver = AaReceiver(
            context = context,
            encoderSurface = decoderSurface,
            log = { ProjectionEventLog.debug("PHONE_ONLY_AA", it) },
            onVideoReady = { AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Streaming) },
            onSessionEnded = { clean, userExit ->
                AndroidAutoRuntime.publish(
                    AndroidAutoRuntimeState.Stopped(
                        when {
                            userExit -> "The user ended the Android Auto session."
                            clean -> "Android Auto ended the session."
                            else -> "Android Auto connection closed unexpectedly."
                        }
                    )
                )
                releaseSession()
            },
            mapTouchToSource = activeCompositor::mapCanvasToUi,
            capabilityProfile = profile,
            downstreamBlockedMillis = activeCompositor::downstreamBlockedMillis
        )
        AndroidAutoReceiverOwnership.claim(this, "phone-only") { stop() }
        if (!activeReceiver.start()) {
            releaseSession()
            AndroidAutoReceiverOwnership.release(this)
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Failed("Android Auto local port ${AaReceiver.PORT} is unavailable."))
            return
        }
        receiver = activeReceiver
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.ReceiverReady)
        triggerSelfModeWhenReady()
    }

    override fun stop() {
        // Now that one bridge is shared by every MainActivity instance, stop() is also reached
        // from onDestroy() of an Activity that never opened a phone-only session at all. The
        // teardown below is idempotent, but the Stopped state is not: publishing it with no
        // session of our own would report over whatever the T-Box session (the other
        // AndroidAutoRuntime publisher) is currently saying.
        val hadSession = receiver != null || compositor != null
        selfModeJob?.cancel()
        selfModeJob = null
        releaseSession()
        AndroidAutoReceiverOwnership.release(this)
        AndroidAutoPreviewRuntime.clear(this)
        if (hadSession) {
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Stopped("Stopped by the user."))
        }
    }

    /**
     * The receiver alone only listens on the local AAP port - nothing asks Google's Android Auto
     * app to actually connect to it. Real T-Box sessions get this from CORE's MainActivity/
     * IpcBridgeService; a phone-only session has neither in the loop, so it must trigger this
     * itself, exactly mirroring IpcBridgeService.triggerSelfModeWhenReady().
     */
    private fun triggerSelfModeWhenReady() {
        selfModeJob?.cancel()
        selfModeJob = scope.launch {
            val state = withTimeoutOrNull(SELF_MODE_READY_TIMEOUT_MS) {
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
                delay(RECEIVER_SETTLE_MS)
                if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                    AaSelfMode.trigger(context) { ProjectionEventLog.record("PHONE_ONLY_AA", it) }
                }
            }
        }
    }

    // Only re-point where the compositor draws - the session itself already exists (built in
    // start()) regardless of how many times the phone's SurfaceView is attached/detached.
    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor?.setOutput(surface, width, height, videoWidth, videoHeight)
    }

    override fun detachPreview() {
        compositor?.clearOutput()
    }

    // Touch/key/scroll route through AaReceiver/AaInputBridge exactly like the real T-Box
    // session's AndroidAutoSessionService does - receiver.sendTouch already applies the
    // canvas→source mapping internally via the mapTouchToSource lambda wired above.
    override fun sendPreviewTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        receiver?.sendTouch(action, pointerId, x, y)
    }

    override fun sendPreviewKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)

    override fun sendPreviewScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

    override fun setPreviewNightMode(isNight: Boolean): Boolean = receiver?.setNightMode(isNight) == true

    private fun releaseSession() {
        receiver?.stop()
        receiver = null
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
    }

    private companion object {
        const val SELF_MODE_READY_TIMEOUT_MS = 10_000L
        const val RECEIVER_SETTLE_MS = 900L
    }
}

private val PHONE_ONLY_ANDROID_AUTO_PROFILE = MotorcycleProfile(
    ssid = "phone-only-android-auto",
    password = "",
    id = "phone-only-android-auto"
)
