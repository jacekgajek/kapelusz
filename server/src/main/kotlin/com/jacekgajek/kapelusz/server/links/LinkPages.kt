package com.jacekgajek.kapelusz.server.links

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.bands.BandAccess
import java.util.Locale
import kotlinx.datetime.number
/**
 * Event links as web pages (I6 §2): the web app's page, with a card for link readers (WhatsApp, Messenger,
 * Slack) in its `og:` tags: the event's name and date; an unknown or deleted one keeps the page's own, generic tags.
 * Nothing else about the band is given away: anyone with a link sees the card. Join links keep the generic card: one
 * naming the band would tell anyone, unsigned and without the app's limit on guesses, whether a code is valid.
 */
internal class LinkPages(private val index: WebIndex, private val bands: BandAccess, private val pointers: EventPointers) {
    fun event(eventId: String): HttpResponse {
        val card = runCatching {
            val bandId = pointers.bandOf(eventId)
            val event = bandId?.let { bands.load(it)?.first?.event(eventId) }
            // Numbers only: link readers don't say the reader's language (I6 §2).
            event?.let { Card(it.name, String.format(Locale.ROOT, "%02d.%02d.%04d", it.date.day, it.date.month.number, it.date.year)) }
        }.getOrNull()
        return page(card)
    }

    private class Card(val title: String, val description: String?)

    private fun page(card: Card?): HttpResponse {
        val html = index.html() ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No page")
        // Briefly without a card: the event may be on its way (a push not sent yet), or the card's text may change.
        val cache = if (card == null) "public, max-age=60" else "public, max-age=300"
        return HttpResponse(200, if (card == null) html else withCard(html, card.title, card.description), mapOf("content-type" to "text/html; charset=utf-8", "cache-control" to cache))
    }

    companion object {
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
