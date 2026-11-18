package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse

/**
 * One request, as one line in the API's log. The line starts with `TIMING`.
 *
 * The Lambda writes one line for each request. The admin panel reads the failed ones back. That is how it knows
 * which devices could not sync.
 *
 * This class both writes the line and reads it. So the two always agree.
 * A value that is not known is written as `-`. No value contains a space.
 */
data class RequestLine(
    val method: String,
    /** The request's path. Secrets are taken out of it: calendar links and invite codes. */
    val path: String,
    val status: Int,
    val millis: Long,
    /** The signed-in user. Null when there was none, for example when the sign-in was refused. */
    val userId: String? = null,
    /** The Android installation. The app sends it. Null for the web app. */
    val installation: String? = null,
    /** The app's version code. The app sends it. Null when the request did not come from the app. */
    val appVersion: String? = null,
    /** The error code, when the request was refused ([ApiErrors]). */
    val error: String? = null,
) {
    /** The log line. [timers] says where the time went (`s3=… db=…`). It is for people. It is not read back. */
    fun format(timers: String = ""): String = listOf(
        "TIMING ${method.takeIf { METHOD_SHAPE.matches(it) } ?: "?"} ${whole(path)} $status ${millis}ms",
        timers,
        "user=${userId.orDash()} install=${installation.orDash()} app=${appVersion.orDash()} error=${error.orDash()}",
    ).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        private val LINE = Regex("""TIMING (\S+) (\S+) (\d{3}) (\d+)ms(?: .*)? user=(\S+) install=(\S+) app=(\S+) error=(\S+)""")
        private val VALUE = Regex("[A-Za-z0-9._:-]{1,64}")

        // The numbers of the groups in LINE.
        private const val METHOD = 1
        private const val PATH = 2
        private const val STATUS = 3
        private const val MILLIS = 4
        private const val USER = 5
        private const val INSTALLATION = 6
        private const val APP = 7
        private const val ERROR = 8

        /** The request in a log line. Null for a line of another kind, and for a line in the older format. */
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
         * The line for [request] and its [response].
         *
         * [path] is the request's path with the secrets taken out. The installation and the version come from the
         * query, where the app sends them. The error code comes from the response.
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

        /** The error code in the body of a refusal. Null when the body is something else. */
        private fun errorCode(body: String): String? = runCatching { decode(ErrorResponse.serializer(), body).error }.getOrNull()

        private val METHOD_SHAPE = Regex("[A-Z]{1,10}")
        private val BREAKS = Regex("""[\s\p{Cntrl}]""")

        /**
         * [path] as one word.
         *
         * The path comes from the request. With a space or a line break in it, a caller could write a log line of
         * their own, naming any user. So these characters are replaced.
         */
        private fun whole(path: String) = path.replace(BREAKS, "_").ifEmpty { "/" }

        /** [path] with its secrets taken out. Calendar links and invite codes are never logged. Join links hold a code too. */
        fun withoutSecrets(path: String) = path.replace(Regex("^/calendar/.*"), "/calendar/…")
            .replace(Regex("/invites/[^/]+"), "/invites/…")
            .replace(Regex("^/join/.*"), "/join/…")

        /** A request with this status or a higher one has failed. */
        const val FAILED = 400

        /** The value, or `-`. Values come from requests. One that could break the line is left out. */
        private fun String?.orDash() = this?.takeIf { VALUE.matches(it) } ?: "-"

        private fun String.orNull() = takeIf { it != "-" }
    }
}
