package com.jacekgajek.kapelusz.app.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
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

/** [StoreUpdates] through Play's in-app updates library; every failure (not from Play, offline) is "nothing to offer". */
private class PlayStoreUpdates(private val activity: Activity) : StoreUpdates {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private val _state = MutableStateFlow(StoreUpdateState.NONE)
    override val state: StateFlow<StoreUpdateState> = _state.asStateFlow()
    private var info: AppUpdateInfo? = null

    private val listener = InstallStateUpdatedListener { install ->
        _state.value = when (install.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> StoreUpdateState.DOWNLOADING
            InstallStatus.DOWNLOADED -> StoreUpdateState.DOWNLOADED
            // Cancelled or failed: offered again; installed: the app restarts anyway.
            InstallStatus.CANCELED, InstallStatus.FAILED -> StoreUpdateState.AVAILABLE
            else -> _state.value
        }
    }

    fun start() = manager.registerListener(listener)

    fun stop() = manager.unregisterListener(listener)

    override fun check() {
        manager.appUpdateInfo.addOnSuccessListener { found ->
            info = found
            _state.value = when {
                found.installStatus() == InstallStatus.DOWNLOADED -> StoreUpdateState.DOWNLOADED
                found.installStatus() == InstallStatus.DOWNLOADING || found.installStatus() == InstallStatus.PENDING -> StoreUpdateState.DOWNLOADING
                found.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && found.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> StoreUpdateState.AVAILABLE
                else -> StoreUpdateState.NONE
            }
        }.addOnFailureListener { _state.value = StoreUpdateState.NONE }
    }

    override fun download() {
        val found = info ?: return
        runCatching { manager.startUpdateFlow(found, activity, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)) }
    }

    override fun install() {
        manager.completeUpdate()
    }

    override suspend fun updateNow(): Boolean {
        val found = runCatching { manager.appUpdateInfo.awaitOrNull() }.getOrNull() ?: return false
        val offered = found.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
            found.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        if (!offered || !found.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) return false
        return runCatching { manager.startUpdateFlow(found, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)) }.isSuccess
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
}
