package com.jacekgajek.kapelusz.android

import android.content.Context
import com.jacekgajek.kapelusz.app.platform.Distribution
import com.jacekgajek.kapelusz.app.platform.UpdateControls
import com.jacekgajek.kapelusz.app.platform.UpdateStatus
import com.jacekgajek.kapelusz.app.updates.UpdateWorker
import com.jacekgajek.kapelusz.app.updates.Updater
import kotlinx.coroutines.flow.StateFlow

/** The website's APK (ITERATION_7 §1.1): it keeps itself up to date (ITERATION_2 §1). */
internal object Flavor {
    fun install() {
        Distribution.selfUpdater = { context -> Controls(context) }
    }

    fun onStart(context: Context) {
        Updater.appVisible = true
        UpdateWorker.schedule(context)
        UpdateWorker.checkSoon(context)
    }

    fun onStop() {
        Updater.appVisible = false
    }

    private class Controls(private val context: Context) : UpdateControls {
        override val status: StateFlow<UpdateStatus> = Updater.status

        override fun checkNow() = Updater.launch { Updater.checkAndDownload(context) }

        override fun installNow() = Updater.installNow(context)

        override fun openInstallPermission() = Updater.openInstallPermission(context)

        override fun confirm() = Updater.confirm(context)
    }
}
