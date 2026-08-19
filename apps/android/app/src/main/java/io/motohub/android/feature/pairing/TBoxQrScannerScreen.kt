package io.motohub.android.feature.pairing

import io.motohub.android.i18n.motoHubText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import androidx.activity.compose.BackHandler
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun TBoxQrScannerScreen(
    onPayload: (TBoxQrPayload) -> Unit,
    onManualPairing: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    var scanStatus by remember { mutableStateOf("Frame the EasyConn QR code shown on the TFT") }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    fun setZoom(requestedRatio: Float) {
        val value = requestedRatio.coerceIn(minZoomRatio, maxZoomRatio)
        zoomRatio = value
        camera?.cameraControl?.setZoomRatio(value)
    }

    DisposableEffect(cameraProviderFuture, scanner, analyzerExecutor) {
        onDispose {
            analyzerExecutor.shutdown()
            scanner.close()
            cameraProviderFuture.addListener({
                runCatching { cameraProviderFuture.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).also { view ->
                    previewView = view
                    view.apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    cameraProviderFuture.addListener({
                        val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull()
                            ?: return@addListener
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(
                                    analyzerExecutor,
                                    TBoxQrAnalyzer(
                                        scanner = scanner,
                                        onPayload = onPayload,
                                        onStatus = { scanStatus = it }
                                    )
                                )
                            }
                        runCatching {
                            cameraProvider.unbindAll()
                            val boundCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                            camera = boundCamera
                            boundCamera.cameraInfo.zoomState.value?.let { zoomState ->
                                minZoomRatio = zoomState.minZoomRatio
                                maxZoomRatio = zoomState.maxZoomRatio
                                zoomRatio = zoomState.zoomRatio
                            }
                            torchAvailable = boundCamera.cameraInfo.hasFlashUnit()
                        }
                    }, ContextCompat.getMainExecutor(viewContext))
                }
                }
            }
        )

        // Tap anywhere in the camera image to focus on the QR code. This is especially useful
        // when the code is displayed behind TFT glass or at an angle.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera, previewView) {
                    detectTapGestures { offset ->
                        val activeCamera = camera ?: return@detectTapGestures
                        val activePreview = previewView ?: return@detectTapGestures
                        val point = activePreview.meteringPointFactory.createPoint(offset.x, offset.y)
                        activeCamera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        )
                        scanStatus = "Focus locked. Hold the phone steady..."
                    }
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            MotoHubHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (torchAvailable) {
                            TextButton(
                                onClick = {
                                    val enabled = !torchEnabled
                                    camera?.cameraControl?.enableTorch(enabled)
                                    torchEnabled = enabled
                                }
                            ) {
                                Text(if (torchEnabled) "Flash ON" else "Flash")
                            }
                        }
                        TextButton(onClick = onClose) { Text(motoHubText("Close")) }
                    }
                }
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(268.dp)
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(28.dp)
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    motoHubText("T-BOX SCAN"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    scanStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                // Some dashes print the SSID and passphrase instead of a code, and a code the
                // parser cannot use leaves this screen scanning indefinitely. Without a way out
                // from here the rider has to guess that the home screen offers one.
                TextButton(onClick = onManualPairing) {
                    Text(motoHubText("No QR? Connect manually"))
                }
                if (maxZoomRatio > minZoomRatio + 0.01f) {
                    Text(
                        text = "Zoom ${formatZoom(zoomRatio)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = zoomRatio,
                        onValueChange = ::setZoom,
                        valueRange = minZoomRatio..maxZoomRatio
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ZoomButton("1×", minZoomRatio, zoomRatio, Modifier.weight(1f), ::setZoom)
                        ZoomButton("2×", 2f, zoomRatio, Modifier.weight(1f), ::setZoom)
                        ZoomButton("Max", maxZoomRatio, zoomRatio, Modifier.weight(1f), ::setZoom)
                    }
                }
                Text(
                    text = "Tap the QR code to focus • Use zoom if it is small",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ZoomButton(
    label: String,
    requestedRatio: Float,
    currentRatio: Float,
    modifier: Modifier,
    onZoom: (Float) -> Unit
) {
    Button(
        onClick = { onZoom(requestedRatio) },
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (kotlin.math.abs(currentRatio - requestedRatio) < 0.08f) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (kotlin.math.abs(currentRatio - requestedRatio) < 0.08f) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        Text(label)
    }
}

private fun formatZoom(value: Float): String =
    if (value >= 10f) "${value.toInt()}×" else "%.1f×".format(java.util.Locale.US, value)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class TBoxQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onPayload: (TBoxQrPayload) -> Unit,
    private val onStatus: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val handled = AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (handled.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                if (handled.get()) return@addOnSuccessListener
                val rawValue = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    ?: return@addOnSuccessListener
                onStatus("QR code detected. Checking T-Box details...")
                val payload = TBoxQrParser.parse(rawValue).getOrElse { failure ->
                    // The parser names what it actually read (vehicle-info code, a bare web
                    // address, the wrong Moto Morini screen), so its own words beat a generic
                    // "unrecognized" that leaves the rider polishing the display.
                    onStatus(
                        failure.message?.takeIf(String::isNotBlank)
                            ?: "Unrecognized QR code."
                    )
                    return@addOnSuccessListener
                }
                if (payload.origin == TBoxQrOrigin.UNVERIFIED) {
                    onStatus("Network details read from an unfamiliar code. Confirm to continue.")
                }
                if (handled.compareAndSet(false, true)) onPayload(payload)
            }
            .addOnFailureListener {
                if (!handled.get()) {
                    onStatus("Scan failed. Hold the phone steady and try again.")
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
