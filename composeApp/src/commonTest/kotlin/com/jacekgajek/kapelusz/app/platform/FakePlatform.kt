package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.app.data.KeyValueStore

/**
 * A [Platform] for tests: the web kind, in-memory store and cache, nobody signed in, a clipboard and sharing that
 * fail; messages, reload, the listing, maps, PDFs and the scanner absent unless given.
 */
class FakePlatform(
    override val kind: PlatformKind = PlatformKind.WEB,
    override val store: KeyValueStore = InMemoryStore(),
    override val fileCache: InMemoryByteCache = InMemoryByteCache(),
    override val google: GoogleAuth = NoGoogle,
    override val messages: MessageChannel? = null,
    override val reload: (() -> Unit)? = null,
    override val storeListing: String? = null,
    override val clipboard: Clipboard = NoClipboard,
    override val sharing: Sharing = NoSharing,
    override val maps: Maps? = null,
    override val pdf: PdfRenderer? = null,
    override val qrScanner: QrScanner? = null,
    private val upload: suspend (url: String, headers: Map<String, String>, base64: String) -> Int = { _, _, _ -> 200 },
    private val installState: WebInstallState? = null,
) : Platform {
    override suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int = upload(url, headers, base64)

    override suspend fun webInstallState(): WebInstallState? = installState

    @Composable
    override fun rememberStoreUpdates(): StoreUpdates? = null

    @Composable
    override fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker = NoPicker

    @Composable
    override fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit = {}

    @Composable
    override fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun provideLocale(language: String?): ProvidedValue<*> = LocalFakeLanguage provides language
}

private val LocalFakeLanguage = staticCompositionLocalOf<String?> { null }

/** Nobody signed in with Google. */
object NoGoogle : GoogleAuth {
    override suspend fun refresh(): String? = null

    override suspend fun signOut() = Unit
}

object NoClipboard : Clipboard {
    @Composable
    override fun rememberCopy(): suspend (text: String) -> Boolean = { false }

    @Composable
    override fun rememberPaste(): suspend () -> String? = { null }

    @Composable
    override fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean = { _, _ -> false }
}

object NoSharing : Sharing {
    @Composable
    override fun rememberShare(): suspend (text: String) -> ShareResult = { ShareResult.FAILED }
}

private object NoPicker : AttachmentPicker {
    override fun pickFile() = Unit

    override val takePhoto: (() -> Unit)? = null
}

/** The files kept, open for a test to look at or clear. */
class InMemoryByteCache : ByteCache {
    val kept = mutableMapOf<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = kept[key]

    override suspend fun write(key: String, bytes: ByteArray) {
        kept[key] = bytes
    }
}
