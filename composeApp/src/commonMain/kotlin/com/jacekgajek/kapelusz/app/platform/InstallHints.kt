package com.jacekgajek.kapelusz.app.platform

/** What the web page knows about where it runs (I3 §2.3). */
data class BrowserFacts(
    val userAgent: String,
    /** iPadOS Safari reports itself as a Mac; touch points tell them apart. */
    val maxTouchPoints: Int,
    /** Opened from the home screen (installed web app), not in a browser tab. */
    val standalone: Boolean,
    /** The browser can tell the Android app is installed (installed related apps, Chrome on Android). */
    val androidAppInstalled: Boolean,
)

/** Everything the web hints are chosen from; see [webHint]. */
data class WebInstallState(
    val browser: BrowserFacts,
    /** `/download/version.json` (the deployed version), or null when it can't be read (offline, a local build). */
    val published: PublishedVersion?,
    /** The running web app's version code; 0 in local builds. */
    val runningVersionCode: Int,
)

/** A strip above the bottom bar on the web: a newer deploy to reload into, or a way to install the app. */
sealed interface WebHint {
    /** Started from the cached app (offline) or open since before a deploy: a reload starts the new one. */
    data class NewVersion(val versionName: String) : WebHint

    /** The Android app, suggested everywhere but on iPhones and iPads. */
    data object AndroidApp : WebHint

    /** "Add to Home Screen", in Safari on iPhones and iPads. */
    data object HomeScreen : WebHint
}

private val otherIosBrowsers = Regex("CriOS|FxiOS|EdgiOS|OPiOS|OPT/|YaBrowser|DuckDuckGo|GSA/|Brave|FBAN|FBAV|Instagram")

/** iPhone, iPod or iPad, including iPadOS, which reports itself as a Mac with a touch screen. */
fun isAppleMobile(userAgent: String, maxTouchPoints: Int): Boolean = Regex("iPhone|iPad|iPod").containsMatchIn(userAgent) || ("Macintosh" in userAgent && maxTouchPoints > 1)

/** Safari itself (not Chrome, Firefox or an app's browser on iOS), where "Add to Home Screen" is in the Share menu. */
fun isIosSafari(userAgent: String, maxTouchPoints: Int): Boolean = isAppleMobile(userAgent, maxTouchPoints) && "Safari/" in userAgent && !otherIosBrowsers.containsMatchIn(userAgent)

/**
 * The hint to show, or null. A newer deploy comes first; then the install hint of this device: "Add to Home Screen" in
 * Safari on iPhones and iPads unless already opened from the home screen, the Android app from Google Play elsewhere
 * (joining the test while it is closed, [PLAY_INSTALL]) unless the browser sees it installed. A hint the user dismissed ("Not now") stays hidden until the
 * next visit to the event list, i.e. [dismissed] only holds this visit's dismissals.
 */
fun webHint(state: WebInstallState, dismissed: Set<WebHint>): WebHint? {
    val browser = state.browser
    val published = state.published
    val newVersion = published?.takeIf { state.runningVersionCode > 0 && it.versionCode > state.runningVersionCode }
        ?.let { WebHint.NewVersion(it.versionName) }
    val install = when {
        isAppleMobile(browser.userAgent, browser.maxTouchPoints) ->
            WebHint.HomeScreen.takeIf { isIosSafari(browser.userAgent, browser.maxTouchPoints) && !browser.standalone }

        !browser.androidAppInstalled -> WebHint.AndroidApp

        else -> null
    }
    return listOfNotNull(newVersion, install).firstOrNull { it !in dismissed }
}
