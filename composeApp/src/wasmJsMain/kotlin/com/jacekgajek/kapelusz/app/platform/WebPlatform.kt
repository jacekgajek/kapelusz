package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.WebGoogleAuth
import com.jacekgajek.kapelusz.app.auth.WebSignInButton
import com.jacekgajek.kapelusz.app.storage.IndexedDbStore
import com.jacekgajek.kapelusz.app.storage.KeyValueStore

/** The web app's [Platform]: the browser. */
class WebPlatform : Platform {
    override val kind = PlatformKind.WEB
    override val store: KeyValueStore = IndexedDbStore()
    override val fileCache: ByteCache = WebFileCache()
    override val google: GoogleAuth = WebGoogleAuth
    override val messages: MessageChannel? = null

    // The web app is always the deployed version; a reload is its update.
    override val reload: (() -> Unit) = ::reloadPage
    override val storeListing: String? = null
    override val clipboard: Clipboard = WebClipboard
    override val sharing: Sharing = WebSharing

    // No map on the web (ITERATION_6 §3): Compose for the web has none; the browser shows PDFs itself; a phone's own
    // camera opens an invite link anyway.
    override val maps: Maps? = null
    override val pdf: PdfRenderer? = null
    override val qrScanner: QrScanner? = null

    override suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int = putFile(url, headers, base64)

    override suspend fun webInstallState(): WebInstallState = readWebInstallState()

    // The web app is the deployed version; a reload is its update.
    @Composable
    override fun rememberStoreUpdates(): StoreUpdates? = null

    @Composable
    override fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker = rememberWebAttachmentPicker(onResult)

    @Composable
    override fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit = rememberWebUrlOpener()

    @Composable
    override fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) {
        WebSignInButton(onIdToken, onError, modifier)
    }

    @Composable
    override fun provideLocale(language: String?): ProvidedValue<*> = provideWebLocale(language)
}

private fun reloadPage() {
    js("window.location.reload()")
}
