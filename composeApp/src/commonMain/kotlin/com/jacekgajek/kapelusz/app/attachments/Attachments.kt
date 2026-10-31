package com.jacekgajek.kapelusz.app.attachments

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.platform.ByteCache
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RenameAttachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Attaching files to costs, collected entries and events (ITERATION_4 §2.1), renaming, removing and opening them (moved
 * out of [AppRuntime], ITERATION_3 R12).
 */
class Attachments(
    private val bands: BandRepository,
    private val uploads: AttachmentUploads,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
    private val dispatch: (Operation) -> Rejection?,
    /** Attachment files kept on the device (the platform's). */
    private val cache: ByteCache,
    /** Downloads a file from a presigned URL (no app credentials: S3 refuses a second kind of authorisation). */
    private val download: suspend (url: String) -> ByteArray,
) {
    /** Files still waiting to be uploaded. */
    val pending get() = uploads.pending

    /**
     * Attaches a prepared file to [owner]: the attachment is added like any change, and the file waits on the device
     * until the sync has uploaded it. [attachmentId] lets the caller take it off again.
     */
    suspend fun attach(owner: AttachmentOwner, file: PreparedFile, attachmentId: Id = newId()): Rejection? {
        val bandId = bands.currentBandId ?: return null
        val attachment = Attachment(attachmentId, file.kind, file.fileName, file.sizeBytes)
        // The file first, then the attachment (ITERATION_3 R4).
        try {
            runSuspendCatching { uploads.enqueue(PendingUpload(bandId, attachment.id), file.base64) }.onFailure { e ->
                return Rejection(RejectionCode.CONFLICT, "Could not store the file on this device: $e", RejectionReason.STORAGE_FAILED)
            }
            // Another band opened meanwhile (the file was being stored): this attachment belongs to the first one.
            val rejection = if (bands.currentBandId == bandId) {
                dispatch(AddAttachment(owner, attachment))
            } else {
                Rejection(RejectionCode.CONFLICT, "Another band was opened", RejectionReason.NOT_FOUND)
            }
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

    /**
     * The file of an attachment (ITERATION_5 §4.2): the local copy while it waits for its upload, else the one kept on
     * the device, else downloaded once and kept (an attachment's file never changes). Null when it cannot be had now
     * (offline, or not uploaded yet by whoever added it).
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun bytes(attachmentId: Id): ByteArray? {
        uploads.localFile(attachmentId)?.let { return runCatching { Base64.decode(it) }.getOrNull() }
        cache.read(attachmentId)?.let { return it }
        val url = url(attachmentId).getOrNull() ?: return null
        val bytes = runSuspendCatching { download(url) }.getOrNull() ?: return null
        runSuspendCatching { cache.write(attachmentId, bytes) }
        return bytes
    }

    /** A URL to open an attachment; fails with [ApiException] `attachment_not_uploaded` while its file is not on the server. */
    suspend fun url(attachmentId: Id): Result<String> {
        val bandId = bands.currentBandId ?: return Result.failure(IllegalStateException("No band open"))
        return runSuspendCatching { api.attachmentUrl(bandId, attachmentId) }
    }
}
