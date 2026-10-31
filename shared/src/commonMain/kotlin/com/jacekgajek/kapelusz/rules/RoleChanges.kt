package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.Viewers
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role

/**
 * What stops a member from getting a role: the one rule the reducer enforces, the server checks before an invite,
 * and the app tells before trying.
 */
object RoleChanges {
    /** Why [memberId] cannot become [role] now, or null when they can. */
    fun refusal(band: Band, memberId: Id, role: Role): RejectionReason? = when {
        // Someone holding band money cannot become an artist (ITERATION_4 §1.1).
        role == Role.ARTIST && Artists.holdsMoney(band, memberId) -> RejectionReason.HOLDS_MONEY

        // Nor a viewer while playing in an unsettled event or holding the fund (ITERATION_5 B2).
        role == Role.VIEWER && !Viewers.mayBecome(band, memberId) -> RejectionReason.VIEWER_STILL_PLAYS

        else -> null
    }
}
