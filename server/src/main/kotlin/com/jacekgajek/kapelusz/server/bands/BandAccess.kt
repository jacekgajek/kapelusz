package com.jacekgajek.kapelusz.server.bands

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.withPicture
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.retryOnConflict
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.DeletedBand
import com.jacekgajek.kapelusz.server.store.StorageKeys

/** Loading a band's state, and who may see it; used by every handler that works on a band. */
internal class BandAccess(private val store: BlobStore, private val clock: () -> Long = System::currentTimeMillis) {
    /** Deletes a band whose time to be deleted has come ([BandDeletions.deleteNow]); set once everything is built. */
    var whenDue: ((Band) -> Unit)? = null

    /**
     * The band and its ETag; null if there is none, or it was just deleted, its time having come.
     * [deleteIfDue] false reads it as it is: for the deletion itself, and where a deletion would run on a thread pool
     * it uses too ([EventPointers]).
     */
    fun load(bandId: String, deleteIfDue: Boolean = true): Pair<Band, String>? {
        val loaded = store.get(StorageKeys.band(bandId))?.let { decode(Band.serializer(), it.content) to it.etag } ?: return null
        val due = whenDue?.takeIf { deleteIfDue && loaded.first.deletion.let { it != null && it.deleteAt <= clock() } } ?: return loaded
        due(loaded.first)
        return null
    }

    fun loadForMember(user: VerifiedUser, bandId: String): Pair<Band, String> {
        val loaded = load(bandId) ?: throw gone(user, bandId)
        val member = loaded.first.memberOfUser(user.userId)
        // Same answer for "no such band" and "not your band", so band ids cannot be probed. A distinct code (not the
        // generic route 404) lets clients drop the band only when the band itself is gone for them.
        if (member == null || member.archived) throw ApiError(404, ApiErrors.BAND_NOT_FOUND, "No such band")
        return loaded
    }

    fun loadForLeader(user: VerifiedUser, bandId: String, forbidden: String = "Only band leaders manage invites"): Pair<Band, String> {
        val loaded = loadForMember(user, bandId)
        val me = loaded.first.memberOfUser(user.userId)!!
        if (!me.role.atLeast(Role.LEAD)) throw ApiError(403, ApiErrors.FORBIDDEN, forbidden)
        return loaded
    }

    /** "Deleted" for the band's former members, the usual "not found" for everyone else (no probing of ids). */
    fun gone(user: VerifiedUser, bandId: String): ApiError {
        val deleted = store.get(StorageKeys.deleted(bandId))?.let { decode(DeletedBand.serializer(), it.content) }
        return if (deleted != null && user.userId in deleted.userIds) {
            ApiError(404, ApiErrors.BAND_DELETED, "${deleted.name} was deleted by ${deleted.deletedBy}")
        } else {
            ApiError(404, ApiErrors.BAND_NOT_FOUND, "No such band")
        }
    }

    /** Not an operation: the photo is not a change of the band, so it is not in the history (clients get it with the state). */
    fun setMemberPicture(bandId: String, userId: String, url: String) = retryOnConflict<Unit>(exhausted = {}) {
        val (band, etag) = load(bandId) ?: return@retryOnConflict
        // Not in a band they were removed from: their photo stays out of it.
        val member = band.memberOfUser(userId)?.takeIf { !it.archived } ?: return@retryOnConflict
        if (member.pictureUrl == url) return@retryOnConflict
        store.put(StorageKeys.band(bandId), encode(Band.serializer(), band.withPicture(userId, url)), ifMatch = etag)
    }
}
