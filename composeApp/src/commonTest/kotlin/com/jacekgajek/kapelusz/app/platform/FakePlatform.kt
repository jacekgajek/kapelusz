package com.jacekgajek.kapelusz.app.platform

import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.app.data.KeyValueStore

/** A [Platform] for tests: the web kind, in-memory store and cache, nobody signed in; messages, reload and the listing absent unless given. */
class FakePlatform(
    override val kind: PlatformKind = PlatformKind.WEB,
    override val store: KeyValueStore = InMemoryStore(),
    override val fileCache: InMemoryByteCache = InMemoryByteCache(),
    override val google: GoogleAuth = NoGoogle,
    override val messages: MessageChannel? = null,
    override val reload: (() -> Unit)? = null,
    override val storeListing: String? = null,
    private val upload: suspend (url: String, headers: Map<String, String>, base64: String) -> Int = { _, _, _ -> 200 },
    private val installState: WebInstallState? = null,
) : Platform {
    override suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int = upload(url, headers, base64)

    override suspend fun webInstallState(): WebInstallState? = installState
}

/** Nobody signed in with Google. */
object NoGoogle : GoogleAuth {
    override suspend fun refresh(): String? = null

    override suspend fun signOut() = Unit
}

/** The files kept, open for a test to look at or clear. */
class InMemoryByteCache : ByteCache {
    val kept = mutableMapOf<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = kept[key]

    override suspend fun write(key: String, bytes: ByteArray) {
        kept[key] = bytes
    }
}
