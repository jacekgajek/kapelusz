package com.jacekgajek.kapelusz.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToNavigation
import com.jacekgajek.kapelusz.app.data.AppRuntime
import com.jacekgajek.kapelusz.app.data.AppState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.fallback_symbols
import com.jacekgajek.kapelusz.app.generated.fallback_text
import com.jacekgajek.kapelusz.app.intents.AppIntent
import com.jacekgajek.kapelusz.app.platform.WebPlatform
import com.jacekgajek.kapelusz.app.ui.screens.OtherTabScreen
import com.jacekgajek.kapelusz.app.ui.theme.KapeluszTheme
import com.jacekgajek.kapelusz.text.EventLinks
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

private enum class Phase { STARTING, OTHER_TAB, READY }

private const val JOIN_CODE_KEY = "kapelusz-join-code"
private const val EVENT_LINK_KEY = "kapelusz-event-link"

// Whether the tab is hidden (another tab in front, the window minimised, the phone locked): not in Kotlin's bindings.
private fun documentHidden(): Boolean = js("document.hidden")

// The running build, for smoke tests of a deploy: `kapeluszVersion` in the browser console.
private fun exposeVersion(label: String) {
    js("globalThis.kapeluszVersion = label")
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    exposeVersion(AppConfig.VERSION_LABEL)
    val runtime = AppRuntime(WebPlatform())
    // A hidden tab sends what waits, then stops looking for news; shown again, it syncs at once (ITERATION_7 §2.6).
    document.addEventListener("visibilitychange", {
        if (documentHidden()) runtime.onHidden() else runtime.onForeground()
    })
    // A join code waits in this tab's session storage until it is used or dismissed, so reloading before signing in
    // keeps it; an event link likewise, until it is opened (ITERATION_5 §10).
    (takeJoinCodeFromUrl() ?: window.sessionStorage.getItem(JOIN_CODE_KEY))?.let { runtime.intents.offer(AppIntent.Join(it)) }
    (takeEventLinkFromUrl() ?: window.sessionStorage.getItem(EVENT_LINK_KEY))?.let { runtime.intents.offer(AppIntent.OpenEvent(it)) }
    MainScope().launch {
        runtime.intents.pending.collect { pending ->
            keepInSession(JOIN_CODE_KEY, pending.filterIsInstance<AppIntent.Join>().firstOrNull()?.code)
            keepInSession(EVENT_LINK_KEY, pending.filterIsInstance<AppIntent.OpenEvent>().firstOrNull { it.bandId == null }?.eventId)
        }
    }
    ComposeViewport {
        // Nothing is drawn before the fallback fonts: text already drawn isn't drawn again with fonts that arrive later.
        // Loading the bands doesn't wait for them.
        val fontsReady = fallbackFontsReady()
        var phase by remember { mutableStateOf(Phase.STARTING) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            phase = if (TabLock.acquire(steal = false)) {
                runtime.load()
                // Opened or restored in the background: no looking for news until it is shown (ITERATION_7 §2.6).
                if (documentHidden()) runtime.onHidden()
                Phase.READY
            } else {
                Phase.OTHER_TAB
            }
        }
        // Once loaded (a signed-in user's address must survive a reload), the screen leaves the address with the session.
        LaunchedEffect(phase) { if (phase == Phase.READY) forgetScreenWhenSignedOut(runtime) }
        // One loading screen for both waits, so its spinner doesn't start over in between.
        if (!fontsReady || phase == Phase.STARTING) {
            AppLoading()
        } else {
            when (phase) {
                Phase.STARTING -> {}

                Phase.OTHER_TAB -> KapeluszTheme {
                    Surface {
                        OtherTabScreen(onUseHere = {
                            scope.launch {
                                TabLock.acquire(steal = true)
                                // Give the other tab a moment to finish its last write before reading the data (ITERATION_3 R9).
                                delay(1500)
                                runtime.load()
                                // Opened or restored in the background: no looking for news until it is shown (ITERATION_7 §2.6).
                                if (documentHidden()) runtime.onHidden()
                                phase = Phase.READY
                            }
                        })
                    }
                }

                Phase.READY -> App(runtime, onNavHostReady = { window.bindToNavigation(it) })
            }
        }
    }
}

private fun keepInSession(key: String, value: String?) {
    if (value == null) window.sessionStorage.removeItem(key) else window.sessionStorage.setItem(key, value)
}

/**
 * After signing in, the app starts from the event list: the address would otherwise bring back the last screen (e.g.
 * Settings, where the user signed out), as the navigation is bound to it. So the screen (the hash) leaves the address
 * with the session.
 */
private suspend fun forgetScreenWhenSignedOut(runtime: AppRuntime) {
    runtime.state.collect { if (it is AppState.SignedOut) window.history.replaceState(null, "", "/") }
}

/** An event link (`/e/<eventId>`, ITERATION_5 §10): taken once, the address reset to the app root like a join link. */
private fun takeEventLinkFromUrl(): String? {
    val eventId = EventLinks.eventId(window.location.pathname) ?: return null
    window.history.replaceState(null, "", "/" + window.location.hash)
    return eventId
}

/**
 * A join link (`/join/ABC123`, served by CloudFront's SPA fallback) carries an invite code. It is taken once, and the
 * address is reset to the app root so a reload does not offer the same invite again.
 */
private fun takeJoinCodeFromUrl(): String? {
    val match = Regex("^/join/([A-Za-z0-9]{6})/?$").find(window.location.pathname) ?: return null
    window.history.replaceState(null, "", "/" + window.location.hash)
    return match.groupValues[1].uppercase()
}

/**
 * Compose draws text on the web with a font of its own, without the system's: characters it lacks showed as boxes (the
 * hints' ⚙ and →). Fonts preloaded into the resolver are its fallbacks (since Compose 1.8); these are small cuts of Noto
 * Sans and Noto Sans Symbols (SIL Open Font License, licenses/OFL-Noto.txt), web only. Each is registered as it arrives;
 * ready once both are, or after 3 s, so a slow font doesn't hold the first screen back (text laid out later still gets
 * it).
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun fallbackFontsReady(): Boolean {
    val resolver = LocalFontFamilyResolver.current
    val text by preloadFont(Res.font.fallback_text)
    val symbols by preloadFont(Res.font.fallback_symbols)
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(3000)
        timedOut = true
    }
    // Ready once registered, not merely downloaded: registering runs after the frame that sees a font arrive, and text
    // laid out in that frame would miss it.
    var registered by remember { mutableStateOf(0) }
    LaunchedEffect(text) {
        text?.let {
            resolver.preload(FontFamily(it))
            registered++
        }
    }
    LaunchedEffect(symbols) {
        symbols?.let {
            resolver.preload(FontFamily(it))
            registered++
        }
    }
    return registered == 2 || timedOut
}
