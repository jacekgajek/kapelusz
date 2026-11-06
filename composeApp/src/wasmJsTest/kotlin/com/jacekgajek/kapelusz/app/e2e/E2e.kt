package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.platform.WebPlatform
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import io.kotest.core.spec.style.scopes.ExpectSpecRootScope
import io.kotest.core.test.TestScope
import kotlin.random.Random

/** The local server's address from Karma's client config (karma.config.d/ui-tests.js), or null. */
private fun karmaE2eApi(): JsString? = js("(window.__karma__ && window.__karma__.config && window.__karma__.config.kapeluszE2eApi) || null")

/**
 * The local server the end-to-end specs talk to (`./gradlew :composeApp:wasmJsBrowserTest -Pe2e`, LOCAL_DEVELOPMENT.md).
 * Null in a normal test run; the specs are then skipped.
 */
internal val e2eApi: String? = karmaE2eApi()?.toString()

/** One test of an end-to-end spec. Skipped unless the run has a local server. */
internal fun ExpectSpecRootScope.e2e(name: String, test: suspend TestScope.() -> Unit) {
    expect(name).config(enabled = e2eApi != null, test = test)
}

/**
 * The web app as `Main.kt` builds it: the browser's storage and a real HTTP client to the API compiled into the app.
 * With `-Pe2e` that API is the local server. Checked here, so a build without the flag fails at once instead of timing
 * out against the real API.
 */
internal fun e2eRuntime(): AppRuntime {
    check(AppConfig.DEV_SIGN_IN && AppConfig.API_URL == e2eApi) {
        "Built for ${AppConfig.API_URL} (dev sign-in ${AppConfig.DEV_SIGN_IN}); the end-to-end tests need -Pe2e on the whole build"
    }
    return AppRuntime(WebPlatform())
}

/**
 * A sign-in name no other test used. Every test is its own user, so its bands and its session stay apart from the
 * other tests' in the shared browser and server.
 */
internal fun freshUser(name: String): String = "$name ${Random.nextInt(100_000, 1_000_000)}"
