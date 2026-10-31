package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.app_update_download
import com.jacekgajek.kapelusz.app.generated.store_update_available
import com.jacekgajek.kapelusz.app.generated.store_update_downloading
import com.jacekgajek.kapelusz.app.generated.store_update_ready
import com.jacekgajek.kapelusz.app.generated.store_update_restart
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.platform.PlatformKind
import com.jacekgajek.kapelusz.app.platform.StoreUpdateState
import com.jacekgajek.kapelusz.app.platform.StoreUpdates
import org.jetbrains.compose.resources.stringResource

/**
 * A quiet strip above the bottom bar: on the web the install hints ([WebInstallHint], shown again each time the
 * [eventList] opens); in the Android app a newer version on Google Play, downloaded and installed from here.
 */
@Composable
fun AppBanner(eventList: Boolean, modifier: Modifier = Modifier) {
    if (LocalPlatform.current.kind == PlatformKind.WEB) {
        WebInstallHint(eventList, modifier)
        return
    }
    LocalPlatform.current.rememberStoreUpdates()?.let { StoreUpdateStrip(it, modifier) }
}

@Composable
private fun StoreUpdateStrip(updates: StoreUpdates, modifier: Modifier) {
    val state by updates.state.collectAsState()
    val (text, action) = when (state) {
        StoreUpdateState.NONE -> return
        StoreUpdateState.AVAILABLE -> stringResource(Res.string.store_update_available) to (Res.string.app_update_download to updates::download)
        StoreUpdateState.DOWNLOADING -> stringResource(Res.string.store_update_downloading) to null
        StoreUpdateState.DOWNLOADED -> stringResource(Res.string.store_update_ready) to (Res.string.store_update_restart to updates::install)
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text,
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(stringResource(label)) } }
        }
    }
}
