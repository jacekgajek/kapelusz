package com.jacekgajek.kapelusz.android

import com.jacekgajek.kapelusz.text.EventLinks
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.jacekgajek.kapelusz.app.intents.AppIntent
import com.jacekgajek.kapelusz.app.intents.SharedFiles
import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.prepareShared
import kotlinx.coroutines.launch
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.AndroidAppRuntime
import com.jacekgajek.kapelusz.app.App
import com.jacekgajek.kapelusz.app.AppLoading
import com.jacekgajek.kapelusz.app.sync.BackgroundSyncWorker
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val runtime by lazy { AndroidAppRuntime.get(this) }

    // Sync only while the app is visible.
    override fun onStart() {
        super.onStart()
        // The screen in front is the one Google's sign-in UI uses (also after another one was closed).
        AndroidAppRuntime.platform(this).google.activity = WeakReference(this)
        runtime.onForeground()
    }

    override fun onStop() {
        runtime.onBackground()
        // Changes not sent yet go out as soon as there is a network.
        if (runtime.hasUnsentWork()) BackgroundSyncWorker.syncSoon(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeJoinCode(intent)
        takeSharedFiles(intent)
    }

    /**
     * Photos or PDFs shared to Kapelusz (ITERATION_6 §1): prepared (photos shrunk) while the permission to read them
     * lasts, then offered for "Attach to…".
     */
    private fun takeSharedFiles(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            // No target takes more: the rest is said, not read (each could be large).
            val results = prepareShared(applicationContext, uris.take(MAX_EVENT_ATTACHMENTS), intent.type)
            val shared = SharedFiles(
                results.filterIsInstance<PickResult.Picked>().map { it.file },
                results.filter { it !is PickResult.Picked },
                tooMany = (uris.size - MAX_EVENT_ATTACHMENTS).coerceAtLeast(0),
            )
            runtime.intents.offer(AppIntent.ShareFiles(shared))
        }
    }

    /**
     * A join link (https://kapelusz.jacekgajek.com/join/CODE) opened the app: offer to join with its code. An event link
     * (…/e/EVENT_ID, ITERATION_5 §10): open that event.
     */
    private fun takeJoinCode(intent: Intent?) {
        val path = intent?.data?.path ?: return
        Regex("^/join/([A-Za-z0-9]{6})/?$").find(path)?.let { runtime.intents.offer(AppIntent.Join(it.groupValues[1].uppercase())) }
        EventLinks.eventId(path)?.let { runtime.intents.offer(AppIntent.OpenEvent(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a real launch: after a rotation Android re-delivers the same intent, which must not reopen a dismissed join.
        // Nor when started again from Recents (e.g. after a reboot): that is the task's old intent (V5-M1 D7).
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) {
            takeJoinCode(intent)
            takeSharedFiles(intent)
        }
        setContent {
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(runtime) {
                runtime.load()
                loaded = true
            }
            if (loaded) App(runtime) else AppLoading()
        }
    }
}
