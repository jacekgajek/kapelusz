package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.server.RequestLine
import java.util.concurrent.ConcurrentLinkedDeque

/** One request that the API answered. [at] is when, in epoch millis. */
data class LoggedRequest(val at: Long, val line: RequestLine)

/** The API's request log. This is the part of it that the admin panel reads. */
fun interface RequestLog {
    /** The failed requests from [since] to [until]. Failed means status [RequestLine.FAILED] or higher. In any order. */
    fun failed(since: Long, until: Long): List<LoggedRequest>
}

/**
 * The request log, kept in memory. The local server uses it, because it has no CloudWatch. Tests use it too.
 *
 * It keeps the newest [capacity] failed requests. Older ones are dropped.
 */
class InMemoryRequestLog(private val clock: () -> Long = System::currentTimeMillis, private val capacity: Int = 1000) : RequestLog {
    private val requests = ConcurrentLinkedDeque<LoggedRequest>()

    fun record(line: RequestLine) {
        if (line.status < RequestLine.FAILED) return
        requests.addFirst(LoggedRequest(clock(), line))
        while (requests.size > capacity) requests.pollLast()
    }

    override fun failed(since: Long, until: Long): List<LoggedRequest> = requests.filter { it.at in since..until }
}
