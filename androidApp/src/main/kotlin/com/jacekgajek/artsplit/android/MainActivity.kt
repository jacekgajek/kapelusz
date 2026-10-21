package com.jacekgajek.artsplit.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.artsplit.app.AndroidAppGraph
import com.jacekgajek.artsplit.app.App
import com.jacekgajek.artsplit.app.auth.AndroidGoogleAuth
import com.jacekgajek.artsplit.app.updates.UpdateWorker
import com.jacekgajek.artsplit.app.notify.BackgroundSyncWorker
import com.jacekgajek.artsplit.app.notify.Notifier
import com.jacekgajek.artsplit.app.updates.Updater
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val graph by lazy { AndroidAppGraph.get(this) }

    // Sync only while the app is visible.
    override fun onStart() {
        super.onStart()
        if (graph.sessions.session.value != null) graph.sync.start()
        Updater.appVisible = true
        UpdateWorker.schedule(this)
        UpdateWorker.checkSoon(this)
        BackgroundSyncWorker.schedule(this)
    }

    override fun onStop() {
        graph.sync.stop()
        Updater.appVisible = false
        // A downloaded update installs while the app is out of sight (it restarts the app).
        UpdateWorker.installWhenIdle(this)
        // Reminders follow what was just changed; transfers seen in the app are not notified later.
        BackgroundSyncWorker.refreshLocal(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeJoinCode(intent)
        takeEventToOpen(intent)
    }

    /** A tapped reminder or transfer notification: open its gig. */
    private fun takeEventToOpen(intent: Intent?) {
        val band = intent?.getStringExtra(Notifier.EXTRA_BAND) ?: return
        val event = intent.getStringExtra(Notifier.EXTRA_EVENT) ?: return
        graph.pendingOpenEvent.value = band to event
        intent.removeExtra(Notifier.EXTRA_BAND)
    }

    /** A join link (https://artsplit.jacekgajek.com/join/CODE) opened the app: offer to join with its code. */
    private fun takeJoinCode(intent: Intent?) {
        val path = intent?.data?.path ?: return
        Regex("^/join/([A-Za-z0-9]{6})/?$").find(path)?.let { graph.pendingJoinCode.value = it.groupValues[1].uppercase() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGoogleAuth.activity = WeakReference(this)
        // Only on a real launch: after a rotation Android re-delivers the same intent, which must not reopen a dismissed join.
        if (savedInstanceState == null) {
            takeJoinCode(intent)
            takeEventToOpen(intent)
        }
        setContent {
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(graph) {
                graph.load()
                loaded = true
            }
            if (loaded) App(graph)
        }
    }
}
