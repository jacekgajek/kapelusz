package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.server.RequestLine

/**
 * Decides which failed requests mean "this device could not sync". Also groups those failures by device.
 *
 * It only knows what the server saw. A device that is offline sends nothing. Neither does an app that crashed.
 */
internal object SyncFailures {
    /** The requests of one sync round. Create, push, upload, pull, the band list, a new session. */
    private val SYNC_ROUTES = listOf(
        "POST" to Regex("/bands"),
        "POST" to Regex("/bands/[^/]+/ops"),
        "POST" to Regex("/bands/[^/]+/attachments"),
        "GET" to Regex("/bands/[^/]+/state"),
        "GET" to Regex("/me"),
        "POST" to Regex("/session"),
    )

    /**
     * Refusals that are normal. The app deals with them by itself, so they are not failures.
     * - The band is gone for this user. The app drops it.
     * - The file was uploaded before, or its cost is gone. The upload is finished either way.
     * - The place search is down. It is not part of a sync.
     */
    private val EXPECTED = setOf(
        ApiErrors.BAND_NOT_FOUND,
        ApiErrors.BAND_DELETED,
        ApiErrors.ATTACHMENT_EXISTS,
        ApiErrors.ATTACHMENT_NOT_FOUND,
        ApiErrors.PLACES_UNAVAILABLE,
    )

    private val SESSION = Regex("/session")
    private const val UNAUTHORIZED = 401

    private const val SERVER_ERROR = 500
    private const val UPDATE_REQUIRED = 426

    /**
     * True when [line] is a request of the app that failed and stopped its sync.
     *
     * Every server error counts. Every "update required" counts. Other refusals count only for the requests of a
     * sync round. A refusal of something the user tried does not count. A wrong invite code is an example.
     */
    fun counts(line: RequestLine): Boolean = when {
        line.status < RequestLine.FAILED -> false

        // This is not the app. The app always sends its version. Bots and wrong addresses end here.
        line.appVersion == null && line.userId == null -> false

        line.error in EXPECTED -> false

        // The token ran out. This is normal, many times a day. The app gets a new token and asks again.
        // The device is stuck only when the new token is refused too. That is the request POST /session.
        // "Signed out everywhere" is another case. It has its own error code.
        line.status == UNAUTHORIZED && line.error == ApiErrors.UNAUTHORIZED && !SESSION.matches(line.path) -> false

        line.status >= SERVER_ERROR || line.status == UPDATE_REQUIRED -> true

        else -> SYNC_ROUTES.any { (method, path) -> method == line.method && path.matches(line.path) }
    }

    /**
     * Groups [requests] by device. The device that failed last comes first.
     *
     * A device is one installation of one user. The web app has no installation. So all browsers of a user count
     * as one device.
     *
     * Some requests have neither a user nor an installation. They go to [SyncFailureList.unidentified]. Examples
     * are a refused sign-in, and an app too old to be let in. Nobody knows whose they were.
     */
    fun grouped(requests: List<LoggedRequest>, days: Int): SyncFailureList {
        val (known, unknown) = requests.filter { counts(it.line) }.partition { it.line.userId != null || it.line.installation != null }
        val devices = known.groupBy { it.line.userId to it.line.installation }.values.map(::device).sortedByDescending { it.last }
        val unidentified = unknown.takeIf { it.isNotEmpty() }?.let(::device)
        return SyncFailureList(available = true, days = days, devices = devices, unidentified = unidentified)
    }

    private fun device(requests: List<LoggedRequest>): FailingDevice {
        val newest = requests.maxBy { it.at }
        return FailingDevice(
            userId = newest.line.userId,
            installation = newest.line.installation,
            appVersion = newest.line.appVersion,
            count = requests.size,
            first = requests.minOf { it.at },
            last = newest.at,
            kinds = requests.groupBy { it.line.status to it.line.error }.map { (kind, same) ->
                val latest = same.maxBy { it.at }
                FailureKind(kind.first, kind.second, same.size, latest.at, "${latest.line.method} ${latest.line.path}")
            }.sortedByDescending { it.last },
        )
    }
}
