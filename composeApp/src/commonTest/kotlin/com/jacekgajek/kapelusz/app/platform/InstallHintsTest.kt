package com.jacekgajek.kapelusz.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallHintsTest {
    private val iPhoneSafari = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    private val iPhoneChrome = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1"
    private val iPhoneFacebook = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/470.0.0.0]"
    private val macSafari = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    private val androidChrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private val desktopChrome = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val published = PublishedVersion(versionCode = 40, versionName = "0.9.0")

    private fun state(
        userAgent: String,
        touchPoints: Int = 5,
        standalone: Boolean = false,
        androidAppInstalled: Boolean = false,
        published: PublishedVersion? = this.published,
        running: Int = 40,
    ) = WebInstallState(BrowserFacts(userAgent, touchPoints, standalone, androidAppInstalled), published, running)

    @Test
    fun safariOnIphoneAndIpadIsAskedToAddToTheHomeScreen() {
        assertEquals(WebHint.HomeScreen, webHint(state(iPhoneSafari), emptySet()))
        // iPadOS Safari reports itself as a Mac; a touch screen gives it away.
        assertEquals(WebHint.HomeScreen, webHint(state(macSafari, touchPoints = 5), emptySet()))
    }

    @Test
    fun noHomeScreenHintOnceOpenedFromTheHomeScreen() {
        assertNull(webHint(state(iPhoneSafari, standalone = true), emptySet()))
    }

    @Test
    fun otherBrowsersOnIphoneGetNoHint() {
        assertNull(webHint(state(iPhoneChrome), emptySet(), playPublic = true))
        assertNull(webHint(state(iPhoneFacebook), emptySet(), playPublic = true))
        assertFalse(isIosSafari(iPhoneChrome, 5))
        assertTrue(isIosSafari(iPhoneSafari, 5))
    }

    @Test
    fun appleDevicesAreNeverOfferedTheAndroidApp() {
        assertNull(webHint(state(iPhoneChrome, androidAppInstalled = false), emptySet(), playPublic = true))
        assertNull(webHint(state(iPhoneSafari, standalone = true), emptySet()))
    }

    @Test
    fun androidAndDesktopBrowsersAreOfferedTheAndroidApp() {
        assertEquals(WebHint.AndroidApp, webHint(state(androidChrome), emptySet(), playPublic = true))
        assertEquals(WebHint.AndroidApp, webHint(state(desktopChrome, touchPoints = 0), emptySet(), playPublic = true))
        // A Mac without a touch screen is a desktop.
        assertEquals(WebHint.AndroidApp, webHint(state(macSafari, touchPoints = 0), emptySet(), playPublic = true))
    }

    @Test
    fun noAndroidHintOnceTheAppIsInstalledOrBeforeItIsPublicOnPlay() {
        assertNull(webHint(state(androidChrome, androidAppInstalled = true), emptySet(), playPublic = true))
        assertNull(webHint(state(androidChrome), emptySet(), playPublic = false), "the closed test: Play has it only for testers")
    }

    @Test
    fun notNowHidesAHintOnlyForThisVisit() {
        assertNull(webHint(state(iPhoneSafari), setOf(WebHint.HomeScreen)))
        assertNull(webHint(state(androidChrome), setOf(WebHint.AndroidApp), playPublic = true))
        // The next visit starts with nothing dismissed.
        assertEquals(WebHint.AndroidApp, webHint(state(androidChrome), emptySet(), playPublic = true))
    }

    @Test
    fun aNewerDeployOffersAReloadFirst() {
        val stale = state(iPhoneSafari, running = 39)
        assertEquals(WebHint.NewVersion("0.9.0"), webHint(stale, emptySet()))
        // Dismissed, the install hint follows.
        assertEquals(WebHint.HomeScreen, webHint(stale, setOf(WebHint.NewVersion("0.9.0"))))
        // Also from the home screen, where there is no install hint.
        assertEquals(WebHint.NewVersion("0.9.0"), webHint(state(iPhoneSafari, standalone = true, running = 39), emptySet()))
    }

    @Test
    fun localBuildsAndCurrentVersionsAreNotOfferedAReload() {
        assertEquals(WebHint.AndroidApp, webHint(state(androidChrome, running = 0), emptySet(), playPublic = true))
        assertEquals(WebHint.AndroidApp, webHint(state(androidChrome, running = 40), emptySet(), playPublic = true))
        assertEquals(WebHint.AndroidApp, webHint(state(androidChrome, running = 41), emptySet(), playPublic = true))
    }
}
