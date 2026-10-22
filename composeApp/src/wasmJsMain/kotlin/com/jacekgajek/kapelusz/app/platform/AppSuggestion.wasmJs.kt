package com.jacekgajek.kapelusz.app.platform

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.Promise

private const val APK_URL = "/download/kapelusz.apk"

// iPadOS reports itself as a Mac, so a Mac with a touch screen counts as an iPad.
private fun isAppleMobile(): Boolean = js(
    "/iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1)",
)

private fun isDismissed(): Boolean = js("(() => { try { return localStorage.getItem('kapelusz-app-banner-dismissed') === '1'; } catch (e) { return false; } })()")

private fun markDismissed() {
    js("(() => { try { localStorage.setItem('kapelusz-app-banner-dismissed', '1'); } catch (e) {} })()")
}

// Resolves to the text of version.json, or null when nothing is published yet.
private fun fetchVersion(): Promise<JsString?> = js("fetch('/download/version.json', { cache: 'no-cache' }).then((r) => r.ok ? r.text() : null, () => null)")

private val json = Json { ignoreUnknownKeys = true }

// Fetched once per page load; the banner is composed again on every tab screen.
private var published: Result<PublishedVersion?>? = null

actual suspend fun appDownload(): AppDownload? {
    if (isAppleMobile() || isDismissed()) return null
    val version = (published ?: runCatching {
        fetchVersion().await<JsString?>()?.toString()?.let { json.decodeFromString<PublishedVersion>(it) }
    }.also { published = it }).getOrNull() ?: return null
    return AppDownload(APK_URL, isUpdate = false, version.versionCode, version.versionName)
}

actual fun dismissAppDownload(download: AppDownload) = markDismissed()
