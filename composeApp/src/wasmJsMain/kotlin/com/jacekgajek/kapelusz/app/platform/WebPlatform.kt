package com.jacekgajek.kapelusz.app.platform

import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.WebGoogleAuth
import com.jacekgajek.kapelusz.app.data.IndexedDbStore
import com.jacekgajek.kapelusz.app.data.KeyValueStore

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

    override suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int = putFile(url, headers, base64)

    override suspend fun webInstallState(): WebInstallState = readWebInstallState()
}

private fun reloadPage() {
    js("window.location.reload()")
}
