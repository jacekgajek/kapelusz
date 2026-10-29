package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.update_required_button
import com.jacekgajek.kapelusz.app.generated.update_required_text
import com.jacekgajek.kapelusz.app.generated.update_required_title
import com.jacekgajek.kapelusz.app.platform.appReloader
import com.jacekgajek.kapelusz.app.platform.storeListing
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.jacekgajek.kapelusz.app.platform.rememberStoreUpdates
import com.jacekgajek.kapelusz.app.ui.components.VersionLabel
import org.jetbrains.compose.resources.stringResource

/**
 * The server no longer serves this app version (ITERATION_2 §2). Nothing would sync, so the app says so plainly and
 * offers the update: a reload on the web, the automatic updater on Android (or the download if that cannot install).
 */
@Composable
fun UpdateRequiredScreen() {
    val reload = appReloader()
    val store = storeListing()
    val storeUpdates = rememberStoreUpdates()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // Clear of the system bars (edge to edge on Android 15+).
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.update_required_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.update_required_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                // The web reloads into the deployed version; Android updates from Google Play (ITERATION_7 §1.1): its
                // full-screen update when Play offers one here (§4), else the listing.
                when {
                    reload != null -> reload()
                    store != null -> scope.launch { if (storeUpdates?.updateNow() != true) uriHandler.openUri(store) }
                }
            }) { Text(stringResource(Res.string.update_required_button)) }
        }
        VersionLabel(Modifier.align(Alignment.BottomEnd))
    }
}
