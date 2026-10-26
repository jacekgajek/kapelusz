package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

/** How a scan ended (ITERATION_6 §10). */
sealed interface QrScan {
    data class Scanned(val text: String) : QrScan

    /** The user closed the scanner. */
    data object Cancelled : QrScan

    /** The scanner couldn't open or read (no Play services, its module not downloaded yet, the camera). */
    data object Failed : QrScan
}

/** Scans a QR code with the camera (ITERATION_6 §10), e.g. an invite's. Null where there's no scanner (the web). */
@Composable
expect fun rememberQrScanner(): (suspend () -> QrScan)?
