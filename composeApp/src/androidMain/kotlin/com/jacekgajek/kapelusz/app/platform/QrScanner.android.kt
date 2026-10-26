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
 * Google Play services' code scanner: its own camera screen, so the app needs no camera permission; the scanning module
 * is downloaded by Play services on first use.
 */
@Composable
actual fun rememberQrScanner(): (suspend () -> String?)? {
    val context = LocalContext.current
    return remember(context) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        val scan: suspend () -> String? = {
            suspendCancellableCoroutine { continuation ->
                scanner.startScan()
                    .addOnSuccessListener { continuation.resume(it.rawValue) }
                    .addOnCanceledListener { continuation.resume(null) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }
        scan
    }
}
