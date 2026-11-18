package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse

/**
 * One request as the API's log keeps it: a line that starts with `TIMING`. The Lambda writes one per request, and
 * the admin panel reads the failed ones back to show which devices could not sync.
 *
 * Written and read here, so the two cannot drift apart. A value that is not known is `-`. No value has a space.
 */
data class RequestLine(
    val method: String,
    /** With secrets taken out (calendar links, invite codes). */
    val path: String,
    val status: Int,
    val millis: Long,
    /** The signed-in user. Null when the request never got that far, e.g. its sign-in was refused. */
    val userId: String? = null,
    /** The Android installation, as the app sends it. Null for the web app. */
    val installation: String? = null,
    /** The app's version code, as the app sends it. Null for anything that is not the app. */
    val appVersion: String? = null,
    /** The error code of a refusal ([ApiErrors]). */
    val error: String? = null,
) {
    /** The log line. [timers] is where the time went (`s3=… db=…`); it is for people, and not read back. */
    fun format(timers: String = ""): String = listOf(
        "TIMING ${method.takeIf { METHOD_SHAPE.matches(it) } ?: "?"} ${whole(path)} $status ${millis}ms",
        timers,
        "user=${userId.orDash()} install=${installation.orDash()} app=${appVersion.orDash()} error=${error.orDash()}",
    ).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        private val LINE = Regex("""TIMING (\S+) (\S+) (\d{3}) (\d+)ms(?: .*)? user=(\S+) install=(\S+) app=(\S+) error=(\S+)""")
        private val VALUE = Regex("[A-Za-z0-9._:-]{1,64}")

        // The groups of LINE, in its order.
        private const val METHOD = 1
        private const val PATH = 2
        private const val STATUS = 3
        private const val MILLIS = 4
        private const val USER = 5
        private const val INSTALLATION = 6
        private const val APP = 7
        private const val ERROR = 8

        /** The request a log line tells of. Null for any other line, and for lines from before these fields. */
        fun parse(message: String): RequestLine? {
            val found = LINE.matchEntire(message.trim())?.groupValues ?: return null
            return RequestLine(
                method = found[METHOD],
                path = found[PATH],
                status = found[STATUS].toInt(),
                millis = found[MILLIS].toLong(),
                userId = found[USER].orNull(),
                installation = found[INSTALLATION].orNull(),
                appVersion = found[APP].orNull(),
                error = found[ERROR].orNull(),
            )
        }

        /**
         * The line for [request] answered with [response]. [path] is the request's path without its secrets.
         * The installation and the version come from the query, as the app sends them; the error from the answer.
         */
        fun of(request: HttpRequest, path: String, response: HttpResponse, millis: Long, userId: String?) = RequestLine(
            method = request.method,
            path = path,
            status = response.status,
            millis = millis,
            userId = userId,
            installation = request.query[ApiErrors.INSTALLATION_PARAMETER],
            appVersion = request.query[ApiErrors.APP_VERSION_PARAMETER],
            error = response.takeIf { it.status >= FAILED }?.body?.let(::errorCode),
        )

        /** The error code in a refusal's body; null when the body is something else. */
        private fun errorCode(body: String): String? = runCatching { decode(ErrorResponse.serializer(), body).error }.getOrNull()

        private val METHOD_SHAPE = Regex("[A-Z]{1,10}")
        private val BREAKS = Regex("""[\s\p{Cntrl}]""")

        /**
         * [path] as one word. A path comes from the request, and a space or a line break in it would let a caller
         * write a line of their own into the log, with any user in it.
         */
        private fun whole(path: String) = path.replace(BREAKS, "_").ifEmpty { "/" }

        /** [path] without its secrets: calendar links and invite codes (also in join links) are never logged. */
        fun withoutSecrets(path: String) = path.replace(Regex("^/calendar/.*"), "/calendar/…")
            .replace(Regex("/invites/[^/]+"), "/invites/…")
            .replace(Regex("^/join/.*"), "/join/…")

        /** From this status on a request has failed. */
        const val FAILED = 400

        /** Values come from requests: one that could break the line is left out. */
        private fun String?.orDash() = this?.takeIf { VALUE.matches(it) } ?: "-"

        private fun String.orNull() = takeIf { it != "-" }
    }
}
