package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import kotlinx.datetime.number
import kotlinx.serialization.Serializable
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.Locale

/** The web app's `index.html`, which link pages are made of. */
fun interface WebIndex {
    /** The page, or null when it can't be had (then the plain page is served by CloudFront). */
    fun html(): String?
}

/** `index.html` from the web app's bucket, kept for a minute: a deploy changes it. */
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
        const val KEEP_MS = 60_000L
    }
}

/** Which band an event is in, `events/{eventId}.json`: link cards find an event without reading every band. */
@Serializable
data class EventPointer(val bandId: String)

/**
 * Event and join links as web pages (ITERATION_6 §2): the web app's page, with a card for link readers (WhatsApp,
 * Messenger, Slack) in its `og:` tags. An event: its name and date; an invite: its band's name; anything else (unknown,
 * deleted, expired) keeps the page's own, generic tags. Nothing else about the band is given away: anyone with a
 * link sees the card.
 */
internal class LinkPages(private val store: BlobStore, private val index: WebIndex?, private val clock: () -> Long, private val bands: BandAccess) {

    fun event(eventId: String): HttpResponse {
        val card = runCatching {
            val bandId = store.get(StorageKeys.eventPointer(eventId))?.let { decode(EventPointer.serializer(), it.content).bandId }
            val event = bandId?.let { bands.load(it)?.first?.event(eventId) }
            // Numbers only: link readers don't say the reader's language (ITERATION_6 §2).
            event?.let { Card(it.name, String.format(Locale.ROOT, "%02d.%02d.%04d", it.date.day, it.date.month.number, it.date.year)) }
        }.getOrNull()
        return page(card)
    }

    fun join(code: String): HttpResponse {
        val card = runCatching {
            val invite = store.get(StorageKeys.invite(code))?.let { decode(StoredInvite.serializer(), it.content).info }
                ?.takeIf { it.expiresAt > clock() }
            val band = invite?.let { bands.load(it.bandId)?.first }
            band?.takeIf { it.member(invite.memberId)?.archived == false }?.let { Card(it.name, null) }
        }.getOrNull()
        return page(card)
    }

    /** Records where events created by a push are, so their links find them. */
    fun pointTo(bandId: String, eventIds: Collection<String>) = eventIds.forEach { eventId ->
        runCatching { store.put(StorageKeys.eventPointer(eventId), encode(EventPointer.serializer(), EventPointer(bandId))) }
            .onFailure { System.err.println("Could not record event $eventId of band $bandId: $it") }
    }

    private class Card(val title: String, val description: String?)

    private fun page(card: Card?): HttpResponse {
        val html = index?.html() ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No page")
        return HttpResponse(200, if (card == null) html else withCard(html, card), mapOf("content-type" to "text/html; charset=utf-8", "cache-control" to "public, max-age=300"))
    }

    companion object {
        private val title = Regex("""<meta property="og:title" content="[^"]*">""")
        private val description = Regex("""\s*<meta property="og:description" content="[^"]*">""")

        /** The page's generic `og:title` (and `og:description`) replaced by the card's. */
        internal fun withCard(html: String, title: String, description: String?): String {
            val titled = html.replace(this.title, Regex.escapeReplacement("""<meta property="og:title" content="${escape(title)}">"""))
            val withoutDescription = titled.replace(this.description, "")
            if (description == null) return withoutDescription
            return withoutDescription.replace(
                this.title,
                Regex.escapeReplacement("""<meta property="og:title" content="${escape(title)}">""" + "\n    " + """<meta property="og:description" content="${escape(description)}">"""),
            )
        }

        private fun withCard(html: String, card: Card) = withCard(html, card.title, card.description)

        private fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
