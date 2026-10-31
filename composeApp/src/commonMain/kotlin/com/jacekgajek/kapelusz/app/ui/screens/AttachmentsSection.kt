package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.app.attachments.Attachments
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.attachment_add_file
import com.jacekgajek.kapelusz.app.generated.attachment_failed
import com.jacekgajek.kapelusz.app.generated.attachment_file_name
import com.jacekgajek.kapelusz.app.generated.attachment_hint
import com.jacekgajek.kapelusz.app.generated.attachment_limit_partial
import com.jacekgajek.kapelusz.app.generated.attachment_not_available
import com.jacekgajek.kapelusz.app.generated.attachment_open_failed
import com.jacekgajek.kapelusz.app.generated.attachment_remove_confirm
import com.jacekgajek.kapelusz.app.generated.attachment_remove_title
import com.jacekgajek.kapelusz.app.generated.attachment_removed
import com.jacekgajek.kapelusz.app.generated.attachment_rename
import com.jacekgajek.kapelusz.app.generated.attachment_take_photo
import com.jacekgajek.kapelusz.app.generated.attachment_too_large
import com.jacekgajek.kapelusz.app.generated.attachment_unsupported
import com.jacekgajek.kapelusz.app.generated.attachment_uploading
import com.jacekgajek.kapelusz.app.generated.attachment_waiting_for_save
import com.jacekgajek.kapelusz.app.generated.attachments
import com.jacekgajek.kapelusz.app.generated.remove
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.sync.ApiException
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.removeLabel
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.MAX_ATTACHMENTS
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Photos and PDFs of a cost, a collected entry or an event (I4 §2.1). For a saved one ([owner] set) a picked
 * file is attached right away; for a new one the files wait in [drafts] until it is saved.
 */
@Composable
fun AttachmentsSection(
    fileStore: Attachments,
    owner: AttachmentOwner?,
    attachments: List<Attachment>,
    drafts: List<PreparedFile>,
    onDrafts: (List<PreparedFile>) -> Unit,
    editable: Boolean,
    actions: Actions,
    /** Saved files may be removed; [editable] alone adds and renames (a locked cost's receipt, I7 §3.3). */
    removable: Boolean = editable,
    max: Int = MAX_ATTACHMENTS,
    hint: StringResource = Res.string.attachment_hint,
    title: String? = stringResource(Res.string.attachments),
    /** Removing applies at once with Undo in the snackbar (I5 §2); not inside a dialog, which would hide it. */
    undoRemoval: Boolean = false,
) {
    // Inside a dialog, the dialog's [DialogNotice] shows these; the screen's snackbar would be hidden behind it.
    val say: (UiMessage) -> Unit = actions::say
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val open = platform.rememberUrlOpener()
    // PDFs show in the app's viewer where the platform renders them; elsewhere they open in the browser.
    val pdfInViewer = platform.pdf != null
    val pending by fileStore.pending.collectAsState()
    var removing by remember { mutableStateOf<Attachment?>(null) }
    var renaming by remember { mutableStateOf<Attachment?>(null) }
    val count = attachments.size + drafts.size
    val picker = platform.rememberAttachmentPicker { results ->
        // As many as there is room for, in the order chosen; the rest is said, not silently dropped.
        val files = results.filterIsInstance<PickResult.Picked>().map { it.file }
        val taken = files.take((max - count).coerceAtLeast(0))
        if (owner == null) onDrafts(drafts + taken) else scope.launch { taken.forEach { actions.show(fileStore.attach(owner, it)) } }
        val problem = results.firstOrNull { it !is PickResult.Picked }
        when {
            taken.size < files.size -> say(UiMessage.Text(Res.string.attachment_limit_partial, listOf(taken.size, files.size, max)))
            problem == PickResult.TooLarge -> say(UiMessage.Text(Res.string.attachment_too_large))
            problem == PickResult.Unsupported -> say(UiMessage.Text(Res.string.attachment_unsupported))
            problem == PickResult.Failed -> say(UiMessage.Text(Res.string.attachment_failed))
        }
    }
    var viewing by remember { mutableStateOf<Int?>(null) }
    // At once with Undo where the screen shows the snackbar, else asked first (in a dialog, which would hide it).
    val remove = { attachment: Attachment ->
        if (!undoRemoval) {
            removing = attachment
        } else if (owner != null) {
            val removed = UiMessage.Text(Res.string.attachment_removed, listOf(attachment.fileName ?: attachment.id))
            actions.runWithUndo(RemoveAttachment(owner, attachment.id), removed)
        }
    }
    val openInBrowser = { attachment: Attachment ->
        open {
            fileStore.url(attachment.id).getOrElse { error ->
                val notUploaded = (error as? ApiException)?.code == ApiErrors.ATTACHMENT_NOT_UPLOADED
                say(UiMessage.Text(if (notUploaded) Res.string.attachment_not_available else Res.string.attachment_open_failed))
                null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        // Images as thumbnails, PDFs as rows (I5 §4.2); tapping one opens the viewer on it, swiping through
        // the rest. On the web the viewer has the images only: PDFs open in the browser, as before.
        val viewable = attachments.filter { it.kind == AttachmentKind.IMAGE || pdfInViewer }
        val images = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val imageDrafts = drafts.filter { it.kind == AttachmentKind.IMAGE }
        if (images.isNotEmpty() || imageDrafts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { attachment ->
                    key(attachment.id) {
                        // Removed by its X in the corner, as the photos not saved yet (opening it first was a detour).
                        Box {
                            AttachmentThumbnail(fileStore, attachment, THUMBNAIL) { viewing = viewable.indexOf(attachment) }
                            if (removable) ThumbnailRemoveButton(attachment.fileName ?: attachment.id, Modifier.align(Alignment.TopEnd)) { remove(attachment) }
                        }
                    }
                }
                imageDrafts.forEach { draft -> key(draft) { DraftThumbnail(draft, onRemove = { onDrafts(drafts - draft) }.takeIf { editable }) } }
            }
        }
        attachments.filter { it.kind == AttachmentKind.PDF }.forEach { attachment ->
            val uploading = pending.any { it.attachmentId == attachment.id }
            AttachmentRow(
                attachment.kind,
                attachment.fileName ?: attachment.id,
                attachment.sizeBytes,
                status = if (uploading) stringResource(Res.string.attachment_uploading) else null,
                onOpen = { if (pdfInViewer) viewing = viewable.indexOf(attachment) else openInBrowser(attachment) },
                onRemove = { remove(attachment) }.takeIf { removable },
                onRename = { renaming = attachment }.takeIf { editable },
            )
        }
        drafts.filter { it.kind == AttachmentKind.PDF }.forEach { draft ->
            AttachmentRow(
                draft.kind,
                draft.fileName,
                draft.sizeBytes,
                status = stringResource(Res.string.attachment_waiting_for_save),
                onOpen = null,
                onRemove = { onDrafts(drafts - draft) }.takeIf { editable },
            )
        }
        if (editable && count < max) {
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
            Text(stringResource(hint, max), style = MaterialTheme.typography.bodySmall)
        }
    }

    viewing?.let { start ->
        val viewable = attachments.filter { it.kind == AttachmentKind.IMAGE || pdfInViewer }
        AttachmentViewer(
            fileStore,
            viewable,
            start,
            onRename = { attachment: Attachment -> renaming = attachment }.takeIf { editable },
            onRemove = { attachment: Attachment -> remove(attachment) }.takeIf { removable },
            onOpenElsewhere = openInBrowser,
            onDismiss = { viewing = null },
        )
    }
    removing?.let { attachment ->
        ConfirmDialog(
            title = stringResource(Res.string.attachment_remove_title),
            text = stringResource(Res.string.attachment_remove_confirm, attachment.fileName ?: attachment.id),
            confirmLabel = stringResource(Res.string.remove),
            onConfirm = { owner?.let { actions.show(fileStore.remove(it, attachment.id)) } },
            onDismiss = { removing = null },
        )
    }
    renaming?.let { attachment ->
        TextInputDialog(
            title = stringResource(Res.string.attachment_rename),
            label = stringResource(Res.string.attachment_file_name),
            initial = attachment.fileName.orEmpty(),
            onConfirm = { name -> if (name != attachment.fileName) owner?.let { actions.show(fileStore.rename(it, attachment.id, name)) } },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun AttachmentRow(
    kind: AttachmentKind,
    name: String,
    size: Long?,
    status: String?,
    onOpen: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (kind == AttachmentKind.PDF) Icons.Default.PictureAsPdf else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, maxLines = 1)
            Text(listOfNotNull(size?.let(::formatSize), status).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        if (onRename != null) IconButton(onClick = onRename) { Icon(Icons.Default.Edit, stringResource(Res.string.attachment_rename) + ": " + name) }
        if (onRemove != null) RemoveButton(name, onRemove)
    }
}

/** A photo picked for something not saved yet, from its local copy; the cross takes it off again. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
internal fun DraftThumbnail(draft: PreparedFile, onRemove: (() -> Unit)?, size: Dp = THUMBNAIL) {
    val bytes = remember(draft) { runCatching { Base64.decode(draft.base64) }.getOrNull() }
    Box {
        ThumbnailBox(size, onClick = null, name = draft.fileName) {
            // Keyed by the content: another photo with the same name and size must not show this one.
            if (bytes != null) {
                BytesImage(bytes, "draft-${draft.base64.hashCode()}-${draft.sizeBytes}", ContentScale.Crop, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onRemove != null) ThumbnailRemoveButton(draft.fileName, Modifier.align(Alignment.TopEnd), onRemove)
    }
}

/** The X in a thumbnail's top-right corner, on a round backing so it shows on any photo. */
@Composable
private fun ThumbnailRemoveButton(name: String, modifier: Modifier, onRemove: () -> Unit) {
    IconButton(onClick = onRemove, modifier = modifier.size(32.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
            Icon(Icons.Default.Close, removeLabel(name), Modifier.padding(2.dp).size(18.dp))
        }
    }
}

private val THUMBNAIL = 96.dp

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    else -> "${(bytes + 1023) / 1024} KB"
}
