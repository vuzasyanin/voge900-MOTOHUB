package io.motohub.android

import io.motohub.android.i18n.motoHubText

import android.app.Activity
import android.app.ActivityManager
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.androidauto.PhoneOnlyAndroidAutoLaunchRequest
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoSelfModeHelp
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoSessionService
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.ipc.IpcBridgeContract
import io.motohub.android.androidauto.TBoxDisplayGeometryStore
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.androidauto.TBoxScreenMarginsStore
import io.motohub.android.data.MotorcyclePhotoStore
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.tbox.ThinkerRideGate
import io.motohub.android.feature.about.AboutScreen
import io.motohub.android.feature.about.MOTO_HUB_DISCORD_URL
import io.motohub.android.feature.about.MOTO_HUB_GITHUB_URL
import io.motohub.android.feature.garage.GarageTabContent
import io.motohub.android.feature.garage.MotorcycleDetailsScreen
import io.motohub.android.feature.garage.TBoxCapabilityScreen
import io.motohub.android.feature.home.HubHomeScreen
import io.motohub.android.feature.home.HubViewModel
import io.motohub.android.feature.androidauto.AndroidAutoHelpScreen
import io.motohub.android.feature.androidauto.AndroidAutoPreviewScreen
import io.motohub.android.feature.androidauto.OfficialCfmotoWarningDialog
import io.motohub.android.feature.diagnostics.NetworkDiagnosticsScreen
import io.motohub.android.feature.diagnostics.NetworkDiagnosticsViewModel
import io.motohub.android.feature.diagnostics.ApplicationLogScreen
import io.motohub.android.feature.diagnostics.DiagnosticLogShare
import io.motohub.android.feature.pairing.ManualPairingScreen
import io.motohub.android.feature.pairing.TBoxQrOrigin
import io.motohub.android.feature.pairing.TBoxQrPayload
import io.motohub.android.feature.pairing.TBoxQrPhotoDecoder
import io.motohub.android.feature.pairing.TBoxQrPhotoProcessingDialog
import io.motohub.android.feature.pairing.TBoxQrScannerScreen
import io.motohub.android.feature.pairing.UnverifiedQrDialog
import io.motohub.android.feature.safety.SafetyDisclaimerDialog
import io.motohub.android.feature.settings.AutostartService
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.SettingsTabContent
import io.motohub.android.feature.update.DownloadProgress
import io.motohub.android.feature.update.GithubRelease
import io.motohub.android.feature.update.GithubUpdateDialog
import io.motohub.android.feature.update.GithubUpdateInstaller
import io.motohub.android.feature.update.GithubUpdateRepository
import io.motohub.android.feature.update.latestNewerApkRelease
import io.motohub.android.session.ProjectionSessionService
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.PhoneDisplayDimmer
import io.motohub.android.session.PhoneDisplayDimPreferences
import io.motohub.android.session.SessionPhase
import io.motohub.android.externaldisplay.AoaAccessoryRuntime
import io.motohub.android.externaldisplay.AoaExternalRuntime
import io.motohub.android.externaldisplay.AoaExternalRuntimeState
import io.motohub.android.externaldisplay.AoaExternalService
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxPortScanResult
import io.motohub.android.tbox.TBoxPortScanner
import io.motohub.android.tbox.OfficialCfmotoClient
import io.motohub.android.tbox.WifiGate
import io.motohub.android.ui.components.HubTab
import io.motohub.android.ui.theme.MotoHubTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private val TBoxScreenMarginsSaver = listSaver<TBoxScreenMargins, Int>(
    save = { margins -> listOf(margins.top, margins.bottom, margins.left, margins.right) },
    restore = { values ->
        TBoxScreenMargins(
            top = values[0],
            bottom = values[1],
            left = values[2],
            right = values[3]
        )
    }
)

private fun applyPhoneOnlyAndroidAutoDisplayMode(context: Context, displayMode: String?) {
    val mode = displayMode?.let { runCatching { AndroidAutoDisplayMode.valueOf(it) }.getOrNull() } ?: return
    AndroidAutoDisplayModeStore(context).save(
        MotorcycleProfile(
            ssid = "phone-only-android-auto",
            password = "",
            id = "phone-only-android-auto"
        ),
        mode
    )
}

class MainActivity : ComponentActivity() {
    private val viewModel: HubViewModel by viewModels()
    private val diagnosticsViewModel: NetworkDiagnosticsViewModel by viewModels()
   private val androidAutoLaunchPending = AtomicBoolean(false)
    private val androidAutoPhoneOnlyBridge by lazy {
        io.motohub.android.androidauto.createAndroidAutoPhoneOnlyBridge(applicationContext)
    }

    /**
     * Starts a connection only once Android is willing to accept the Wi-Fi request behind it.
     *
     * `WifiNetworkFactory` drops a `WifiNetworkSpecifier` request coming from a process that is
     * neither a foreground app nor a foreground service, and answers `onUnavailable` a few tens
     * of milliseconds later. The join never happens, and the verdict reads exactly like a dash
     * that stopped broadcasting - riders were re-scanning QR codes over a race they could not
     * see. The race is real: the runtime permission sheet leaves this activity PAUSED for a few
     * frames after its result callback runs, so a first pairing (the one case that always asks
     * for permissions) connected from a background process every single time.
     *
     * The gate is the importance the platform itself reads, not the lifecycle state alone -
     * during mirroring the projection foreground service legitimately carries the request with
     * this activity in the background, and that path must keep working untouched.
     */
    private fun connectWhenAndroidAccepts(reason: String) {
        if (isForegroundEnoughForWifiRequest()) {
            viewModel.connectAndDiscover()
            return
        }
        ProjectionEventLog.record(
            "CONNECTION",
            "Connect ($reason) deferred: MOTO-HUB is not in the foreground yet " +
                "(importance=${processImportance()}); Android would refuse the Wi-Fi request."
        )
        lifecycleScope.launch {
            withResumed {}
            // Process importance trails the resume callback by a frame or two on some builds.
            var waited = 0L
            while (!isForegroundEnoughForWifiRequest() && waited < FOREGROUND_SETTLE_TIMEOUT_MS) {
                delay(FOREGROUND_SETTLE_POLL_MS)
                waited += FOREGROUND_SETTLE_POLL_MS
            }
            ProjectionEventLog.record(
                "CONNECTION",
                "Running the deferred connect ($reason) ${waited}ms after the resume; " +
                    "importance=${processImportance()}."
            )
            viewModel.connectAndDiscover()
        }
    }

    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    /** Mirrors AOSP's `WifiNetworkFactory.isRequestFromForegroundAppOrService`. */
    private fun isForegroundEnoughForWifiRequest(): Boolean =
        processImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProjectionEventLog.record("UI", "Main activity created.")
        enableEdgeToEdge()
        refreshAoaAccessoryConnected(intent)
        handleAndroidAutoPreviewLaunchIntent(intent)

        setContent {
            MotoHubTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val diagnosticsState by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
                val projectionEvents by ProjectionEventLog.events.collectAsStateWithLifecycle()
                val androidAutoState by AndroidAutoRuntime.state.collectAsStateWithLifecycle()
                val androidAutoActive = androidAutoState is AndroidAutoRuntimeState.Preparing ||
                    androidAutoState is AndroidAutoRuntimeState.ReceiverReady ||
                    androidAutoState is AndroidAutoRuntimeState.Streaming
                val androidAutoStreaming = androidAutoState is AndroidAutoRuntimeState.Streaming
                val aoaExternalState by AoaExternalRuntime.state.collectAsStateWithLifecycle()
                val externalDisplayActive = aoaExternalState is AoaExternalRuntimeState.Starting ||
                    aoaExternalState is AoaExternalRuntimeState.Streaming
                val externalDisplayStreaming = aoaExternalState is AoaExternalRuntimeState.Streaming
                var selectedTab by rememberSaveable { mutableStateOf(HubTab.RIDE) }
                var showQrScanner by rememberSaveable { mutableStateOf(false) }
                var showManualPairing by rememberSaveable { mutableStateOf(false) }
                var showNetworkDiagnostics by rememberSaveable { mutableStateOf(false) }
                var showApplicationLogs by rememberSaveable { mutableStateOf(false) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                var showAndroidAutoHelp by rememberSaveable { mutableStateOf(false) }
                val launchedPhoneOnlyAa =
                    intent?.getBooleanExtra(IpcBridgeContract.EXTRA_START_PHONE_ONLY_ANDROID_AUTO, false) == true
                val launchedPhoneOnlyAaDisplayMode =
                    intent?.getStringExtra(IpcBridgeContract.EXTRA_ANDROID_AUTO_DISPLAY_MODE)
                var showAndroidAutoPreview by rememberSaveable { mutableStateOf(launchedPhoneOnlyAa) }
                var androidAutoPreviewIsPhoneOnly by rememberSaveable { mutableStateOf(launchedPhoneOnlyAa) }
                var androidAutoPhoneOnlyLaunchedFromPro by rememberSaveable { mutableStateOf(launchedPhoneOnlyAa) }
                var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
                var updateAutoCheckAttempted by rememberSaveable { mutableStateOf(false) }
                var updateLoading by remember { mutableStateOf(false) }
                var updateError by remember { mutableStateOf<String?>(null) }
                var updateReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var installingUpdateTag by remember { mutableStateOf<String?>(null) }
                var installingUpdateProgress by remember { mutableStateOf<DownloadProgress?>(null) }
                var qrPhotoProcessing by remember { mutableStateOf(false) }
                var qrPhotoProgress by remember { mutableStateOf(0 to 0) }
                var pendingUnverifiedQr by remember { mutableStateOf<TBoxQrPayload?>(null) }
                var lastAutoConnectAttemptAt by remember { mutableStateOf(0L) }
                var editorProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var capabilityProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var photoTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var returnToGarageAfterPairing by rememberSaveable { mutableStateOf(false) }
                val context = LocalContext.current

                // A code that corroborates itself is saved straight away; anything else decoded
                // cleanly but from a source we cannot vouch for waits for the rider to confirm.
                fun acceptQrPayload(payload: TBoxQrPayload) {
                    if (payload.origin == TBoxQrOrigin.RECOGNISED) {
                        viewModel.applyQrPairing(payload)
                    } else {
                        ProjectionEventLog.record(
                            "PAIRING",
                            "QR decoded from an unrecognised provisioning source; " +
                                "asking the rider before saving ssid=${payload.ssid}."
                        )
                        pendingUnverifiedQr = payload
                    }
                }
                LaunchedEffect(Unit) {
                    // Cold start: the launch Intent is available before composition, so start
                    // directly here; the SharedFlow event below is only for warm starts.
                    if (launchedPhoneOnlyAa) {
                        applyPhoneOnlyAndroidAutoDisplayMode(context, launchedPhoneOnlyAaDisplayMode)
                        androidAutoPhoneOnlyBridge.start(onFailure = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        })
                    }
                    PhoneOnlyAndroidAutoLaunchRequest.requests.collect { displayMode ->
                        applyPhoneOnlyAndroidAutoDisplayMode(context, displayMode)
                        androidAutoPhoneOnlyBridge.start(onFailure = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        })
                        androidAutoPreviewIsPhoneOnly = true
                        androidAutoPhoneOnlyLaunchedFromPro = true
                        showAndroidAutoPreview = true
                    }
                }
                var showSafetyDisclaimer by rememberSaveable {
                    mutableStateOf(!MotoHubSettings.safetyDisclaimerAcknowledged(context))
                }
                var seamlessResumeEnabled by remember {
                    mutableStateOf(
                        MotoHubSettings.seamlessResume(context) && Settings.canDrawOverlays(context)
                    )
                }
                var seamlessResumePermissionPending by remember { mutableStateOf(false) }
                var unknownSourcesAllowed by remember {
                    mutableStateOf(GithubUpdateInstaller.canInstallUnknownSources(this@MainActivity))
                }
                val updateRepository = remember { GithubUpdateRepository() }
                val updateScope = rememberCoroutineScope()
                fun checkForUpdates(openDialog: Boolean) {
                    if (!openDialog) {
                        // Automatic checks are throttled to once/24h so a rider who opens
                        // MOTO-HUB many times a day doesn't hit GitHub's anonymous API rate
                        // limit (60 req/h) on every launch; a manual tap always bypasses this.
                        // A new CORE version also bypasses the throttle once, so the first
                        // launch of the newly installed APK can discover its next update.
                        val elapsed = System.currentTimeMillis() - MotoHubSettings.lastAutoUpdateCheckAtMillis(context)
                        val appVersionChanged = MotoHubSettings.lastAutoUpdateCheckVersion(context) !=
                            BuildConfig.VERSION_NAME
                        if (elapsed < AUTO_UPDATE_CHECK_THROTTLE_MS && !appVersionChanged) {
                            ProjectionEventLog.debug(
                                "UPDATES",
                                "Automatic GitHub check skipped; last check was ${elapsed / 60_000L} minute(s) ago."
                            )
                            return
                        }
                        MotoHubSettings.setLastAutoUpdateCheckAtMillis(context, System.currentTimeMillis())
                        MotoHubSettings.setLastAutoUpdateCheckVersion(context, BuildConfig.VERSION_NAME)
                    }
                    if (openDialog) showUpdateDialog = true
                    if (updateLoading) return
                    updateLoading = true
                    updateError = null
                    updateScope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { updateRepository.fetchReleases() }
                        }
                        updateLoading = false
                        result.onSuccess { releases ->
                            val skippedTag = MotoHubSettings.skippedUpdateTag(context)
                            updateReleases = listOfNotNull(
                                latestNewerApkRelease(
                                    releases,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE
                                )
                            ).filter { openDialog || it.tagName != skippedTag }
                            if (!openDialog && updateReleases.isEmpty()) {
                                ProjectionEventLog.debug(
                                    "UPDATES",
                                    "Automatic GitHub check found no newer, non-skipped APK release."
                                )
                            } else {
                                showUpdateDialog = true
                            }
                        }.onFailure { failure ->
                            updateError = "Unable to check GitHub releases: ${failure.message}"
                            ProjectionEventLog.warning("UPDATES", updateError.orEmpty(), failure)
                        }
                    }
                }
                val displayModeStore = remember(context) { AndroidAutoDisplayModeStore(context) }
               val displayGeometryStore = remember(context) { TBoxDisplayGeometryStore(context) }
                val screenMarginsStore = remember(context) { TBoxScreenMarginsStore(context) }
                val capabilityStore = remember(context) { TBoxCapabilityStore(context) }
                var portScanInProgress by remember { mutableStateOf(false) }
                var portScanResult by remember { mutableStateOf<TBoxPortScanResult?>(null) }
                val portScanScope = rememberCoroutineScope()
                fun scanTBoxPorts(profile: MotorcycleProfile) {
                    if (portScanInProgress) return
                    portScanInProgress = true
                    portScanResult = null
                    portScanScope.launch {
                        portScanResult = TBoxPortScanner.scan(context, profile).getOrNull()
                        portScanInProgress = false
                    }
                }
                val motorcyclePhotoStore = remember(context) { MotorcyclePhotoStore(context) }
                val motorcycleId = state.session.motorcycle?.id
               var motorcycleDetailsDisplayMode by rememberSaveable {
                   mutableStateOf(AndroidAutoDisplayMode.LETTERBOX)
               }
                var motorcycleDetailsScreenMargins by rememberSaveable(
                    stateSaver = TBoxScreenMarginsSaver
                ) {
                    mutableStateOf(TBoxScreenMargins.NONE)
                }
                var dimDisplayEnabled by rememberSaveable {
                    mutableStateOf(PhoneDisplayDimPreferences.isEnabled(context))
                }
                val projectionManager = context.getSystemService(
                    MediaProjectionManager::class.java
                )
                val motorcyclePhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    val profileId = photoTargetProfileId
                    photoTargetProfileId = null
                    val profile = state.motorcycles.firstOrNull { it.id == profileId }
                    if (uri == null || profile == null) return@rememberLauncherForActivityResult
                    motorcyclePhotoStore.copyFromUri(profile.id, uri)
                        .onSuccess { photoPath ->
                            if (viewModel.updateMotorcycle(profile.copy(photoPath = photoPath))) {
                                motorcyclePhotoStore.delete(profile.photoPath)
                            } else {
                                motorcyclePhotoStore.delete(photoPath)
                            }
                            ProjectionEventLog.record("GARAGE", "Photo updated for motorcycle ${profile.ssid}.")
                        }
                        .onFailure {
                            ProjectionEventLog.error("GARAGE", "Unable to store the selected motorcycle photo.", it)
                            Toast.makeText(context, motoHubText("Unable to save the motorcycle photo"), Toast.LENGTH_SHORT).show()
                        }
                }
                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "Screen capture consent returned resultCode=${result.resultCode}, hasData=${result.data != null}."
                    )
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        ProjectionSessionService.start(context, result.resultCode, result.data!!)
                        viewModel.onProjectionRequested()
                    } else {
                        viewModel.onProjectionCancelled()
                    }
                }
                val externalDisplayProjectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "External display screen capture consent returned resultCode=${result.resultCode}, hasData=${result.data != null}."
                    )
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        AoaExternalService.start(context, result.resultCode, result.data!!)
                    }
                }
                val aoaAccessoryConnected by AoaAccessoryRuntime.connected.collectAsStateWithLifecycle()
                DisposableEffect(Unit) {
                    // ACTION_USB_ACCESSORY_ATTACHED is only ever resolved to an activity
                    // launch/onNewIntent by the system, never sent as a broadcast - handled in
                    // onCreate/onNewIntent below. DETACHED, unlike ATTACHED, is a real broadcast.
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(receiverContext: Context, intent: Intent) {
                            AoaAccessoryRuntime.publish(AoaExternalService.isAccessoryConnected(context))
                        }
                    }
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose { context.unregisterReceiver(receiver) }
                }
                var projectionPermissionPending by rememberSaveable { mutableStateOf(false) }
                var androidAutoPermissionPending by rememberSaveable { mutableStateOf(false) }
                var showOfficialCfmotoWarning by rememberSaveable { mutableStateOf(false) }
                var externalDisplayPermissionPending by rememberSaveable { mutableStateOf(false) }
                var microphonePermissionAction by rememberSaveable { mutableStateOf<String?>(null) }
                val microphonePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val action = microphonePermissionAction
                    microphonePermissionAction = null
                    ProjectionEventLog.record("PERMISSION", "Microphone permission result: granted=$granted.")
                    if (granted) {
                        when (action) {
                            "full" -> startAndroidAuto()
                        }
                    }
                }
                val requestMicAndStart: (String) -> Unit = { action ->
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startAndroidAuto()
                    } else {
                        microphonePermissionAction = action
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
               val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    ProjectionEventLog.record("PERMISSION", "Notification permission result: granted=$granted.")
                    if (projectionPermissionPending) {
                        projectionPermissionPending = false
                        if (granted) {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            viewModel.onNotificationPermissionDenied()
                        }
                    } else if (androidAutoPermissionPending) {
                        androidAutoPermissionPending = false
                       if (granted) {
                            requestMicAndStart("full")
                       } else {
                            viewModel.onNotificationPermissionDenied()
                        }
                    } else if (externalDisplayPermissionPending) {
                        externalDisplayPermissionPending = false
                        if (granted) {
                            externalDisplayProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            viewModel.onNotificationPermissionDenied()
                        }
                    }
                }
                val continueAndroidAutoStart: () -> Unit = {
                    val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    if (notificationGranted) {
                        requestMicAndStart("full")
                    } else {
                        androidAutoPermissionPending = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                // Hoisted out of the home screen's callbacks so autostart can run the exact same
                // sequence a tap does - permission checks included - instead of a second, subtly
                // different copy of it.
                val startMirroring: () -> Unit = {
                    val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    if (notificationGranted) {
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    } else {
                        projectionPermissionPending = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val startAndroidAutoWithWarning: () -> Unit = {
                    if (OfficialCfmotoClient.isInstalled(context) &&
                        !MotoHubSettings.motoPlayWarningSuppressed(context)
                    ) {
                        ProjectionEventLog.record(
                            "ANDROID_AUTO",
                            "Official CFMOTO app is installed; showing MotoPlay conflict warning before launch."
                        )
                        showOfficialCfmotoWarning = true
                    } else {
                        continueAndroidAutoStart()
                    }
                }
                val wifiPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "Wi-Fi permission results: ${grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}."
                    )
                    if (grants.values.all { it }) {
                        connectWhenAndroidAccepts("after the Wi-Fi permission grant")
                    } else {
                        viewModel.onNearbyWifiPermissionDenied()
                    }
                }
                fun reconnectAfterModeStop(mode: String) {
                    if (!MotoHubSettings.autoConnect(context)) {
                        ProjectionEventLog.debug(
                            "AUTO_CONNECT",
                            "Auto-connect after $mode stop skipped because the setting is disabled."
                        )
                        return
                    }
                    updateScope.launch {
                        delay(AUTO_CONNECT_AFTER_STOP_DELAY_MS)
                        if (!MotoHubSettings.autoConnect(context)) return@launch
                        var waitAttempts = 0
                        while (
                            waitAttempts < AUTO_CONNECT_AFTER_STOP_MAX_ATTEMPTS &&
                            (ProjectionRuntime.isActive() ||
                                AndroidAutoRuntime.isActive() ||
                                viewModel.uiState.value.session.phase != SessionPhase.NETWORK_SETUP_REQUIRED &&
                                viewModel.uiState.value.session.phase != SessionPhase.ERROR)
                        ) {
                            delay(AUTO_CONNECT_AFTER_STOP_POLL_MS)
                            waitAttempts++
                        }
                        if (viewModel.uiState.value.session.motorcycle == null) {
                            ProjectionEventLog.debug(
                                "AUTO_CONNECT",
                                "Auto-connect after $mode stop skipped because no motorcycle is selected."
                            )
                            return@launch
                        }
                        ProjectionEventLog.record(
                            "AUTO_CONNECT",
                            "Reconnecting automatically after $mode stop."
                        )
                        val permissions =
                            tboxConnectPermissions(viewModel.uiState.value.session.motorcycle)
                        if (permissions.all { permission ->
                                ContextCompat.checkSelfPermission(context, permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            connectWhenAndroidAccepts("after the $mode stop")
                        } else {
                            wifiPermissionLauncher.launch(permissions)
                        }
                    }
                }
                val unknownSourcesLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    unknownSourcesAllowed = GithubUpdateInstaller.canInstallUnknownSources(context)
                }
                LaunchedEffect(showSafetyDisclaimer) {
                    if (showSafetyDisclaimer) return@LaunchedEffect
                    if (updateAutoCheckAttempted) return@LaunchedEffect
                    updateAutoCheckAttempted = true
                    if (!MotoHubSettings.autoUpdateChecks(context)) {
                        ProjectionEventLog.debug("UPDATES", "Automatic GitHub update checks are disabled in General settings.")
                        return@LaunchedEffect
                    }
                    delay(AUTO_UPDATE_CHECK_DELAY_MS)
                    checkForUpdates(openDialog = false)
                }
                suspend fun attemptAutoConnect() {
                    if (!MotoHubSettings.autoConnect(context)) {
                        ProjectionEventLog.debug("AUTO_CONNECT", "Auto-connect on launch is disabled.")
                        return
                    }
                    val profile = state.session.motorcycle
                    val phase = state.session.phase
                    if (profile == null ||
                        (phase != SessionPhase.NETWORK_SETUP_REQUIRED && phase != SessionPhase.ERROR)
                    ) {
                        ProjectionEventLog.debug(
                            "AUTO_CONNECT",
                            "Auto-connect skipped; profilePresent=${profile != null}, phase=$phase."
                        )
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAutoConnectAttemptAt < AUTO_CONNECT_RETRY_COOLDOWN_MS) return
                    lastAutoConnectAttemptAt = now
                    delay(AUTO_CONNECT_START_DELAY_MS)
                    ProjectionEventLog.record(
                        "AUTO_CONNECT",
                        "Launching automatic connection to saved motorcycle ${profile.ssid}."
                    )
                    val permissions = tboxConnectPermissions(profile)
                    if (permissions.all { permission ->
                            ContextCompat.checkSelfPermission(context, permission) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                    ) {
                        connectWhenAndroidAccepts("auto-connect")
                    } else {
                        wifiPermissionLauncher.launch(permissions)
                    }
                }
                // Retries on every resume (not just app launch): the motorcycle's Wi-Fi AP
                // may not be in range yet the first time MOTO-HUB opens - e.g. the app is
                // opened before the bike is powered on. attemptAutoConnect()'s own phase
                // check already skips this once a connection is established or in progress.
                val autoConnectScope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            autoConnectScope.launch { attemptAutoConnect() }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                // ── Autostart on connect ────────────────────────────────────────────────────
                //
                // Fires at most once per app launch, the first time a T-Box link comes up (phase
                // READY - the "what should I show?" screen). One-shot on purpose: stopping a mode
                // reconnects by itself when auto-connect is on, and re-arming there would restart
                // the very screen the rider just stopped, leaving no way back to the picker.
                var autostartArmed by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(state.session.phase) {
                    if (state.session.phase != SessionPhase.READY) return@LaunchedEffect
                    if (!autostartArmed) return@LaunchedEffect
                    if (!MotoHubSettings.autostartEnabled(context)) return@LaunchedEffect
                    val service = MotoHubSettings.autostartService(context)
                    autostartArmed = false
                    if (service.advancedOnly) {
                        ProjectionEventLog.warning(
                            "AUTOSTART",
                            "${service.label} is configured but this edition cannot run it; nothing started."
                        )
                        return@LaunchedEffect
                    }
                    ProjectionEventLog.record(
                        "AUTOSTART",
                        "T-Box link is up; starting ${service.label} automatically."
                    )
                    // Let the mode screen settle before a system consent dialog lands on top of it.
                    delay(AUTOSTART_ON_CONNECT_DELAY_MS)
                    when (service) {
                        AutostartService.MIRRORING -> startMirroring()
                        AutostartService.ANDROID_AUTO -> startAndroidAutoWithWarning()
                        AutostartService.RIDE_DASHBOARD -> Unit
                    }
                }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    val granted = PhoneDisplayDimmer.canDim(context)
                    ProjectionEventLog.record("PERMISSION", "Display overlay permission result: granted=$granted.")
                    PhoneDisplayDimPreferences.setEnabled(context, granted)
                    dimDisplayEnabled = granted
                    if (granted && state.session.phase == SessionPhase.CAPTURING) {
                        ProjectionSessionService.dimDisplay(context)
                    }
                }
                val seamlessResumePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    val granted = Settings.canDrawOverlays(context)
                    if (granted && seamlessResumePermissionPending) {
                        MotoHubSettings.setSeamlessResume(context, true)
                        seamlessResumeEnabled = true
                    } else if (!granted) {
                        MotoHubSettings.setSeamlessResume(context, false)
                        seamlessResumeEnabled = false
                    }
                    seamlessResumePermissionPending = false
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "Seamless resume overlay permission result: granted=$granted, " +
                            "enabled=$seamlessResumeEnabled."
                    )
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    ProjectionEventLog.record("PERMISSION", "Camera permission result: granted=$granted.")
                    if (granted) {
                        showQrScanner = true
                    } else {
                        if (returnToGarageAfterPairing) {
                            returnToGarageAfterPairing = false
                            selectedTab = HubTab.GARAGE
                        }
                        viewModel.onCameraPermissionDenied()
                    }
                }
                val qrPhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri == null) {
                        ProjectionEventLog.debug("PAIRING", "QR photo picker closed without a selection.")
                        return@rememberLauncherForActivityResult
                    }
                    ProjectionEventLog.record("PAIRING", "QR photo selected; starting ML Kit decoding.")

                    qrPhotoProcessing = true
                    qrPhotoProgress = 0 to 0
                    TBoxQrPhotoDecoder.scan(
                        context = context,
                        uri = uri,
                        onProgress = { attempt, total -> qrPhotoProgress = attempt to total }
                    ) { result ->
                        qrPhotoProcessing = false
                        result
                            .onSuccess(::acceptQrPayload)
                            .onFailure { failure ->
                                ProjectionEventLog.debug(
                                    "PAIRING",
                                    "QR photo decoding failed after preprocessing attempts: ${failure.message}"
                                )
                                viewModel.onQrImportFailed(
                                    "No QR code with motorcycle Wi-Fi details could be read from the photo."
                                )
                            }
                    }
                }

                if (showApplicationLogs) {
                    ApplicationLogScreen(
                        events = projectionEvents,
                        onCopy = {
                            val text = ProjectionEventLog.exportText()
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText(motoHubText("MOTO-HUB diagnostics"), text)
                            )
                            ProjectionEventLog.record("LOG", "Diagnostic log copied to the clipboard.")
                            Toast.makeText(context, motoHubText("Log copied to clipboard"), Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val text = ProjectionEventLog.exportText()
                            val shareIntent = runCatching {
                                DiagnosticLogShare.createShareIntent(context, text)
                            }.onFailure { failure ->
                                ProjectionEventLog.error("LOG", "Diagnostic log file share failed.", failure)
                            Toast.makeText(context, motoHubText("Unable to create log file"), Toast.LENGTH_SHORT).show()
                            }.getOrNull()
                            if (shareIntent != null) {
                                ProjectionEventLog.record("LOG", "Diagnostic log file share sheet opened.")
                                context.startActivity(Intent.createChooser(shareIntent, "Share MOTO-HUB log"))
                            }
                        },
                        onClear = ProjectionEventLog::clear,
                        onBack = {
                            ProjectionEventLog.record("UI", "Application log screen closed.")
                            showApplicationLogs = false
                        }
                    )
                } else if (showAndroidAutoHelp) {
                    AndroidAutoHelpScreen(
                        onBack = {
                            ProjectionEventLog.record("UI", "Android Auto help screen closed.")
                            showAndroidAutoHelp = false
                        }
                    )
                } else if (showAbout) {
                    AboutScreen(
                        onOpenGithub = {
                            ProjectionEventLog.record("UI", "GitHub repository link opened.")
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(MOTO_HUB_GITHUB_URL))
                                )
                            }.onFailure {
                                ProjectionEventLog.error("UI", "Unable to open the GitHub repository.", it)
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open GitHub"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onOpenDiscord = {
                            ProjectionEventLog.record("UI", "Discord community link opened.")
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(MOTO_HUB_DISCORD_URL))
                                )
                            }.onFailure {
                                ProjectionEventLog.error("UI", "Unable to open the Discord link.", it)
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open Discord"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onCheckUpdates = {
                            ProjectionEventLog.record("UPDATES", "Manual GitHub update check requested.")
                            checkForUpdates(openDialog = true)
                        },
                        onBack = {
                            ProjectionEventLog.record("UI", "About screen closed.")
                            showAbout = false
                        }
                    )
                } else if (showAndroidAutoPreview) {
                    AndroidAutoPreviewScreen(
                        onBack = {
                            ProjectionEventLog.record("UI", "Android Auto phone preview closed.")
                            if (androidAutoPreviewIsPhoneOnly) {
                                androidAutoPhoneOnlyBridge.stop()
                            }
                            showAndroidAutoPreview = false
                            androidAutoPreviewIsPhoneOnly = false
                            if (androidAutoPhoneOnlyLaunchedFromPro) {
                                androidAutoPhoneOnlyLaunchedFromPro = false
                                finish()
                            }
                        }
                    )
                } else if (capabilityProfileId != null) {
                    val profile = state.motorcycles.firstOrNull { it.id == capabilityProfileId }
                    if (profile == null) {
                        capabilityProfileId = null
                        selectedTab = HubTab.GARAGE
                    } else {
                        TBoxCapabilityScreen(
                            profile = profile,
                            snapshot = capabilityStore.load(profile),
                            geometry = displayGeometryStore.load(profile.ssid),
                            portScanInProgress = portScanInProgress,
                            portScanResult = portScanResult,
                            onScanPorts = { scanTBoxPorts(profile) },
                            onBack = {
                                capabilityProfileId = null
                                editorProfileId = profile.id
                                portScanResult = null
                            }
                        )
                    }
                } else if (editorProfileId != null) {
                    val profile = state.motorcycles.firstOrNull { it.id == editorProfileId }
                    if (profile == null) {
                        editorProfileId = null
                        selectedTab = HubTab.GARAGE
                    } else {
                        MotorcycleDetailsScreen(
                           profile = profile,
                           displayMode = motorcycleDetailsDisplayMode,
                            screenMargins = motorcycleDetailsScreenMargins,
                           onBack = {
                                editorProfileId = null
                                selectedTab = HubTab.GARAGE
                            },
                            onSave = { updatedProfile -> viewModel.updateMotorcycle(updatedProfile) },
                            onOpenCapabilities = {
                                capabilityProfileId = profile.id
                                editorProfileId = null
                                ProjectionEventLog.record(
                                    "UI",
                                    "T-Box capability inspector opened for ${profile.ssid}."
                                )
                            },
                            onCustomizeDashboard = {},
                            onDisplayModeChanged = { mode ->
                                displayModeStore.save(profile, mode)
                                motorcycleDetailsDisplayMode = mode
                               ProjectionEventLog.record(
                                   "ANDROID_AUTO",
                                   "TFT display mode changed for ${profile.ssid}: $mode."
                               )
                           },
                            onScreenMarginsChanged = { margins ->
                                motorcycleDetailsScreenMargins = margins
                                screenMarginsStore.save(profile, margins)
                                ProjectionEventLog.record("ANDROID_AUTO", "TFT screen margins changed for ${profile.ssid}: $margins.")
                            },
                           onChoosePhoto = {
                                photoTargetProfileId = profile.id
                                motorcyclePhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemovePhoto = {
                                val oldPath = profile.photoPath
                                if (viewModel.updateMotorcycle(profile.copy(photoPath = null))) {
                                    motorcyclePhotoStore.delete(oldPath)
                                    ProjectionEventLog.record("GARAGE", "Photo removed for motorcycle ${profile.ssid}.")
                                }
                            },
                            onDelete = {
                                motorcyclePhotoStore.delete(profile.photoPath)
                                viewModel.deleteMotorcycle(profile.id)
                                editorProfileId = null
                                selectedTab = HubTab.GARAGE
                            }
                        )
                    }
                } else if (showNetworkDiagnostics) {
                    NetworkDiagnosticsScreen(
                        state = diagnosticsState,
                        projectionEvents = projectionEvents,
                        onRunTests = diagnosticsViewModel::runTests,
                        onBack = {
                            ProjectionEventLog.record("UI", "Network diagnostics screen closed.")
                            showNetworkDiagnostics = false
                        }
                    )
                } else if (showQrScanner) {
                    TBoxQrScannerScreen(
                        onPayload = { payload ->
                            acceptQrPayload(payload)
                            ProjectionEventLog.record("UI", "QR scanner closed after a valid code.")
                            showQrScanner = false
                            if (returnToGarageAfterPairing) {
                                returnToGarageAfterPairing = false
                                selectedTab = HubTab.GARAGE
                            }
                        },
                        onManualPairing = {
                            ProjectionEventLog.record(
                                "UI",
                                "QR scanner handed over to manual pairing."
                            )
                            showQrScanner = false
                            viewModel.resetManualPairingForm()
                            showManualPairing = true
                        },
                        onClose = {
                            ProjectionEventLog.record("UI", "QR scanner cancelled by the user.")
                            showQrScanner = false
                            if (returnToGarageAfterPairing) {
                                returnToGarageAfterPairing = false
                                selectedTab = HubTab.GARAGE
                            }
                        }
                    )
                } else if (showManualPairing) {
                    ManualPairingScreen(
                        ssid = state.ssid,
                        password = state.password,
                        connectionMode = state.connectionMode,
                        formError = state.formError,
                        onSsidChanged = viewModel::onSsidChanged,
                        onPasswordChanged = viewModel::onPasswordChanged,
                        onConnectionModeChanged = viewModel::onConnectionModeChanged,
                        onSave = {
                            if (viewModel.saveMotorcycle()) {
                                ProjectionEventLog.record("UI", "Manual pairing screen closed after a saved profile.")
                                showManualPairing = false
                                if (returnToGarageAfterPairing) {
                                    returnToGarageAfterPairing = false
                                    selectedTab = HubTab.GARAGE
                                }
                            }
                        },
                        onClose = {
                            ProjectionEventLog.record("UI", "Manual pairing screen cancelled by the user.")
                            showManualPairing = false
                            if (returnToGarageAfterPairing) {
                                returnToGarageAfterPairing = false
                                selectedTab = HubTab.GARAGE
                            }
                        }
                    )
                } else {
                    HubHomeScreen(
                        state = state,
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        onScanQr = {
                            ProjectionEventLog.record("UI", "User requested live QR scanning.")
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                showQrScanner = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onImportQrPhoto = {
                            ProjectionEventLog.record("UI", "User requested QR decoding from a photo.")
                            qrPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onManualPairing = {
                            ProjectionEventLog.record("UI", "User requested manual (no-QR) pairing.")
                            viewModel.resetManualPairingForm()
                            showManualPairing = true
                        },
                        onTryPhoneHotspot = {
                            // Same screen as manual pairing, but pre-filled instead of reset: the
                            // rider is changing one setting on a motorcycle they already entered,
                            // not adding a new one.
                            viewModel.preparePhoneHotspotRetry()
                            showManualPairing = true
                        },
                        onConnectAndDiscover = {
                            val permissions =
                                tboxConnectPermissions(viewModel.uiState.value.session.motorcycle)
                            if (permissions.all { permission ->
                                    ContextCompat.checkSelfPermission(context, permission) ==
                                        PackageManager.PERMISSION_GRANTED
                                }
                            ) {
                                connectWhenAndroidAccepts("Connect button")
                            } else {
                                wifiPermissionLauncher.launch(permissions)
                            }
                        },
                        officialCfmotoAppInstalled = OfficialCfmotoClient.isInstalled(context),
                        onCloseOfficialCfmotoAndRetry = {
                            // Android 14+ cannot close another app's process; this action is a
                            // plain retry for after the user has force-stopped the official app.
                            ProjectionEventLog.record(
                                "CONNECTION",
                                "Retry requested from the official-app conflict help."
                            )
                            lifecycleScope.launch {
                                delay(OFFICIAL_APP_CLOSE_RETRY_DELAY_MS)
                                // The rider was sent to another app's settings to force-stop it,
                                // so this retry often lands with MOTO-HUB still in the background.
                                connectWhenAndroidAccepts("official-app conflict retry")
                            }
                        },
                        onOpenOfficialCfmotoSettings = {
                            if (!OfficialCfmotoClient.openAppSettings(context)) {
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open official CFMOTO app settings"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onOpenAndroidAutoSettings = {
                            // The guide, not Android Auto itself: dropping the rider into another
                            // app without telling them which hidden menu to open is what made the
                            // first tester hunt for a setting that is not in the settings list.
                            ProjectionEventLog.record("UI", "Android Auto help screen opened.")
                            showAndroidAutoHelp = true
                        },
                        onOpenWifiSettings = {
                            if (!WifiGate.openWifiSettings(context)) {
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open Wi-Fi settings"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onCancelConnection = viewModel::cancelConnection,
                        onDisconnect = viewModel::disconnect,
                        onStartProjection = {
                            ProjectionEventLog.record("MIRROR", "User selected mirroring mode.")
                            startMirroring()
                        },
                        androidAutoActive = androidAutoActive,
                        androidAutoStreaming = androidAutoStreaming,
                        onStartAndroidAuto = startAndroidAutoWithWarning,
                        onStopAndroidAuto = {
                            ProjectionEventLog.record("ANDROID_AUTO", "User requested Android Auto stop.")
                            AndroidAutoSessionService.stop(context)
                            reconnectAfterModeStop("Android Auto")
                        },
                        onOpenAndroidAutoPreview = {
                            ProjectionEventLog.record("UI", "Android Auto phone preview opened.")
                            showAndroidAutoPreview = true
                        },
                        dimDisplayEnabled = dimDisplayEnabled,
                        onDimDisplayChanged = { enabled ->
                            ProjectionEventLog.record("DISPLAY", "User changed display dimmer preference to enabled=$enabled.")
                            if (!enabled) {
                                PhoneDisplayDimPreferences.setEnabled(context, false)
                                dimDisplayEnabled = false
                                if (state.session.phase == SessionPhase.CAPTURING) {
                                    ProjectionSessionService.restoreDisplay(context)
                                }
                            } else if (PhoneDisplayDimmer.canDim(context)) {
                                PhoneDisplayDimPreferences.setEnabled(context, true)
                                dimDisplayEnabled = true
                                if (state.session.phase == SessionPhase.CAPTURING) {
                                    ProjectionSessionService.dimDisplay(context)
                                }
                            } else {
                                overlayPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        onStopProjection = {
                            ProjectionEventLog.record("MIRROR", "User requested mirroring stop.")
                            ProjectionSessionService.stop(context)
                            reconnectAfterModeStop("mirroring")
                        },
                        // ── External display (USB AOA) ──
                        aoaAccessoryConnected = aoaAccessoryConnected,
                        externalDisplayActive = externalDisplayActive,
                        externalDisplayStreaming = externalDisplayStreaming,
                        onStartExternalDisplay = {
                            ProjectionEventLog.record("EXTERNAL", "User selected external display mode.")
                            val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            if (notificationGranted) {
                                externalDisplayProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            } else {
                                externalDisplayPermissionPending = true
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onStopExternalDisplay = {
                            ProjectionEventLog.record("EXTERNAL", "User requested external display stop.")
                            AoaExternalService.stop(context)
                        },
                        garageContent = {
                            GarageTabContent(
                                profiles = state.motorcycles,
                                activeProfileId = state.session.motorcycle?.id,
                                onAddMotorcycle = {
                                    returnToGarageAfterPairing = true
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        showQrScanner = true
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                onAddMotorcycleManually = {
                                    ProjectionEventLog.record("UI", "User requested manual (no-QR) pairing from the Garage.")
                                    returnToGarageAfterPairing = true
                                    viewModel.resetManualPairingForm()
                                    showManualPairing = true
                                },
                                onSelectMotorcycle = { profileId ->
                                    viewModel.selectMotorcycle(profileId)
                                    selectedTab = HubTab.RIDE
                                },
                                onOpenDetails = { profileId ->
                                    val profile = state.motorcycles.firstOrNull { it.id == profileId }
                                    if (profile != null) {
                                       motorcycleDetailsDisplayMode = displayModeStore.load(profile)
                                        motorcycleDetailsScreenMargins = screenMarginsStore.load(
                                            profile,
                                            TBoxModelProfile.fromModelId(profile.modelId).defaultScreenMargins
                                        )
                                       editorProfileId = profileId
                                    }
                                }
                            )
                        },
                        settingsContent = {
                            SettingsTabContent(
                                onOpenNetworkDiagnostics = {
                                    ProjectionEventLog.record("UI", "Network diagnostics screen opened.")
                                    showNetworkDiagnostics = true
                                },
                                onOpenApplicationLogs = {
                                    ProjectionEventLog.record("UI", "Application log screen opened.")
                                    showApplicationLogs = true
                                },
                                onOpenAndroidAutoHelp = {
                                    ProjectionEventLog.record("UI", "Android Auto help screen opened.")
                                    showAndroidAutoHelp = true
                                },
                                seamlessResumeEnabled = seamlessResumeEnabled,
                                onSeamlessResumeChanged = { enabled ->
                                    if (!enabled) {
                                        MotoHubSettings.setSeamlessResume(context, false)
                                        seamlessResumeEnabled = false
                                    } else if (Settings.canDrawOverlays(context)) {
                                        MotoHubSettings.setSeamlessResume(context, true)
                                        seamlessResumeEnabled = true
                                    } else {
                                        seamlessResumePermissionPending = true
                                        ProjectionEventLog.record(
                                            "PERMISSION",
                                            "Opening overlay permission for seamless resume."
                                        )
                                        seamlessResumePermissionLauncher.launch(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
                if (showUpdateDialog) {
                    GithubUpdateDialog(
                        releases = updateReleases,
                        isLoading = updateLoading,
                        error = updateError,
                        installingTag = installingUpdateTag,
                        installingProgress = installingUpdateProgress,
                        canInstallUnknownSources = unknownSourcesAllowed,
                        onDismiss = { showUpdateDialog = false },
                        onRetry = { checkForUpdates(openDialog = true) },
                        onSkip = { release ->
                            MotoHubSettings.setSkippedUpdateTag(context, release.tagName)
                            updateReleases = updateReleases.filterNot { it.tagName == release.tagName }
                            ProjectionEventLog.record("UPDATES", "Skipped release ${release.versionName}.")
                            if (updateReleases.isEmpty()) showUpdateDialog = false
                        },
                        onAllowUnknownSources = {
                            unknownSourcesLauncher.launch(
                                GithubUpdateInstaller.unknownSourcesSettingsIntent(context)
                            )
                        },
                        onInstall = { release ->
                            installingUpdateTag = release.tagName
                            installingUpdateProgress = null
                            updateError = null
                            updateScope.launch {
                                GithubUpdateInstaller.downloadAndInstall(
                                    context,
                                    release,
                                    onProgress = { progress -> installingUpdateProgress = progress }
                                ).onFailure { failure ->
                                    updateError = "Unable to install ${release.versionName}: " +
                                        (failure.message ?: "unknown error")
                                    ProjectionEventLog.error("UPDATES", updateError.orEmpty(), failure)
                                }
                                installingUpdateTag = null
                                installingUpdateProgress = null
                            }
                        }
                    )
                }
                if (qrPhotoProcessing) {
                    TBoxQrPhotoProcessingDialog(
                        completedAttempts = qrPhotoProgress.first,
                        totalAttempts = qrPhotoProgress.second
                    )
                }
                pendingUnverifiedQr?.let { payload ->
                    UnverifiedQrDialog(
                        payload = payload,
                        onConfirm = {
                            pendingUnverifiedQr = null
                            ProjectionEventLog.record(
                                "PAIRING",
                                "Rider confirmed the unrecognised pairing code for ssid=${payload.ssid}."
                            )
                            viewModel.applyQrPairing(payload)
                        },
                        onDismiss = {
                            pendingUnverifiedQr = null
                            ProjectionEventLog.record(
                                "PAIRING",
                                "Rider declined the unrecognised pairing code for ssid=${payload.ssid}."
                            )
                        }
                    )
                }
                if (showOfficialCfmotoWarning) {
                    var doNotShowMotoPlayWarningAgain by rememberSaveable { mutableStateOf(false) }
                    OfficialCfmotoWarningDialog(
                        doNotShowAgain = doNotShowMotoPlayWarningAgain,
                        onDoNotShowAgainChanged = { doNotShowMotoPlayWarningAgain = it },
                        onDismiss = {
                            if (doNotShowMotoPlayWarningAgain) {
                                MotoHubSettings.setMotoPlayWarningSuppressed(context, true)
                            }
                            showOfficialCfmotoWarning = false
                        },
                        onOpenOfficialAppSettings = {
                            if (!OfficialCfmotoClient.openAppSettings(context)) {
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open official CFMOTO app settings"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onContinue = {
                            if (doNotShowMotoPlayWarningAgain) {
                                MotoHubSettings.setMotoPlayWarningSuppressed(context, true)
                            }
                            showOfficialCfmotoWarning = false
                            ProjectionEventLog.record(
                                "ANDROID_AUTO",
                                "User continued Android Auto launch after MotoPlay conflict warning; " +
                                    "doNotShowAgain=$doNotShowMotoPlayWarningAgain."
                            )
                            continueAndroidAutoStart()
                        }
                    )
                }
                if (showSafetyDisclaimer) {
                    var doNotShowAgain by rememberSaveable { mutableStateOf(false) }
                    SafetyDisclaimerDialog(
                        doNotShowAgain = doNotShowAgain,
                        onDoNotShowAgainChanged = { doNotShowAgain = it },
                        onContinue = {
                            if (doNotShowAgain) {
                                MotoHubSettings.setSafetyDisclaimerAcknowledged(context, true)
                            }
                            ProjectionEventLog.record(
                                "SAFETY",
                                "Startup safety disclaimer acknowledged; doNotShowAgain=$doNotShowAgain."
                            )
                            showSafetyDisclaimer = false
                        }
                    )
                }
            }
        }
    }

  private fun startAndroidAuto() {
      if (!androidAutoLaunchPending.compareAndSet(false, true)) {
            ProjectionEventLog.warning("ANDROID_AUTO", "Start request ignored because another launch is pending.")
            return
        }
        ProjectionEventLog.record("ANDROID_AUTO", "User requested Android Auto startup.")
        AndroidAutoSessionService.start(this)
        lifecycleScope.launch {
            val state = withTimeoutOrNull(10_000L) {
                // A foreground service is started asynchronously.  Ignore terminal state left
                // by the previous launch; otherwise `first` can consume that old failure before
                // the new service has published Preparing and no self-mode trigger is sent.
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
            when (state) {
                AndroidAutoRuntimeState.ReceiverReady -> {
                    ProjectionEventLog.record("ANDROID_AUTO", "AAP receiver is ready; waiting before self-mode trigger.")
                    delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                    if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                        AaSelfMode.trigger(
                            context = this@MainActivity,
                            log = { ProjectionEventLog.record("AAP", it) }
                        )
                    }
                }
                is AndroidAutoRuntimeState.Failed -> Unit
                else -> {
                    ProjectionEventLog.error("ANDROID_AUTO", "Timed out while preparing Android Auto.")
                    AndroidAutoSessionService.stop(this@MainActivity)
                }
            }
            androidAutoLaunchPending.set(false)
            ProjectionEventLog.debug("ANDROID_AUTO", "Launch coordinator released.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshAoaAccessoryConnected(intent)
        handleAndroidAutoPreviewLaunchIntent(intent)
    }

    /**
     * ACTION_USB_ACCESSORY_ATTACHED is resolved by the system straight to an activity
     * launch/onNewIntent (see the manifest's accessory_filter meta-data), never a broadcast -
     * this is the only reliable point to learn a head unit just attached. Also called from
     * onCreate to cover the cold-start case where the accessory launched MOTO-HUB directly.
     */
    private fun refreshAoaAccessoryConnected(intent: Intent?) {
        val viaAttachIntent = intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        AoaAccessoryRuntime.publish(
            viaAttachIntent || AoaExternalService.isAccessoryConnected(this)
        )
    }

    override fun onDestroy() {
        ProjectionEventLog.record("UI", "Main activity destroyed. changingConfigurations=$isChangingConfigurations")
        if (!isChangingConfigurations) {
            androidAutoPhoneOnlyBridge.stop()
        }
        super.onDestroy()
    }

    /** Handles the phone-only Android Auto deep-link sent by PRO. */
    private fun handleAndroidAutoPreviewLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(IpcBridgeContract.EXTRA_START_PHONE_ONLY_ANDROID_AUTO, false) != true) return
        PhoneOnlyAndroidAutoLaunchRequest.publish(
            intent.getStringExtra(IpcBridgeContract.EXTRA_ANDROID_AUTO_DISPLAY_MODE)
        )
    }

    override fun onStart() {
        super.onStart()
        ProjectionEventLog.debug("UI", "Main activity started.")
    }

    override fun onResume() {
        super.onResume()
        ProjectionEventLog.debug("UI", "Main activity resumed.")
    }

    override fun onPause() {
        ProjectionEventLog.debug("UI", "Main activity paused.")
        super.onPause()
    }

    override fun onStop() {
        ProjectionEventLog.debug("UI", "Main activity stopped.")
        super.onStop()
    }

    private companion object {
        const val ANDROID_AUTO_RECEIVER_SETTLE_MS = 900L
       const val AUTO_CONNECT_START_DELAY_MS = 600L
        const val AUTO_CONNECT_RETRY_COOLDOWN_MS = 5_000L
        const val AUTO_CONNECT_AFTER_STOP_DELAY_MS = 900L
        const val AUTO_CONNECT_AFTER_STOP_POLL_MS = 200L
        const val AUTO_CONNECT_AFTER_STOP_MAX_ATTEMPTS = 25
        const val AUTOSTART_ON_CONNECT_DELAY_MS = 800L
        const val OFFICIAL_APP_CLOSE_RETRY_DELAY_MS = 1_500L
        const val FOREGROUND_SETTLE_TIMEOUT_MS = 1_000L
        const val FOREGROUND_SETTLE_POLL_MS = 50L
        const val AUTO_UPDATE_CHECK_DELAY_MS = 1_200L
        const val AUTO_UPDATE_CHECK_THROTTLE_MS = 24 * 60 * 60 * 1_000L
    }
}

/**
 * Runtime permissions a connect to [profile] needs before it can start: the Wi-Fi join set
 * always, plus the Bluetooth pair for a ThinkerRide (KOVE) motorcycle — asked together so the
 * rider sees one permission sheet, not one per radio.
 */
private fun tboxConnectPermissions(profile: MotorcycleProfile?): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    if (ThinkerRideGate.requiresBle(profile)) {
        permissions += ThinkerRideGate.blePermissions
    }
    return permissions.toTypedArray()
}
