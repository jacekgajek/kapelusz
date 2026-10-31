package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.SystemUpdate
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
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.app_banner_download
import com.jacekgajek.kapelusz.app.generated.app_banner_text
import com.jacekgajek.kapelusz.app.generated.app_update_download
import com.jacekgajek.kapelusz.app.generated.app_update_text
import com.jacekgajek.kapelusz.app.generated.install_home_screen_text
import com.jacekgajek.kapelusz.app.generated.install_not_now
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.platform.PLAY_INSTALL
import com.jacekgajek.kapelusz.app.platform.WebHint
import com.jacekgajek.kapelusz.app.platform.WebInstallState
import com.jacekgajek.kapelusz.app.platform.webHint
import org.jetbrains.compose.resources.stringResource

/** Hints dismissed with "Not now" since the event list last opened; kept in memory only (ITERATION_3 §2.3). */
private var dismissedThisVisit by mutableStateOf(emptySet<WebHint>())

/**
 * The web's strip: a newer deploy to reload into, the Android app, or "Add to Home Screen" on iPhones. It comes back
 * every time the event list opens ([eventList]) until installed. Nothing in the Android app.
 */
@Composable
internal fun WebInstallHint(eventList: Boolean, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    var state by remember { mutableStateOf<WebInstallState?>(null) }
    LaunchedEffect(eventList) {
        if (eventList) dismissedThisVisit = emptySet()
        if (eventList || state == null) state = platform.webInstallState()
    }
    val hint = state?.let { webHint(it, dismissedThisVisit) } ?: return
    val uriHandler = LocalUriHandler.current
    val reload = platform.reload
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (hint) {
                is WebHint.NewVersion -> Icons.Default.SystemUpdate
                WebHint.AndroidApp -> Icons.Default.Android
                WebHint.HomeScreen -> Icons.Default.IosShare
            }
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val text = when (hint) {
                is WebHint.NewVersion -> stringResource(Res.string.app_update_text, hint.versionName)
                WebHint.AndroidApp -> stringResource(Res.string.app_banner_text)
                WebHint.HomeScreen -> stringResource(Res.string.install_home_screen_text)
            }
            Text(
                text,
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (hint) {
                is WebHint.NewVersion -> if (reload != null) TextButton(onClick = reload) { Text(stringResource(Res.string.app_update_download)) }

                // From Google Play only (ITERATION_7 §1.1).
                WebHint.AndroidApp -> TextButton(onClick = { uriHandler.openUri(PLAY_INSTALL) }) { Text(stringResource(Res.string.app_banner_download)) }

                WebHint.HomeScreen -> Unit
            }
            IconButton(onClick = { dismissedThisVisit = dismissedThisVisit + hint }) {
                Icon(Icons.Default.Close, stringResource(Res.string.install_not_now))
            }
        }
    }
}
