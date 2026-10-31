package com.jacekgajek.kapelusz.app.platform

/** The app on Google Play, for links from the web too. */
const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.jacekgajek.kapelusz"

/** Joining the closed test: who opens it becomes a tester and can then install the app from Play. */
const val PLAY_TESTING = "https://play.google.com/apps/testing/com.jacekgajek.kapelusz"

/**
 * Whether anyone can install the app from [PLAY_LISTING]. False during the closed test, where the listing is "not
 * found" for everyone but the testers: the web then links to joining the test. Set to true at the production release.
 */
const val PLAY_PUBLIC = false

/** Where the web's "Get the app" goes: the listing, or joining the test until it is public. */
val PLAY_INSTALL = if (PLAY_PUBLIC) PLAY_LISTING else PLAY_TESTING
