package com.jacekgajek.artsplit.app.platform

import kotlinx.coroutines.await
import kotlin.js.Promise

private const val APK_URL = "/download/art-split.apk"

// iPadOS reports itself as a Mac, so a Mac with a touch screen counts as an iPad.
private fun isAppleMobile(): Boolean = js(
    "/iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1)",
)

private fun isDismissed(): Boolean = js("(() => { try { return localStorage.getItem('art-split-app-banner-dismissed') === '1'; } catch (e) { return false; } })()")

private fun markDismissed() {
    js("(() => { try { localStorage.setItem('art-split-app-banner-dismissed', '1'); } catch (e) {} })()")
}

// The APK is published next to version.json; no version.json means there is nothing to download yet.
private fun apkPublished(): Promise<JsBoolean> = js("fetch('/download/version.json', { cache: 'no-cache' }).then((r) => r.ok, () => false)")

// Checked once per page load; the banner is composed again on every tab screen.
private var published: Boolean? = null

actual suspend fun androidAppDownloadUrl(): String? {
    if (isAppleMobile() || isDismissed()) return null
    val available = published ?: runCatching { apkPublished().await<JsBoolean>().toBoolean() }.getOrDefault(false).also { published = it }
    return APK_URL.takeIf { available }
}

actual fun dismissAndroidAppSuggestion() = markDismissed()
