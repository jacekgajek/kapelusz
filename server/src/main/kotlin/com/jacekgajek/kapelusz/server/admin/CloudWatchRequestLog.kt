package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.server.RequestLine
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryStatus
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField

/** The log query did not finish, or CloudWatch gave it up. */
class RequestLogUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The API Lambda's log group, read with Logs Insights. A query is started and then asked for until it is done,
 * which takes a few seconds; after [patienceMillis] it is stopped and given up.
 *
 * The query only picks the lines of failed requests. They are read with [RequestLine.parse], the same code that
 * wrote them.
 */
class CloudWatchRequestLog(
    private val logGroup: String,
    private val logs: CloudWatchLogsClient,
    private val patienceMillis: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
) : RequestLog {

    /** CloudWatch refusing or failing is "the log cannot be read just now" too, like a query that never ends. */
    override fun failed(since: Long, until: Long): List<LoggedRequest> = try {
        query(since, until)
    } catch (e: SdkException) {
        throw RequestLogUnavailable("CloudWatch Logs: ${e.message}", e)
    }

    private fun query(since: Long, until: Long): List<LoggedRequest> {
        val queryId = logs.startQuery {
            // Logs Insights counts in seconds; the end is rounded up so the newest lines are in.
            it.logGroupName(logGroup).startTime(since / SECOND).endTime(until / SECOND + 1).queryString(QUERY).limit(LIMIT)
        }.queryId()
        return runCatching { results(queryId) }.getOrElse { e ->
            // Given up, so it must not go on running (and being paid for) on CloudWatch's side.
            runCatching { logs.stopQuery { it.queryId(queryId) } }
            throw e
        }
    }

    /** Asks for the query's rows until it is done, or the patience runs out. */
    private fun results(queryId: String): List<LoggedRequest> {
        val deadline = clock() + patienceMillis
        while (true) {
            val results = logs.getQueryResults { it.queryId(queryId) }
            when (results.status()) {
                QueryStatus.COMPLETE -> return results.results().mapNotNull(::request)

                QueryStatus.SCHEDULED, QueryStatus.RUNNING -> {
                    if (clock() >= deadline) throw RequestLogUnavailable("The log query took too long")
                    pause(POLL_MILLIS)
                }

                else -> throw RequestLogUnavailable("The log query ended as ${results.statusAsString()}")
            }
        }
    }

    /** A result row as a request; null when the line is not one this code wrote (an older format). */
    private fun request(row: List<ResultField>): LoggedRequest? {
        val at = row.firstOrNull { it.field() == "at" }?.value()?.toDoubleOrNull()?.toLong() ?: return null
        val line = row.firstOrNull { it.field() == "@message" }?.value()?.let(RequestLine::parse) ?: return null
        return LoggedRequest(at, line)
    }

    companion object {
        /**
         * The lines of requests answered with a 4xx or 5xx, newest first. Not those of nobody at all (no user, no
         * installation, no app version): scanners knocking, which would fill the rows a query may give.
         */
        const val QUERY = "fields toMillis(@timestamp) as at, @message" +
            """ | filter @message like /^TIMING \S+ \S+ [45]\d\d /""" +
            """ | filter @message not like / user=- install=- app=- /""" +
            " | sort @timestamp desc"

        /** The most rows a query gives. A band-sized API fails far less often than this in two weeks. */
        const val LIMIT = 10_000
        private const val SECOND = 1000L
        private const val POLL_MILLIS = 400L

        fun defaultClient(region: Region): CloudWatchLogsClient = CloudWatchLogsClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(region)
            .build()
    }
}
