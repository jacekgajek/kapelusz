package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role

/** Artists see only their own events, costs and payouts. What they cannot see is hidden in the UI only. */
fun Band.isArtist(userId: String?): Boolean = userId != null && memberOfUser(userId)?.role == Role.ARTIST
