package com.jacekgajek.kapelusz.app.platform

import kotlinx.coroutines.flow.StateFlow

/** Where an update from Google Play stands (I7 §4). */
enum class StoreUpdateState {
    /** Nothing newer on Play for this phone (or not installed from Play, e.g. a debug build). */
    NONE,

    /** A newer version is on Play: the app offers to download it. */
    AVAILABLE,

    /** Downloading in the background; the app keeps working. */
    DOWNLOADING,

    /** Downloaded: a restart installs it. */
    DOWNLOADED,
}

/**
 * Google Play's in-app updates (I7 §4): the app learns that Play has a newer version, downloads it in the
 * background on a tap, and installs it with a restart. Only for apps installed from Play; none on the web.
 */
interface StoreUpdates {
    val state: StateFlow<StoreUpdateState>

    /** Asks Play again, e.g. when the app comes to the screen. */
    fun check()

    /** Downloads the update in the background (Play's "flexible" update). */
    fun download()

    /** Installs the downloaded update; the app restarts. */
    fun install()

    /** Play's full-screen update, for an app too old for the server; false when Play has none to offer. */
    suspend fun updateNow(): Boolean
}
