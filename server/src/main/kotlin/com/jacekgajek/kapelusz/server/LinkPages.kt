package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.datetime.number
import kotlinx.serialization.Serializable
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/** The web app's `index.html`, which link pages are made of. */
fun interface WebIndex {
    /** The page, or null when it can't be had (then the plain page is served by CloudFront). */
    fun html(): String?
}

/** `index.html` from the web app's bucket, kept for a few seconds: a deploy changes it. */
class S3WebIndex(private val bucket: String, private val s3: S3Client = S3BlobStore.defaultClient(), private val clock: () -> Long = System::currentTimeMillis) : WebIndex {
    @Volatile private var cached: Pair<Long, String>? = null

    override fun html(): String? {
        cached?.let { (at, html) -> if (clock() - at < KEEP_MS) return html }
        val html = try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key("index.html").build()).asUtf8String()
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: Exception) {
            System.err.println("Could not read the web app's index.html: $e")
            // The last one rather than none.
            return cached?.second
        }
        if (html != null) cached = clock() to html
        return html
    }

    private companion object {
        const val KEEP_MS = 15_000L
    }
}

/** Which band an event is in, `events/{eventId}.json`: link cards find an event without reading every band. */
@Serializable
data class EventPointer(val bandId: String)

/**
 * Event links as web pages (ITERATION_6 §2): the web app's page, with a card for link readers (WhatsApp, Messenger,
 * Slack) in its `og:` tags: the event's name and date; an unknown or deleted one keeps the page's own, generic tags.
 * Nothing else about the band is given away: anyone with a link sees the card. Join links keep the generic card: one
 * naming the band would tell anyone, unsigned and without the app's limit on guesses, whether a code is valid.
 */
internal class LinkPages(private val store: BlobStore, private val index: WebIndex?, private val clock: () -> Long, private val bands: BandAccess) {

    fun event(eventId: String): HttpResponse {
        val card = runCatching {
            val bandId = pointer(eventId)
            val event = bandId?.let { bands.load(it)?.first?.event(eventId) }
            // Numbers only: link readers don't say the reader's language (ITERATION_6 §2).
            event?.let { Card(it.name, String.format(Locale.ROOT, "%02d.%02d.%04d", it.date.day, it.date.month.number, it.date.year)) }
        }.getOrNull()
        return page(card)
    }

    /**
     * Records where events a push created are, so their links find them; in parallel, as a push can create hundreds.
     * The first band to have an event id keeps it: another band giving an event the same id (anyone who saw the link
     * could) must not take over its card. Unless that band no longer has the event.
     */
    fun pointTo(bandId: String, eventIds: Collection<String>) = inParallel(eventIds) { eventId ->
        val key = StorageKeys.eventPointer(eventId)
        val content = encode(EventPointer.serializer(), EventPointer(bandId))
        try {
            store.put(key, content, ifNoneMatch = true)
        } catch (e: PreconditionFailed) {
            val existing = store.get(key) ?: return@inParallel
            val owner = decode(EventPointer.serializer(), existing.content).bandId
            if (owner != bandId && bands.load(owner, deleteIfDue = false)?.first?.event(eventId) == null) {
                runCatching { store.put(key, content, ifMatch = existing.etag) }
            }
        }
    }

    /** Takes away the pointers of [eventIds] (deleted, or their band deleted) where they still point to [bandId]. */
    fun forget(bandId: String, eventIds: Collection<String>) = inParallel(eventIds) { eventId ->
        if (pointer(eventId) == bandId) store.delete(StorageKeys.eventPointer(eventId))
    }

    private fun pointer(eventId: String): String? = store.get(StorageKeys.eventPointer(eventId))?.let { decode(EventPointer.serializer(), it.content).bandId }

    private fun inParallel(eventIds: Collection<String>, write: (String) -> Unit) {
        if (eventIds.isEmpty()) return
        eventIds.map { eventId ->
            writers.submit {
                runCatching { write(eventId) }.onFailure { System.err.println("Could not update the pointer of event $eventId: $it") }
            }
        }.forEach { it.get() }
    }

    private class Card(val title: String, val description: String?)

    private fun page(card: Card?): HttpResponse {
        val html = index?.html() ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No page")
        // Briefly without a card: the event may be on its way (a push not sent yet), or the card's text may change.
        val cache = if (card == null) "public, max-age=60" else "public, max-age=300"
        return HttpResponse(200, if (card == null) html else withCard(html, card.title, card.description), mapOf("content-type" to "text/html; charset=utf-8", "cache-control" to cache))
    }

    companion object {
        private val writers by lazy { Executors.newFixedThreadPool(8) { task -> Thread(task, "event-pointers").apply { isDaemon = true } } }

        private val title = Regex("""\s*<meta property="og:title" content="[^"]*">""")
        private val description = Regex("""\s*<meta property="og:description" content="[^"]*">""")

        /**
         * The page with the card's `og:title` (and `og:description`) in place of its generic ones; put before `</head>`
         * when the page has none (its tags written differently, say), so cards don't quietly disappear.
         */
        internal fun withCard(html: String, title: String, description: String?): String {
            val tags = buildString {
                append("\n    <meta property=\"og:title\" content=\"${escape(title)}\">")
                if (description != null) append("\n    <meta property=\"og:description\" content=\"${escape(description)}\">")
            }
            val without = html.replace(this.description, "")
            val match = this.title.find(without)
            if (match != null) return without.replaceRange(match.range, tags)
            System.err.println("The web app's page has no og:title: the card goes before </head>")
            val head = without.indexOf("</head>")
            return if (head < 0) without else without.substring(0, head) + tags.trimStart() + "\n" + without.substring(head)
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
