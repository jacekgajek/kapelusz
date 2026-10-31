package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.HistoryResponse
import java.time.YearMonth
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** `GET /bands/{bandId}/log`: pages of the band's history ([HistoryLog]), a few months at a time. */
internal class HistoryHandlers(private val clock: () -> Long, private val bands: BandAccess, private val log: HistoryLog) {
    /**
     * One page of a month's history, newest first. [before] is the `next` cursor of the previous page.
     *
     * With [months] above 1, the first page of that many months back from [month] in one answer (no `next`): one
     * request instead of one per month, so the app does not wake a new server instance for each month.
     */
    fun history(user: VerifiedUser, bandId: String, month: String?, before: String?, months: String? = null): HttpResponse {
        val selected = month ?: monthOf(clock())
        badRequestIf(!selected.matches(Regex("\\d{4}-\\d{2}"))) { "month must be yyyy-MM" }
        val first = runCatching { YearMonth.parse(selected) }.getOrNull()
        badRequestIf(first == null) { "month must be yyyy-MM" }
        val count = if (months == null) 1 else months.toIntOrNull() ?: 0
        badRequestIf(count !in 1..MAX_MONTHS) { "months must be 1 to $MAX_MONTHS" }
        badRequestIf(count > 1 && before != null) { "before pages a single month" }
        // Each month gets at most HISTORY_PAGE entries and its share of about HISTORY_PAGE_BYTES (the recorded changes
        // can be large): a busy month never crowds out the others, and the answer stays small.
        val budget = Api.HISTORY_PAGE_BYTES / count
        // The months are read while the membership is checked; nothing is answered unless the check passes.
        val pages = (0 until count).map { offset ->
            val month = first!!.minusMonths(offset.toLong()).toString()
            reader.submit<HistoryLog.Page> { log.page(bandId, month, before.takeIf { offset == 0 }, budget) }
        }
        try {
            bands.loadForMember(user, bandId)
        } catch (e: Exception) {
            pages.forEach { it.cancel(true) }
            throw e
        }
        val read = awaitAll(pages)
        val next = read.first().next.takeIf { count == 1 }
        return ok(HistoryResponse.serializer(), HistoryResponse(selected, read.flatMap { it.entries }, next))
    }

    /** The results of [running]; if one fails, the others are not waited for. */
    private fun <T> awaitAll(running: List<Future<T>>): List<T> = try {
        running.map { it.get() }
    } catch (e: ExecutionException) {
        running.forEach { it.cancel(true) }
        throw e.cause ?: e
    }

    companion object {
        /** Months one history request may cover. */
        const val MAX_MONTHS = 3

        /** Reads the months of one request in parallel. */
        private val reader by lazy { Executors.newFixedThreadPool(MAX_MONTHS) { task -> Thread(task, "history-reader").apply { isDaemon = true } } }
    }
}
