package com.jacekgajek.kapelusz.app.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jacekgajek.kapelusz.app.AndroidAppRuntime
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firebase Cloud Messaging for sync messages. Off until signed in (automatic start is off in the
 * manifest), and off again after signing out. Without Google Play services every call fails quietly: the app then
 * looks for news every 30 s, as before.
 */
class FirebaseMessageChannel(private val context: Context) : MessageChannel {
    override val installationId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(INSTALLATION_ID, null) ?: newId().also { prefs.edit { putString(INSTALLATION_ID, it) } }
    }

    override suspend fun token(): String? = runCatching {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.token.await()
    }.getOrNull()

    override suspend fun deleteToken() {
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            messaging.deleteToken().await()
        }
    }

    private companion object {
        const val PREFS = "kapelusz-messages"
        const val INSTALLATION_ID = "installationId"

        /** 32 random letters and digits: what the server takes as an installation id. */
        fun newId(): String {
            val random = SecureRandom()
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return String(CharArray(32) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result) else continuation.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
    }
}

/**
 * Receives sync messages: "band X changed" makes an app on screen sync it, and is ignored otherwise. A new token is registered with the
 * server.
 */
class SyncMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val bandId = message.data["band"] ?: return
        // Firebase calls on a thread of its own; the sync engine lives on the main one.
        val runtime = AndroidAppRuntime.get(applicationContext)
        Handler(Looper.getMainLooper()).post { runtime.onMessage(bandId) }
    }

    override fun onNewToken(token: String) {
        AndroidAppRuntime.get(applicationContext).onNewToken()
    }
}
