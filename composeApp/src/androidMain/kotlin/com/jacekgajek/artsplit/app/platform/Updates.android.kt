package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jacekgajek.artsplit.app.updates.Updater
import kotlinx.coroutines.flow.StateFlow

@Composable
actual fun rememberUpdateControls(): UpdateControls? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : UpdateControls {
            override val status: StateFlow<UpdateStatus> = Updater.status

            override fun checkNow() = Updater.launch { Updater.checkAndDownload(context) }

            override fun installNow() = Updater.installNow(context)

            override fun openInstallPermission() = Updater.openInstallPermission(context)

            override fun confirm() = Updater.confirm(context)
        }
    }
}

actual fun appReloader(): (() -> Unit)? = null

actual fun isAndroidApp(): Boolean = true
