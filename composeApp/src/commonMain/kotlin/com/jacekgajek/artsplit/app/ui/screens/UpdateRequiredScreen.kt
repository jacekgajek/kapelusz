package com.jacekgajek.artsplit.app.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.update_required_button
import com.jacekgajek.artsplit.app.generated.update_required_text
import com.jacekgajek.artsplit.app.generated.update_required_title
import com.jacekgajek.artsplit.app.generated.updates_checking
import com.jacekgajek.artsplit.app.generated.updates_downloading
import com.jacekgajek.artsplit.app.generated.updates_failed
import com.jacekgajek.artsplit.app.generated.updates_installing
import com.jacekgajek.artsplit.app.platform.UpdateStatus
import com.jacekgajek.artsplit.app.platform.appReloader
import com.jacekgajek.artsplit.app.platform.rememberUpdateControls
import com.jacekgajek.artsplit.app.ui.components.VersionLabel
import org.jetbrains.compose.resources.stringResource

/**
 * The server no longer serves this app version (ITERATION_2 §2). Nothing would sync, so the app says so plainly and
 * offers the update: a reload on the web, the automatic updater on Android (or the download if that cannot install).
 */
@Composable
fun UpdateRequiredScreen() {
    val updates = rememberUpdateControls()
    val reload = appReloader()
    val uriHandler = LocalUriHandler.current
    val status = updates?.status?.collectAsState()?.value
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.update_required_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.update_required_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                when {
                    reload != null -> reload()
                    // Without the install permission, or in a debug build, the browser download still works.
                    updates == null || status is UpdateStatus.Unavailable || status is UpdateStatus.NeedsInstallPermission ->
                        uriHandler.openUri("${AppConfig.WEB_URL}/download/art-split.apk")
                    status is UpdateStatus.NeedsConfirmation -> updates.confirm()
                    else -> updates.installNow()
                }
            }) { Text(stringResource(Res.string.update_required_button)) }
            // Android: where the update stands, so a tap visibly does something.
            when (status) {
                is UpdateStatus.Checking -> stringResource(Res.string.updates_checking)
                is UpdateStatus.Downloading -> stringResource(Res.string.updates_downloading, status.versionName)
                is UpdateStatus.Installing -> stringResource(Res.string.updates_installing, status.versionName)
                is UpdateStatus.Failed, is UpdateStatus.InstallFailed -> stringResource(Res.string.updates_failed)
                else -> null
            }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        VersionLabel(Modifier.align(Alignment.BottomEnd))
    }
}
