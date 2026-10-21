package com.jacekgajek.artsplit.app.updates

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.platform.PublishedVersion
import com.jacekgajek.artsplit.app.platform.UpdateStatus
import com.jacekgajek.artsplit.app.platform.fetchPublished
import com.jacekgajek.artsplit.app.platform.installedVersionCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automatic updates without the Play Store (VERSION_2 §1): find a newer published build, download and verify it, and
 * install it with [PackageInstaller], silently where Android allows (Android 12+, once Art Split installed itself),
 * otherwise after the user confirms. Installing replaces the running app, so it only happens while the app is in the
 * background, unless the user asks for it.
 */
object Updater {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    private val lock = Mutex()

    /** Downloads and installs outlive the screen that started them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The downloaded build waiting to be installed. */
    private var pending: Pair<PublishedVersion, File>? = null

    /** Set by MainActivity: the app is on screen. */
    @Volatile
    var appVisible = false

    /** Android's confirmation screen for the last install it would not do silently. */
    @SuppressLint("StaticFieldLeak")
    private var confirmation: Intent? = null

    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1

    fun isDebugBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Checks for a newer build and downloads it; installs it right away when the app is in the background (or
     * [installNow]). Returns false when the check should be retried (e.g. offline).
     */
    suspend fun checkAndDownload(context: Context, installNow: Boolean = false): Boolean = lock.withLock {
        if (isDebugBuild(context)) {
            _status.value = UpdateStatus.Unavailable
            return true
        }
        _status.value = UpdateStatus.Checking
        val published = withContext(Dispatchers.IO) { runCatching { fetchPublished() }.getOrNull() }
        if (published == null) {
            Log.i(TAG, "Check failed: no version.json")
            _status.value = UpdateStatus.Failed
            return false
        }
        Log.i(TAG, "Published ${published.versionCode}, installed ${installedVersionCode(context)}")
        if (published.versionCode <= installedVersionCode(context)) {
            clearDownloads(context)
            _status.value = UpdateStatus.UpToDate
            return true
        }
        val apk = apkFile(context, published.versionCode)
        if (!apk.exists()) {
            _status.value = UpdateStatus.Downloading(published.versionName)
            if (!download(context, published, apk)) {
                Log.i(TAG, "Download of ${published.versionCode} failed")
                _status.value = UpdateStatus.Failed
                return false
            }
        }
        pending = published to apk
        _status.value = UpdateStatus.Ready(published.versionName)
        if (installNow || !appVisible) install(context, published, apk)
        true
    }

    /** The app is in the background: install a downloaded update (also one that waited for the install permission). */
    suspend fun installIfReady(context: Context) = lock.withLock {
        if (appVisible) return@withLock
        val status = _status.value
        if (status !is UpdateStatus.Ready && status !is UpdateStatus.NeedsInstallPermission) return@withLock
        val (published, apk) = pending ?: return@withLock
        if (apk.exists()) install(context, published, apk)
    }

    /** For buttons: work that must not stop when the screen goes away. */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun install(context: Context, published: PublishedVersion, apk: File) {
        Log.i(TAG, "Installing ${published.versionCode}")
        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "Not allowed to install apps yet")
            _status.value = UpdateStatus.NeedsInstallPermission(published.versionName)
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Without asking when Art Split is the app's installer of record (Android 12+); Android decides.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("art-split.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val result = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_VERSION_NAME, published.versionName)
                // Mutable: the installer adds the status and, if needed, its confirmation intent.
                val pending = PendingIntent.getBroadcast(context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed", e)
            runCatching { installer.abandonSession(sessionId) }
            _status.value = UpdateStatus.Failed
        }
    }

    /** From [InstallResultReceiver]: Android wants the user to confirm. */
    internal fun onNeedsConfirmation(context: Context, intent: Intent, versionName: String) {
        confirmation = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        _status.value = UpdateStatus.NeedsConfirmation(versionName)
        if (appVisible) {
            confirm(context)
        } else {
            notifyReady(context, versionName, intent)
        }
    }

    internal fun onFailed() {
        _status.value = UpdateStatus.Failed
    }

    /** Shows the pending confirmation, if any. */
    fun confirm(context: Context) {
        val intent = confirmation ?: return
        runCatching { context.startActivity(intent) }
    }

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun notifyReady(context: Context, versionName: String, confirmIntent: Intent) {
        val notifications = NotificationManagerCompat.from(context)
        // Without the notification permission the in-app strip and Settings still offer the install.
        if (!notifications.areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, Strings.channelName(context), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val tap = PendingIntent.getActivity(context, 0, confirmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(Strings.readyTitle(context, versionName))
            .setContentText(Strings.readyText(context))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun download(context: Context, published: PublishedVersion, target: File): Boolean = withContext(Dispatchers.IO) {
        clearDownloads(context)
        val partial = File(target.path + ".part")
        val ok = runCatching {
            val connection = URL("${AppConfig.WEB_URL}/download/art-split.apk").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.useCaches = false
                if (connection.responseCode != 200) return@runCatching false
                partial.outputStream().use { out -> connection.inputStream.use { it.copyTo(out) } }
            } finally {
                connection.disconnect()
            }
            // The website could serve a different file than version.json announced (e.g. mid-deploy): only the
            // announced build of this app is kept. Android checks the signature when installing.
            val info = context.packageManager.getPackageArchiveInfo(partial.path, 0)
            val code = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
            info?.packageName == context.packageName && code == published.versionCode && partial.renameTo(target)
        }.getOrDefault(false)
        if (!ok) partial.delete()
        ok
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, versionCode: Long) = File(updatesDir(context), "art-split-$versionCode.apk")

    private fun clearDownloads(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.delete() }
    }

    internal const val EXTRA_VERSION_NAME = "versionName"
    internal const val TAG = "ArtSplitUpdater"
}
