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

/** The local server's address. Karma puts it in its client config (karma.config.d/ui-tests.js). Null without it. */
private fun karmaE2eApi(): JsString? = js("window.__karma__?.config?.kapeluszE2eApi || null")

/** The one spec to run (`-Pe2e.spec`). Null means all. */
private fun karmaE2eSpec(): JsString? = js("window.__karma__?.config?.kapeluszE2eSpec || null")

/**
 * The local server the end-to-end specs talk to. Set by `./gradlew :composeApp:wasmJsBrowserTest -Pe2e`
 * (LOCAL_DEVELOPMENT.md). Null in a normal test run. The specs are then skipped.
 */
internal val e2eApi: String? = karmaE2eApi()?.toString()

private val e2eSpec: String? = karmaE2eSpec()?.toString()

/** The spec that runs now. A failure message says how to rerun it. */
internal var currentSpec: String = ""

/**
 * One test of an end-to-end spec. It is skipped without a local server. It is also skipped when `-Pe2e.spec` names
 * another spec.
 *
 * The app starts the way `Main.kt` starts it: the browser's storage, a real HTTP client, the API URL compiled into the
 * app. With `-Pe2e` that URL is the local server. The app starts signed out. [test] drives it through its [Screen].
 * At the end the app signs out. That also stops its syncing. All tests share one browser, so the next test starts clean.
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

/** A build without `-Pe2e` fails here at once. Otherwise it would time out against the real API. */
private suspend fun e2eRuntime(): AppRuntime {
    check(AppConfig.DEV_SIGN_IN && AppConfig.API_URL == e2eApi) {
        "Built for ${AppConfig.API_URL} (dev sign-in ${AppConfig.DEV_SIGN_IN}); the end-to-end tests need -Pe2e on the whole build"
    }
    val platform = WebPlatform()
    // A test that stopped early leaves these behind. The session would start this test signed in. The settings
    // should be the defaults.
    platform.store.remove(SessionRepository.KEY)
    platform.store.remove(SettingsRepository.KEY)
    return AppRuntime(platform)
}

/**
 * A sign-in name that no other test used. Each test is its own user. Its bands stay apart from the other tests' bands
 * on the shared server.
 */
internal fun freshUser(name: String): String = "$name ${Random.nextInt(100_000, 1_000_000)}"
