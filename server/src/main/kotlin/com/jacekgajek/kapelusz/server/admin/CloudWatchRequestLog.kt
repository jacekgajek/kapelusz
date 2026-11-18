package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.server.RequestLine
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryStatus
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField

/** The log could not be read. The query did not finish, or CloudWatch stopped or refused it. */
class RequestLogUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The request log in CloudWatch. It reads the API Lambda's log group with Logs Insights.
 *
 * A query is started. Then its result is asked for until the query is done. That takes a few seconds. After
 * [patienceMillis] the query is stopped, and reading fails.
 *
 * The query picks only the lines of failed requests. [RequestLine.parse] reads them. The same class wrote them.
 */
class CloudWatchRequestLog(
    private val logGroup: String,
    private val logs: CloudWatchLogsClient,
    private val patienceMillis: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
) : RequestLog {

    /** The failed requests. An error from CloudWatch also means "the log cannot be read right now". */
    override fun failed(since: Long, until: Long): List<LoggedRequest> = try {
        query(since, until)
    } catch (e: SdkException) {
        throw RequestLogUnavailable("CloudWatch Logs: ${e.message}", e)
    }

    private fun query(since: Long, until: Long): List<LoggedRequest> {
        val queryId = logs.startQuery {
            // Logs Insights takes seconds. The end is rounded up, so the newest lines are included.
            it.logGroupName(logGroup).startTime(since / SECOND).endTime(until / SECOND + 1).queryString(QUERY).limit(LIMIT)
        }.queryId()
        return runCatching { results(queryId) }.getOrElse { e ->
            // Reading was given up. The query is stopped, so it does not keep running and costing money.
            runCatching { logs.stopQuery { it.queryId(queryId) } }
            throw e
        }
    }

    /** Asks for the query's rows until the query is done. Gives up when the patience runs out. */
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

    /** One result row as a request. Null when the line is in an older format. */
    private fun request(row: List<ResultField>): LoggedRequest? {
        val at = row.firstOrNull { it.field() == "at" }?.value()?.toDoubleOrNull()?.toLong() ?: return null
        val line = row.firstOrNull { it.field() == "@message" }?.value()?.let(RequestLine::parse) ?: return null
        return LoggedRequest(at, line)
    }

    companion object {
        /**
         * Picks the lines of requests that got a 4xx or 5xx answer, newest first.
         *
         * Lines with no user, no installation and no app version are left out. Those are scanners trying
         * addresses. There can be many, and a query gives only a limited number of rows.
         */
        const val QUERY = "fields toMillis(@timestamp) as at, @message" +
            """ | filter @message like /^TIMING \S+ \S+ [45]\d\d /""" +
            """ | filter @message not like / user=- install=- app=- /""" +
            " | sort @timestamp desc"

        /** The most rows one query gives. An API of this size fails far less often than this in two weeks. */
        const val LIMIT = 10_000
        private const val SECOND = 1000L
        private const val POLL_MILLIS = 400L

        fun defaultClient(region: Region): CloudWatchLogsClient = CloudWatchLogsClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(region)
            .build()
    }
}
