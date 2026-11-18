package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.server.Api
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.Route
import com.jacekgajek.kapelusz.server.Router
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.error
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.store.BlobEntry
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.DeletedBand
import com.jacekgajek.kapelusz.server.store.HistoryStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.ScheduledDeletions
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.inviteCodes
import com.jacekgajek.kapelusz.server.store.scheduledDeletions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.SerializationException

/** The settings of the admin panel. Each place that runs the panel sets them. */
data class AdminConfig(
    /** The Google user ids that may use the panel. If empty, nobody may. */
    val adminUserIds: Set<String>,
    /**
     * The OAuth client that the page signs in with.
     * Empty means the panel is not set up, and nobody can sign in.
     * Null means the local server, with its development sign-in.
     */
    val googleClientId: String?,
    /** The largest size a band's file may have. The page uses it to show how full a band is. */
    val maxBandBytes: Int = Api.MAX_BAND_BYTES,
)

/**
 * The admin panel. It serves one page, and the routes that the page calls.
 *
 * It is separate from [Api]. It has its own Lambda, its own address and its own sign-in. The app's API has none of
 * these routes.
 *
 * It reads. There are two exceptions:
 * - It can ask for a band to be deleted, if nobody has changed the band for a long time ([scheduleDeletion]).
 * - It can put a band back as it was at an earlier time ([Restores]).
 *
 * It never loads a band through `BandAccess`. That would delete a band whose time has come.
 */
class AdminApi(
    private val store: BlobStore,
    private val verifier: TokenVerifier,
    private val config: AdminConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The API's request log. The panel finds the devices that could not sync in it. Null: there is no log. */
    private val requestLog: RequestLog? = null,
    /** The bands' history. A restore adds an entry to it. Null: this server keeps no history. */
    historyStore: HistoryStore? = null,
) {
    private val restores = Restores(store, clock, historyStore)
    private val devices = DeviceRegistry(store, clock)
    private val page = AdminPage(config.googleClientId)

    fun handle(request: HttpRequest): HttpResponse {
        val response = try {
            route(request)
        } catch (e: ApiError) {
            error(e.status, e.code, e.message.orEmpty())
        }
        // Every answer gets these headers. A header the answer already has is kept.
        return response.copy(headers = ALWAYS + response.headers)
    }

    private fun route(request: HttpRequest): HttpResponse {
        // The page needs no sign-in. It holds no data. The sign-in happens on it.
        if (request.method == "GET" && request.path.trim('/').isEmpty()) return page.response()
        if (!request.path.startsWith("/api/")) throw notFound("No such page")
        val user = authenticate(request)
        return router.handle(request) { params -> Call(request, user, params, installation = null) }
            ?: throw notFound("No route for ${request.method} ${request.path}")
    }

    private val router = Router(
        listOf(
            Route("GET", "api/bands") { bands() },
            Route("GET", "api/bands/{bandId}", ::band),
            Route("GET", "api/bands/{bandId}/raw", ::raw),
            Route("GET", "api/sync-failures", ::syncFailures),
            Route("POST", "api/bands/{bandId}/delete", ::scheduleDeletion),
            // Restore: list the copies of a band's file, preview one, put one back.
            Route("GET", "api/bands/{bandId}/versions") { ok(VersionList.serializer(), restores.versions(it.param("bandId"))) },
            Route("GET", "api/bands/{bandId}/restore", ::restorePreview),
            Route("POST", "api/bands/{bandId}/restore") {
                ok(RestoreDone.serializer(), restores.restore(it.param("bandId"), it.body(RestoreRequest.serializer()), it.user))
            },
        ),
    )

    /** The user of the request. A valid token is not enough. The user must also be on the list of admins. */
    private fun authenticate(request: HttpRequest): VerifiedUser {
        val admins = config.adminUserIds
        val token = request.header("authorization")?.removePrefix("Bearer ")?.trim()
        // When there are no admins, the token is not even checked.
        val user = token?.takeIf { admins.isNotEmpty() }?.let(verifier::verify)
        throw when {
            token == null -> ApiError(401, ApiErrors.UNAUTHORIZED, "Missing bearer token")

            // The same answer as for a user who is not an admin. An outsider must not learn that the panel is not set up.
            admins.isEmpty() -> ApiError(403, ApiErrors.FORBIDDEN, "Not an admin")

            user == null -> ApiError(401, ApiErrors.UNAUTHORIZED, "Invalid or expired token")

            user.userId !in admins -> ApiError(403, ApiErrors.FORBIDDEN, "Not an admin")

            else -> return user
        }
    }

    /** The list of all bands. Also the deleted bands, of which only a small record is left. */
    private fun bands(): HttpResponse {
        val entries = store.entries(BANDS)
        // The listing also has the attachment files. Their sizes are added up for each band.
        val attached = entries.filter { ATTACHMENTS in it.key }.groupingBy { bandIdOf(it.key) }.fold(0L) { sum, file -> sum + file.size }
        val rows = inParallel(entries.filter { it.key.endsWith(STATE) }, ::row)
            .map { it.copy(attachmentBytes = attached[it.id] ?: 0) }
            .sortedBy { it.name?.lowercase() }
        val deleted = inParallel(entries.filter { it.key.endsWith(DELETED) }, ::deletedRow).filterNotNull()
        return ok(BandList.serializer(), BandList(rows, deleted.sortedByDescending { it.deletedAt }, config.maxBandBytes))
    }

    /** One band's row. A band that cannot be read still gets a row. One bad file must not hide the other bands. */
    private fun row(entry: BlobEntry): BandRow {
        val id = bandIdOf(entry.key)
        val band = runCatching { read(entry.key) }.getOrNull()?.second
            ?: return BandRow(id, null, 0, 0, 0, entry.size, 0, entry.modified, unreadable = true, accounts = emptyList())
        return BandRow(
            id = id,
            name = band.name,
            members = band.members.count { !it.archived },
            linked = band.members.count { !it.archived && it.userId != null },
            events = band.events.size,
            bytes = entry.size,
            // Set later by the list. Only the list knows the sizes of the attachment files.
            attachmentBytes = 0,
            modified = entry.modified,
            deleteAt = band.deletion?.deleteAt,
            unreadable = false,
            accounts = band.members.filter { !it.archived }.mapNotNull { member -> member.userId?.let { AccountRow(it, member.name) } },
        )
    }

    private fun deletedRow(entry: BlobEntry): DeletedRow? {
        val deleted = runCatching { store.get(entry.key)?.let { decode(DeletedBand.serializer(), it.content) } }.getOrNull() ?: return null
        return DeletedRow(bandIdOf(entry.key), deleted.name, deleted.deletedBy, deleted.deletedAt)
    }

    /** The devices that could not sync in the last `days` days. From 1 to [MAX_DAYS]. Without `days`: [DEFAULT_DAYS]. */
    private fun syncFailures(call: Call): HttpResponse {
        val days = (call.query("days")?.toIntOrNull() ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
        val now = clock()
        val list = when (val log = requestLog) {
            null -> SyncFailureList(available = false, days = days, devices = emptyList())
            else -> SyncFailures.grouped(failed(log, now - days * DAY_MILLIS, now), days)
        }
        return ok(SyncFailureList.serializer(), list)
    }

    /** The failed requests in [log]. If the log cannot be read right now, the answer is "try later", not a server error. */
    private fun failed(log: RequestLog, since: Long, until: Long): List<LoggedRequest> {
        val read = runCatching { log.failed(since, until) }
        return read.getOrElse { e ->
            if (e !is RequestLogUnavailable) throw e
            System.err.println("The request log could not be read: $e")
            throw ApiError(503, ApiErrors.BUSY, "The request log could not be read: ${e.message}")
        }
    }

    /** One band's page: its members, their devices, and how much the band holds. */
    private fun band(call: Call): HttpResponse {
        val bandId = call.param("bandId")
        val key = StorageKeys.band(bandId)
        // All files stored for the band: its own file and its attachment files.
        val stored = store.entries(StorageKeys.bandPrefix(bandId))
        val entry = stored.firstOrNull { it.key == key } ?: throw notFound("No such band")
        val attachments = stored.filter { it.key.startsWith(StorageKeys.attachmentsPrefix(bandId)) }
        val (etag, band) = read(key) ?: throw ApiError(422, UNREADABLE, "The band's file cannot be read as a band; see the raw data")
        val details = BandDetails(
            id = bandId,
            name = band.name,
            currency = band.currency,
            etag = etag,
            bytes = entry.size,
            attachments = attachments.size,
            attachmentBytes = attachments.sumOf { it.size },
            modified = entry.modified,
            deletion = band.deletion?.let {
                DeletionRow(band.member(it.requestedBy)?.name, it.requestedAt, it.deleteAt, bySupport = it.requestedBy == SUPPORT)
            },
            stale = band.deletion == null && stale(entry),
            members = band.members.map { member ->
                MemberRow(
                    id = member.id,
                    name = member.name,
                    role = member.role.name,
                    userId = member.userId,
                    archived = member.archived,
                    devices = member.userId?.let(::devicesOf).orEmpty(),
                )
            },
            treasurer = band.treasurerMemberId?.let { band.member(it)?.name },
            events = band.events.size,
            costs = band.costs.size,
            fundEntries = band.fundEntries.size,
            inviteCodes = runCatching { store.inviteCodes(bandId).get()?.codes?.size }.getOrNull() ?: 0,
        )
        return ok(BandDetails.serializer(), details)
    }

    private fun restorePreview(call: Call): HttpResponse {
        val versionId = call.query("version") ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "Which copy?")
        return ok(RestorePreview.serializer(), restores.preview(call.param("bandId"), versionId))
    }

    /** True when nobody changed the band's file for [STALE_DAYS] days. Only such a band can be deleted from the panel. */
    private fun stale(entry: BlobEntry) = clock() - entry.modified >= STALE_DAYS * DAY_MILLIS

    /**
     * Asks for a [stale] band to be deleted. It works like the owner's own request.
     *
     * The band is deleted a week later. Until then the members see a notice in the app, and a leader can keep the
     * band. The API does the deleting when the week is over. The panel deletes nothing itself.
     *
     * Two things are written: the band's file, now with the deletion request in it, and the list of bands waiting.
     * No entry is added to the band's history. No sync message is sent. The apps see the change at their next sync.
     */
    private fun scheduleDeletion(call: Call): HttpResponse {
        val bandId = call.param("bandId")
        val key = StorageKeys.band(bandId)
        val (etag, band) = deletable(key)
        val now = clock()
        val scheduled = scheduled(band, now)
        // The list is written first, on purpose.
        // If the band's file is then not written, the list has one band too many. That is harmless. When the time
        // comes, the API sees that the band is not to be deleted, and takes it off the list.
        // In the other order, the band could have a deletion request that the API never finds.
        wait(bandId, now + BandDeletion.DELAY_MS)
        try {
            store.put(key, encode(Band.serializer(), scheduled), ifMatch = etag)
        } catch (ignored: PreconditionFailed) {
            throw conflict("The band was changed just now")
        }
        println("ADMIN ${call.user.userId} asked for band $bandId to be deleted")
        return HttpResponse(204)
    }

    /**
     * Puts [bandId] on the list of bands waiting to be deleted. The API checks this list every hour. So the band is
     * found even if nobody opens it again.
     *
     * The list may not exist yet. The API creates it at the first deletion request. For a missing list, S3 tells the
     * panel "forbidden", not "not found". So a failed read is taken to mean "no list yet", and the list is created.
     * If the list does exist, the create is refused. Then the first failure is thrown.
     */
    private fun wait(bandId: String, deleteAt: Long) {
        val list = store.scheduledDeletions()
        runCatching { list.update { stored -> ScheduledDeletions(stored?.bands.orEmpty() + (bandId to deleteAt)) } }.onFailure { unread ->
            runCatching { list.create(ScheduledDeletions(mapOf(bandId to deleteAt))) }.onFailure { throw unread }
        }
    }

    /** The band at [key] and its ETag, if the panel may ask for its deletion. Otherwise the request is refused. */
    private fun deletable(key: String): Pair<String, Band> {
        // The file is read first, then its time. A change between the two shows in the time. A later change fails the write.
        val loaded = listed(key)?.let { read(key) }
        val entry = listed(key)
        throw when {
            loaded == null || entry == null -> notFound("No such band")
            loaded.second.deletion != null -> conflict("The band is already to be deleted")
            !stale(entry) -> conflict("The band was changed in the last $STALE_DAYS days")
            else -> return loaded
        }
    }

    /** The listing's entry for the object at [key]. Null when there is no such object. */
    private fun listed(key: String): BlobEntry? = store.entries(key).firstOrNull { it.key == key }

    /** [band] with a deletion request made at [now]. The shared rules apply it, so the app would accept the result too. */
    private fun scheduled(band: Band, now: Long): Band {
        val op = ScheduleBandDeletion(BandDeletion(SUPPORT, now, now + BandDeletion.DELAY_MS))
        return when (val result = Engine.apply(band, Actor.System, op, "admin-delete-$now", now)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> throw conflict(result.rejection.message)
        }
    }

    private fun conflict(message: String) = ApiError(409, ApiErrors.CONFLICT, message)

    /** The user's Android devices. Their tokens are not sent to the page. */
    private fun devicesOf(userId: String): List<DeviceRow> = runCatching { devices.stored(userId) }.getOrDefault(emptyList())
        .sortedByDescending { it.lastSeen }
        .map { DeviceRow(it.lastSeen, devices.fresh(it)) }

    /** The band's file exactly as stored. */
    private fun raw(call: Call): HttpResponse {
        val key = StorageKeys.band(call.param("bandId"))
        // The listing is checked first. For a missing object, S3 tells the panel "forbidden", not "not found".
        val blob = store.entries(key).firstOrNull { it.key == key }?.let { store.get(key) } ?: throw notFound("No such band")
        return HttpResponse(200, blob.content, mapOf("content-type" to "application/json; charset=utf-8", "etag" to blob.etag))
    }

    /** The band at [key] with its ETag. Null when the file is missing or is not a band. */
    private fun read(key: String): Pair<String, Band>? {
        val blob = store.get(key) ?: return null
        return try {
            blob.etag to decode(Band.serializer(), blob.content)
        } catch (ignored: SerializationException) {
            null
        }
    }

    private fun <T, R> inParallel(items: List<T>, work: (T) -> R): List<R> {
        val tasks = items.map { Callable { work(it) } }
        return readers.invokeAll(tasks).map { it.get() }
    }

    private fun notFound(message: String) = ApiError(404, ApiErrors.NOT_FOUND, message)

    private companion object {
        const val BANDS = "bands/"
        const val STATE = "/state.json"
        const val DELETED = "/deleted.json"
        const val ATTACHMENTS = "/attachments/"
        const val READERS = 16

        /** The most days of sync failures the panel shows. The API's log is kept for 14 days. */
        const val MAX_DAYS = 14
        const val DEFAULT_DAYS = 3
        const val DAY_MILLIS = 24 * 3600 * 1000L

        /** After this many days without a change, a band can be deleted from the panel. About two months. */
        const val STALE_DAYS = 60

        /**
         * The requester in a deletion request made from the panel. It is not the id of any member.
         * The app then names nobody. It says the band was not used for a long time.
         * When the API deletes the band, it names the owner.
         */
        const val SUPPORT = "support"

        /** The error code for a band's file that exists but is not a band. */
        const val UNREADABLE = "unreadable"

        /**
         * Headers on every answer. They say three things. Do not keep this in any cache. Do not guess the content
         * type. Use HTTPS only.
         */
        val ALWAYS = mapOf(
            "cache-control" to "no-store",
            "x-content-type-options" to "nosniff",
            "strict-transport-security" to "max-age=31536000",
        )

        /** Threads for reading several bands at once. The list needs every band's file. The whole process shares them. */
        val readers = Executors.newFixedThreadPool(READERS) { task -> Thread(task, "admin-reader").apply { isDaemon = true } }

        /** The band id in a key. For `bands/{id}/state.json` it is `{id}`. */
        fun bandIdOf(key: String) = key.removePrefix(BANDS).substringBefore('/')
    }
}
