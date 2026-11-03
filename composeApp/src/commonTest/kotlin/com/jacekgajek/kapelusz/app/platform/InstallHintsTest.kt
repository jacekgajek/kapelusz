package com.jacekgajek.kapelusz.app.platform

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val IPHONE_SAFARI =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
private const val IPHONE_CHROME =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1"
private const val IPHONE_FACEBOOK =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/470.0.0.0]"
private const val MAC_SAFARI =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
private const val ANDROID_CHROME =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_CHROME =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
private val PUBLISHED = PublishedVersion(versionCode = 40, versionName = "0.9.0")

private fun state(
    userAgent: String,
    touchPoints: Int = 5,
    standalone: Boolean = false,
    androidAppInstalled: Boolean = false,
    published: PublishedVersion? = PUBLISHED,
    running: Int = 40,
) = WebInstallState(BrowserFacts(userAgent, touchPoints, standalone, androidAppInstalled), published, running)

class InstallHintsTest :
    ExpectSpec({

        context("the home screen hint") {
            expect("safari on iPhone and iPad is asked to add to the home screen") {
                webHint(state(IPHONE_SAFARI), emptySet()) shouldBe WebHint.HomeScreen
                // iPadOS Safari reports itself as a Mac; a touch screen gives it away.
                webHint(state(MAC_SAFARI, touchPoints = 5), emptySet()) shouldBe WebHint.HomeScreen
            }

            expect("no hint once opened from the home screen") {
                webHint(state(IPHONE_SAFARI, standalone = true), emptySet()).shouldBeNull()
            }

            expect("other browsers on iPhone get no hint") {
                webHint(state(IPHONE_CHROME), emptySet()).shouldBeNull()
                webHint(state(IPHONE_FACEBOOK), emptySet()).shouldBeNull()
                isIosSafari(IPHONE_CHROME, 5) shouldBe false
                isIosSafari(IPHONE_SAFARI, 5) shouldBe true
            }
        }

        context("the Android app hint") {
            expect("Apple devices are never offered it") {
                webHint(state(IPHONE_CHROME, androidAppInstalled = false), emptySet()).shouldBeNull()
                webHint(state(IPHONE_SAFARI, standalone = true), emptySet()).shouldBeNull()
            }

            expect("Android and desktop browsers are offered it") {
                webHint(state(ANDROID_CHROME), emptySet()) shouldBe WebHint.AndroidApp
                webHint(state(DESKTOP_CHROME, touchPoints = 0), emptySet()) shouldBe WebHint.AndroidApp
                // A Mac without a touch screen is a desktop.
                webHint(state(MAC_SAFARI, touchPoints = 0), emptySet()) shouldBe WebHint.AndroidApp
            }

            expect("no hint once the app is installed") {
                webHint(state(ANDROID_CHROME, androidAppInstalled = true), emptySet()).shouldBeNull()
            }
        }

        expect("not now hides a hint only for this visit") {
            webHint(state(IPHONE_SAFARI), setOf(WebHint.HomeScreen)).shouldBeNull()
            webHint(state(ANDROID_CHROME), setOf(WebHint.AndroidApp)).shouldBeNull()
            // The next visit starts with nothing dismissed.
            webHint(state(ANDROID_CHROME), emptySet()) shouldBe WebHint.AndroidApp
        }

        context("the new version hint") {
            expect("a newer deploy offers a reload first") {
                val stale = state(IPHONE_SAFARI, running = 39)
                webHint(stale, emptySet()) shouldBe WebHint.NewVersion("0.9.0")
                // Dismissed, the install hint follows.
                webHint(stale, setOf(WebHint.NewVersion("0.9.0"))) shouldBe WebHint.HomeScreen
                // Also from the home screen, where there is no install hint.
                webHint(state(IPHONE_SAFARI, standalone = true, running = 39), emptySet()) shouldBe WebHint.NewVersion("0.9.0")
            }

            expect("local builds and current versions are not offered a reload") {
                webHint(state(ANDROID_CHROME, running = 0), emptySet()) shouldBe WebHint.AndroidApp
                webHint(state(ANDROID_CHROME, running = 40), emptySet()) shouldBe WebHint.AndroidApp
                webHint(state(ANDROID_CHROME, running = 41), emptySet()) shouldBe WebHint.AndroidApp
            }
        }
    })
