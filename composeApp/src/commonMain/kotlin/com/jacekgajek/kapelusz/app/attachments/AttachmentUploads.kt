package com.jacekgajek.kapelusz.app.attachments

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.ApiException
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.ops.AddAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An attachment whose file is stored on this device until it has been uploaded. */
@Serializable
data class PendingUpload(val bandId: Id, val attachmentId: Id)

/**
 * The offline queue of attachment files (ITERATION_1 §4.5). A picked file is stored locally (base64, scoped to the
 * user) while its `AddAttachment` operation goes through the normal sync; once the server has the attachment, the
 * file is uploaded to a presigned URL and the local copy dropped.
 */
class AttachmentUploads(
    private val store: KeyValueStore,
    private val api: ApiClient,
    private val bands: BandRepository,
    /** PUTs a file to its presigned URL (the platform's). */
    private val put: suspend (url: String, headers: Map<String, String>, base64: String) -> Int,
) {
    private val lock = Mutex()
    private var userId: String? = null
    private val _pending = MutableStateFlow<List<PendingUpload>>(emptyList())

    /** What is still waiting to be uploaded; the UI shows these attachments as "uploading". */
    val pending: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

    suspend fun load(userId: String) = lock.withLock {
        this.userId = userId
        _pending.value = store.read(indexKey(userId))
            ?.let { runCatching { KapeluszJson.decodeFromString(ListSerializer(PendingUpload.serializer()), it) }.getOrNull() }
            .orEmpty()
    }

    /** Attachments whose file is queued but whose operation is not dispatched yet: not "removed", just not there yet. */
    private val attaching = mutableSetOf<Id>()

    /**
     * Stores the file and queues it, before the attachment is published (ITERATION_3 R4): published first, a crash
     * or a full device would leave everyone an attachment without a file. Throws when it cannot be stored; call
     * [published] once the operation is dispatched (or refused).
     */
    suspend fun enqueue(upload: PendingUpload, base64: String) = lock.withLock {
        val user = checkNotNull(userId) { "Not signed in" }
        attaching += upload.attachmentId
        store.write(fileKey(user, upload.attachmentId), base64)
        save(user, _pending.value + upload)
    }

    fun published(attachmentId: Id) {
        attaching -= attachmentId
    }

    fun bandsWithUploads(): List<Id> = _pending.value.map { it.bandId }.distinct()

    /**
     * Uploads every file of [bandId] whose attachment the server already has. Network problems leave the queue as it
     * is for the next sync; attachments that no longer exist (removed, or refused by the server) are dropped.
     */
    suspend fun uploadFor(bandId: Id) {
        for (upload in _pending.value.filter { it.bandId == bandId }) {
            // The band is gone from this device (deleted, or access lost): its files have nowhere to go.
            val local = bands.local(bandId) ?: run {
                drop(upload)
                null
            } ?: continue
            if (local.pending.any { (it.op as? AddAttachment)?.attachment?.id == upload.attachmentId }) continue // not pushed yet
            if (upload.attachmentId in attaching) continue // its operation is being dispatched right now
            val onServer = local.base.allAttachments().any { it.id == upload.attachmentId }
            if (!onServer) {
                drop(upload)
                continue
            }
            val user = userId ?: return
            val base64 = store.read(fileKey(user, upload.attachmentId))
            if (base64 == null) {
                drop(upload)
                continue
            }
            val target = try {
                api.requestUpload(bandId, upload.attachmentId)
            } catch (e: ApiException) {
                // Already uploaded (e.g. an interrupted earlier attempt), or gone from the server: nothing left to do.
                // Only these explicit answers discard the file; anything else (an older server without this route,
                // a server error) keeps it for the next sync, because the local copy is the only one.
                if (e.code == ApiErrors.ATTACHMENT_EXISTS || e.code == ApiErrors.ATTACHMENT_NOT_FOUND) drop(upload)
                continue
            }
            val status = put(target.putUrl, target.headers, base64)
            // 412: the file exists already (If-None-Match), i.e. an earlier attempt succeeded.
            if (status in 200..299 || status == 412) drop(upload)
        }
    }

    /**
     * The attachments' operations were discarded unsent (ITERATION_3 R9): their files are no longer queued, but stay on
     * the device with the backup of those operations (found by attachment id), as the user was promised. Otherwise
     * [uploadFor] would delete them as removed.
     */
    suspend fun keepUnsent(attachmentIds: Set<Id>) = lock.withLock {
        val user = userId ?: return@withLock
        save(user, _pending.value.filterNot { it.attachmentId in attachmentIds })
    }

    /** The file of an attachment still waiting for its upload (base64), e.g. to show it before it is on the server. */
    suspend fun localFile(attachmentId: Id): String? {
        val user = userId ?: return null
        if (_pending.value.none { it.attachmentId == attachmentId }) return null
        return store.read(fileKey(user, attachmentId))
    }

    /** The user removed the attachment before its file was uploaded. */
    suspend fun cancel(attachmentId: Id) {
        _pending.value.firstOrNull { it.attachmentId == attachmentId }?.let { drop(it) }
    }

    private suspend fun drop(upload: PendingUpload) = lock.withLock {
        val user = userId ?: return@withLock
        store.remove(fileKey(user, upload.attachmentId))
        save(user, _pending.value - upload)
    }

    private suspend fun save(user: String, uploads: List<PendingUpload>) {
        _pending.value = uploads
        store.write(indexKey(user), KapeluszJson.encodeToString(ListSerializer(PendingUpload.serializer()), uploads))
    }

    companion object {
        fun indexKey(userId: String) = "attachment-uploads-$userId"
        fun fileKey(userId: String, attachmentId: Id) = "attachment-file-$userId-$attachmentId"
    }
}
