package com.jacekgajek.kapelusz.app.platform

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json

private fun userAgent(): String = js("navigator.userAgent || ''")

private fun maxTouchPoints(): Int = js("navigator.maxTouchPoints || 0")

// Safari on iOS sets navigator.standalone; other browsers report the manifest's display mode.
private fun standalone(): Boolean = js(
    "navigator.standalone === true || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)",
)

// Chrome on Android sees the app through the manifest's related_applications and the app's asset statements.
private fun androidAppInstalled(): Promise<JsBoolean> = js(
    """(navigator.getInstalledRelatedApps
        ? navigator.getInstalledRelatedApps().then((apps) => apps.some((app) => app.id === 'com.jacekgajek.kapelusz'), () => false)
        : Promise.resolve(false))""",
)

// Resolves to the text of version.json (written by every deploy), or null when it can't be read (offline).
private fun fetchVersion(): Promise<JsString?> = js("fetch('/download/version.json', { cache: 'no-cache' }).then((r) => r.ok ? r.text() : null, () => null)")

private val json = Json { ignoreUnknownKeys = true }

// Read again on every visit to the event list: a tab open for days still learns of a new deploy.
internal suspend fun readWebInstallState(): WebInstallState {
    val published = runSuspendCatching {
        fetchVersion().await<JsString?>()?.toString()?.let { json.decodeFromString<PublishedVersion>(it) }
    }.getOrNull()
    val installed = runSuspendCatching { androidAppInstalled().await<JsBoolean>().toBoolean() }.getOrDefault(false)
    return WebInstallState(BrowserFacts(userAgent(), maxTouchPoints(), standalone(), installed), published, AppConfig.VERSION_CODE)
}
