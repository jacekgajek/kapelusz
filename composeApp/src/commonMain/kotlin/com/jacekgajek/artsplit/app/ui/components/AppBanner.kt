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
import com.jacekgajek.artsplit.app.platform.androidAppDownloadUrl
import com.jacekgajek.artsplit.app.platform.dismissAndroidAppSuggestion
import org.jetbrains.compose.resources.stringResource

/** A quiet strip on the website suggesting the Android app; nothing is shown where it does not apply. */
@Composable
fun AndroidAppBanner(modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { url = androidAppDownloadUrl() }
    val link = url ?: return
    val uriHandler = LocalUriHandler.current
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(Res.string.app_banner_text),
                Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(link) }) { Text(stringResource(Res.string.app_banner_download)) }
            IconButton(onClick = {
                dismissAndroidAppSuggestion()
                url = null
            }) { Icon(Icons.Default.Close, stringResource(Res.string.app_banner_dismiss)) }
        }
    }
}
