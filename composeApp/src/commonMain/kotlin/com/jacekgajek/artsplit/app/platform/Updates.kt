package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** Where the automatic update (Android, VERSION_2 §1) stands. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Downloading(val versionName: String) : UpdateStatus

    /** Downloaded; it installs as soon as the app is no longer in the foreground. */
    data class Ready(val versionName: String) : UpdateStatus

    /** Android must first allow Art Split to install apps ("install unknown apps"). */
    data class NeedsInstallPermission(val versionName: String) : UpdateStatus

    /** Android wants the user to confirm this install. */
    data class NeedsConfirmation(val versionName: String) : UpdateStatus

    data object Failed : UpdateStatus

    /** Debug builds (installDebug) never update themselves. */
    data object Unavailable : UpdateStatus
}

/** Automatic updates of the Android app; there are none on the web. */
interface UpdateControls {
    val status: StateFlow<UpdateStatus>

    /** Checks right away (and downloads a newer version) regardless of the daily schedule. */
    fun checkNow()

    /** Installs the downloaded version now, e.g. from Settings: the user expects the app to restart. */
    fun installNow()

    /** Opens Android's "install unknown apps" setting for Art Split. */
    fun openInstallPermission()

    /** Shows Android's confirmation for an install it would not do silently. */
    fun confirm()
}

/** Null on platforms without automatic updates. */
@Composable
expect fun rememberUpdateControls(): UpdateControls?
