package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.api.ApiErrors
import com.jacekgajek.artsplit.app.platform.uploadFile
import com.jacekgajek.artsplit.app.sync.ApiClient
import com.jacekgajek.artsplit.app.sync.ApiException
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.ops.AddAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An attachment whose file is stored on this device until it has been uploaded. */
@Serializable
data class PendingUpload(val bandId: Id, val costId: Id, val attachmentId: Id)

/**
 * The offline queue of attachment files (VERSION_1 §4.5). A picked file is stored locally (base64, scoped to the
 * user) while its `AddAttachment` operation goes through the normal sync; once the server has the attachment, the
 * file is uploaded to a presigned URL and the local copy dropped.
 */
class AttachmentUploads(
    private val store: KeyValueStore,
    private val api: ApiClient,
    private val bands: BandRepository,
    private val put: suspend (url: String, headers: Map<String, String>, base64: String) -> Int = ::uploadFile,
) {
    private val lock = Mutex()
    private var userId: String? = null
    private val _pending = MutableStateFlow<List<PendingUpload>>(emptyList())

    /** What is still waiting to be uploaded; the UI shows these attachments as "uploading". */
    val pending: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

    suspend fun load(userId: String) = lock.withLock {
        this.userId = userId
        _pending.value = store.read(indexKey(userId))
            ?.let { runCatching { ArtSplitJson.decodeFromString(ListSerializer(PendingUpload.serializer()), it) }.getOrNull() }
            .orEmpty()
    }

    /** Keeps [base64] on the device until [upload] reaches the server. Call after its `AddAttachment` was applied. */
    suspend fun enqueue(upload: PendingUpload, base64: String) = lock.withLock {
        val user = userId ?: return@withLock
        store.write(fileKey(user, upload.attachmentId), base64)
        save(user, _pending.value + upload)
    }

    fun bandsWithUploads(): List<Id> = _pending.value.map { it.bandId }.distinct()

    /**
     * Uploads every file of [bandId] whose attachment the server already has. Network problems leave the queue as it
     * is for the next sync; attachments that no longer exist (removed, or refused by the server) are dropped.
     */
    suspend fun uploadFor(bandId: Id) {
        for (upload in _pending.value.filter { it.bandId == bandId }) {
            // The band is gone from this device (deleted, or access lost): its files have nowhere to go.
            val local = bands.local(bandId) ?: run { drop(upload); null } ?: continue
            if (local.pending.any { (it.op as? AddAttachment)?.attachment?.id == upload.attachmentId }) continue // not pushed yet
            val onServer = local.base.cost(upload.costId)?.attachments?.any { it.id == upload.attachmentId } == true
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
                api.requestUpload(bandId, upload.costId, upload.attachmentId)
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
        store.write(indexKey(user), ArtSplitJson.encodeToString(ListSerializer(PendingUpload.serializer()), uploads))
    }

    companion object {
        fun indexKey(userId: String) = "attachment-uploads-$userId"
        fun fileKey(userId: String, attachmentId: Id) = "attachment-file-$userId-$attachmentId"
    }
}
