package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role

/** Artists see only their own events, costs and payouts (ITERATION_4 §1.1). Hidden in the UI only, see its §1.4. */
fun Band.isArtist(userId: String?): Boolean = userId != null && memberOfUser(userId)?.role == Role.ARTIST
