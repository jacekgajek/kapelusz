package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.server.RequestLine
import java.util.concurrent.ConcurrentLinkedDeque

/** A request the API answered, and when (epoch millis). */
data class LoggedRequest(val at: Long, val line: RequestLine)

/** The API's request log, as far as the admin panel reads it. */
fun interface RequestLog {
    /** The failed requests ([RequestLine.FAILED] and up) from [since] to [until], in any order. */
    fun failed(since: Long, until: Long): List<LoggedRequest>
}

/**
 * The log in memory: for the local server, which has no CloudWatch, and for tests. It keeps the newest [capacity]
 * failed requests and forgets the rest.
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
