package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.platform.isAndroidApp

/**
 * The web's strip: a newer deploy to reload into, or the install hints ([WebInstallHint], shown again each time the
 * [eventList] opens). Nothing in the Android app, which Google Play updates (ITERATION_7 §1.1).
 */
@Composable
fun AppBanner(eventList: Boolean, modifier: Modifier = Modifier) {
    if (!isAndroidApp()) WebInstallHint(eventList, modifier)
}
