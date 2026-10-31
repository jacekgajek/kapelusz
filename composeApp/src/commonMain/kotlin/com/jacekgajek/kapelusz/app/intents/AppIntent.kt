package com.jacekgajek.kapelusz.app.intents

import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.model.AttachmentOwner

/**
 * Something the app was asked to do from outside its own screens (a join or event link, files shared to Kapelusz) or
 * by a screen that cannot do it itself (REFACTORING.md 3.6). Offered to the [IntentInbox]; the `IntentRouter` acts on
 * it once the app can.
 */
sealed interface AppIntent {
    /** Join a band: with the invite [code] of a join link (web `/join/CODE`, Android App Link), or asked for by hand. */
    data class Join(val code: String? = null) : AppIntent

    /**
     * Open an event: of [bandId], or of an event link (ITERATION_5 §10, [bandId] null) in whichever of the user's bands
     * has it; at [section] when given, with [carry] for the screen that opens.
     */
    data class OpenEvent(
        val eventId: String,
        val bandId: String? = null,
        val section: EventSection? = null,
        val carry: Carry? = null,
    ) : AppIntent

    /** Photos or PDFs shared to Kapelusz (Android, ITERATION_6 §1), waiting for "Attach to…". */
    data class ShareFiles(val files: SharedFiles) : AppIntent
}

/** What travels with [AppIntent.OpenEvent] into the screen that opens: held by the inbox, taken once by its id (a route argument). */
sealed interface Carry {
    /** Shared files for a new cost of the event: its form opens with them. */
    data class NewCost(val files: List<PreparedFile>) : Carry

    /** Shared files for new received money of the event: the entry's dialog opens with them. */
    data class NewCollected(val files: List<PreparedFile>) : Carry

    /** Shared files to attach to [owner] once its band is open; then [Attached]. */
    data class Attach(val owner: AttachmentOwner, val files: List<PreparedFile>) : Carry

    /** Attachments [attachmentIds] just added to [owner] from shared files ([failed] of them could not be): the event offers Undo. */
    data class Attached(val owner: AttachmentOwner, val attachmentIds: List<String>, val failed: Int) : Carry
}

/**
 * Files shared to Kapelusz: those ready to attach, what could not be used ([PickResult] other than Picked), and how many
 * more were shared than any target takes ([tooMany], not read).
 */
class SharedFiles(val files: List<PreparedFile>, val skipped: List<PickResult>, val tooMany: Int = 0)
