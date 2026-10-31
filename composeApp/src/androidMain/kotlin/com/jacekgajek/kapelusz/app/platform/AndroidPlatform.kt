package com.jacekgajek.kapelusz.app.platform

import android.content.Context
import com.jacekgajek.kapelusz.app.auth.AndroidGoogleAuth
import com.jacekgajek.kapelusz.app.data.FileStore
import com.jacekgajek.kapelusz.app.data.KeyValueStore
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
}
