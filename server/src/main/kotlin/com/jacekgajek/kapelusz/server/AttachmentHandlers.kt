package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.AttachmentUrlResponse
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role

/** Files of attachments: the band's state lists them, the bucket holds them, uploaded and opened by presigned URLs. */
internal class AttachmentHandlers(private val store: BlobStore, private val bands: BandAccess) {
    /**
     * A URL to upload the file of an attachment already listed on its cost. Any member who can add costs may upload
     * (the attachment op itself was permission-checked); the URL can only create the file, never replace it, and is
     * signed for exactly the declared size.
     */
    fun upload(user: VerifiedUser, bandId: String, request: AttachmentUploadRequest): HttpResponse {
        val (band, _) = bands.loadForMember(user, bandId)
        if (!band.memberOfUser(user.userId)!!.role.atLeast(Role.ARTIST)) throw ApiError(403, ApiErrors.FORBIDDEN, "Viewers cannot upload")
        val attachment = band.cost(request.costId)?.attachments?.firstOrNull { it.id == request.attachmentId }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        val key = StorageKeys.attachment(bandId, attachment)
        if (store.exists(key)) throw ApiError(409, ApiErrors.ATTACHMENT_EXISTS, "Already uploaded")
        val presigned = store.presignPut(key, attachment.kind.contentType, attachment.sizeBytes ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "No size"))
        return ok(AttachmentUploadResponse.serializer(), AttachmentUploadResponse(presigned.url, presigned.headers))
    }

    /** A URL to open an attachment; anyone who sees the cost (every member) may. */
    fun download(user: VerifiedUser, bandId: String, attachmentId: String): HttpResponse {
        val (band, _) = bands.loadForMember(user, bandId)
        val attachment = band.costs.firstNotNullOfOrNull { cost -> cost.attachments.firstOrNull { it.id == attachmentId } }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        val key = StorageKeys.attachment(bandId, attachment)
        if (!store.exists(key)) throw ApiError(404, ApiErrors.ATTACHMENT_NOT_UPLOADED, "Not uploaded yet")
        val fileName = attachment.fileName ?: "${attachment.id}.${attachment.kind.extension}"
        return ok(AttachmentUrlResponse.serializer(), AttachmentUrlResponse(store.presignGet(key, attachment.kind.contentType, fileName)))
    }

    /** Files of attachments that are gone from [after] (removed, or their cost deleted). */
    fun deleteRemoved(bandId: String, before: Band, after: Band) {
        val kept = after.costs.flatMap { it.attachments }.map { it.id }.toSet()
        before.costs.flatMap { it.attachments }.filter { it.id !in kept }.forEach { store.delete(StorageKeys.attachment(bandId, it)) }
    }
}
