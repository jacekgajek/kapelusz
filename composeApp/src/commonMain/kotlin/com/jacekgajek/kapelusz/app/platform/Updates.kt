package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** Where the automatic update (Android, ITERATION_2 §1) stands. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Downloading(val versionName: String) : UpdateStatus

    /** Downloaded; it installs as soon as the app is no longer in the foreground. */
    data class Ready(val versionName: String) : UpdateStatus

    /** Android must first allow Kapelusz to install apps ("install unknown apps"). */
    data class NeedsInstallPermission(val versionName: String) : UpdateStatus

    /** Android wants the user to confirm this install. */
    data class NeedsConfirmation(val versionName: String) : UpdateStatus

    /** Handed to Android; the app restarts once it is installed. */
    data class Installing(val versionName: String) : UpdateStatus

    /** The user declined installing this version; it is not offered again automatically. */
    data class Declined(val versionName: String) : UpdateStatus

    /** Android could not install this version; it is not retried automatically. */
    data class InstallFailed(val versionName: String) : UpdateStatus

    /** The check itself failed (e.g. offline); it is retried later. */
    data object Failed : UpdateStatus

    /** Debug builds (installSiteDebug) never update themselves. */
    data object Unavailable : UpdateStatus
}

/** Automatic updates of the Android app; there are none on the web. */
interface UpdateControls {
    val status: StateFlow<UpdateStatus>

    /** Checks right away (and downloads a newer version) regardless of the daily schedule. */
    fun checkNow()

    /** Installs the downloaded version now, e.g. from Settings: the user expects the app to restart. */
    fun installNow()

    /** Opens Android's "install unknown apps" setting for Kapelusz. */
    fun openInstallPermission()

    /** Shows Android's confirmation for an install it would not do silently. */
    fun confirm()
}

/** Null on platforms without automatic updates. */
@Composable
expect fun rememberUpdateControls(): UpdateControls?

/** Reloads the app from the server (the web's way to update); null where updating works differently (Android). */
expect fun appReloader(): (() -> Unit)?

/** True in the Android app, false on the web. */
expect fun isAndroidApp(): Boolean

/**
 * The store page of an app installed from Google Play (ITERATION_7 §1.1), which updates it; null for the website's APK
 * (which updates itself) and on the web. Such an app shows no Support me! either (Play's payments policy).
 */
expect fun storeListing(): String?
