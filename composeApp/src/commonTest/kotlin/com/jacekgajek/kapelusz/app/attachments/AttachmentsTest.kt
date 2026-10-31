package com.jacekgajek.kapelusz.app.attachments

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.platform.FakePlatform
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.testing.FakeServer
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** [Attachments] over a [FakePlatform]: where an attachment's file comes from. */
class AttachmentsTest {
    private var nextId = 0

    /** I5 §4.2: an attachment's file is the local copy while it uploads, then downloaded once and kept. */
    @Test
    fun anAttachmentsFileComesFromTheDeviceOrIsDownloadedOnce() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { "alice:0" }
        val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> 200 }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        val downloads = mutableListOf<String>()
        var online = true
        val platform = FakePlatform()
        val attachments = Attachments(repository, uploads, api, sync, this, repository::dispatch, download = { url ->
            check(online) { "offline" }
            downloads += url
            byteArrayOf(9, 9)
        }, cache = platform.fileCache)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val me = (repository.state.value as BandState.Ready).me!!.id
        repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1)))
        repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 3)))
        uploads.enqueue(PendingUpload(repository.currentBandId!!, "a1"), "AQID")
        uploads.published("a1")

        assertTrue(byteArrayOf(1, 2, 3).contentEquals(attachments.bytes("a1")), "still uploading: the local copy")
        assertTrue(sync.syncNow())
        assertTrue(byteArrayOf(9, 9).contentEquals(attachments.bytes("a1")), "uploaded: downloaded")
        assertTrue(byteArrayOf(9, 9).contentEquals(attachments.bytes("a1")))
        assertEquals(listOf("https://s3.test/get/a1"), downloads, "once: then from the device")

        online = false
        platform.fileCache.kept.clear()
        assertEquals(null, attachments.bytes("a1"), "offline and not kept: nothing to show")
    }
}
