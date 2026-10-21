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
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val graph by lazy { AndroidAppGraph.get(this) }

    // Sync only while the app is visible.
    override fun onStart() {
        super.onStart()
        if (graph.sessions.session.value != null) graph.sync.start()
    }

    override fun onStop() {
        graph.sync.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeJoinCode(intent)
    }

    /** A join link (https://artsplit.jacekgajek.com/join/CODE) opened the app: offer to join with its code. */
    private fun takeJoinCode(intent: Intent?) {
        val path = intent?.data?.path ?: return
        Regex("^/join/([A-Za-z0-9]{6})/?$").find(path)?.let { graph.pendingJoinCode.value = it.groupValues[1].uppercase() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGoogleAuth.activity = WeakReference(this)
        takeJoinCode(intent)
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
