package com.jacekgajek.kapelusz.app.updates

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
import androidx.core.content.edit
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.platform.PublishedVersion
import com.jacekgajek.kapelusz.app.platform.UpdateMemory
import com.jacekgajek.kapelusz.app.platform.UpdatePolicy
import com.jacekgajek.kapelusz.app.platform.UpdateStatus
import com.jacekgajek.kapelusz.app.platform.UpdateStep
import com.jacekgajek.kapelusz.app.platform.fetchPublished
import com.jacekgajek.kapelusz.app.platform.installedVersionCode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automatic updates without the Play Store (ITERATION_2 §1): find a newer published build, download and verify it, and
 * install it with [PackageInstaller], silently where Android allows (Android 12+, once Kapelusz installed itself),
 * otherwise after the user confirms. The rules of when that happens live in [UpdatePolicy]; this object does the
 * Android side and remembers declined and failed versions, so nobody is asked again and again.
 */
object Updater {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    private val lock = Mutex()

    /** Downloads and installs outlive the screen that started them; a failure must never crash the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Update failed", e) })

    /** Set by MainActivity: the app is on screen. */
    @Volatile
    var appVisible = false

    /** Android's confirmation screen for the last install it would not do silently (lost with the process). */
    @SuppressLint("StaticFieldLeak")
    private var confirmation: Intent? = null

    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1
    private const val PREFS = "kapelusz-updater"

    fun isDebugBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Checks for a newer build and downloads it, then installs it if [UpdatePolicy] allows (or right away when the
     * user asked: [installNow]). Returns false when the check should be retried soon (offline).
     */
    suspend fun checkAndDownload(context: Context, installNow: Boolean = false): Boolean = lock.withLock {
        if (isDebugBuild(context)) {
            _status.value = UpdateStatus.Unavailable
            return true
        }
        if (!installNow || pendingApk(context) == null) {
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
                confirmation = null
                cancelReady(context)
                _status.value = UpdateStatus.UpToDate
                return true
            }
            if (apkFile(context, published.versionCode).let { !it.exists() }) {
                _status.value = UpdateStatus.Downloading(published.versionName)
                if (!download(context, published)) {
                    Log.i(TAG, "Download of ${published.versionCode} failed")
                    _status.value = UpdateStatus.Failed
                    // A mismatch between version.json and the APK (mid-deploy) is not retried with backoff, which
                    // would download it again and again; the next daily check tries again.
                    return true
                }
                saveName(context, published)
            }
        }
        val (published, apk) = pendingApk(context) ?: return true
        val memory = memory(context)
        val step = UpdatePolicy.afterCheck(published.versionCode, published.versionName, installNow, canInstallApps(context), memory, System.currentTimeMillis())
        when (step) {
            UpdateStep.Install -> install(context, published, apk)
            is UpdateStep.Show -> {
                _status.value = step.status
                // Downloaded while the app is away: say so once; tapping opens the app, whose strip offers the install.
                if (step.status is UpdateStatus.Ready && !appVisible && memory.notifiedVersion != published.versionCode) {
                    val shown = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { notifyReady(context, published.versionName, it) } == true
                    // Only once shown: with notifications off now, a later check (after they are allowed) still tells.
                    if (shown) remember(context) { it.copy(notifiedVersion = published.versionCode) }
                }
            }
        }
        true
    }

    /** For buttons: work that must not stop when the screen goes away. */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private suspend fun install(context: Context, published: PublishedVersion, apk: File) {
        Log.i(TAG, "Installing ${published.versionCode}")
        if (!canInstallApps(context)) {
            Log.i(TAG, "Not allowed to install apps yet")
            _status.value = UpdateStatus.NeedsInstallPermission(published.versionName)
            return
        }
        val installer = context.packageManager.packageInstaller
        var sessionId: Int? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                // Without asking when Kapelusz is the app's installer of record (Android 12+); Android decides.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params).also { sessionId = it }
            installer.openSession(id).use { session ->
                // File work on the I/O threads, not the shared CPU ones.
                withContext(Dispatchers.IO) {
                    session.openWrite("kapelusz.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val result = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(EXTRA_VERSION_NAME, published.versionName)
                    .putExtra(EXTRA_VERSION_CODE, published.versionCode)
                // Mutable: the installer adds the status and, if needed, its confirmation intent.
                val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                remember(context) { it.installing(published.versionCode, System.currentTimeMillis()) }
                _status.value = UpdateStatus.Installing(published.versionName)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed", e)
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            remember(context) { it.finishedWithoutInstall(published.versionCode, declined = false) }
            _status.value = UpdateStatus.InstallFailed(published.versionName)
        }
    }

    /** From [InstallResultReceiver]: Android wants the user to confirm. */
    internal fun onNeedsConfirmation(context: Context, intent: Intent, versionCode: Long, versionName: String) {
        confirmation = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        remember(context) { it.confirming(versionCode) }
        _status.value = UpdateStatus.NeedsConfirmation(versionName)
        if (appVisible) confirm(context) else notifyReady(context, versionName, intent)
    }

    /** From [InstallResultReceiver]: the user declined, or Android could not install it. */
    internal fun onFinishedWithoutInstall(context: Context, versionCode: Long, versionName: String, declined: Boolean) {
        confirmation = null
        cancelReady(context)
        remember(context) { it.finishedWithoutInstall(versionCode, declined) }
        _status.value = if (declined) UpdateStatus.Declined(versionName) else UpdateStatus.InstallFailed(versionName)
    }

    /**
     * Shows Android's confirmation. Without one at hand (the app was restarted meanwhile), installing again brings it
     * up, since the app is on screen.
     */
    fun confirm(context: Context) {
        cancelReady(context)
        val intent = confirmation
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        } else {
            launch { checkAndDownload(context, installNow = true) }
        }
    }

    /** The user asked for it (Settings): forget earlier declines and failures of this version and install now. */
    fun installNow(context: Context) {
        remember(context) { it.askedByUser() }
        launch { checkAndDownload(context, installNow = true) }
    }

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun canInstallApps(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** The "ready to install" notification, once it no longer applies: confirmed in the app, declined, or installed. */
    private fun cancelReady(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** False when notifications are off. */
    private fun notifyReady(context: Context, versionName: String, confirmIntent: Intent): Boolean {
        val notifications = NotificationManagerCompat.from(context)
        val strings = Strings(AndroidAppGraph.get(context).settings.settings.value.language)
        // Without the notification permission (the app no longer asks for it, ITERATION_6 §9) the in-app strip offers the install.
        if (!notifications.areNotificationsEnabled()) return false
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, strings.channelName(), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 0, confirmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(strings.readyTitle(versionName))
            .setContentText(strings.readyText())
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
        notifications.notify(NOTIFICATION_ID, notification)
        return true
    }

    private suspend fun download(context: Context, published: PublishedVersion): Boolean = withContext(Dispatchers.IO) {
        clearDownloads(context)
        val target = apkFile(context, published.versionCode)
        val partial = File(target.path + ".part")
        val ok = runCatching {
            val connection = URL("${AppConfig.WEB_URL}/download/kapelusz.apk").openConnection() as HttpURLConnection
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

    /** The downloaded, verified build waiting to be installed, if it is still newer than the installed one. */
    private fun pendingApk(context: Context): Pair<PublishedVersion, File>? {
        val (apk, code) = updatesDir(context).listFiles().orEmpty().firstNotNullOfOrNull { f -> UpdatePolicy.apkVersion(f.name)?.let { f to it } } ?: return null
        if (code <= installedVersionCode(context)) return null
        val name = prefs(context).getString(KEY_NAME, null) ?: ""
        return PublishedVersion(code, name) to apk
    }

    private fun saveName(context: Context, published: PublishedVersion) = prefs(context).edit { putString(KEY_NAME, published.versionName) }

    private fun memory(context: Context): UpdateMemory = prefs(context).let { p ->
        UpdateMemory(
            declinedVersion = p.getLong(KEY_DECLINED, 0).takeIf { it > 0 },
            failedVersion = p.getLong(KEY_FAILED, 0).takeIf { it > 0 },
            confirmVersion = p.getLong(KEY_CONFIRM, 0).takeIf { it > 0 },
            installingVersion = p.getLong(KEY_INSTALLING, 0).takeIf { it > 0 },
            installingSince = p.getLong(KEY_INSTALLING_SINCE, 0),
            notifiedVersion = p.getLong(KEY_NOTIFIED, 0).takeIf { it > 0 },
        )
    }

    private fun remember(context: Context, change: (UpdateMemory) -> UpdateMemory) {
        val next = change(memory(context))
        prefs(context).edit {
            putLong(KEY_DECLINED, next.declinedVersion ?: 0)
            putLong(KEY_FAILED, next.failedVersion ?: 0)
            putLong(KEY_CONFIRM, next.confirmVersion ?: 0)
            putLong(KEY_INSTALLING, next.installingVersion ?: 0)
            putLong(KEY_INSTALLING_SINCE, next.installingSince)
            putLong(KEY_NOTIFIED, next.notifiedVersion ?: 0)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, versionCode: Long) = File(updatesDir(context), UpdatePolicy.apkName(versionCode))

    private fun clearDownloads(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.delete() }
    }

    internal const val EXTRA_VERSION_NAME = "versionName"
    internal const val EXTRA_VERSION_CODE = "versionCode"
    internal const val TAG = "KapeluszUpdater"
    private const val KEY_NAME = "pendingVersionName"
    private const val KEY_DECLINED = "declinedVersion"
    private const val KEY_FAILED = "failedVersion"
    private const val KEY_CONFIRM = "confirmVersion"
    private const val KEY_INSTALLING = "installingVersion"
    private const val KEY_INSTALLING_SINCE = "installingSince"
    private const val KEY_NOTIFIED = "notified"
}
