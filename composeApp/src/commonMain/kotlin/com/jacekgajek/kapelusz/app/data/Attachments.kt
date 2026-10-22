package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.Rejection
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Attaching files to costs, removing and opening them (moved out of [AppGraph], ITERATION_3 R12). */
class Attachments(
    private val bands: BandRepository,
    private val uploads: AttachmentUploads,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
    private val dispatch: (Operation) -> Rejection?,
) {
    /**
     * Attaches a prepared file to a cost: the attachment is added like any change, and the file waits on the device
     * until the sync has uploaded it.
     */
    suspend fun attach(costId: Id, file: PreparedFile): Rejection? {
        val bandId = bands.currentBandId ?: return null
        val attachment = Attachment(newId(), file.kind, file.fileName, file.sizeBytes)
        // The file first, then the attachment (ITERATION_3 R4).
        try {
            try {
                uploads.enqueue(PendingUpload(bandId, costId, attachment.id), file.base64)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Rejection(RejectionCode.CONFLICT, "Could not store the file on this device: $e", RejectionReason.STORAGE_FAILED)
            }
            // Another band opened meanwhile (the file was being stored): this attachment belongs to the first one.
            val rejection = if (bands.currentBandId == bandId) dispatch(AddAttachment(costId, attachment))
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
    fun attachAfterSave(costId: Id, files: List<PreparedFile>) {
        if (files.isNotEmpty()) scope.launch { files.forEach { attach(costId, it) } }
    }

    fun remove(costId: Id, attachmentId: Id): Rejection? {
        val rejection = dispatch(RemoveAttachment(costId, attachmentId))
        if (rejection == null) scope.launch { uploads.cancel(attachmentId) }
        return rejection
    }

    /** A URL to open an attachment; fails with [ApiException] `attachment_not_uploaded` while its file is not on the server. */
    suspend fun url(attachmentId: Id): Result<String> {
        val bandId = bands.currentBandId ?: return Result.failure(IllegalStateException("No band open"))
        return runSuspendCatching { api.attachmentUrl(bandId, attachmentId) }
    }
}
