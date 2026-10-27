package com.jacekgajek.kapelusz.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.jacekgajek.kapelusz.app.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Application context for platform helpers that run outside composition; set by AndroidAppGraph. */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    var context: Context? = null
}

private const val PREFS = "kapelusz-updates"
private const val DISMISSED_VERSION = "dismissedVersionCode"
private val json = Json { ignoreUnknownKeys = true }

fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
}

/** `/download/version.json`, the APK the website publishes; also read by the website APK's updater. */
fun fetchPublished(): PublishedVersion? {
    val connection = URL("${AppConfig.WEB_URL}/download/version.json").openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.useCaches = false
        if (connection.responseCode != 200) null
        else json.decodeFromString<PublishedVersion>(connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
    }
}

// Checked once per process (the banner is composed again on every tab screen); a failed check (e.g. offline) is tried
// again next time rather than kept for the life of the process (V5-M1 D7).
private var published: PublishedVersion? = null

actual suspend fun appDownload(): AppDownload? {
    val context = AndroidPlatform.context ?: return null
    // From Google Play, which updates it: the website's APK is not its update (ITERATION_7 §1.1).
    if (Distribution.selfUpdater == null) return null
    // Debug builds (installSiteDebug, installPlayDebug) always have version 1; they would see every published release as an update.
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return null
    val published = published ?: withContext(Dispatchers.IO) { runCatching { fetchPublished() }.getOrNull() }?.also { published = it } ?: return null
    val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DISMISSED_VERSION, 0)
    if (published.versionCode <= installedVersionCode(context) || published.versionCode <= dismissed) return null
    return AppDownload("${AppConfig.WEB_URL}/download/kapelusz.apk", isUpdate = true, published.versionCode, published.versionName)
}

actual fun dismissAppDownload(download: AppDownload) {
    AndroidPlatform.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putLong(DISMISSED_VERSION, download.versionCode)?.apply()
}
