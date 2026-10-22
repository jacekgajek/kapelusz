package com.jacekgajek.splitart.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.splitart.app.data.AppGraph
import com.jacekgajek.splitart.app.generated.Res
import com.jacekgajek.splitart.app.generated.attachment_add_file
import com.jacekgajek.splitart.app.generated.attachment_failed
import com.jacekgajek.splitart.app.generated.attachment_hint
import com.jacekgajek.splitart.app.generated.attachment_not_available
import com.jacekgajek.splitart.app.generated.attachment_open_failed
import com.jacekgajek.splitart.app.generated.attachment_remove_confirm
import com.jacekgajek.splitart.app.generated.attachment_remove_title
import com.jacekgajek.splitart.app.generated.attachment_take_photo
import com.jacekgajek.splitart.app.generated.attachment_too_large
import com.jacekgajek.splitart.app.generated.attachment_unsupported
import com.jacekgajek.splitart.app.generated.attachment_uploading
import com.jacekgajek.splitart.app.generated.attachment_waiting_for_save
import com.jacekgajek.splitart.app.generated.attachments
import com.jacekgajek.splitart.app.generated.remove
import com.jacekgajek.splitart.api.ApiErrors
import com.jacekgajek.splitart.app.platform.PickResult
import com.jacekgajek.splitart.app.sync.ApiException
import com.jacekgajek.splitart.app.platform.PreparedFile
import com.jacekgajek.splitart.app.platform.rememberAttachmentPicker
import com.jacekgajek.splitart.app.platform.rememberUrlOpener
import com.jacekgajek.splitart.app.ui.components.Actions
import com.jacekgajek.splitart.app.ui.components.ConfirmDialog
import com.jacekgajek.splitart.app.ui.components.RemoveButton
import com.jacekgajek.splitart.model.Attachment
import com.jacekgajek.splitart.model.AttachmentKind
import com.jacekgajek.splitart.model.MAX_ATTACHMENTS
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Photos and PDFs of a cost. For a saved cost ([costId] set) a picked file is attached right away; for a new cost
 * the files wait in [drafts] until the cost is saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentsSection(
    graph: AppGraph,
    costId: String?,
    attachments: List<Attachment>,
    drafts: List<PreparedFile>,
    onDrafts: (List<PreparedFile>) -> Unit,
    editable: Boolean,
    actions: Actions,
) {
    val scope = rememberCoroutineScope()
    val open = rememberUrlOpener()
    val pending by graph.uploads.pending.collectAsState()
    var removing by remember { mutableStateOf<Attachment?>(null) }
    val tooLarge = stringResource(Res.string.attachment_too_large)
    val unsupported = stringResource(Res.string.attachment_unsupported)
    val failed = stringResource(Res.string.attachment_failed)
    val notAvailable = stringResource(Res.string.attachment_not_available)
    val openFailed = stringResource(Res.string.attachment_open_failed)
    val picker = rememberAttachmentPicker { result ->
        when (result) {
            is PickResult.Picked -> if (costId == null) onDrafts(drafts + result.file) else scope.launch { actions.show(graph.attach(costId, result.file)) }
            PickResult.TooLarge -> actions.say(tooLarge)
            PickResult.Unsupported -> actions.say(unsupported)
            PickResult.Failed -> actions.say(failed)
        }
    }
    val count = attachments.size + drafts.size

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.attachments), style = MaterialTheme.typography.labelLarge)
        attachments.forEach { attachment ->
            val uploading = pending.any { it.attachmentId == attachment.id }
            AttachmentRow(
                attachment.kind,
                attachment.fileName ?: attachment.id,
                attachment.sizeBytes,
                status = if (uploading) stringResource(Res.string.attachment_uploading) else null,
                onOpen = {
                    if (uploading) actions.say(notAvailable)
                    else open {
                        graph.attachmentUrl(attachment.id).getOrElse { error ->
                            val notUploaded = (error as? ApiException)?.code == ApiErrors.ATTACHMENT_NOT_UPLOADED
                            actions.say(if (notUploaded) notAvailable else openFailed)
                            null
                        }
                    }
                },
                onRemove = { removing = attachment }.takeIf { editable },
            )
        }
        drafts.forEach { draft ->
            AttachmentRow(
                draft.kind, draft.fileName, draft.sizeBytes,
                status = stringResource(Res.string.attachment_waiting_for_save),
                onOpen = null,
                onRemove = { onDrafts(drafts - draft) }.takeIf { editable },
            )
        }
        if (editable && count < MAX_ATTACHMENTS) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = picker::pickFile) {
                    Icon(Icons.Default.AttachFile, null)
                    Text(stringResource(Res.string.attachment_add_file), Modifier.padding(start = 8.dp))
                }
                picker.takePhoto?.let { takePhoto ->
                    OutlinedButton(onClick = takePhoto) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Text(stringResource(Res.string.attachment_take_photo), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Text(stringResource(Res.string.attachment_hint, MAX_ATTACHMENTS), style = MaterialTheme.typography.bodySmall)
        }
    }

    removing?.let { attachment ->
        ConfirmDialog(
            title = stringResource(Res.string.attachment_remove_title),
            text = stringResource(Res.string.attachment_remove_confirm, attachment.fileName ?: attachment.id),
            confirmLabel = stringResource(Res.string.remove),
            onConfirm = { costId?.let { actions.show(graph.removeAttachment(it, attachment.id)) } },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun AttachmentRow(kind: AttachmentKind, name: String, size: Long?, status: String?, onOpen: (() -> Unit)?, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (kind == AttachmentKind.PDF) Icons.Default.PictureAsPdf else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, maxLines = 1)
            Text(listOfNotNull(size?.let(::formatSize), status).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        if (onRemove != null) RemoveButton(name, onRemove)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    else -> "${(bytes + 1023) / 1024} KB"
}
