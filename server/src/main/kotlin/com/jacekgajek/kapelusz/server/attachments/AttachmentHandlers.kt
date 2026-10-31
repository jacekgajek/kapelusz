package com.jacekgajek.kapelusz.server.attachments

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.AttachmentUrlResponse
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.ok

/** Files of attachments: the band's state lists them, the bucket holds them ([AttachmentFiles]), uploaded and opened by presigned URLs. */
internal class AttachmentHandlers(private val bands: BandAccess, private val files: AttachmentFiles) {
    /**
     * A URL to upload the file of an attachment already listed on its cost, collected entry or event (I4
     * §2.1). Any member who can add costs may upload
     * (the attachment op itself was permission-checked); the URL can only create the file, never replace it, and is
     * signed for exactly the declared size.
     */
    fun upload(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
        val request = call.body(AttachmentUploadRequest.serializer())
        val (band, _) = bands.loadForMember(user, bandId)
        if (!band.memberOfUser(user.userId)!!.role.atLeast(Role.ARTIST)) throw ApiError(403, ApiErrors.FORBIDDEN, "Viewers cannot upload")
        val attachment = band.allAttachments().firstOrNull { it.id == request.attachmentId }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        if (files.exists(bandId, attachment)) throw ApiError(409, ApiErrors.ATTACHMENT_EXISTS, "Already uploaded")
        val size = attachment.sizeBytes ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "No size")
        val presigned = files.presignPut(bandId, attachment, size)
        return ok(AttachmentUploadResponse.serializer(), AttachmentUploadResponse(presigned.url, presigned.headers))
    }

    /** A URL to open an attachment; anyone who sees what it belongs to (every member) may. */
    fun download(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
        val attachmentId = call.param("attachmentId")
        val (band, _) = bands.loadForMember(user, bandId)
        val attachment = band.allAttachments().firstOrNull { it.id == attachmentId }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        if (!files.exists(bandId, attachment)) throw ApiError(404, ApiErrors.ATTACHMENT_NOT_UPLOADED, "Not uploaded yet")
        val fileName = attachment.fileName ?: "${attachment.id}.${attachment.kind.extension}"
        return ok(AttachmentUrlResponse.serializer(), AttachmentUrlResponse(files.presignGet(bandId, attachment, fileName)))
    }
}
