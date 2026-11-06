@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.navigation.NavController
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.app.App
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.bands.BandIndex
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandStore
import com.jacekgajek.kapelusz.app.bands.LocalBand
import com.jacekgajek.kapelusz.app.platform.FakePlatform
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.testing.FakeServer
import com.jacekgajek.kapelusz.app.testing.awaitText
import com.jacekgajek.kapelusz.app.testing.composeUiTest
import com.jacekgajek.kapelusz.app.ui.screens.SignInScreen
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.rules.Actor
import io.kotest.core.spec.style.ExpectSpec
import kotlinx.datetime.LocalDate

private const val USER = "alice"
private const val FAR = 4_000_000_000_000L

private fun bandWithEvent(): Band {
    val band = Band.create("b1", "Night Owls", "PLN", "m-alice", "Alice", USER)
    val gig = CreateEvent("e1", "Jazz Night", LocalDate(2026, 11, 20), participants = listOf(Participant("m-alice")))
    return (Engine.apply(band, Actor.System, gig, "srv", 0) as ApplyResult.Applied).band
}

/**
 * The app as it starts for a signed-in user, with [band] on the device and open. [AppRuntime.load] starts the sync
 * loop, which keeps polling [server] on the browser's loop after the test (its scope is the app's, never cancelled).
 */
private fun runtime(server: FakeServer, band: Band?): AppRuntime {
    val session =
        Session(USER, name = "Alice Band", idToken = "$USER:google", expiresAt = FAR, apiToken = "$USER:api", apiExpiresAt = FAR)
    val values = mutableMapOf(SessionRepository.KEY to KapeluszJson.encodeToString(Session.serializer(), session))
    if (band != null) {
        val index = BandIndex(USER, band.id, listOf(BandSummary(band.id, band.name, "Alice", Role.OWNER)))
        values[BandStore.indexKey(USER)] = KapeluszJson.encodeToString(BandIndex.serializer(), index)
        val local = LocalBand(base = band, version = "v1", clientId = "c1", userId = USER, schema = BandRepository.CURRENT_SCHEMA)
        values[BandStore.bandKey(USER, band.id)] = KapeluszJson.encodeToString(LocalBand.serializer(), local)
    }
    return AppRuntime(FakePlatform(store = InMemoryStore(values)), http = server.client)
}

/**
 * The app's screens come up (REFACTORING.md 4.6): five smoke tests in the browser, the one target with a composition
 * to test in (no JVM target; the Android host tests have no Activity). Each boots the real [App] on a [FakePlatform]
 * and a [FakeServer], with the session and band already stored, as after a sign-in.
 */
class SmokeTest :
    ExpectSpec({
        expect("sign-in screen shows") {
            composeUiTest {
                setContent {
                    CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                        SignInScreen(onIdToken = {})
                    }
                }
                awaitText("Pass the Hat")
                awaitText("Sign in to share your band's settlements with your bandmates.")
            }
        }

        expect("zero screen shows without a band") {
            composeUiTest {
                val runtime = runtime(FakeServer(), band = null)
                runtime.load()
                setContent { App(runtime) }
                awaitText("Create your band to start settling concerts")
                awaitText("Join a band")
            }
        }

        expect("an event opens") {
            composeUiTest {
                val server = FakeServer()
                val band = bandWithEvent()
                server.bands[band.id] = band
                val runtime = runtime(server, band)
                runtime.load()
                var nav: NavController? = null
                setContent { App(runtime, onNavHostReady = { nav = it }) }
                awaitText("Jazz Night")
                waitForIdle()
                nav!!.navigate(EventRoute("e1"))
                awaitText("Gig")
                awaitText("Money")
                awaitText("Split")
            }
        }

        expect("settings pages open") {
            composeUiTest {
                val server = FakeServer()
                val band = bandWithEvent()
                server.bands[band.id] = band
                val runtime = runtime(server, band)
                runtime.load()
                var nav: NavController? = null
                setContent { App(runtime, onNavHostReady = { nav = it }) }
                awaitText("Jazz Night")
                waitForIdle()
                nav!!.navigate(SettingsRoute())
                awaitText("Settings")
                awaitText("Personal")
                for ((page, title) in listOf("FUND" to "Band fund", "AGENDA" to "Event agenda", "CALENDAR" to "Calendar subscription")) {
                    nav!!.navigate(SettingsRoute(page))
                    awaitText(title)
                }
            }
        }

        expect("an outdated app shows update required") {
            composeUiTest {
                val server = FakeServer()
                val band = bandWithEvent()
                server.bands[band.id] = band
                val runtime = runtime(server, band)
                runtime.load()
                setContent { App(runtime) }
                awaitText("Jazz Night")
                // The server stops serving this version: the next round learns it.
                server.updateRequired = true
                runtime.sync.syncNow()
                awaitText("Update Pass the Hat")
            }
        }
    })
