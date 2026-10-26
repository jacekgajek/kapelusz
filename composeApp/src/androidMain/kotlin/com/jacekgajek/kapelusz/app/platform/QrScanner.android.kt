package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play services' code scanner: its own camera screen, so the app needs no camera permission; its module comes
 * with the app's install (the manifest's ML Kit dependencies), or else on first use.
 */
@Composable
actual fun rememberQrScanner(): (suspend () -> QrScan)? {
    val context = LocalContext.current
    return remember(context) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        val scan: suspend () -> QrScan = {
            suspendCancellableCoroutine { continuation ->
                scanner.startScan()
                    .addOnSuccessListener { code -> continuation.resume(code.rawValue?.let { QrScan.Scanned(it) } ?: QrScan.Failed) }
                    .addOnCanceledListener { continuation.resume(QrScan.Cancelled) }
                    .addOnFailureListener { continuation.resume(QrScan.Failed) }
            }
        }
        scan
    }
}
