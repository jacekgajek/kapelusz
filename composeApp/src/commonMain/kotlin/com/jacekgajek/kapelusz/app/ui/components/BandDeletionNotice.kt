package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_deletion_keep
import com.jacekgajek.kapelusz.app.generated.band_deletion_notice
import com.jacekgajek.kapelusz.app.generated.band_deletion_notice_leader
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.model.Band
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * The band is to be deleted (I7 §9): who asked and when it goes, for everyone; with [onKeep] (leaders), the
 * button that keeps it. Nothing while it isn't.
 */
@Composable
fun BandDeletionNotice(band: Band, onKeep: (() -> Unit)?, modifier: Modifier = Modifier) {
    val deletion = band.deletion ?: return
    val by = band.member(deletion.requestedBy)?.name.orEmpty()
    val on = formatDate(Instant.fromEpochMilliseconds(deletion.deleteAt).toLocalDateTime(TimeZone.currentSystemDefault()).date, language)
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Row(Modifier.padding(16.dp, 12.dp, 16.dp, if (onKeep != null) 4.dp else 12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.DeleteForever, null, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (onKeep != null) Res.string.band_deletion_notice_leader else Res.string.band_deletion_notice, by, on))
                if (onKeep != null) {
                    TextButton(onClick = onKeep, modifier = Modifier.align(Alignment.End)) { Text(stringResource(Res.string.band_deletion_keep)) }
                }
            }
        }
    }
}
