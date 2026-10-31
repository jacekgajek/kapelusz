package com.jacekgajek.kapelusz.app.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.AndroidGoogleAuth
import com.jacekgajek.kapelusz.app.auth.AndroidSignInButton
import com.jacekgajek.kapelusz.app.storage.FileStore
import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.app.sync.FirebaseMessageChannel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android app's [Platform], over the application [app]. */
class AndroidPlatform(app: Context) : Platform {
    override val kind = PlatformKind.ANDROID
    override val store: KeyValueStore = FileStore(File(app.filesDir, "kapelusz"))
    override val fileCache: ByteCache = AndroidFileCache(app)
    override val google: AndroidGoogleAuth = AndroidGoogleAuth(app)
    override val messages: MessageChannel = FirebaseMessageChannel(app)
    override val reload: (() -> Unit)? = null
    override val storeListing: String = PLAY_LISTING
    override val clipboard: Clipboard = AndroidClipboard
    override val sharing: Sharing = AndroidSharing

    // Only with a Maps key (a local build may have none).
    override val maps: Maps? = if (AppConfig.HAS_MAPS_KEY) GoogleMaps else null
    override val pdf: PdfRenderer = AndroidPdf
    override val qrScanner: QrScanner = AndroidQrScanner

    override suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int = withContext(Dispatchers.IO) {
        val bytes = Base64.getDecoder().decode(base64)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setFixedLengthStreamingMode(bytes.size)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    // The Android app is installed; the web's install hints do not apply.
    override suspend fun webInstallState(): WebInstallState? = null

    @Composable
    override fun rememberStoreUpdates(): StoreUpdates? = rememberPlayUpdates()

    @Composable
    override fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker {
        val picker = rememberAndroidAttachmentPicker(onResult)
        return picker
    }

    @Composable
    override fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit = rememberAndroidUrlOpener()

    @Composable
    override fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) {
        AndroidSignInButton(onIdToken, onError, modifier)
    }

    @Composable
    override fun provideLocale(language: String?): ProvidedValue<*> = provideAndroidLocale(language)
}
