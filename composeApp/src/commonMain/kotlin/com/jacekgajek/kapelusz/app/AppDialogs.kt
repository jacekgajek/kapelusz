package com.jacekgajek.kapelusz.app

import com.jacekgajek.kapelusz.app.sync.SyncEngine
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_was_deleted
import com.jacekgajek.kapelusz.app.generated.lost_access
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.sync.LostBand
import org.jetbrains.compose.resources.stringResource

/** A dialog, not a snackbar: losing the open band leaves its screens, so they cannot show the message. */
@Composable
internal fun LostAccessDialog(sync: SyncEngine) {
    var lostBand by remember { mutableStateOf<LostBand?>(null) }
    LaunchedEffect(sync) { sync.lostAccess.collect { lostBand = it } }
    lostBand?.let { lost ->
        AlertDialog(
            onDismissRequest = { lostBand = null },
            text = { Text(stringResource(if (lost.deleted) Res.string.band_was_deleted else Res.string.lost_access, lost.name)) },
            confirmButton = { TextButton(onClick = { lostBand = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }
}
