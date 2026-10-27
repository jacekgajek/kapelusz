package com.jacekgajek.kapelusz.app.platform

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
actual fun rememberStoreUpdates(): StoreUpdates? {
    val activity = LocalContext.current as? Activity ?: return null
    val updates = remember(activity) { PlayStoreUpdates(activity) }
    DisposableEffect(updates) {
        updates.start()
        onDispose { updates.stop() }
    }
    // Asked again each time the app comes back to the screen, not only when the strip first shows.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(updates, resumed) { if (resumed) updates.check() }
    return updates
}

/**
 * [StoreUpdates] through Play's in-app updates library (its manager is one per process, holding only the application
 * context). Every failure (not from Play, offline) is "nothing to offer". A version declined in Play's dialog isn't
 * offered again; a newer one is.
 */
private class PlayStoreUpdates(private val activity: Activity) : StoreUpdates {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(StoreUpdateState.NONE)
    override val state: StateFlow<StoreUpdateState> = _state.asStateFlow()

    private val listener = InstallStateUpdatedListener { install ->
        when (install.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> _state.value = StoreUpdateState.DOWNLOADING
            InstallStatus.DOWNLOADED -> _state.value = StoreUpdateState.DOWNLOADED
            // Cancelled or failed: what Play offers now, asked afresh.
            InstallStatus.CANCELED, InstallStatus.FAILED -> check()
            else -> Unit
        }
    }

    fun start() = manager.registerListener(listener)

    fun stop() = manager.unregisterListener(listener)

    override fun check() {
        manager.appUpdateInfo.addOnSuccessListener { found ->
            _state.value = when {
                found.installStatus() == InstallStatus.DOWNLOADED -> StoreUpdateState.DOWNLOADED
                found.installStatus() == InstallStatus.DOWNLOADING || found.installStatus() == InstallStatus.PENDING -> StoreUpdateState.DOWNLOADING
                offered(found, AppUpdateType.FLEXIBLE) && found.availableVersionCode() > prefs.getInt(DECLINED, 0) -> StoreUpdateState.AVAILABLE
                else -> StoreUpdateState.NONE
            }
        }.addOnFailureListener { _state.value = StoreUpdateState.NONE }
    }

    override fun download() {
        // A fresh answer each time: one already used for a flow (a cancelled download, a second tap) can't start another.
        manager.appUpdateInfo.addOnSuccessListener { found ->
            if (!offered(found, AppUpdateType.FLEXIBLE)) return@addOnSuccessListener
            manager.startUpdateFlow(found, activity, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
                .addOnSuccessListener { result ->
                    // Declined in Play's dialog: not this version again (a newer one is offered).
                    if (result == Activity.RESULT_CANCELED) {
                        prefs.edit { putInt(DECLINED, found.availableVersionCode()) }
                        _state.value = StoreUpdateState.NONE
                    }
                }
        }
    }

    override fun install() {
        manager.completeUpdate()
    }

    /** True when it is under way or the user chose not to (nothing else to do); false when Play can't, for the listing. */
    override suspend fun updateNow(): Boolean {
        val found = manager.appUpdateInfo.awaitOrNull() ?: return false
        // Downloaded already: installing it is the update.
        if (found.installStatus() == InstallStatus.DOWNLOADED) {
            manager.completeUpdate()
            return true
        }
        val resumable = found.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        if (!resumable && !offered(found, AppUpdateType.IMMEDIATE)) return false
        val result = manager.startUpdateFlow(found, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)).awaitOrNull()
        return result != null && result != ActivityResult.RESULT_IN_APP_UPDATE_FAILED
    }

    private fun offered(info: AppUpdateInfo, type: Int) =
        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(type)

    private companion object {
        const val PREFS = "kapelusz-store-updates"
        const val DECLINED = "declinedVersionCode"
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
}
