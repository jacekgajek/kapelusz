package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.model.Band
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
import com.jacekgajek.kapelusz.server.error
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.store.BlobEntry
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.DeletedBand
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.inviteCodes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.SerializationException

/**
 * What a deployment decides about the admin panel.
 */
data class AdminConfig(
    /** The Google user ids that may use the panel. Empty: nobody may. */
    val adminUserIds: Set<String>,
    /** The OAuth client the page signs in with. Empty: not set up, nobody can sign in. Null: the local server's development sign-in. */
    val googleClientId: String?,
    /** The largest band file, so the page can show how full a band is. */
    val maxBandBytes: Int = Api.MAX_BAND_BYTES,
)

/**
 * The admin panel: one page and the few routes it reads. It is a separate entry point from [Api], with its own
 * Lambda, address and sign-in; the app's API has none of these routes.
 *
 * It only reads. It never loads a band through `BandAccess`, because that deletes a band whose time has come.
 */
class AdminApi(
    private val store: BlobStore,
    private val verifier: TokenVerifier,
    private val config: AdminConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The API's request log, for the devices that could not sync. Null: there is none to read. */
    private val requestLog: RequestLog? = null,
) {
    private val devices = DeviceRegistry(store, clock)
    private val page = AdminPage(config.googleClientId)

    fun handle(request: HttpRequest): HttpResponse {
        val response = try {
            route(request)
        } catch (e: ApiError) {
            error(e.status, e.code, e.message.orEmpty())
        }
        // The same on every answer; what an answer sets itself stays.
        return response.copy(headers = ALWAYS + response.headers)
    }

    private fun route(request: HttpRequest): HttpResponse {
        // The page itself is public: it holds no data, and signing in happens on it.
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
        ),
    )

    /** A valid token is not enough: its user must be on the list. */
    private fun authenticate(request: HttpRequest): VerifiedUser {
        val admins = config.adminUserIds
        val token = request.header("authorization")?.removePrefix("Bearer ")?.trim()
        // A panel with no admins never looks at a token.
        val user = token?.takeIf { admins.isNotEmpty() }?.let(verifier::verify)
        throw when {
            token == null -> ApiError(401, ApiErrors.UNAUTHORIZED, "Missing bearer token")

            // The same words as for a user who is not on the list: whether the panel is set up is nobody's business.
            admins.isEmpty() -> ApiError(403, ApiErrors.FORBIDDEN, "Not an admin")

            user == null -> ApiError(401, ApiErrors.UNAUTHORIZED, "Invalid or expired token")

            user.userId !in admins -> ApiError(403, ApiErrors.FORBIDDEN, "Not an admin")

            else -> return user
        }
    }

    /** Every band, and what is left of the deleted ones. */
    private fun bands(): HttpResponse {
        val entries = store.entries(BANDS)
        val rows = inParallel(entries.filter { it.key.endsWith(STATE) }, ::row).sortedBy { it.name?.lowercase() }
        val deleted = inParallel(entries.filter { it.key.endsWith(DELETED) }, ::deletedRow).filterNotNull()
        return ok(BandList.serializer(), BandList(rows, deleted.sortedByDescending { it.deletedAt }, config.maxBandBytes))
    }

    /** A band that cannot be read, for whatever reason, is still in the list: one bad file must not hide the others. */
    private fun row(entry: BlobEntry): BandRow {
        val id = bandIdOf(entry.key)
        val band = runCatching { read(entry.key) }.getOrNull()?.second
            ?: return BandRow(id, null, 0, 0, 0, entry.size, entry.modified, unreadable = true, accounts = emptyList())
        return BandRow(
            id = id,
            name = band.name,
            members = band.members.count { !it.archived },
            linked = band.members.count { !it.archived && it.userId != null },
            events = band.events.size,
            bytes = entry.size,
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

    /** The devices that could not sync in the last `days` days (1 to [MAX_DAYS], [DEFAULT_DAYS] when not said). */
    private fun syncFailures(call: Call): HttpResponse {
        val days = (call.query("days")?.toIntOrNull() ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
        val now = clock()
        val list = when (val log = requestLog) {
            null -> SyncFailureList(available = false, days = days, devices = emptyList())
            else -> SyncFailures.grouped(failed(log, now - days * DAY_MILLIS, now), days)
        }
        return ok(SyncFailureList.serializer(), list)
    }

    /** The failed requests in [log]; a log that cannot be read just now is "try later", not this server's error. */
    private fun failed(log: RequestLog, since: Long, until: Long): List<LoggedRequest> {
        val read = runCatching { log.failed(since, until) }
        return read.getOrElse { e ->
            if (e !is RequestLogUnavailable) throw e
            System.err.println("The request log could not be read: $e")
            throw ApiError(503, ApiErrors.BUSY, "The request log could not be read: ${e.message}")
        }
    }

    /** One band: its members with their devices, and how much is in it. */
    private fun band(call: Call): HttpResponse {
        val bandId = call.param("bandId")
        val key = StorageKeys.band(bandId)
        // Everything stored for the band: its file, and the attachment files beside it.
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
            deletion = band.deletion?.let { DeletionRow(band.member(it.requestedBy)?.name, it.requestedAt, it.deleteAt) },
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

    /** The user's Android devices. Their tokens stay on the server. */
    private fun devicesOf(userId: String): List<DeviceRow> = runCatching { devices.stored(userId) }.getOrDefault(emptyList())
        .sortedByDescending { it.lastSeen }
        .map { DeviceRow(it.lastSeen, devices.fresh(it)) }

    /** The band's file exactly as stored. */
    private fun raw(call: Call): HttpResponse {
        val key = StorageKeys.band(call.param("bandId"))
        // Looked up in the listing first: the panel's role may not be told by S3 that an object is missing.
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
        const val READERS = 16

        /** How far back sync failures are looked for: the API's log is kept 14 days. */
        const val MAX_DAYS = 14
        const val DEFAULT_DAYS = 3
        const val DAY_MILLIS = 24 * 3600 * 1000L

        /** The band's file is there but is not a band. */
        const val UNREADABLE = "unreadable"

        /**
         * On every answer. Band data must not stay in a browser's or a proxy's cache; the browser must not guess a
         * type; and it must come back over HTTPS only.
         */
        val ALWAYS = mapOf(
            "cache-control" to "no-store",
            "x-content-type-options" to "nosniff",
            "strict-transport-security" to "max-age=31536000",
        )

        /** Bands are read several at a time: the list needs every band's file. One pool for the process. */
        val readers = Executors.newFixedThreadPool(READERS) { task -> Thread(task, "admin-reader").apply { isDaemon = true } }

        /** `bands/{id}/state.json` gives `{id}`. */
        fun bandIdOf(key: String) = key.removePrefix(BANDS).substringBefore('/')
    }
}
