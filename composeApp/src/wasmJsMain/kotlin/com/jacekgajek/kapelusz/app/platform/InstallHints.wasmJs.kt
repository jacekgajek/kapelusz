@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app.platform

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.jsObject
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response

private fun userAgent(): String = window.navigator.userAgent

private fun maxTouchPoints(): Int = window.navigator.maxTouchPoints

// Safari on iOS sets navigator.standalone; other browsers report the manifest's display mode.
// language=javascript
private fun standalone(): Boolean = js(
    "navigator.standalone === true || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)",
)

// Chrome on Android sees the app through the manifest's related_applications and the app's asset statements.
// language=javascript
private fun androidAppInstalled(): Promise<JsBoolean> = js(
    """(navigator.getInstalledRelatedApps
        ? navigator.getInstalledRelatedApps().then((apps) => apps.some((app) => app.id === 'com.jacekgajek.kapelusz'), () => false)
        : Promise.resolve(false))""",
)

// Resolves to the text of version.json (written by every deploy), or null when it can't be read (offline).
private suspend fun fetchVersion(): String? = runSuspendCatching {
    val request = jsObject<RequestInit>().apply { cache = "no-cache".toJsString().unsafeCast<RequestCache>() }
    window.fetch("/download/version.json", request).await<Response>()
        .takeIf { it.ok }
        ?.text()
        ?.await<JsString>()
        ?.toString()
}.getOrNull()

private val json = Json { ignoreUnknownKeys = true }

// Read again on every visit to the event list: a tab open for days still learns of a new deploy.
internal suspend fun readWebInstallState(): WebInstallState {
    val published = runSuspendCatching { fetchVersion()?.let { json.decodeFromString<PublishedVersion>(it) } }.getOrNull()
    val installed = runSuspendCatching { androidAppInstalled().await<JsBoolean>().toBoolean() }.getOrDefault(false)
    return WebInstallState(BrowserFacts(userAgent(), maxTouchPoints(), standalone(), installed), published, AppConfig.VERSION_CODE)
}
