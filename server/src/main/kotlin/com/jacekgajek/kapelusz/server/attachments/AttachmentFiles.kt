package com.jacekgajek.kapelusz.server.attachments

import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.PresignedRequest
import com.jacekgajek.kapelusz.server.store.StorageKeys

/** The files of attachments in the bucket (I4 §2.1), under the band's prefix; the HTTP side is [AttachmentHandlers]. */
internal class AttachmentFiles(private val store: BlobStore) {
    fun exists(bandId: String, attachment: Attachment): Boolean {
        val key = StorageKeys.attachment(bandId, attachment)
        return store.exists(key)
    }

    /** A request to upload the file of exactly [contentLength] bytes; it can only create the file, never replace it. */
    fun presignPut(bandId: String, attachment: Attachment, contentLength: Long): PresignedRequest {
        val key = StorageKeys.attachment(bandId, attachment)
        return store.presignPut(key, attachment.kind.contentType, contentLength)
    }

    /** A URL to open the file, served as its kind and named [fileName]. */
    fun presignGet(bandId: String, attachment: Attachment, fileName: String): String {
        val key = StorageKeys.attachment(bandId, attachment)
        return store.presignGet(key, attachment.kind.contentType, fileName)
    }

    /** Files of attachments that are gone from [after] (removed, or what they belonged to deleted). */
    fun deleteRemoved(bandId: String, before: Band, after: Band) {
        val kept = after.allAttachments().map { it.id }.toSet()
        before.allAttachments().filter { it.id !in kept }.forEach { store.delete(StorageKeys.attachment(bandId, it)) }
    }
}
