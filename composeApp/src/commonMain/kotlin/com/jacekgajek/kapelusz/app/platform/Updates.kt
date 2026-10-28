package com.jacekgajek.kapelusz.app.platform

/** Reloads the app from the server (the web's way to update); null where updating works differently (Android). */
expect fun appReloader(): (() -> Unit)?

/** True in the Android app, false on the web. */
expect fun isAndroidApp(): Boolean

/**
 * The Android app's page on Google Play, which updates it and is the only place it comes from (ITERATION_7 §1.1, Play
 * only since 2026-10-27); null on the web.
 */
expect fun storeListing(): String?

/** The app on Google Play, for links from the web too. */
const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.jacekgajek.kapelusz"

/** Joining the closed test: who opens it becomes a tester and can then install the app from Play. */
const val PLAY_TESTING = "https://play.google.com/apps/testing/com.jacekgajek.kapelusz"

/**
 * Whether anyone can install the app from [PLAY_LISTING]. False during the closed test, where the listing is "not
 * found" for everyone but the testers: the web then links to joining the test. Set to true at the production release
 * (ITERATION_7 §1.5).
 */
const val PLAY_PUBLIC = false

/** Where the web's "Get the app" goes: the listing, or joining the test until it is public. */
val PLAY_INSTALL = if (PLAY_PUBLIC) PLAY_LISTING else PLAY_TESTING
