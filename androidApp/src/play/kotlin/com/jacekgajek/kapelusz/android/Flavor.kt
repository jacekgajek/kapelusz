package com.jacekgajek.kapelusz.android

import android.content.Context

/** The Google Play build (ITERATION_7 §1.1): Play updates it, so there is nothing to set up. */
internal object Flavor {
    fun install() {}

    @Suppress("UNUSED_PARAMETER")
    fun onStart(context: Context) {}

    fun onStop() {}
}
