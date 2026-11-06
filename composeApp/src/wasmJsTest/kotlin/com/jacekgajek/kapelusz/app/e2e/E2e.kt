@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import com.jacekgajek.kapelusz.app.App
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.platform.WebPlatform
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import com.jacekgajek.kapelusz.app.settings.SettingsRepository
import com.jacekgajek.kapelusz.app.testing.composeUiTest
import io.kotest.core.spec.style.scopes.ExpectSpecRootScope
import io.kotest.core.test.TestScope
import kotlin.random.Random

/** The local server's address from Karma's client config (karma.config.d/ui-tests.js), or null. */
private fun karmaE2eApi(): JsString? = js("window.__karma__?.config?.kapeluszE2eApi || null")

/** The one spec to run (`-Pe2e.spec`), or null for all. */
private fun karmaE2eSpec(): JsString? = js("window.__karma__?.config?.kapeluszE2eSpec || null")

/**
 * The local server the end-to-end specs talk to (`./gradlew :composeApp:wasmJsBrowserTest -Pe2e`, LOCAL_DEVELOPMENT.md).
 * Null in a normal test run; the specs are then skipped.
 */
internal val e2eApi: String? = karmaE2eApi()?.toString()

private val e2eSpec: String? = karmaE2eSpec()?.toString()

/** The spec that is running now. A failure message says how to rerun it. */
internal var currentSpec: String = ""

/**
 * One test of an end-to-end spec. Skipped unless the run has a local server, or when `-Pe2e.spec` asks for another spec.
 * The app starts as `Main.kt` builds it: the browser's storage and a real HTTP client to the API compiled into the app,
 * which `-Pe2e` sets to the local server. It starts signed out. [test] drives it by the words on its [Screen]. At the
 * end the app signs out, which also stops its syncing. The tests share one browser, so the next test starts clean.
 */
internal fun ExpectSpecRootScope.e2e(name: String, test: suspend Screen.() -> Unit) {
    val spec = this::class.simpleName.orEmpty()
    val enabled = e2eApi != null && (e2eSpec == null || spec.contains(e2eSpec, ignoreCase = true))
    expect(name).config(enabled = enabled) {
        currentSpec = spec
        composeUiTest {
            val runtime = e2eRuntime()
            runtime.load()
            setContent { App(runtime) }
            try {
                Screen(this).test()
            } finally {
                runtime.account.signOut(tellServer = false)
            }
        }
    }
}

/** The check makes a build without `-Pe2e` fail at once, instead of timing out against the real API. */
private suspend fun e2eRuntime(): AppRuntime {
    check(AppConfig.DEV_SIGN_IN && AppConfig.API_URL == e2eApi) {
        "Built for ${AppConfig.API_URL} (dev sign-in ${AppConfig.DEV_SIGN_IN}); the end-to-end tests need -Pe2e on the whole build"
    }
    val platform = WebPlatform()
    // A test that did not reach its end leaves these behind. The session would start this test signed in; the
    // settings should be the app's defaults in every test.
    platform.store.remove(SessionRepository.KEY)
    platform.store.remove(SettingsRepository.KEY)
    return AppRuntime(platform)
}

/**
 * A sign-in name no other test used. Every test is its own user, so its bands stay apart from the other tests' in
 * the shared server.
 */
internal fun freshUser(name: String): String = "$name ${Random.nextInt(100_000, 1_000_000)}"
