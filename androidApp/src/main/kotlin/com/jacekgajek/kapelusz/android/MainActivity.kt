package com.jacekgajek.kapelusz.android

import com.jacekgajek.kapelusz.domain.EventLinks
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.jacekgajek.kapelusz.app.data.SharedFiles
import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.prepareShared
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import com.jacekgajek.kapelusz.app.App
import com.jacekgajek.kapelusz.app.AppLoading
import com.jacekgajek.kapelusz.app.auth.AndroidGoogleAuth
import com.jacekgajek.kapelusz.app.updates.UpdateWorker
import com.jacekgajek.kapelusz.app.notify.BackgroundSyncWorker
import com.jacekgajek.kapelusz.app.notify.Notifier
import com.jacekgajek.kapelusz.app.updates.Updater
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val graph by lazy { AndroidAppGraph.get(this) }

    // Sync only while the app is visible.
    override fun onStart() {
        super.onStart()
        // The screen in front is the one Google's sign-in UI uses (also after another one was closed).
        AndroidGoogleAuth.activity = WeakReference(this)
        if (graph.sessions.session.value != null) graph.sync.start()
        Updater.appVisible = true
        UpdateWorker.schedule(this)
        UpdateWorker.checkSoon(this)
        BackgroundSyncWorker.schedule(this)
    }

    override fun onStop() {
        graph.sync.stop()
        Updater.appVisible = false
        // Reminders follow what was just changed; transfers seen in the app are not notified later.
        BackgroundSyncWorker.refreshLocal(this)
        // Changes not sent yet go out as soon as there is a network, not hours later with the periodic sync.
        if (graph.hasUnsentWork()) BackgroundSyncWorker.syncSoon(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeJoinCode(intent)
        takeEventToOpen(intent)
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
            val results = prepareShared(applicationContext, uris)
            graph.sharedFiles.value = SharedFiles(results.filterIsInstance<PickResult.Picked>().map { it.file }, results.filter { it !is PickResult.Picked })
        }
    }

    /** A tapped reminder or transfer notification: open its gig (a debt reminder: at To settle, ITERATION_3 §2.2). */
    private fun takeEventToOpen(intent: Intent?) {
        val band = intent?.getStringExtra(Notifier.EXTRA_BAND) ?: return
        val event = intent.getStringExtra(Notifier.EXTRA_EVENT) ?: return
        graph.pendingToSettle.value = event.takeIf { intent.getBooleanExtra(Notifier.EXTRA_TO_SETTLE, false) }
        graph.pendingOpenEvent.value = band to event
        intent.removeExtra(Notifier.EXTRA_BAND)
    }

    /**
     * A join link (https://kapelusz.jacekgajek.com/join/CODE) opened the app: offer to join with its code. An event link
     * (…/e/EVENT_ID, ITERATION_5 §10): open that event.
     */
    private fun takeJoinCode(intent: Intent?) {
        val path = intent?.data?.path ?: return
        Regex("^/join/([A-Za-z0-9]{6})/?$").find(path)?.let { graph.pendingJoinCode.value = it.groupValues[1].uppercase() }
        EventLinks.eventId(path)?.let { graph.pendingEventLink.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a real launch: after a rotation Android re-delivers the same intent, which must not reopen a dismissed join.
        // Nor when started again from Recents (e.g. after a reboot): that is the task's old intent (V5-M1 D7).
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) {
            takeJoinCode(intent)
            takeEventToOpen(intent)
            takeSharedFiles(intent)
        }
        setContent {
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(graph) {
                graph.load()
                loaded = true
            }
            if (loaded) App(graph) else AppLoading()
        }
    }
}
