package com.phynex.NexLink.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.phynex.NexLink.model.DeviceInfo
import com.phynex.NexLink.ui.theme.*
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * QR Scanner screen — cloud-only architecture.
 *
 * Expected QR payload (JSON):
 * {
 *   "userId":     "firebase-uid",
 *   "deviceId":   "uuid-of-pc",
 *   "deviceName": "My Desktop",
 *   "pairId":     "server-pair-id",
 *   "relayUrl":   "https://nexlink-1.onrender.com"   (optional, default used if absent)
 * }
 *
 * The old ip/port/token fields are ignored.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalGetImage::class)
@Composable
fun QRScannerScreen(onScanned: (DeviceInfo) -> Unit) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var scanned by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "scan_anim")
    val scanLineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_line"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        // Animated gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(inversePrimary.copy(alpha = 0.3f), background),
                        center = Offset(0.5f, 0.3f),
                        radius = 800f
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector    = Icons.Default.Sensors,
                        contentDescription = null,
                        tint           = primary,
                        modifier       = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "NexLink",
                        style        = MaterialTheme.typography.titleLarge,
                        fontWeight   = FontWeight.Black,
                        color        = primary,
                        letterSpacing = (-0.5).sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Main Glass Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .glassCard(RoundedCornerShape(24.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Scan to Connect",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Open NexLink on your PC and scan the QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    when {
                        cameraPermissionState.status.isGranted -> {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(background)
                            ) {
                                val context = LocalContext.current
                                val lifecycleOwner = LocalLifecycleOwner.current

                                AndroidView(
                                    factory = { ctx ->
                                        val previewView = PreviewView(ctx)
                                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                        cameraProviderFuture.addListener({
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.setSurfaceProvider(previewView.surfaceProvider)
                                            }
                                            val options = BarcodeScannerOptions.Builder()
                                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                                .build()
                                            val scanner = BarcodeScanning.getClient(options)
                                            val imageAnalysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()
                                            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                                if (!scanned) {
                                                    val mediaImage = imageProxy.image
                                                    if (mediaImage != null) {
                                                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                                        scanner.process(image)
                                                            .addOnSuccessListener { barcodes ->
                                                                for (barcode in barcodes) {
                                                                    val raw = barcode.rawValue ?: continue
                                                                    try {
                                                                        val json = JSONObject(raw)
                                                                        // Cloud-only QR payload
                                                                        val device = DeviceInfo(
                                                                            userId     = json.getString("userId"),
                                                                            deviceId   = json.getString("deviceId"),
                                                                            deviceName = json.optString("deviceName", "PC"),
                                                                            pairId     = json.optString("pairId", ""),
                                                                            relayUrl   = json.optString("relayUrl", "https://nexlink-1.onrender.com")
                                                                        )
                                                                        scanned = true
                                                                        onScanned(device)
                                                                    } catch (e: Exception) {
                                                                        errorMsg = "Invalid QR code format"
                                                                    }
                                                                }
                                                            }
                                                            .addOnCompleteListener { imageProxy.close() }
                                                    } else { imageProxy.close() }
                                                } else { imageProxy.close() }
                                            }
                                            try {
                                                cameraProvider.unbindAll()
                                                cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                                    preview, imageAnalysis
                                                )
                                            } catch (e: Exception) {
                                                errorMsg = "Camera error: ${e.message}"
                                            }
                                        }, ContextCompat.getMainExecutor(ctx))
                                        previewView
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Scanning line overlay
                                val scanPrimary = primary
                                val scanSecondary = secondary
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawLine(
                                        brush = Brush.horizontalGradient(
                                            listOf(Color.Transparent, scanPrimary, scanSecondary, scanPrimary, Color.Transparent)
                                        ),
                                        start       = Offset(0f, size.height * scanLineY),
                                        end         = Offset(size.width, size.height * scanLineY),
                                        strokeWidth = 3f
                                    )
                                }

                                 // Corner brackets
                                Canvas(modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)) {
                                    val cornerSize  = 40f
                                    val strokeWidth = 4f
                                    val c           = scanPrimary
                                    drawLine(c, Offset(0f, cornerSize), Offset(0f, 0f), strokeWidth)
                                    drawLine(c, Offset(0f, 0f), Offset(cornerSize, 0f), strokeWidth)
                                    drawLine(c, Offset(size.width - cornerSize, 0f), Offset(size.width, 0f), strokeWidth)
                                    drawLine(c, Offset(size.width, 0f), Offset(size.width, cornerSize), strokeWidth)
                                    drawLine(c, Offset(0f, size.height - cornerSize), Offset(0f, size.height), strokeWidth)
                                    drawLine(c, Offset(0f, size.height), Offset(cornerSize, size.height), strokeWidth)
                                    drawLine(c, Offset(size.width - cornerSize, size.height), Offset(size.width, size.height), strokeWidth)
                                    drawLine(c, Offset(size.width, size.height - cornerSize), Offset(size.width, size.height), strokeWidth)
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "Point at the QR code on your PC",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }

                        else -> {
                            Spacer(Modifier.height(40.dp))
                            Icon(
                                Icons.Default.CameraAlt, null,
                                tint     = primary,
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Camera permission required\nto scan QR codes",
                                style     = MaterialTheme.typography.bodyLarge,
                                color     = onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { cameraPermissionState.launchPermissionRequest() },
                                colors  = ButtonDefaults.buttonColors(containerColor = primary),
                                shape   = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Camera, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Grant Camera Access")
                            }
                        }
                    }

                    errorMsg?.let { err ->
                        Spacer(Modifier.height(16.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Info card — cloud connection
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                    Text(
                        "cloud connection",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style    = MaterialTheme.typography.labelMedium,
                        color    = onSurfaceVariant
                    )
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Connection info chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1A2236)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Cloud, null, tint = primary, modifier = Modifier.size(18.dp))
                        Text(
                            "nexlink-1.onrender.com",
                            style  = MaterialTheme.typography.bodySmall,
                            color  = onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Works on any network — no local Wi-Fi required",
                    style     = MaterialTheme.typography.labelSmall,
                    color     = onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
