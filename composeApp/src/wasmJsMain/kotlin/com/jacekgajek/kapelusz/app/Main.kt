package com.jacekgajek.kapelusz.app

import kotlinx.coroutines.delay
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToNavigation
import com.jacekgajek.kapelusz.app.auth.WebGoogleAuth
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.IndexedDbStore
import com.jacekgajek.kapelusz.app.ui.screens.OtherTabScreen
import com.jacekgajek.kapelusz.app.ui.theme.KapeluszTheme
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private enum class Phase { STARTING, OTHER_TAB, READY }

private const val JOIN_CODE_KEY = "kapelusz-join-code"

// The running build, for smoke tests of a deploy: `kapeluszVersion` in the browser console.
private fun exposeVersion(label: String) {
    js("globalThis.kapeluszVersion = label")
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    exposeVersion(AppConfig.VERSION_LABEL)
    val graph = AppGraph(IndexedDbStore(), WebGoogleAuth)
    // A join code waits in this tab's session storage until it is used or dismissed, so reloading before signing in
    // keeps it.
    (takeJoinCodeFromUrl() ?: window.sessionStorage.getItem(JOIN_CODE_KEY))?.let { graph.pendingJoinCode.value = it }
    MainScope().launch {
        graph.pendingJoinCode.collect { code ->
            if (code == null) window.sessionStorage.removeItem(JOIN_CODE_KEY) else window.sessionStorage.setItem(JOIN_CODE_KEY, code)
        }
    }
    ComposeViewport {
        var phase by remember { mutableStateOf(Phase.STARTING) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            phase = if (TabLock.acquire(steal = false)) {
                graph.load()
                Phase.READY
            } else {
                Phase.OTHER_TAB
            }
        }
        when (phase) {
            Phase.STARTING -> {}
            Phase.OTHER_TAB -> KapeluszTheme {
                Surface {
                    OtherTabScreen(onUseHere = {
                        scope.launch {
                            TabLock.acquire(steal = true)
                            // Give the other tab a moment to finish its last write before reading the data (ITERATION_3 R9).
                            delay(1500)
                            graph.load()
                            phase = Phase.READY
                        }
                    })
                }
            }
            Phase.READY -> App(graph, onNavHostReady = { window.bindToNavigation(it) })
        }
    }
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
