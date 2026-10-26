package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

/**
 * Scans a QR code with the camera (ITERATION_6 §10), e.g. an invite's: its text, or null when cancelled or it failed.
 * Null where there's no scanner (the web).
 */
@Composable
expect fun rememberQrScanner(): (suspend () -> String?)?
