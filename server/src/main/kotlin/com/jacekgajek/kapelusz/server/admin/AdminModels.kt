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
    /** The members with an account, so the page can say whose device a sync failure is. */
    val accounts: List<AccountRow>,
)

/** A member's account: the user id and the member's name in this band. */
@Serializable
data class AccountRow(val userId: String, val name: String)

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
    /** The size of the band's file, which is what the size limit counts. */
    val bytes: Long,
    /** The attachment files stored for the band, and their size together. */
    val attachments: Int,
    val attachmentBytes: Long,
    val modified: Long,
    val deletion: DeletionRow? = null,
    /** Unchanged for so long that the panel may ask for it to be deleted. */
    val stale: Boolean,
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
data class DeletionRow(
    /** The name of the member who asked; null when it was no member. */
    val requestedBy: String?,
    val requestedAt: Long,
    val deleteAt: Long,
    /** Asked for from this panel. */
    val bySupport: Boolean,
)

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

/**
 * The devices that could not sync in the last [days] days, the latest failure first. Not [available]: this server
 * has no request log to read. [unidentified] are the failures nobody can be named for, e.g. a refused sign-in.
 */
@Serializable
data class SyncFailureList(
    val available: Boolean,
    val days: Int,
    val devices: List<FailingDevice>,
    val unidentified: FailingDevice? = null,
)

/**
 * A device and its failed requests. [installation] is null for the web app: all of a user's browsers are one device
 * here. [appVersion] is the version code of the latest failure.
 */
@Serializable
data class FailingDevice(
    val userId: String? = null,
    val installation: String? = null,
    val appVersion: String? = null,
    val count: Int,
    val first: Long,
    val last: Long,
    val kinds: List<FailureKind>,
)

/** Failures of one kind: the HTTP status and the API's error code. [request] is the latest one, e.g. `POST /bands/b1/ops`. */
@Serializable
data class FailureKind(val status: Int, val error: String? = null, val count: Int, val last: Long, val request: String)
