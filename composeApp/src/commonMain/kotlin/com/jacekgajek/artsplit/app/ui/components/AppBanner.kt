package com.jacekgajek.artsplit.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.app_banner_dismiss
import com.jacekgajek.artsplit.app.generated.app_banner_download
import com.jacekgajek.artsplit.app.generated.app_banner_text
import com.jacekgajek.artsplit.app.generated.app_update_download
import com.jacekgajek.artsplit.app.generated.app_update_text
import com.jacekgajek.artsplit.app.platform.AppDownload
import com.jacekgajek.artsplit.app.platform.UpdateControls
import com.jacekgajek.artsplit.app.platform.UpdateStatus
import com.jacekgajek.artsplit.app.platform.rememberUpdateControls
import com.jacekgajek.artsplit.app.generated.updates_needs_confirmation
import com.jacekgajek.artsplit.app.generated.updates_needs_permission
import com.jacekgajek.artsplit.app.generated.updates_install
import com.jacekgajek.artsplit.app.generated.updates_allow
import androidx.compose.runtime.collectAsState
import com.jacekgajek.artsplit.app.platform.appDownload
import com.jacekgajek.artsplit.app.platform.dismissAppDownload
import org.jetbrains.compose.resources.stringResource

/**
 * A quiet strip suggesting the Android app on the website, or a newer version in the app; nothing is shown where it
 * does not apply.
 */
@Composable
fun AndroidAppBanner(autoUpdate: Boolean, modifier: Modifier = Modifier) {
    // With automatic updates the app updates itself; the strip only asks for what Android needs from the user.
    val updates = rememberUpdateControls()
    if (updates != null && autoUpdate) {
        UpdateStrip(updates, modifier)
        return
    }
    var download by remember { mutableStateOf<AppDownload?>(null) }
    LaunchedEffect(Unit) { download = appDownload() }
    val current = download ?: return
    val uriHandler = LocalUriHandler.current
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (current.isUpdate) stringResource(Res.string.app_update_text, current.versionName) else stringResource(Res.string.app_banner_text),
                Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(current.url) }) {
                Text(stringResource(if (current.isUpdate) Res.string.app_update_download else Res.string.app_banner_download))
            }
            IconButton(onClick = {
                dismissAppDownload(current)
                download = null
            }) { Icon(Icons.Default.Close, stringResource(Res.string.app_banner_dismiss)) }
        }
    }
}

@Composable
private fun UpdateStrip(controls: UpdateControls, modifier: Modifier) {
    val status by controls.status.collectAsState()
    val (text, button, action) = when (val s = status) {
        is UpdateStatus.NeedsConfirmation -> Triple(stringResource(Res.string.updates_needs_confirmation, s.versionName), Res.string.updates_install, controls::confirm)
        is UpdateStatus.NeedsInstallPermission -> Triple(stringResource(Res.string.updates_needs_permission, s.versionName), Res.string.updates_allow, controls::openInstallPermission)
        else -> return
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = action) { Text(stringResource(button)) }
        }
    }
}
