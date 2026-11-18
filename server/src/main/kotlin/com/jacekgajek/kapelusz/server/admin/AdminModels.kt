package com.jacekgajek.kapelusz.server.admin

import kotlinx.serialization.Serializable

// The data that the admin page gets. Times are in epoch millis. Sizes are in bytes.
// The JSON leaves out a value that equals its default. So a field has a default only if it may be missing.

/** The front page. It has the bands, the deleted bands, and the size limit of a band's file. */
@Serializable
data class BandList(val bands: List<BandRow>, val deleted: List<DeletedRow>, val maxBandBytes: Int)

/** One band in the list. [name] is null when the band's file is [unreadable]. Archived members are not counted. */
@Serializable
data class BandRow(
    val id: String,
    val name: String? = null,
    val members: Int,
    /** How many members have an account. */
    val linked: Int,
    val events: Int,
    /** The size of the band's file. The size limit counts only this. */
    val bytes: Long,
    /** The size of all the band's attachment files together. */
    val attachmentBytes: Long,
    val modified: Long,
    /** When the band will be deleted. Null when no deletion was asked for. */
    val deleteAt: Long? = null,
    val unreadable: Boolean,
    /** The members who have an account. With them the page can say whose device failed to sync. */
    val accounts: List<AccountRow>,
)

/** One member's account. It has the user id, and the member's name in this band. */
@Serializable
data class AccountRow(val userId: String, val name: String)

/** A deleted band. Only its name is kept, with who deleted it and when. */
@Serializable
data class DeletedRow(val id: String, val name: String, val deletedBy: String, val deletedAt: Long)

/** The data of one band's page. */
@Serializable
data class BandDetails(
    val id: String,
    val name: String,
    val currency: String,
    val etag: String,
    /** The size of the band's file. The size limit counts only this. */
    val bytes: Long,
    /** How many attachment files the band has. [attachmentBytes] is their size together. */
    val attachments: Int,
    val attachmentBytes: Long,
    val modified: Long,
    val deletion: DeletionRow? = null,
    /** True when the band was not changed for so long that the panel may ask for its deletion. */
    val stale: Boolean,
    val members: List<MemberRow>,
    /** The name of the member who holds the band fund. */
    val treasurer: String? = null,
    val events: Int,
    val costs: Int,
    val fundEntries: Int,
    /** How many invite codes the band has. The codes themselves are not sent. Each one lets somebody join. */
    val inviteCodes: Int,
)

@Serializable
data class DeletionRow(
    /** The name of the member who asked. Null when no member asked. */
    val requestedBy: String?,
    val requestedAt: Long,
    val deleteAt: Long,
    /** True when the deletion was asked for from this panel. */
    val bySupport: Boolean,
)

/** One member. [userId] is null when nobody has joined as this member yet. */
@Serializable
data class MemberRow(
    val id: String,
    val name: String,
    val role: String,
    val userId: String? = null,
    val archived: Boolean,
    val devices: List<DeviceRow>,
)

/** One Android device of a member. Not [fresh] means it was not seen for a long time, and gets no sync messages. */
@Serializable
data class DeviceRow(val lastSeen: Long, val fresh: Boolean)

/**
 * The devices that could not sync in the last [days] days. The device that failed last comes first.
 *
 * [available] is false when this server has no request log.
 * [unidentified] holds the failures that belong to nobody known. A refused sign-in is an example.
 */
@Serializable
data class SyncFailureList(
    val available: Boolean,
    val days: Int,
    val devices: List<FailingDevice>,
    val unidentified: FailingDevice? = null,
)

/**
 * One device and its failed requests.
 *
 * [installation] is null for the web app. All browsers of a user count as one device.
 * [appVersion] is the version code in the latest failure.
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

/** Failures of one kind. A kind is an HTTP status and an error code. [request] is the latest one, like `POST /bands/b1/ops`. */
@Serializable
data class FailureKind(val status: Int, val error: String? = null, val count: Int, val last: Long, val request: String)

/** The copies of a band's file, newest first, and the restores made so far. [more]: there are older copies, not listed. */
@Serializable
data class VersionList(val versions: List<VersionRow>, val more: Boolean, val restores: List<RestoreRow>)

/** One copy of a band's file. It was written at [modified]. [latest]: this is the file as stored now. */
@Serializable
data class VersionRow(val id: String, val modified: Long, val bytes: Long, val latest: Boolean)

/** A restore made at [at]. The band was put back as it was at [stateOf]. */
@Serializable
data class RestoreRow(
    val at: Long,
    val stateOf: Long,
    val versionId: String,
    /** The copy that the restore replaced. Putting it back undoes the restore. */
    val replacedVersionId: String? = null,
    /** The records beside the band that could not be updated. */
    val problems: List<String>,
)

/**
 * What would change if a copy were put back.
 *
 * The page sends [currentEtag] back with the restore request. It shows the band has not changed since this preview.
 * [name] is what the admin must type to confirm.
 */
@Serializable
data class RestorePreview(
    val versionId: String,
    val stateOf: Long,
    val currentEtag: String,
    val name: String,
    /** False: the file stored now is not a band. Then there is nothing to compare the copy with. */
    val currentReadable: Boolean,
    val latest: Boolean,
    val members: PartDiff,
    val events: PartDiff,
    val costs: PartDiff,
    val fundEntries: PartDiff,
    /** The band's own settings differ: its name, currency, fund or agenda template. */
    val settingsChange: Boolean,
    /** Names of members who can open the band again after the restore. [loseAccess]: those who no longer can. */
    val gainAccess: List<String>,
    val loseAccess: List<String>,
    /** How many attachments would come back without their file. The file was deleted with the attachment. */
    val missingFiles: Int,
    /** When the band is to be deleted. A restore does not change that. Null when no deletion was asked for. */
    val deleteAt: Long? = null,
)

/** What a restore does to one list. [back] items come back. [gone] items disappear. [changed] items go back to how they were. */
@Serializable
data class PartDiff(val back: Int, val gone: Int, val changed: Int, val backNames: List<String>, val goneNames: List<String>)

@Serializable
data class RestoreRequest(val versionId: String, val etag: String, val name: String)

/** The answer to a restore: the band was put back. [problems] names the records beside it that could not be updated. */
@Serializable
data class RestoreDone(val problems: List<String>)
