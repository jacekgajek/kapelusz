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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

/** [Attachments] over a [FakePlatform]: where an attachment's file comes from. */
class AttachmentsTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        // An attachment's file is the local copy while it uploads, then downloaded once and kept.
        expect("an attachment's file comes from the device or is downloaded once") {
            val server = FakeServer()
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
            val api = ApiClient(server.client, "https://api.test") { "alice:0" }
            val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> 200 }
            val sync = SyncEngine(api, repository, coroutineTestScope, uploads = uploads)
            val downloads = mutableListOf<String>()
            var online = true
            val platform = FakePlatform()
            val attachments = Attachments(repository, uploads, api, sync, coroutineTestScope, repository::dispatch, download = { url ->
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

            withClue("still uploading: the local copy") { attachments.bytes("a1") shouldBe byteArrayOf(1, 2, 3) }
            sync.syncNow() shouldBe true
            withClue("uploaded: downloaded") { attachments.bytes("a1") shouldBe byteArrayOf(9, 9) }
            attachments.bytes("a1") shouldBe byteArrayOf(9, 9)
            withClue("once: then from the device") { downloads shouldBe listOf("https://s3.test/get/a1") }

            online = false
            platform.fileCache.kept.clear()
            withClue("offline and not kept: nothing to show") { attachments.bytes("a1").shouldBeNull() }
        }
    })
