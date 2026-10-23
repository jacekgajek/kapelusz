package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.Rejection
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RenameAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Attaching files to costs, collected entries and events (ITERATION_4 §2.1), renaming, removing and opening them (moved
 * out of [AppGraph], ITERATION_3 R12).
 */
class Attachments(
    private val bands: BandRepository,
    private val uploads: AttachmentUploads,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
    private val dispatch: (Operation) -> Rejection?,
) {
    /**
     * Attaches a prepared file to [owner]: the attachment is added like any change, and the file waits on the device
     * until the sync has uploaded it.
     */
    suspend fun attach(owner: AttachmentOwner, file: PreparedFile): Rejection? {
        val bandId = bands.currentBandId ?: return null
        val attachment = Attachment(newId(), file.kind, file.fileName, file.sizeBytes)
        // The file first, then the attachment (ITERATION_3 R4).
        try {
            try {
                uploads.enqueue(PendingUpload(bandId, attachment.id), file.base64)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Rejection(RejectionCode.CONFLICT, "Could not store the file on this device: $e", RejectionReason.STORAGE_FAILED)
            }
            // Another band opened meanwhile (the file was being stored): this attachment belongs to the first one.
            val rejection = if (bands.currentBandId == bandId) dispatch(AddAttachment(owner, attachment))
            else Rejection(RejectionCode.CONFLICT, "Another band was opened", RejectionReason.NOT_FOUND)
            if (rejection != null) {
                uploads.cancel(attachment.id)
                return rejection
            }
            sync.requestSync()
            return null
        } finally {
            uploads.published(attachment.id)
        }
    }

    /** Attaches the files picked while adding [costId]; runs in the app scope because the form closes on save. */
    fun attachAfterSave(costId: Id, files: List<PreparedFile>) = attachAfterSave(AttachmentOwner.OfCost(costId), files)

    /** Attaches the files picked while adding what [owner] names (a cost, a collected entry). */
    fun attachAfterSave(owner: AttachmentOwner, files: List<PreparedFile>) {
        if (files.isNotEmpty()) scope.launch { files.forEach { attach(owner, it) } }
    }

    /**
     * Removes an attachment. Its waiting file stays until the server agrees (V5-M1 A8): if it refuses the removal, the
     * attachment stays and still needs that file; once the attachment is gone there, the upload queue drops it.
     */
    fun remove(owner: AttachmentOwner, attachmentId: Id): Rejection? = dispatch(RemoveAttachment(owner, attachmentId))

    fun rename(owner: AttachmentOwner, attachmentId: Id, fileName: String): Rejection? = dispatch(RenameAttachment(owner, attachmentId, fileName))

    /** A URL to open an attachment; fails with [ApiException] `attachment_not_uploaded` while its file is not on the server. */
    suspend fun url(attachmentId: Id): Result<String> {
        val bandId = bands.currentBandId ?: return Result.failure(IllegalStateException("No band open"))
        return runSuspendCatching { api.attachmentUrl(bandId, attachmentId) }
    }
}
