package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role

/**
 * Links to an event: `<web>/e/<eventId>`. Event ids are unique across bands, so the link needs no
 * band: the app finds the event among the user's bands. On Android a verified App Link opens the app; elsewhere the
 * web app opens it.
 */
object EventLinks {
    private val path = Regex("^/e/([A-Za-z0-9-]{1,64})/?$")

    fun url(webUrl: String, eventId: Id): String = "${webUrl.trimEnd('/')}/e/$eventId"

    /** The event id in a link's path (`/e/<id>`), or null for another path. */
    fun eventId(path: String): Id? = this.path.find(path)?.groupValues?.get(1)

    /** The user may open the event: an active member of its band, and for an artist only a gig they play. */
    fun canOpen(band: Band, userId: String, event: Event): Boolean {
        val me = band.memberOfUser(userId)?.takeIf { !it.archived } ?: return false
        return me.role != Role.ARTIST || event.participant(me.id) != null
    }
}
