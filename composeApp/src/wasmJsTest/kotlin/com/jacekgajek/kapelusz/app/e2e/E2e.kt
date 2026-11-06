package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.platform.WebPlatform
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import io.kotest.core.spec.style.scopes.ExpectSpecRootScope
import io.kotest.core.test.TestScope
import kotlin.random.Random

/** The address Karma's client config carries in an end-to-end run (karma.config.d/ui-tests.js), else null. */
private fun karmaE2eApi(): JsString? = js("(window.__karma__ && window.__karma__.config && window.__karma__.config.kapeluszE2eApi) || null")

/**
 * The local server the end-to-end specs talk to (`./gradlew :composeApp:wasmJsBrowserTest -Pe2e`, LOCAL_DEVELOPMENT.md),
 * or null in an ordinary test run, where they are skipped.
 */
internal val e2eApi: String? = karmaE2eApi()?.toString()

/** A test of an end-to-end spec: skipped unless the run has a local server. */
internal fun ExpectSpecRootScope.e2e(name: String, test: suspend TestScope.() -> Unit) {
    expect(name).config(enabled = e2eApi != null, test = test)
}

/**
 * The web app as `Main.kt` builds it: the browser's own storage and a real HTTP client to the compiled-in API, which
 * `-Pe2e` points at the local server. Checked here so a build without the flag fails at once instead of timing out
 * against the real API.
 */
internal fun e2eRuntime(): AppRuntime {
    check(AppConfig.DEV_SIGN_IN && AppConfig.API_URL == e2eApi) {
        "Built for ${AppConfig.API_URL} (dev sign-in ${AppConfig.DEV_SIGN_IN}); the end-to-end tests need -Pe2e on the whole build"
    }
    return AppRuntime(WebPlatform())
}

/**
 * A name for the development sign-in that no other test used: every test is its own user, so its bands and its
 * stored session are apart from the others' in the one browser and the one server of the run.
 */
internal fun freshUser(name: String): String = "$name ${Random.nextInt(100_000, 1_000_000)}"
