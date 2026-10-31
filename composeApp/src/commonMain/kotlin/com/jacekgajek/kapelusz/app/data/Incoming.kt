package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.model.AttachmentOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What came from outside the app's own screens (join and event links, files shared to Kapelusz) or is on its way between
 * them, waiting for the UI to take it up.
 */
class Incoming {
    /** An invite code from a join link (web `/join/CODE`, Android App Link), waiting to be shown to the user. */
    val pendingJoinCode = MutableStateFlow<String?>(null)

    /** An event link's event id (ITERATION_5 §10), opened once the user is signed in and their bands are known. */
    val pendingEventLink = MutableStateFlow<String?>(null)

    /** A gig to open, e.g. from an event link or where shared files went, switching band if needed. */
    val pendingOpenEvent = MutableStateFlow<OpenEvent?>(null)

    /**
     * The band whose screens the navigation was last set up for by whoever switched to it (an event link opening one of
     * its events): the "another band is shown" reset then leaves that navigation alone (V5-M1 C2b).
     */
    var navigatedForBand: String? = null

    /** Photos or PDFs shared to Kapelusz (Android, ITERATION_6 §1), waiting for "Attach to…". */
    val sharedFiles = MutableStateFlow<SharedFiles?>(null)

    /** Shared files on their way to a new cost or collected entry, which takes them as its files to attach. */
    val sharedHandover = MutableStateFlow<SharedHandover?>(null)

    /** Shared files just attached to something saved: the event's snackbar offers to take them off again. */
    val sharedAttached = MutableStateFlow<SharedAttached?>(null)

    /** The shared files handed to a new cost ([toCost]) or collected entry of [eventId], once; empty if none. */
    fun takeSharedFiles(eventId: String, toCost: Boolean): List<PreparedFile> {
        val handover = sharedHandover.value?.takeIf { it.eventId == eventId && it.toCost == toCost } ?: return emptyList()
        sharedHandover.value = null
        return handover.files
    }
}

/**
 * Files shared to Kapelusz: those ready to attach, what could not be used ([PickResult] other than Picked), and how many
 * more were shared than any target takes ([tooMany], not read).
 */
class SharedFiles(val files: List<PreparedFile>, val skipped: List<PickResult>, val tooMany: Int = 0)

/** Shared [files] for a new cost ([toCost]) or a new collected entry of [eventId]. */
class SharedHandover(val eventId: String, val toCost: Boolean, val files: List<PreparedFile>)

/**
 * Attachments [attachmentIds] just added to [owner] from shared files, in event [eventId] of band [bandId]; [failed] of
 * the files could not be attached.
 */
data class SharedAttached(val bandId: String, val eventId: String, val owner: AttachmentOwner, val attachmentIds: List<String>, val failed: Int)

/** An event to open: in band [bandId], at [section] when given. */
data class OpenEvent(val bandId: String, val eventId: String, val section: EventSection? = null)
