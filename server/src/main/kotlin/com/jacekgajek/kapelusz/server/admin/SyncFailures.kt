package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.server.RequestLine

/**
 * Which failed requests mean "this device could not sync", and the failures put together per device.
 *
 * Only what the server saw can be here. A device that is offline, or whose app crashed, sends nothing.
 */
internal object SyncFailures {
    /** What a sync round asks for: create, push, upload, pull, the band list, a new session. */
    private val SYNC_ROUTES = listOf(
        "POST" to Regex("/bands"),
        "POST" to Regex("/bands/[^/]+/ops"),
        "POST" to Regex("/bands/[^/]+/attachments"),
        "GET" to Regex("/bands/[^/]+/state"),
        "GET" to Regex("/me"),
        "POST" to Regex("/session"),
    )

    /**
     * Refusals that are the normal end of something, and that the app handles by itself: the band is gone for this
     * user (the app drops it), a file was uploaded already or its cost is gone (the upload is done with), and the
     * place search is down (no part of a sync).
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
     * A request of the app that failed in a way that stops its sync. Any server error and any "update required"
     * count; otherwise only the requests a sync round makes. A refusal of something the user tried (a wrong invite
     * code, leaving as the owner) is not a sync failure.
     */
    fun counts(line: RequestLine): Boolean = when {
        line.status < RequestLine.FAILED -> false

        // Not the app at all: it always sends its version. Bots and wrong addresses end here.
        line.appVersion == null && line.userId == null -> false

        line.error in EXPECTED -> false

        // A token that ran out: the app gets a new one and asks again, many times a day. Only when getting the new
        // one is refused (POST /session) is the device stuck. "Signed out everywhere" has its own error code.
        line.status == UNAUTHORIZED && line.error == ApiErrors.UNAUTHORIZED && !SESSION.matches(line.path) -> false

        line.status >= SERVER_ERROR || line.status == UPDATE_REQUIRED -> true

        else -> SYNC_ROUTES.any { (method, path) -> method == line.method && path.matches(line.path) }
    }

    /**
     * [requests] per device, the one that failed last first. A device is a user's installation; the web app has no
     * installation, so all of a user's browsers are one. Requests with neither are [SyncFailureList.unidentified]:
     * a sign-in that was refused for good, or an app too old to be let in, so nobody knows whose it was.
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
