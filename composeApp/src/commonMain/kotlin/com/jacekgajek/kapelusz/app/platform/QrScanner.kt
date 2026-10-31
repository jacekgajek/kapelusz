package com.jacekgajek.kapelusz.app.platform

/** How a scan ended. */
sealed interface QrScan {
    data class Scanned(val text: String) : QrScan

    /** The user closed the scanner. */
    data object Cancelled : QrScan

    /** The scanner couldn't open or read (no Play services, its module not downloaded yet, the camera). */
    data object Failed : QrScan
}
