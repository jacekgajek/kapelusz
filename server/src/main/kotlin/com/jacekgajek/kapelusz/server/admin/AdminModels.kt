package com.jacekgajek.kapelusz.server.admin

import kotlinx.serialization.Serializable

// What the admin page gets. Times are epoch millis; sizes are bytes.
// The JSON leaves out values that equal their default, so only fields that may be missing have one.

/** The front page: the bands, the deleted ones, and the size limit of a band's file. */
@Serializable
data class BandList(val bands: List<BandRow>, val deleted: List<DeletedRow>, val maxBandBytes: Int)

/** A band in the list. [name] is null when its file is [unreadable]. Counts leave archived members out. */
@Serializable
data class BandRow(
    val id: String,
    val name: String? = null,
    val members: Int,
    /** Members with an account. */
    val linked: Int,
    val events: Int,
    val bytes: Long,
    val modified: Long,
    /** When the band will be deleted, if its owner asked for that. */
    val deleteAt: Long? = null,
    val unreadable: Boolean,
)

/** A deleted band: only its name, who deleted it and when are kept. */
@Serializable
data class DeletedRow(val id: String, val name: String, val deletedBy: String, val deletedAt: Long)

/** One band's page. */
@Serializable
data class BandDetails(
    val id: String,
    val name: String,
    val currency: String,
    val etag: String,
    val bytes: Long,
    val modified: Long,
    val deletion: DeletionRow? = null,
    val members: List<MemberRow>,
    /** The name of the member who holds the band fund. */
    val treasurer: String? = null,
    val events: Int,
    val costs: Int,
    val fundEntries: Int,
    /** How many invite codes the band has. The codes are not shown: each one lets somebody join. */
    val inviteCodes: Int,
)

@Serializable
data class DeletionRow(val requestedBy: String?, val requestedAt: Long, val deleteAt: Long)

/** A member. [userId] is null when nobody has joined as this member. */
@Serializable
data class MemberRow(
    val id: String,
    val name: String,
    val role: String,
    val userId: String? = null,
    val archived: Boolean,
    val devices: List<DeviceRow>,
)

/** An Android device of a member. Not [fresh]: unseen for so long that it gets no sync messages any more. */
@Serializable
data class DeviceRow(val lastSeen: Long, val fresh: Boolean)
